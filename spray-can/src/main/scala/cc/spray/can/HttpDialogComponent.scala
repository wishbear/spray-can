/*
 * Copyright (C) 2011 Mathias Doenitz
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.spray.can
import akka.actor.{ActorSystem, Scheduler}
import akka.dispatch.{DefaultPromise, Future}
import akka.util
import akka.util.Duration
import org.slf4j.LoggerFactory

trait HttpDialogComponent {
  private lazy val log = LoggerFactory.getLogger(getClass)

  /**
   * An `HttpDialog` encapsulates an exchange of HTTP messages over the course of one connection.
   * It provides a fluent API for constructing a "chain" of scheduled tasks that define what to do over the course of
   * the dialog.
   */
  class HttpDialog[A](connectionF: Future[HttpConnection], resultF: Future[A])(implicit system: ActorSystem) {

    /**
     * Chains the sending of the given [[cc.spray.can.HttpRequest]] into the dialog.
     * The request will be sent as soon as the connection has been established and any `awaitResponse` and
     * `waitIdle` tasks potentially chained in before this `send` have been completed.
     * Several `send` tasks not separated by `awaitResponse`/`waitIdle` will cause the corresponding requests to be send
     * in a pipelined fashion, one right after the other.
     */
    def send[B](request: HttpRequest)(implicit concat: (A, Future[HttpResponse]) => Future[B]): HttpDialog[B] = {
      appendToResultChain {
        val responseF = doSend(request)
        concat(_, responseF)
      }
    }

    /**
     * Chains the sending of the given [[cc.spray.can.HttpRequest]] instances into the dialog.
     * The requests will be sent as soon as the connection has been established and any `awaitResponse` and
     * `waitIdle` tasks potentially chained in before this `send` have been completed.
     * All of the given HttpRequests are send in a pipelined fashion, one right after the other.
     */
    def send[B](requests: Seq[HttpRequest])(implicit concat: (A, Seq[Future[HttpResponse]]) => Future[Seq[HttpResponse]]): HttpDialog[Seq[HttpResponse]] = {
      appendToResultChain {
        val responseFs = requests.map(doSend)
        concat(_, responseFs)
      }
    }

    /**
     * Like `send`, but with a chunked request. If the given [[cc.spray.can.HttpRequest]] contains a body this body will
     * be used as the first chunk. Subsequent chunks as well as the closing of the stream are generated by the "chunker"
     * function.
     */
    def sendChunked[B](request: HttpRequest)(chunker: ChunkedRequester => Future[HttpResponse])(implicit concat: (A, Future[HttpResponse]) => Future[B]): HttpDialog[B] = {
      appendToResultChain {
        val responseF = connectionF.flatMap { connection =>
          log.debug("Sending chunked request start {}", request)
          chunker(connection.startChunkedRequest(request))
        }
        concat(_, responseF)
      }
    }

    /**
     * Chains a simple responder function into the task chain.
     * Only legal after exactly one preceding `send` task. `reply` can be repeated, so the task chain
     * `send(...).reply(...).reply(...).reply(...)` is legal.
     */
    def reply(f: HttpResponse => HttpRequest)(implicit ev: A <:< HttpResponse): HttpDialog[HttpResponse] = {
      appendToResultChain(response => doSend(f(response)))
    }

    /**
     * Delays all subsequent `send` tasks until all previously pending responses have come in.
     */
    def awaitResponse: HttpDialog[A] = appendToConnectionChain { connection =>
      make(new DefaultPromise[HttpConnection]()(resultF executor)) { nextConnectionF =>
        // only complete the next connection future once the result is in
        log.debug("Awaiting response")
        resultF.onComplete(_ => nextConnectionF.success(connection))
      }
    }

    /**
     * Delays all subsequent `send` tasks by the given time duration.
     */
    def waitIdle(duration: Duration): HttpDialog[A] = appendToConnectionChain { connection =>
      make(new DefaultPromise[HttpConnection]()(resultF executor)) { nextConnectionF =>
        // delay completion of the next connection future by the given time
        log.debug("Waiting {} ms", duration.toMillis)
        system.scheduler.scheduleOnce(duration) { nextConnectionF.success(connection) }

      }
    }

    /**
     * Schedules a proper closing of the `HttpDialogs` connection.
     * The result is of type `Future[HttpResponse]` if the dialog consists only of a single `send` (with potentially
     * following `reply` tasks), of type `Future[Seq[HttpResponse]]` if the dialog involves more than one request and
     * of type `Future[Unit]` if the dialog does not contain any `send`.
     */
    def end: Future[A] = resultF.onComplete { _ =>
      connectionF.onComplete {
        case conn: HttpConnection => {
          log.debug("Closing connection after HttpDialog completion")
          conn.close()
        }
      }
    }

    private def appendToConnectionChain[B](f: HttpConnection => Future[HttpConnection]): HttpDialog[A] =
      new HttpDialog(connectionF.flatMap(f), resultF)

    private def appendToResultChain[B](f: A => Future[B]): HttpDialog[B] = {
      // map(identity) creates a fresh future, so onComplete listeners are invoked in order of registration
      new HttpDialog(connectionF.map(identity), resultF.flatMap(f))
    }

    private def doSend(request: HttpRequest): Future[HttpResponse] = connectionF.flatMap { connection =>
      log.debug("Sending request {}", request)
      connection.send(request)
    }
  }

  object HttpDialog {
    /**
     * Constructs a new `HttpDialog` for a connection to the given host and port.
     */
    def apply(host: String, port: Int = 80,
      clientActorId: String = ClientConfig.fromAkkaConf.clientActorId)(implicit system: ActorSystem): HttpDialog[Unit] = {
      implicit val timeout = new util.Timeout(Long.MaxValue)
      val connection = (actor(clientActorId) ? Connect(host, port)).mapTo[HttpConnection]
      new HttpDialog(connection, connection.map(_ => ())) // start out with result type Unit
    }
  }

  implicit def concat1(value: Unit, responseFuture: Future[HttpResponse]) = responseFuture
  implicit def concat2(value: HttpResponse, responseFuture: Future[HttpResponse]) = responseFuture.map(Seq(value, _))
  implicit def concat3(value: Seq[HttpResponse], responseFuture: Future[HttpResponse]) = responseFuture.map(value :+ _)
  implicit def concat4(value: Unit, responseFutures: Seq[Future[HttpResponse]]) = {
    implicit val executor = responseFutures.head.executor
    Future.sequence(responseFutures)
  }
  implicit def concat5(value: HttpResponse, responseFutures: Seq[Future[HttpResponse]]) = {
    implicit val executor = responseFutures.head.executor
    Future.sequence(responseFutures).map(value +: _)
  }
  implicit def concat6(value: Seq[HttpResponse], responseFutures: Seq[Future[HttpResponse]]) = {
    implicit val executor = responseFutures.head.executor
    Future.sequence(responseFutures).map(value ++ _)
  }
}
