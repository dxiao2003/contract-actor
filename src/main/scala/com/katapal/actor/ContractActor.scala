/*
 * Copyright (c) 2016. Katapal, Inc.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, you can obtain one at http://mozilla.org/MPL/2.0/.
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


object ContractActor {
  type CallHandler = PartialFunction[Returning[_], Future[Any]]

  /** Method types for [[ContractActor]] should be case classes subclassing [[Returning]].  The method arguments
    * should be the case class's arguments and the return type should be the type parameter of [[Returning]].
    *
    * @tparam T
    */
  trait Returning[+T] extends Serializable

  /**
    * [[NamedCallReturning]] just wraps a call along with an ID
    * @param id The ID of this call, to be used in the reply to specify
    * @param call The call and its arguments
    * @tparam T
    */
  case class NamedCallReturning[T](id: CallId, call: Returning[T])

}

/** Class that permits generating typesafe interfaces for Akka Actors.
  *
  * To use the framework define the contract of the methods that the actor supports.
  * A contract is a subclass of [[com.katapal.actor.ContractActor.Returning]].  The contract class's parameters
  * correspond to the method's arguments, while the type parameter for [[com.katapal.actor.ContractActor.Returning]]
  * corresponds to the method's return type.  To create a contract consisting of multiple methods, create one
  * parameterized subclass of [[com.katapal.actor.ContractActor.Returning]] that all other methods will inherit from.
  * For example
  *
  * {{{
  *   object Dog {
  *     sealed abstract class Message[T] extends Returning[T]
  *     case class Feed(dogfood: Food) extends Message[Cuddles]
  *   }
  *
  *   class Dog(t: Timeout) extends ContractActor(t) {
  *     override def receiveCall: CallHandler = {
  *       case Feed(dogfood) => Future.value(new Cuddles())
  *     }
  *   }
  * }}}
  *
  * To implement the functionality, create a subclass of [[ContractActor]] and provide an
  * implementation of the [[ContractActor#receiveCall]] method that handles all messages defined in the contract.
  * Optionally also implement [[ContractActor#receiveOther]], which processes messages that are
  * outside the [[ContractActor]] framework (e.g. handling DeathWatch messages).
  *
  * [[ContractActor#receiveCall]] should return a [[Future]].  All [[Exception]]s will be sent back to the caller
  * unless wrapped in an [[EscalateException]].  [[EscalateException]] instances will be processed as a normal
  * [[Actor]] would (by notifying the supervisor).
  *
  * To get typesafe access to a [[ContractActor]] from within the definition of another [[ContractActor]], create a
  * [[ContractActor#Handle]] for it; to make the typesafe call, use the [[ContractActor#Handle.call]] method of the
  * handle.  To get access  from outside a [[ContractActor]], use a [[StandaloneHandle]].
  *
  * Note that because [[ContractActor]] is a subclass of [[DeferrableActor]], the asynchronous calls inside a
  * [[ContractActor]] including [[ContractActor#Handle.call]] will by default be executed using
  * [[DeferrableActor#deferrableExecutionContext]], and therefore the callback functions will be threadsafe.
  *
  * @param timeout Default timeout to use for asynchronous calls between actors.
  */
abstract class ContractActor(timeout: Timeout) extends DeferrableActor {
  implicit val defaultTimeout = timeout

  import ContractActor._
  import DeferrableActor._

  // ***************************************************************************************************************
  // OVERVIEW OF IMPLEMENTATION
  // When one ContractActor calls another contract actor,
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

  /**
    * Subclasses are not allowed to override [[ContractActor.receive]] to prevent access to the call processing
    * pipeline. It is possible for the child of [[ContractActor]] to process non-contract messages; to do so,
    * override the [[ContractActor.receiveOther]] method.
    *
    */
  final override def receive: Actor.Receive =
      receiveNamedCall orElse
      receiveControlMessage orElse 
      receiveOther

  protected def receiveNamedCall: Actor.Receive = {
    case n: NamedCallReturning[_] =>
      _currentNamedCall = n
      processNamedCall(n)
  }

  protected def receiveControlMessage: Actor.Receive = {
    case Terminated(a) => processTermination(a)
  }

  /** Override this to implement handling of messages that don't subclass [[Returning]] */
  protected def receiveOther: Actor.Receive = Actor.emptyBehavior

  /**
    * Called when we are notified that an [[ActorRef]] we are watching terminates.  By default cancels all calls that
    * were sent to this [[ActorRef]].  Override to impelment custom behavior.
    *
    * @param a The [[ActorRef]] that has terminated
    */
  protected def processTermination(a: ActorRef): Unit = cancelCallsForDest(a)

  /** Override this to implement handling of calls specified by a contract (i.e. a subclass of [[Returning]]).  Care
    * must be taken that each call returns a [[Future]] with the correct parameterized type, since this can't be
    * enforced by the compiler.
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

  /**
    * Records call's ID and processes reply.  Override to implement custom behavior, e.g. queueing calls.
    * @param n The call to process
    */
  protected def processNamedCall(n: NamedCallReturning[_]): Unit = {
    // remember the sender on the stack since it may change between now and whenever the call completes
    val s = sender()

    // Call receiveCall and then send the reply with the correct call ID
    receiveCall.applyOrElse(n.call, unhandledCall) onComplete {
      case Success(r) => s ! Reply(n.id, Success(r))
      case Failure(EscalateException(original)) => throw original
      case Failure(e) => s ! Reply(n.id, Failure(e))
    }
  }

  private def unhandledCall: CallHandler = {
    case c: Returning[_] => Future.failed(UnhandledCallException(c))
  }

  /**
    * Remove a callback from the list of outstanding calls.
    * @param callID The ID of the callback to remove
    */
  protected override def removeCallback(callID: CallId): Unit = {
    super.removeCallback(callID)
    callRecords -= callID
  }


  /** Do the actual sending of the Akka message to the recipient.
    *
    * @param call The call to be sent
    * @param dest The actor who is procesing the call
    * @param delay An optional delay to wait before sending the call
    * @param t Time to wait for a response
    * @tparam T Return type of call
    * @return Response from actor processing the call
    */
  protected def sendCall[T: ClassTag](call: Returning[T],
                                      dest: ActorRef,
                                      delay: Option[FiniteDuration] = None)
                                     (implicit t: Timeout): Future[T] = {
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

  /**
    * Cancel all outstanding callbacks waiting for a response from another actor.
    * Useful if e.g. we know that ``d`` has failed and so will never reply to previously
    * made calls.  Raises a [[TimeoutException]] at the caller.
    *
    * @param d The [[ActorRef]] to cancel calls for
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
    /**
      * Create a [[Handle]] for an [[ActorRef]]
      * @param a The [[ActorRef]] to create a handle for
      * @tparam M The contract class
      * @return The handle
      */
    def apply[M[X] <: Returning[X]](a: ActorRef): Handle[M] = new HandleImpl[M](Left(a))

    /**
      * Create a [[Handle]] for an [[ActorSelection]]
      * @param a The [[ActorSelection]] to create a handle for
      * @tparam M The contract class
      * @return The handle
      */
    def apply[M[X] <: Returning[X]](a: ActorSelection): Handle[M] = new HandleImpl[M](Right(a))
  }

  /**
    * Handle to use to call another [[ContractActor]].  To use, wrap an [[ActorRef]] with the handle.
    * {{{
    *   class Owner(t: Timeout, dogActor: ActorRef) extends ContractActor(t) {
    *     val dog = new Handle[Dog.Message](dogActor)
    *
    *     def receive: Actor.Receive = {
    *       case FeedReminder =>
    *         dog.call(Feed(Food.dogfood())) foreach { cuddles =>
    *           println("Got some " + cuddles)
    *         }
    *     }
    *   }
    * }}}
    * @tparam M
    */
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
