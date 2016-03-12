/*
 * Copyright (c) 2016. Katapal, Inc.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, you can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.katapal.actor

import java.util.concurrent.Executor

import akka.actor.Actor
import akka.util.Timeout

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException}
import scala.util.{Failure, Success, Try}



/**
 * Created by David on 4/28/2015.
 */


/** Companion object for [[DeferrableActor]]. */
object DeferrableActor {
  type Callback = Promise[Any]

  private[actor] case class TimeoutCallback(callID: CallId)

  private[actor] case class CallId(id: Long) extends AnyVal

  // Reply contains the ID of the corresponding call, along with a data
  // field that contains either the return data or an exception
  private[actor] case class Reply[T](val id: CallId, val data: Try[T])

  private val directExecutionContext =
    ExecutionContext.fromExecutor(new Executor { def execute(c: Runnable): Unit = c.run()})
}

/**
  * A [[DeferrableActor]] is an [[Actor]] that includes a special implicit [[ExecutionContext]] that schedules
  * functions to be called within the message-processing loop of the actor.  This guarantees that [[Future]]s and
  * [[Promise]]s that are executed within the [[DeferrableActor]] are thread-safe, since we are guaranteed that
  * an actor can only process one message at a time.  Because the execution class is set as implicit within the
  * definition of [[DeferrableActor]], all subclasses will automatically use it by default and no additional
  * configuration needs to be done.
  */
abstract class DeferrableActor extends Actor with akka.actor.ActorLogging {
  import DeferrableActor._

  // a map of callbacks that are awaiting responses, keyed by the ID of the callback
  private val callbacks = mutable.Map.empty[CallId, Callback]

  // call IDs are assigned using an incrementing counter
  private var callCounter = 0L

  /** Returns true if there is at least one call currently outstanding. */
  protected def alreadyRunning: Boolean = callbacks.nonEmpty

  /**
    * The default execution context in this [[DeferrableActor]].  The [[DeferrableActor]] keeps a map that maps
    * [[CallId]]s to [[Promise]]s that need to be executed.
    *
    * It schedules a function to be run by adding a [[Promise]] that executes when fulfilled.  The [[Promise]]s are
    * fulfilled when a [[Reply]] is received specifying the [[Promise]] to be executed.  If the [[Promise]] needs to be
    * fulfilled with a value then the [[Reply]] should also provide that value.
    *
    * Scheduled [[Promise]]s can be interrupted with a [[TimeoutException]] if a [[TimeoutCallback]] with the
    * corresponding [[CallId]] is received.
    */
  protected implicit val deferrableExecutionContext = ExecutionContext.fromExecutor(new Executor {
    def execute(command: Runnable): Unit = {
      // add the runnable as a callback
      val p = Promise[Any]
      p.future.onComplete({ case _ => command.run() })(directExecutionContext)
      val callId = addCallback(p)

      // issue the Reply that runs the callback
      self ! Reply(callId, Success(()))
    }
  })

  override def aroundReceive(receive: Actor.Receive, msg: Any): Unit = {
    (receiveDeferrable orElse receive).applyOrElse(msg, unhandled)
  }

  final private def receiveDeferrable: Actor.Receive = {
    // A control message to cancel a callback due to the timeout being exceeded.
    case TimeoutCallback(callID) => callbacks.get(callID) match {
      case Some(p) =>
        // notify the callback that we timed out
        p.failure(new TimeoutException(s"Callback $callID timed out"))
        removeCallback(callID)

      case None =>
      // it's possible a Reply was received while the TimeoutCallback was in the message inbox waiting to be
      // processed; this should be fine so just act as if timeout did not occur
    }

    // Process a response to a callback. Since the reply does not necessarily come from original destination
    // (since it may have been forwarded), identify the call using *only* its ID.
    case r: Reply[Any] =>

      callbacks.get(r.id) match {
        case Some(p) =>
          p.complete(r.data)
          removeCallback(r.id)
        case None =>
          // Since the callbacks are created in order, if we don't find a callback it means that the callback was
          // cancelled due to timeout.  It could also mean that two Reply's with the same call ID were received. This
          // is possible e.g. if a call was sent to an ActorSelection that references more than one actor,
          // and they both reply. In this case the first Reply would have already been processed and the second reply
          // would be logged as a warning and then ignored.
          if (r.id.id < callCounter)
            log.warning(s"$r received from $sender after callback expired")
      }
  }

  /**
    * Schedule a cancellation for the callback for callID after t has elapsed
    *
    * @param callID The call to time out
    * @param t The time to wait
    * @return A [[akka.actor.Cancellable]] that can be used to cancel the timeout
    */
  protected def scheduleTimeout(callID: CallId, t: Timeout) = {
    context.system.scheduler.scheduleOnce(
      t.duration, self, TimeoutCallback(callID)
    )
  }

  /** Add a callback onto the callbacks map
    *
    * @param callback The callback to be added.
    * @return The call ID for this callback as it was added to the callbacks map.
    */
  protected def addCallback(callback: Promise[Any]): CallId = {

    val callId: CallId = CallId(callCounter)
    callCounter += 1

    callbacks += callId -> callback

    callId
  }

  /**
    * Remove a callback
    *
    * @param callID The ID of the callback to remove
    */
  protected def removeCallback(callID: CallId): Unit = {
    callbacks -= callID
  }
}