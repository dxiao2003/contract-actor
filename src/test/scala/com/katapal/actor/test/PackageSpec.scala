/*
 * Copyright (c) 2016. Katapal, Inc.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, you can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.katapal.actor.test

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern._
import akka.testkit.{TestActorRef, TestKit}
import akka.util.Timeout
import com.katapal.actor.deferResolvePath
import org.scalatest.{BeforeAndAfterAll, FunSpecLike, Matchers}

import scala.concurrent.Await
import scala.concurrent.duration._

/**
  * Created by David on 2/26/2016.
  */

class Rabbit extends Actor {
  def receive: Receive = {
    case addr: String =>
      val child = context.actorOf(Props[Rabbit], addr)
      sender() ! child
  }
}


class PackageSpec(system: ActorSystem) extends TestKit(system) with FunSpecLike
  with BeforeAndAfterAll with Matchers {

  private implicit val timeout = Timeout(1.minute)
  implicit val ec = system.dispatcher
  implicit val s = system

  def this() = this(ActorSystem("contract-actor-test-system"))


  override def afterAll {
    shutdown(system)
  }

  describe("The Actor package") {
    it("should resolve child path") {
      val actorRef = TestActorRef[Rabbit]
      Await.result(deferResolvePath(actorRef.path, 10, 1.millis), 100.millis) should === (actorRef)
    }

    it("should resolve child path with delay") {
      val actorRef = TestActorRef[Rabbit]
      val path = actorRef.path / "bugs"
      val fut = deferResolvePath(path, 5, 1000.millis)
      Thread.sleep(500)
      val childRef = Await.result(actorRef ? "bugs", 100.millis).asInstanceOf[ActorRef]
      Await.result(fut, 5.seconds) should === (childRef)
    }
  }
}
