package com.katapal

import akka.actor.{ActorRef, ActorSelection}
import akka.routing.Router

import scala.language.implicitConversions

/** Katapal actor package containing [[com.katapal.actor.ContractActor]], a typesafe way of calling actors. */

package object actor {

  /** Convenience interface allowing us to call either an [[akka.actor.ActorRef]] or [[akka.actor.ActorSelection]].
    * Implicit conversions from [[ActorRef]] and [[ActorSelection]] are provided.
    */
  trait ActorRefLike extends Any {
    def tell(msg: Any, sender: ActorRef): Unit
  }

  private case class AR(a:ActorRef) extends AnyVal with ActorRefLike {
    def tell(msg: Any, sender: ActorRef): Unit = {
      a.!(msg)(sender)
    }
  }
  private case class AS(a:ActorSelection) extends AnyVal with ActorRefLike {
    def tell(msg: Any, sender: ActorRef): Unit = {
      a.!(msg)(sender)
    }
  }
  private case class R(r: Router) extends AnyVal with ActorRefLike {
    def tell(msg: Any, sender: ActorRef): Unit = {
      r.route(msg, sender)
    }
  }

  implicit def actorRefToAR(a: ActorRef): ActorRefLike = AR(a)
  implicit def actorSelToAS(a: ActorSelection): ActorRefLike = AS(a)
  implicit def routerToR(r: Router): ActorRefLike = R(r)
}
