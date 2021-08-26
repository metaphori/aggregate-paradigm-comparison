package it.unibo.aggrcompare

import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import it.unibo.scafi.space.Point3D

import scala.concurrent.duration.DurationInt
import scala.util.Random

trait DeviceProtocol
object DeviceProtocol {
  case class SetSensor[T](name: String, value: T) extends DeviceProtocol
  case class AddToMapSensor[T](name: String, value: T) extends DeviceProtocol
  case class RemoveToMapSensor[T](name: String, value: T) extends DeviceProtocol
  case class SetNbrSensor[T](name: String, nbr: ActorRef[DeviceProtocol], value: T) extends DeviceProtocol
  case class Compute(what: String) extends DeviceProtocol
  case class AddNeighbour(nbr: ActorRef[DeviceProtocol]) extends DeviceProtocol
  case class RemoveNeighbour(nbr: ActorRef[DeviceProtocol]) extends DeviceProtocol
}

object Sensors {
  val nbrRange = "nbrRange"
  val source = "source"
  val position = "position"
  val neighbors = "nbrs"
}

object Data {
  val gradient = "gradient"
}

/**
 * 3rd attempt: OOP style
 */
abstract class DeviceActor[T](context: ActorContext[DeviceProtocol]) extends AbstractBehavior(context) {
  import DeviceProtocol._
  var localSensors = Map[String,Any]()
  type Nbr = ActorRef[DeviceProtocol]

  def sense[T](name: String): T = localSensors(name).asInstanceOf[T]
  def senseOrElse[T](name: String, default: => T): T = localSensors.getOrElse(name, default).asInstanceOf[T]
  def nbrSense[T](name: String)(id: Nbr): T = nbrValue(name)(id)
  def nbrValue[T](name: String): Map[Nbr,T] = senseOrElse[Map[Nbr,T]](name, Map.empty)
  def neighbors: Set[ActorRef[DeviceProtocol]] = nbrValue[Map[Nbr,Any]](Sensors.neighbors).keySet
  def name: String = context.self.path.name

  def compute(what: String, d: DeviceActor[T]): T

  override def onMessage(msg: DeviceProtocol): Behavior[DeviceProtocol] = Behaviors.withTimers{ timers => Behaviors.receiveMessage {
    case SetSensor(name, value) =>
      localSensors += (name -> value)
      this
    case AddToMapSensor(name, value: Tuple2[Any,Any]) =>
      localSensors += name -> (localSensors.getOrElse(name, Map.empty).asInstanceOf[Map[Any,Any]] + value)
      this
    case RemoveToMapSensor(name, value: Tuple2[Any,Any]) =>
      localSensors += name -> (localSensors.getOrElse(name, Map.empty).asInstanceOf[Map[Any,Any]] - value._1)
      this
    case SetNbrSensor(name, nbr, value) =>
      val sval: Map[ActorRef[DeviceProtocol],Any] = localSensors.getOrElse(name, Map.empty).asInstanceOf[Map[Nbr,Any]]
      localSensors += name -> (sval + (nbr -> value))
      this
    case Compute(what) =>
      val result = compute(what, this)
      context.self ! SetNbrSensor(what, context.self, result)
      neighbors.foreach(_ ! SetNbrSensor(what, context.self, result))
      timers.startSingleTimer(Compute(what), 2.seconds * Random.nextInt(2))
      context.log.info(s"${name} computes: ${result}")
      this
    case AddNeighbour(nbr) =>
      context.self ! AddToMapSensor(Sensors.neighbors, nbr -> true)
      context.self ! AddToMapSensor(Sensors.nbrRange, nbr -> 1.0)
      this
    case RemoveNeighbour(nbr) =>
      context.self ! RemoveToMapSensor(Sensors.neighbors, nbr -> false)
      this
  } }
}

object DeviceActor {
  def apply[T](c: (String, DeviceActor[T]) => T): Behavior[DeviceProtocol] = Behaviors.setup(ctx => new DeviceActor[T](ctx) {
    override def compute(what: String, deviceActor: DeviceActor[T]): T = c(what, deviceActor)
  })
}

object Actors3 extends App {
  import DeviceProtocol._
  println("Actors implementation")

  val system = ActorSystem[C.Start.type](Behaviors.setup { ctx =>
    var map = Map[Int, ActorRef[DeviceProtocol]]()
    for(i <- 1 to 10) {
      map += i -> ctx.spawn(DeviceActor[Double]((_,d) => {
        println(s"${d.name}'s context: ${d.localSensors}")
        val (nbr -> nbrg) = d.nbrValue[Double](Data.gradient)
          .minByOption(_._2.asInstanceOf[Double])
          .getOrElse("" -> Double.PositiveInfinity)
        val g = nbrg + 1.0 // d.nbrSense[Double](Sensors.nbrRange)(nbr)
        if(d.senseOrElse("source", false)) 0 else g
      }),s"device-${i}")
    }

    map.keys.foreach(d => {
      map(d) ! SetSensor(Sensors.position, Point3D(d,0,0))
      map(d) ! SetSensor(Sensors.source, false)
      if(d>1) map(d) ! AddNeighbour(map(d - 1))
      if(d<10) map(d) ! AddNeighbour(map(d + 1))
    })

    map(3) ! SetSensor(Sensors.source, true)

    map.values.foreach(_ ! Compute(Data.gradient))

    Behaviors.ignore
  }, "ActorBasedChannel")
}