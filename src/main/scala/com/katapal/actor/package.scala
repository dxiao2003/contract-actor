package com.katapal

import scala.concurrent.{Promise, Future, ExecutionContext, Await}

import akka.actor._
import akka.routing.Router
import akka.util.Timeout
import scala.concurrent.duration.FiniteDuration
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

/** Katapal actor package containing [[com.katapal.actor.ContractActor]], a typesafe way of calling actors. */

package object actor {


  def deferResolvePath(path: ActorPath, iterations: Int, delay: FiniteDuration)
                      (implicit timeout: Timeout,
                       system: ActorSystem,
                       ec: ExecutionContext): Future[ActorRef] = {
    val sel = system.actorSelection(path)
    val scheduler = system.scheduler
    (1 to iterations).foldLeft[Future[ActorRef]](Future.failed(ActorNotFound(sel)))((prev, _) =>
      prev recoverWith {
        case ActorNotFound(_) =>
          val p = Promise[ActorRef]
          scheduler.scheduleOnce(delay) {
            // wait until the child actor has been created
            system.actorSelection(path).resolveOne() foreach p.success
          }
          p.future
        case x =>
          Future.failed(x)
      }
    )
  }
}
