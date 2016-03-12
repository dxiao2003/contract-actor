/*
 * Copyright (c) 2016. Katapal, Inc.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, you can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.katapal.actor

import akka.actor.{ActorRef, ActorSystem, Props, Status}
import akka.pattern.ask
import akka.util.Timeout
import com.katapal.actor.ContractActor.{Returning, CallHandler}

import scala.concurrent._
import scala.language.higherKinds
import scala.reflect.ClassTag
import scala.util.{Failure, Success}




/** A handle to use to call [[com.katapal.actor.ContractActor]]s from _outside_ [[ContractActor]] code.  (From
  * inside a [[ContractActor]] you should use a [[ContractActor#Handle]].)  This creates a
  * [[DummyContractActor]] that sends/receives messages.  Replies are handled by registering callbacks on the returned
  * [[Future]].
  *
  * @param dest The [[ContractActor]] we want to call.
  * @param timeout
  * @param system
  * @tparam M The contract class for [[ContractActor]].
  */
class StandaloneHandle[M[X] <: Returning[X]](dest: ActorRef)(implicit timeout: Timeout, system: ActorSystem) {
  private val dummyActor: ActorRef =
    system.actorOf(Props(classOf[DummyContractActor], dest, timeout))

  def call[T : ClassTag](c: M[T])(implicit t: Timeout): Future[T] = {
    dummyActor.ask(c)(t).mapTo[T]
  }
}

