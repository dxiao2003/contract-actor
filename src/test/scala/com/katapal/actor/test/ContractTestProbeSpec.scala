package com.katapal.actor
package test

import akka.actor._
import akka.testkit.{TestActorRef, TestKit}
import akka.util.Timeout
import com.katapal.actor.ContractActor.NamedCallReturning
import com.katapal.actor.test.ContractActorSpec._
import com.katapal.actor.test.ContractActorSpec.Multiplier._
import com.katapal.actor.test.ContractActorSpec.Evaluator._
import org.scalatest.{Matchers, WordSpecLike, BeforeAndAfterAll}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.reflectiveCalls

class ContractTestProbeSpec(system: ActorSystem) extends TestKit(system)
  with WordSpecLike with BeforeAndAfterAll with Matchers {

  private implicit val timeout = Timeout(1.minute)
  implicit val ec = system.dispatcher
  implicit val s = system

  def this() = this(ActorSystem("contract-test-probe-system"))


  override def afterAll {
    shutdown(system)
  }

  val evaluator = ContractTestProbe()
  val multActor = TestActorRef(new MultiplierActor(evaluator.ref))
  val multiplier =
    new StandaloneHandle[Multiplier.Message](multActor)(timeout, system)

  "The ContractTestProbe" should {
    "receive messages" in {
      multiplier.call(Mult(Plus(Constant(1), Constant(1)), Constant(1), false))

      evaluator.expectMsg(Eval(Plus(Constant(1), Constant(1))))
    }

    "reply to messages" in {
      val f = multiplier.call(
        Mult(Plus(Constant(1), Constant(1)), Constant(1), false))

      evaluator.expectMsg(Eval(Plus(Constant(1), Constant(1)))) match {
        case NamedCallReturning(id, c) =>
          evaluator.reply(multActor, 2L, id)
      }

      evaluator.expectMsg(Eval(Constant(1))) match {
        case NamedCallReturning(id, c) =>
          evaluator.reply(multActor, 1L, id)
      }

      Await.result(f, 1.minute) should === (2L)
    }

    "reply to messages with failure" in {
      val f = multiplier.call(
        Mult(Plus(Constant(1), Constant(1)), Constant(1), false))

      evaluator.expectMsg(Eval(Plus(Constant(1), Constant(1)))) match {
        case NamedCallReturning(id, c) =>
          evaluator.replyFailure(multActor, new Exception("!"), id)
      }

      an [Exception] should be thrownBy { Await.result(f, 1.minute) }

    }

    "process messages" in {
      val f = multiplier.call(
        Mult(Plus(Constant(1), Constant(1)), Constant(1), false))

      evaluator.processMsg {
        case Eval(Plus(Constant(1), Constant(1)), false) => 2L
        case _ =>  throw new Exception("!")
      }

      evaluator.expectMsg(Eval(Constant(1))) match {
        case NamedCallReturning(id, c) =>
          evaluator.reply(multActor, 1L, id)
      }

      Await.result(f, 1.minute) should === (2L)

    }

    "process messages with failure" in {
      val f = multiplier.call(
        Mult(Plus(Constant(1), Constant(1)), Constant(1), false))

      an [Exception] should be thrownBy {
        evaluator.processMsg {
          case Eval(Constant(1), false) => 2L
          case _ =>  throw new Exception("!")
        }
      }

      an [Exception] should be thrownBy { Await.result(f, 1.minute) }
    }
  }
}
