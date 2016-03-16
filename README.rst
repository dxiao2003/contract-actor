Contract Actors for Scala/Akka
==============================

This package defines a framework to use Akka actors in a typesafe way.  The framework allows you to create typesafe
wrappers around ``ActorRef``'s that conform to a pre-defined contract of supported messages and expected responses.

``ContractActor``
-----------------

To use the framework, define the contract of the methods that the actor supports.
A contract is a subclass of ``ContractActor.Returning``.  The contract class's parameters
correspond to the method's arguments, while the type parameter for ``Returning``
corresponds to the method's return type.  To create a contract consisting of multiple methods, create one
parameterized subclass of ``Returning`` that all other methods will inherit from.
For example:

.. code-block:: scala

  object Dog {
     sealed abstract class Message[T] extends Returning[T]
     case class Feed(dogfood: Food) extends Message[Cuddles]
     case object Pet extends Message[Lick]
  }

To implement the functionality, create a subclass of ``ContractActor`` and provide an
implementation of the ``ContractActor#receiveCall`` method that handles all messages defined in the contract.
Optionally also implement ``ContractActor#receiveOther``, which processes messages that are
outside the ``ContractActor`` framework (e.g. handling ``DeathWatch`` messages).

.. code-block:: scala

  class Dog(t: Timeout) extends ContractActor(t) {
    override def receiveCall: CallHandler = {
      case Feed(dogfood) => Future.successful(Cuddles())
      case Pet => Future.successful(Lick())
    }
  }


Note that ``ContractActor#receiveCall`` should return a ``Future`` containing the expected type.  All ``Exception``'s
will be sent back to the caller unless wrapped in an ``EscalateException``.  ``EscalateException`` instances will be
processed as a normal ``Actor`` would (by notifying the supervisor).

To get typesafe access to a ``ContractActor`` from within the definition of another ``ContractActor``, create a
``ContractActor#Handle`` for it; to make the typesafe call, use the ``ContractActor#Handle.call`` method of the
handle.  To get access  from outside a ``ContractActor``, use a ``StandaloneHandle``.

.. code-block:: scala

   class Owner(t: Timeout, dogActor: ActorRef) extends ContractActor(t) {
     val dog = new Handle[Dog.Message](dogActor)

     def receive: Actor.Receive = {
       case FeedReminder =>
         dog.call(Feed(Food.dogfood())) foreach { cuddles =>
           println("Got some " + cuddles)
         }
     }
   }

``ContractActor`` is a subclass of ``DeferrableActor``, which provide a facility for executing ``Future``'s inside
the body of an ``Actor``.

``DeferrableActor``
-------------------

A ``DeferrableActor`` is an ``Actor`` that includes a special implicit ``ExecutionContext`` that schedules
functions to be called within the message-processing loop of the actor.  This guarantees that ``Future``'s and
``Promise``'s that are used within the ``DeferrableActor`` are thread-safe, since we are guaranteed that
an actor can only process one message at a time.  Because the execution class is set as implicit within the
definition of ``DeferrableActor``, all subclasses will automatically use it by default and no additional
configuration needs to be done.
