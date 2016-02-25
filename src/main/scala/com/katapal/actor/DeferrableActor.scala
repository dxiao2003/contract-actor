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

  case class TimeoutCallback(callID: CallId)

  case class CallId(id: Long) extends AnyVal

  // Reply contains the ID of the corresponding call, along with a data
  // field that contains either the return data or an exception
  case class Reply[T](val id: CallId, val data: Try[T])
  val directExecutionContext = ExecutionContext.fromExecutor(new Executor { def execute(c: Runnable): Unit = c.run()})
}


abstract class DeferrableActor extends Actor with akka.actor.ActorLogging {
  import DeferrableActor._

  // a map of callbacks that are awaiting responses, keyed by the ID of the callback
  private val callbacks = mutable.Map.empty[CallId, Callback]

  // call IDs are assigned using an incrementing counter
  private var callCounter = 0L

  /** Returns true if there is a call currently running */
  protected def alreadyRunning: Boolean = callbacks.nonEmpty

  // The execution context for Future's in this DeferrableActor.  This execution context is bound to the
  // Deferrable and executes a Runnable by adding it as a callback and immediately sending the Reply that makes it
  // run.
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

  def receive: Actor.Receive = receiveDeferrable

  def receiveDeferrable: Actor.Receive = {
    // A control message to cancel a callback due to the timeout being exceeded.
    case TimeoutCallback(callID) => callbacks.get(callID) match {
      case Some(p) =>
        // notify the callback that we timed out
        p.failure(new TimeoutException(s"Callback $callID timed out"))
        removeCallback(callID)

      case None =>
      // it's possible the response was received while the TimeoutCallback was waiting to be processed;
      // this should be fine so just act as if timeout did not occur
    }

    // Process a response to a callback. Since reply does not necessarily come from original destination
    // (since it may have been forwarded), so identify it using *only* its ID.
    case r: Reply[_] =>

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

  /** Schedule a cancellation for the callback for callID after t has elapsed */
  protected def scheduleTimeout(callID: CallId, t: Timeout) = {
    context.system.scheduler.scheduleOnce(
      t.duration, self, TimeoutCallback(callID)
    )
  }

  /** Add a callback onto the callbacks map
    *
    * @param callback The callback to be added.
    * @return The key for this callback as it was added to the callbacks map.
    */
  protected def addCallback(callback: Promise[Any]): CallId = {

    val callId: CallId = CallId(callCounter)
    callCounter += 1

    callbacks += callId -> callback

    callId
  }

  protected def removeCallback(callID: CallId): Unit = {
    callbacks -= callID
  }

  /** Wait for all [[scala.concurrent.Future]]s in `futureMap` to complete and return a map of the results.  The
    * results are in the form of [[Try]]s so we can see each single exception rather than an overall failure if some
    * of the [[Future]]s end in failure.  As with [[ContractActor#deferAndAwait]], this does not block; if the
    * [[Future]]s are not ready immediately it registers a callback and then releases the current thread.
    *
    * @param fm A keyed map of [[Future]]s.
    * @tparam K The type of key.
    * @tparam T The type of [[Future]].
    * @return A keyed map of the results of the [[Future]]s.  Each entry contains either [[Success]] if the
    *         corresponding [[Future]] succeeded or [[Failure]] otherwise.
    */
  protected def futureMap[K,T](fm: Map[K,Future[T]]) : Future[Map[K,Try[T]]] = {
    new FutureCollector(fm).future
  }

  protected def executeNow[T](x: => T): Future[T] = {
    Future.fromTry(Try(x))
  }

  /** Convenience class for collecting futures in an [[Iterable]] together.
    *
    * @param futureMap The [[Map]] of [[Future]]s we want to collect together.
    * @tparam T
    */
  private class FutureCollector[K, T](futureMap: Map[K, Future[T]]) {

    private val incompleteFutures: mutable.HashSet[Future[T]] = mutable.HashSet.empty[Future[T]]
    private val values: mutable.HashMap[K, Try[T]] = mutable.HashMap.empty[K, Try[T]]

    private val promise = Promise[Map[K, Try[T]]]()

    futureMap foreach { case (k, f) =>
      // register callback on each future to notify the collector when they are done
      f.value match {
        case Some(Success(x)) => values += ((k, Success(x)))
        case Some(Failure(e)) => values += ((k, Failure(e)))
        case None =>
          log.debug(s"registering callback for future $f in collector")
          incompleteFutures += f
          f onComplete { t =>
            log.debug(s"future $f in collector completed")
            incompleteFutures -= f
            t match {
              case Success(x) => values += ((k, Success(x)))
              case Failure(e) => values += ((k, Failure(e)))
            }

            finishIfComplete()
          }
      }
    }

    finishIfComplete()

    private def finishIfComplete() = {
      log.debug(s"checking incomplete futures: $incompleteFutures")
      if (incompleteFutures.isEmpty) {
        if (!(futureMap.values forall {
          _.isCompleted
        }))
          throw new UnknownError("Futures should be all completed but not")
        else
          promise.success(values.toMap)
      }
    }

    /** @return A [[Future]] that is completed when all of the [[Future]]s in the sequence are completed. */
    def future: Future[Map[K, Try[T]]] = promise.future
  }

}