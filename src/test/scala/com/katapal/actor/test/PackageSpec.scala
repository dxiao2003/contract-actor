package com.katapal.actor.test

import akka.actor.{Actor, ActorRef, Props, ActorSystem}
import akka.testkit.{TestActorRef, TestKit}
import akka.util.Timeout
import akka.pattern._
import org.scalatest.{Matchers, BeforeAndAfterAll, FunSpecLike}

import scala.concurrent.Await
import scala.concurrent.duration._

import com.katapal.actor.deferResolvePath

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
