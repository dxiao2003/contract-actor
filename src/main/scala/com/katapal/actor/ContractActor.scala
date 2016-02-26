/**
 * (c) 2014 David Xiao
 * All Rights Reserved.
 *
 * Unauthorized copying and redistribution in any form is strictly prohibited.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY
 * DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */


package com.katapal.actor

import java.util.concurrent.TimeoutException

import akka.actor._
import akka.util.Timeout
import com.katapal.actor.DeferrableActor.CallId

import scala.collection.mutable
import scala.concurrent._
import scala.concurrent.duration.FiniteDuration
import scala.language.higherKinds
import scala.reflect.{ClassTag, classTag}
import scala.util._
import akka.pattern._

/** Method types for [[ContractActor]] should be case classes subclassing [[Returning]].  The method arguments should be
  * the case class's arguments and the return type should be the type parameter of [[Returning]].
  *
  * @tparam T
  */
trait Returning[+T] extends Serializable

/** Sent to the caller when callback (a [[PartialFunction]]) does not accept the received response as input. */
case class UnhandledResponseException(s: String) extends Throwable


/** Only [[EscalateException]]'s thrown by a call handler are sent to the [[ContractActor]]'s supervisor,
  * other exceptions are sent back to the caller
  *
  * @param original The exception that generated is escalation.
  */
case class EscalateException(original: Throwable)
    extends Exception(s"Escalate exception: ${original}")

object ContractActor {
  type CallHandler = PartialFunction[Returning[_], Future[Any]]

  /** NamedCall just wraps a call along with an ID */
  case class NamedCallReturning[T](id: CallId, call: Returning[T])

}

/** Class that permits generating typesafe interfaces for Akka Actors. Actors subclassing [[ContractActor]] can be
  * wrapped in handles permit typesafe methods to call the actor's methods.
  *
  * To use the framework define in the actor's companion object the contracts of the methods that the actor supports.
  * Each contract specifies a method's type.  A contract is a subclass of [[Returning]].  The contract class's
  * arguments correspond to the method's arguments, while the type parameter for [[Returning]] corresponds to the
  * method's return type.  It is recommended to create for each [[ContractActor]] an abstract contract superclass that
  * subclasses [[Returning]] and from which all contracts for that [[ContractActor]] derive.
  *
  * To get typesafe access to a [[ContractActor]], create a handle for it; to call the method via the typesafe
  * interface, use the `call` method of a handle.
  *
  * There are three types of handles that can be used to wrap a [[ContractActor]].  When interacting
  * with a [[ContractActor]] from either a normal actor or outside of any actor,
  * one can use the [[scala.concurrent.Future]]-based interface provided by [[StandaloneHandle]].

  * To use the [[ContractActor]] framework, first define the contract for the methods to be implemented by
  * subclassing [[Returning]].  (This usually goes in the companion object to the actor you are creating.)
  * Then define the actor you are creating as a subclass of [[ContractActor]] and provide an implementation of the
  * [[ContractActor#receiveCall]] method, which processes calls which are simply messages that are instances of the
  * contract classes. Optionally also implement [[ContractActor#receiveOther]], which processes messages that are
  * outside the [[ContractActor]] framework (e.g. handling DeathWatch messages).
  *
  * Note it is important when using [[Future]]s inside a [[ContractActor]] to be careful about concurrency.  The
  * provided [[ContractActor#deferrableExecutionContext]] is recommended because it pushes all [[Future]] handlers
  * onto the callbacks map so that they are all executed inside the actor and so (by the promise that one actor only
  * executes in one thread at a time) cannot execute concurrently.  To override this default behavior another
  * [[ExecutionContext]] would have to explicitly be passed to the [[Future]] when registering handlers.
  *
  * @param timeout Default timeout to use for asynchronous calls between actors.
  */
abstract class ContractActor(timeout: Timeout) extends DeferrableActor {
  implicit val defaultTimeout = timeout

  import ContractActor._
  import DeferrableActor._

  // ***************************************************************************************************************
  // OVERVIEW OF IMPLEMENTATION
  // When one ContractActor calls another contract actor via either a SyncLikeHandle or an AsyncHandle,
  // the call (a subclass of Returning) is wrapped inside a NamedCallReturning, which simply assigns the call an ID.
  // The code that should be executed when the response is received is added to the callbacks map,
  // keyed by the ID of the call.
  //
  // The receive method of the ContractActor sends NamedCallReturning messages to the receiveCall method.  In the
  // process it automatically takes care of mapping IDs to calls and routing responses,
  // freeing the developer to focus on writing the logic of processing the call.
  //
  // The receive method of the ContractActor processes Reply messages by matching the callId in the reply to an entry
  // in the callback map.  If one is found then it calls the associated callback, otherwise some kind of exception
  // occurs (e.g. a TimeoutException if the callback has already expired).
  // ***************************************************************************************************************

  // a map of outstanding callbacks' destination and message, keyed by callID
  private val callRecords = mutable.Map.empty[CallId, (ActorRef, Returning[_])]

  /** Override this to implement handling of messages that don't subclass [[Returning]] */
  override def receive: Actor.Receive =
    super.receive orElse 
      receiveNamedCall orElse 
      receiveControlMessage orElse 
      receiveOther

  def receiveNamedCall: Actor.Receive = {
    case n: NamedCallReturning[_] =>
      _currentNamedCall = n
      processNamedCall(n)
  }

  def receiveControlMessage: Actor.Receive = {
    case Terminated(a) => processTermination(a)
  }

  protected def receiveOther: Actor.Receive = Actor.emptyBehavior

  def processTermination(a: ActorRef): Unit = cancelCallsForDest(a)

  /** Override this to implement handling of calls specified by a contract (i.e. a subclass of [[Returning]]).
    */
  protected def receiveCall: CallHandler

  /** Can be accessed inside callbacks to get current callback being processed.  However since it may be changed
    * between cps calls, it should be cached on the stack if its value needs to be consistent across possible cps
    * calls (similar to sender).
    */
  protected def currentCall = _currentNamedCall.call

  /** Same as [[currentCall]] but includes the call ID */
  protected def currentNamedCall = _currentNamedCall
  private var _currentNamedCall: NamedCallReturning[_] = _

  /** Records call's ID and processes reply.  Override to implement custom behavior, e.g. queueing calls.
    *
    */
  protected def processNamedCall(n: NamedCallReturning[_]): Unit = {
    // remember the sender on the stack since it may change between now and whenever the call completes
    val s = sender()

    // Call receiveCall and then send the reply with the correct call ID
    receiveCall(n.call) onComplete {
      case Success(r) => s ! Reply(n.id, Success(r))
      case Failure(EscalateException(original)) => throw original
      case Failure(e) => s ! Reply(n.id, Failure(e))
    }
  }

  protected override def removeCallback(callID: CallId): Unit = {
    super.removeCallback(callID)
    callRecords -= callID
  }


  /** Do the actual sending of the Akka message to the recipient.
    *
    * @param call The call to be sent
    * @param dest The actor who is procesing the call
    * @param t Time to wait for a response
    * @tparam T Return type of call
    * @return Response from actor processing the call
    */
  protected def sendCall[T: ClassTag](
                                       call: Returning[T],
                                       dest: ActorRef,
                                       delay: Option[FiniteDuration] = None
                                       )(implicit t: Timeout): Future[T] = {
    val p = Promise[Any]
    val callId = addCallback(p)
    callRecords += callId -> (dest, call)

    val namedCall = NamedCallReturning(callId, call)

    delay match {
      case None =>
        // send the message to the destination actor with the call ID just created so that when we get a response,
        // we continue processing where we left off
        dest ! namedCall
        scheduleTimeout(callId, t)
      case Some(d) =>
        context.system.scheduler.scheduleOnce(d, dest, namedCall)
        scheduleTimeout(callId, Timeout(t.duration + d))
    }

    p.future.mapTo[T]
  }

  /** Cancel all outstanding callbacks waiting for a response from another actor.
    * Useful if e.g. we know that ``d`` has failed and so will never reply to previously
    * made calls.  Causes a [[TimeoutException]] at the caller.
    */
  protected def cancelCallsForDest(d: ActorRef): Unit = {
    val idsToRemove = callRecords.filter( { x =>
      val (callId, (dest, call)) = x
      d == dest
    }).keys

    idsToRemove foreach { id =>
      self ! TimeoutCallback(id)
    }
  }

  object Handle {
    def apply[M[X] <: Returning[X]](a: ActorRef): Handle[M] = new HandleImpl[M](Left(a))
    def apply[M[X] <: Returning[X]](a: ActorSelection): Handle[M] = new HandleImpl[M](Right(a))
  }

  trait Handle[M[X] <: Returning[X]] {
    def call[T: ClassTag](c: M[T], delay: Option[FiniteDuration] = None)(implicit t: Timeout): Future[T]
  }

    /** The [[Handle]] allows a [[ContractActor]] to call methods on another [[ContractActor]] asynchronously; the
      * call returns immediately with a [[Future]], which can be used to register callbacks to be called upon
      * completion of the method.
      *
      * @param destOrSelection The destination who will process the call.  Can be implicitly converted from either an
      *                        [[ActorRef]]or an [[ActorSelection]].  For [[ActorSelection]], lookup of an
      *                        [[ActorRef]] is done on each invocation of ``call``.
      * @tparam M The contract.
      */
  private class HandleImpl[M[X] <: Returning[X]](val destOrSelection: Either[ActorRef, ActorSelection])
      extends Handle[M] {

    // watch for termination so we can immediately cancel calls that were sent to dest
    destOrSelection match {
      case Left(a) => context.watch(a)
      case _ =>
    }

    def call[T: ClassTag](c: M[T], delay: Option[FiniteDuration] = None)(implicit t: Timeout): Future[T] = {
      (destOrSelection match {
        case Left(a) => Future.successful(a)
        case Right(s) =>
          val f = s.resolveOne()(t)
          f.foreach(d => context.watch(d))
          f
      }) flatMap { dest: ActorRef =>
        sendCall(c, dest, delay)(classTag[T], t)
      }
    }

  }
}
