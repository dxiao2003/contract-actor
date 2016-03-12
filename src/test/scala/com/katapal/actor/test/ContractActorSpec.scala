/*
 * Copyright (c) 2016. Katapal, Inc.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, you can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.katapal.actor
package test

import akka.actor._
import akka.testkit.{TestActorRef, TestKit}
import akka.util.Timeout
import com.katapal.actor.ContractActor.{Returning, CallHandler}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.async.Async.{async, await}
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, TimeoutException}
import scala.language.reflectiveCalls
import scala.util._

object ContractActorSpec {

  sealed abstract class Term
  case class Constant(x: Long) extends Term {
    override def toString: String = x.toString
  }
  case class Plus(l: Term, r: Term) extends Term {
    override def toString: String = s"(${l}) + (${r})"
  }
  case class Times(l: Term, r: Term) extends Term {
    override def toString: String = s"(${l}) * (${r})"
  }
  case class EvalError() extends Term



  val timeout = Timeout(1.minute)


  object Adder {

    abstract class Message[T] extends Returning[T]

    case class Add(x: Term, y: Term, batch: Boolean) extends Message[Long]

  }

  object Multiplier {

    abstract class Message[T] extends Returning[T]

    case class Mult(x: Term, y: Term, batch: Boolean) extends Message[Long]

  }

  object Evaluator {
    abstract class Message[T] extends Returning[T]

    case class Eval(x: Term, batch: Boolean = false) extends Message[Long]
  }


  class AdderActor(evalActor: ActorRef) extends ContractActor(timeout) {
    import com.katapal.actor.test.ContractActorSpec.Evaluator._

    val evaluator = Handle[Message](evalActor)

    private implicit val t = timeout

    def receiveCall: CallHandler = {
      case Adder.Add(x, y, b) =>
        if (!b)
          async { await(evaluator.call(Eval(x))) + await(evaluator.call(Eval(y))) }
        else
          for {
            xval <- evaluator.call(Eval(x, true))
            yval <- evaluator.call(Eval(y, true))
          } yield (xval + yval)

    }
  }

  class MultiplierActor(evalActor: ActorRef) extends ContractActor(timeout) {
    import com.katapal.actor.test.ContractActorSpec.Evaluator._

    val evaluator = Handle[Message](evalActor)

    private implicit val t = timeout

    def receiveCall: CallHandler = {
      case Multiplier.Mult(x, y, b) =>
        if (!b)
          async { await(evaluator.call(Eval(x))) * await(evaluator.call(Eval(y))) }
        else
          for {
            xval <- evaluator.call(Eval(x, true))
            yval <- evaluator.call(Eval(y, true))
          } yield (xval * yval)
    }
  }

  class EvaluatorActor extends ContractActor(timeout) {
    import com.katapal.actor.test.ContractActorSpec.Evaluator._

    private implicit val t = timeout

    val adder = Handle[Adder.Message](
        context.actorOf(Props(classOf[AdderActor], self), "Adder")
      )
    val multiplier = Handle[Multiplier.Message](
      context.actorOf(Props(classOf[MultiplierActor], self), "Multiplier")
    )

    def receiveCall: CallHandler = {
      case Eval(Constant(c), _) => Future(c)
      case Eval(Plus(x, y), b) => adder.call(Adder.Add(x, y, b))
      case Eval(Times(x, y), b) => multiplier.call(Multiplier.Mult(x,y,b))
      case Eval(EvalError(), _) => Future.failed(new IllegalArgumentException("Cannot evaluate error"))
    }
  }


  object Quizzer  {
    abstract class Message[T] extends Returning[T]

    case class Quiz(seed: Int) extends Message[(Long, Long)]

  }

  class QuizzerActor(eval: ActorRef) extends ContractActor(timeout) {
    import com.katapal.actor.test.ContractActorSpec.Quizzer._

    val evaluator = Handle[Evaluator.Message](eval)
    var actorSum = 0L

    private implicit val t = timeout

    def quiz(seed: Int): Future[(Long, Long)] = async {
      val rand = new scala.util.Random(seed)

      val l = (1 to 5000) map { _ =>
        val base = rand.nextLong
        (1 to 10).foldLeft[(Long, Term)]((base, Constant(base))) {
          (t, i) =>
          val (c, e) = t
          val n = rand.nextLong

          if (i % 2 == 0)
            (c * n, Times(e, Constant(n)))
          else
            (c + n, Plus(e, Constant(n)))
        }
      }

      val (mySum, myList) =
        l.foldLeft[(Long, List[Future[Long]])](
          0, List.empty[Future[Long]]
        ) {
          (prev, next) =>
          val (sum, list) = prev
          val (n, term) = next
          val f = evaluator.call(Evaluator.Eval(term)) andThen {
            case Success(s) => actorSum += s
            case Failure(e) => throw e
          }

          (sum + n, f :: list)
        }

      await(Future.sequence(myList))

      (mySum, actorSum)
    }

    def receiveCall: CallHandler = {
      case Quiz(seed) => quiz(seed)
    }
  }
}

class ContractActorSpec(system: ActorSystem) extends TestKit(system) with WordSpecLike
    with BeforeAndAfterAll with Matchers {
  import com.katapal.actor.test.ContractActorSpec.Evaluator._
  import com.katapal.actor.test.ContractActorSpec.{Evaluator, EvaluatorActor, _}


  private implicit val timeout = Timeout(1.minute)
  implicit val ec = system.dispatcher
  implicit val s = system

  def this() = this(ActorSystem("contract-actor-test-system"))


  override def afterAll {
    shutdown(system)
  }

  val evalActor = system.actorOf(Props[EvaluatorActor], "Evaluator")
  val evaluator = new StandaloneHandle[Evaluator.Message](evalActor)(timeout, system)

  "The evaluator actor" should {
    "evaluate constants correctly" in {
      Await.result(evaluator.call(Eval(Constant(1))), 1.minute) should === (1)
    }

    "evaluate sums correctly" in {
      Await.result(
        evaluator.call(Eval(Plus(Constant(1), Constant(1)))),
        1.minute
      ) should === (2)
    }

    "evaluate products correctly" in {
      Await.result(
        evaluator.call(Eval((Times(Constant(10), Constant(10))))),
        1.minute
      ) should === (100)
    }

    "evaluate quadratics correctly" in {
      Await.result( evaluator.call(Eval(
        Plus(Times(Constant(2), Constant(3)),
          Times(Constant(4), Plus(Constant(1), Constant(2)))))
      ), 1.minute
      ) should === (18)
    }

    "evaluate cubics correctly" in {
      Await.result( evaluator.call(Eval(
        Plus(Times(Plus(Times(Constant(2), Constant(3)), Constant(1)),
          Times(Plus(Constant(4), Plus(Constant(1), Constant(-2))), Constant(2))),
          Constant(-1)))
      ), 1.minute
      ) should === (41)

    }

    "evaluate complex expressions correctly" in {
      val (v, expr) = (1 to 8).foldLeft[Tuple2[Long, Term]]((1L, Constant(1))) {
        (t, i) =>
        val (c, e) = t
        if (i % 2 == 0) (c * c, Times(e, e)) else (c + c, Plus(e, e))
      }

      Await.result( evaluator.call(Eval(expr)), 1.minute ) should === (v)
    }

    "capture errors correctly" in {
      an [IllegalArgumentException] should be thrownBy {
        Await.result( evaluator.call(Eval(EvalError())), 1.minute)
      }
    }

    "handle batches" in {
      val (v, expr) = (1 to 8).foldLeft[Tuple2[Long, Term]]((2L, Constant(2))) {
        (t, i) =>
        val (c, e) = t
        if (i % 2 == 0) (c * c, Times(e, e)) else (c + c, Plus(e, e))
      }

      Await.result( evaluator.call(Eval(expr, true)), 1.minute ) should === (v)
    }

    "handle async callbacks" in {
      val q = new StandaloneHandle[Quizzer.Message](
        system.actorOf(Props(classOf[QuizzerActor], evalActor))
      )

      val (mySum, actorSum) = Await.result(q.call(Quizzer.Quiz(12893)), 2.minutes)
      mySum should not be 0
      mySum should === (actorSum)
    }

    "cancel immediately when handle actor terminated" in {
      val dummy = TestActorRef(new Actor{ def receive = { case _ => } })
      val q = new StandaloneHandle[Multiplier.Message](
        system.actorOf(Props(classOf[MultiplierActor], dummy))
      )

      val fut = q.call(Multiplier.Mult(Constant(1), Constant(1), false))

      a [TimeoutException] shouldBe thrownBy { Await.ready(fut, 10.seconds) }
      fut.isCompleted should be (false)

      dummy ! PoisonPill

      a [TimeoutException] shouldBe thrownBy {
        Await.result(fut, 1.minute)
      }

      fut.value.get.isFailure should be (true)
    }
  }
}
