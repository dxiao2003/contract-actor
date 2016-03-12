/*
 * Copyright (c) 2016. Katapal, Inc.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, you can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.katapal

import akka.actor._
import akka.util.Timeout

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.language.implicitConversions

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
