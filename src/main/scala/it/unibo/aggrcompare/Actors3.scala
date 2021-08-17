package it.unibo.aggrcompare

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import it.unibo.scafi.space.Point3D

trait DeviceProtocol
object DeviceProtocol {
  case class SetSensor[T](name: String, value: T) extends DeviceProtocol
  case class SetNbrSensor[T](name: String, nbr: String, value: T) extends DeviceProtocol
  case class Compute(what: String) extends DeviceProtocol
}

object Sensors {
  val neighbors = "nbrs"
}

/**
 * 3rd attempt: OOP style
 */
abstract class DeviceActor[T](context: ActorContext[DeviceProtocol]) extends AbstractBehavior(context) {
  import DeviceProtocol._
  var localSensors = Map[String,Any]()

  def sense[T](name: String): T = localSensors(name).asInstanceOf[T]
  def nbrSense[T](name: String)(id: String) = localSensors(name).asInstanceOf[Map[String,T]](id)
  def neighbors: Set[String] = sense(Sensors.neighbors)
  def name: String = context.self.path.name

  def compute(what: String): T

  override def onMessage(msg: DeviceProtocol): Behavior[DeviceProtocol] = Behaviors.receiveMessage {
    case SetSensor(name, value) =>
      localSensors += name -> value
      this
    case SetNbrSensor(name, nbr, value) =>
      val sval: Map[String,Any] = localSensors.getOrElse(name, Map.empty).asInstanceOf[Map[String,Any]]
      localSensors += name -> (sval + (nbr -> value))
      this
    case Compute(what) =>
      val result = compute(what)
      context.self ! SetNbrSensor(what, name, result)
      this
  }
}
object DeviceActor {
  def apply[T](c: String => T): Behavior[DeviceProtocol] = Behaviors.setup(ctx => new DeviceActor[T](ctx) {
    override def compute(what: String): T = c(what)
  })
}

object Actors3 extends App {
  println("Actors implementation")

  val system = ActorSystem[C.Start.type](Behaviors.setup { ctx =>
    var map = Map[Int, ActorRef[DeviceProtocol]]()
    for(i <- 1 to 10) {
      map += i -> ctx.spawn(DeviceActor(_ => "fuck"),s"device-${i}")
    }

    Behaviors.ignore
  }, "ActorBasedChannel")
}