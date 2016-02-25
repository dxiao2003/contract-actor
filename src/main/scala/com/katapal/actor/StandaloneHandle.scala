package com.katapal.actor

import akka.actor.{ActorRef, ActorSystem, Props, Status}
import akka.pattern.ask
import akka.util.Timeout
import com.katapal.actor.ContractActor.CallHandler

import scala.concurrent._
import scala.language.higherKinds
import scala.reflect.ClassTag
import scala.util.{Failure, Success}


private[actor] class DummyContractActor(dest: ActorRef, timeout: Timeout)
    extends ContractActor(timeout) {
  implicit val t = timeout
  def receiveCall: CallHandler = ???

  override def receiveOther = {
    case c : Returning[_] =>
      val s = sender
      sendCall[Any](c, dest) onComplete {
        case Success(r) => s ! r
        case Failure(e) => s ! Status.Failure(e)
      }

  }
}

/** A handle to use to call [[com.katapal.actor.ContractActor]]s from _outside_ [[ContractActor]] code.  (From
  * inside a [[ContractActor]] it's recommended to use an [[ContractActor#AsyncHandle]].)  This creates a dummy
  * [[ContractActor]] that sends/receives messages.  Replies are handled by registering callbacks on the returned
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

