/*
Copyright 2015 Mfg labs.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.mfglabs
package precepte

import java.net.URL
import scala.language.higherKinds
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.language.postfixOps

import akka.actor.{ Actor, Props, ActorSystem }

import default._

case class Influx[C : scalaz.Semigroup](influxdbURL: URL, env: BaseEnv, system: ActorSystem)(implicit ex: ExecutionContext) {

  type P[A] = DefaultPre[Future, C, A]

  private val builder = new com.ning.http.client.AsyncHttpClientConfig.Builder()
  private val WS = new play.api.libs.ws.ning.NingWSClient(builder.build())

  private case object Publish
  private case class Metric(time: Long, span: Span, path: Call.Path[BaseTags], duration: Duration)

  private class InfluxClient extends Actor {
    val metrics = scala.collection.mutable.ArrayBuffer[Metric]()

    // fast and ugly json serialization
    private def json: String = {
      val sep = "/"

      val points =
        metrics.map { case Metric(time, span, path, duration) =>
          val p = path.map { c =>
            c.id.value
          }.mkString(sep, sep, "")

          val callees =
            path.map(_.tags.callee).mkString(sep, sep, "")

          val category =
            path.map(_.tags.category).mkString(sep, sep, "")

          s"""["${env.host.value}", "${env.environment.value}", "$category", "${span.value}", "$p", "$callees", $time, ${duration.toNanos}]"""
        }.mkString(",")

      s"""[{"name": "response_times", "columns": ["host", "environment", "category", "span", "path", "callees", "time", "duration"], "points": [$points] }]"""
    }

    def receive = {
      case m: Metric =>
        metrics += m
        ()
      case Publish =>
        if(metrics.nonEmpty) {
          WS.url(influxdbURL.toString).post(json)
          metrics.clear()
        }
    }
  }

  private val client = system.actorOf(Props(new InfluxClient))

  system.scheduler.schedule(10 seconds, 10 seconds, client, Publish)

  case class Timer(span: Span, path: Call.Path[BaseTags]) {
    def timed[A](f: scala.concurrent.Future[A]) = {
      val t0 = System.nanoTime()
      f.map { x =>
        val t1 = System.nanoTime()
        client ! Metric(t0 / 1000000, span, path, (t1 -t0) nanoseconds)
        x
      }
    }
  }

  def Timed[A](category: Category)(callee: Callee)(f: ST[C] => Future[A])(implicit fu: scalaz.Functor[Future]): P[A] =
    Precepte(BaseTags(callee, category)){ (c: ST[C]) =>
      Timer(c.managed.span, c.managed.path).timed(f(c))
    }

  def TimedM[A](category: Category)(callee: Callee)(f: P[A])(implicit mo: scalaz.Monad[Future]): P[A] =
    Precepte(BaseTags(callee, category)){ (c: ST[C]) =>
      Timer(c.managed.span, c.managed.path).timed(f.eval(c))
    }

}