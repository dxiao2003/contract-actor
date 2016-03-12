/*
 * Copyright (c) 2016. Katapal, Inc.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, you can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.katapal.actor

import akka.actor.{ActorRef, Status}
import akka.util.Timeout
import com.katapal.actor.ContractActor.{Returning, CallHandler}

import scala.util.{Failure, Success}

/**
  * Created by David on 3/11/2016.
  */

/**
  * A stand-alone actor to be used to create a handle that can be used to call a [[ContractActor]] from outside the
  * body of a [[ContractActor]].
  *
  * @param dest The [[ActorRef]] that we want to send calls to.
  * @param timeout Default timeout to use for asynchronous calls between actors.
  */
private[actor] class DummyContractActor(dest: ActorRef, timeout: Timeout)
    extends ContractActor(timeout) {
  implicit val t = timeout

  /** Not used since this [[DummyContractActor]] should never receive calls */
  def receiveCall: CallHandler = ???

  override def receiveOther = {
    case c : Returning[_] =>
      val s = sender
      sendCall[Any](c, dest) onComplete {
        case Success(r) => s ! r
        case Failure(e) => s ! Status.Failure(e)
      }

  }
}
