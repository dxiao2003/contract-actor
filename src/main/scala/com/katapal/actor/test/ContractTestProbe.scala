package com.katapal.actor
package test

import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
import com.katapal.actor.ContractActor._
import com.katapal.actor.DeferrableActor._
import akka.testkit.TestProbe

import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

object ContractTestProbe {
  /** Create a new [[ContractTestProbe]]
    *
    * @param timeout Time for the test probe to wait for responses before timing out.
    * @param as The actor system.
    * @return The new [[ContractTestProbe]].
    */
  def apply()(implicit timeout: Timeout,  as: ActorSystem) =
    new ContractTestProbe
}

/** A probe that helps test [[ContractActor]]s.  Mainly provides convenience methods that manage the call ID's of
  * named calls, freeing the caller to worry only about the testing logic.
  *
  * @param t Timeout to wait for responses.
  * @param actorSystem The actor system where the probe should be created.
  */
class ContractTestProbe(implicit t: Timeout, actorSystem: ActorSystem) {

  val testProbe = TestProbe()
  val timeout = t.duration

  /** @return The underlying [[akka.actor.Actor]]. */
  def ref: ActorRef = testProbe.ref

  /** Wait until the expected method call is received or else throw [[AssertionError]].
    *
    * @param m The method call (instance of a contract) we are returning
    * @tparam T
    * @return The named call containing the method call.
    */
  def expectMsg[T: ClassTag](m: Returning[T]): NamedCallReturning[T] = {
    testProbe.expectMsgPF(timeout) {
      case x:  NamedCallReturning[T] if x.call == m => x
    }
  }

  /** Wait until the expected method call is received and then process it with the given callback function and sends
    * the response to the caller.  If calling `f` raises an exception, it first sends a reply with the exception to
    * the caller, then throws the exception.
    *
    * @param f The callback function to apply to the method call.
    * @tparam S The return type of the call.
    * @return Tuple containing the method call and the result of processing the method call.
    */
  def processMsg[S](f: Returning[_] => S): (Returning[_], S) = {

    val c = testProbe.expectMsgType[NamedCallReturning[_]](timeout)
    val t = Try(f(c.call))
    testProbe.reply(Reply(c.id, t))

    t match {
      case Success(r) => (c.call, r)
      case Failure(e) => throw e
    }
  }

  /** Send a successful reply to a [[ContractActor]].
    *
    * @param a The [[ContractActor]] to reply to.
    * @param msg The result value in the reply.
    * @param id The call ID of the reply.
    * @tparam T The type of the reply value.
    */
  def reply[T](a: ActorRef, msg: T, id: CallId): Unit = {
    testProbe.send(a, Reply(id, Success(msg)))
  }

  /** Send a reply to the most recent sender. */
  def reply[T](msg: T, id: CallId): Unit = {
    testProbe.send(testProbe.sender, Reply(id, Success(msg)))
  }

  /** Send a reply with a failure to a [[ContractActor]].
    *
    * @param a The [[ContractActor]] to reply to.
    * @param e The [[Throwable]] to reply with.
    * @param id The ID of the call to reply with.
    */
  def replyFailure(a: ActorRef, e: Throwable, id: CallId): Unit = {
    testProbe.send(a, Reply(id, Failure(e)))
  }
}
