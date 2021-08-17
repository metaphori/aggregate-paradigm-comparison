package it.unibo.aggrcompare

import akka.actor.typed.receptionist.Receptionist.{Find, Listing}
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.util.Timeout
import it.unibo.scafi.space.Point3D

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.util.{Success, Try}

object C {
  case object Start
}

/**
 * 1st attempt: an actor computing a gradient, atomic behaviour, no separation of concerns
 */
object Gradient {
  sealed trait Msg
  case class SetSource(isSource: Boolean) extends Msg
  case class SetPosition(point: Point3D) extends Msg
  case class GetPosition(replyTo: ActorRef[NbrPos]) extends Msg
  case class SetDistance(distance: Double, from: ActorRef[Msg]) extends Msg
  case object ComputeGradient extends Msg
  case class QueryGradient(replyTo: ActorRef[NbrGradient]) extends Msg
  case class SetNeighbourGradient(distance: Double, from: ActorRef[Msg]) extends Msg
  case class AddNeighbour(nbr: ActorRef[Msg]) extends Msg
  case class RemoveNeighbour(nbr: ActorRef[Msg]) extends Msg
  case object Round extends Msg

  case class NbrPos(position: Point3D, nbr: ActorRef[Msg])
  case class NbrGradient(gradient: Double, nbr: ActorRef[Msg])

  def apply(source: Boolean,
            gradient: Double,
            nbrs: Set[ActorRef[Msg]],
            distances: Map[ActorRef[Msg],Double],
            nbrGradients: Map[ActorRef[Msg],Double],
            position: Point3D = Point3D(0,0,0)): Behavior[Msg] = Behaviors.setup{ ctx =>
    val getPositionAdapter: ActorRef[NbrPos] = ctx.messageAdapter(m => SetDistance(m.position.distance(position), m.nbr))
    val getGradientAdapter: ActorRef[NbrGradient] = ctx.messageAdapter(m => SetNeighbourGradient(m.gradient, m.nbr))

    Behaviors.withTimers { timers => Behaviors.receive { case (ctx,msg) =>
    msg match {
      case SetSource(s) =>
        Gradient(s, 0, nbrs, distances, nbrGradients, position)
      case AddNeighbour(nbr) =>
        Gradient(source, gradient, nbrs + nbr, distances, nbrGradients, position)
      case RemoveNeighbour(nbr) =>
        Gradient(source, gradient, nbrs - nbr, distances, nbrGradients, position)
      case SetPosition(p) =>
        Gradient(source, gradient, nbrs, distances, nbrGradients, p)
      case GetPosition(replyTo) =>
        replyTo ! NbrPos(position, ctx.self)
        Behaviors.same
      case SetDistance(d, from) =>
        Gradient(source, gradient, nbrs, distances + (from -> d), nbrGradients, position)
      case ComputeGradient => {
        val newNbrGradients = nbrGradients + (ctx.self -> gradient)

        // Once gradient is computed, start the next round in a second
        timers.startSingleTimer(Round, 1.second)

        ctx.log.info(s"${ctx.self.path.name} CONTEXT\n${distances}\n${nbrGradients}")

        if(source){
          ctx.log.info(s"GRADIENT (SOURCE): ${ctx.self.path.name} -> ${gradient}")
          Gradient(source, 0, nbrs, distances, newNbrGradients, position)
        } else {
          val minNbr = newNbrGradients.minByOption(_._2)
          val updatedG = minNbr.map(_._2).getOrElse(Double.PositiveInfinity) + minNbr.flatMap(n => distances.get(n._1)).getOrElse(Double.PositiveInfinity)
          ctx.log.info(s"GRADIENT: ${ctx.self.path.name} -> ${updatedG}")
          Gradient(source, updatedG, nbrs, distances, nbrGradients + (ctx.self -> updatedG), position)
        }
      }
      case QueryGradient(replyTo) => {
        replyTo ! NbrGradient(gradient, ctx.self)
        Behaviors.same
      }
      case SetNeighbourGradient(d, from) =>
        Gradient(source, gradient, nbrs, distances, nbrGradients + (from -> d), position)
      case Round => {
        nbrs.foreach(nbr => {
          // Query neighbour for neighbouring sensors
          nbr ! GetPosition(getPositionAdapter)
          // Query neighbour for application data
          nbr ! QueryGradient(getGradientAdapter)
        })
        timers.startSingleTimer(ComputeGradient, 1.seconds)
        Behaviors.same
      }
    }
  } } }
}

/**
 * 2nd attempt: generalisation and separation of concerns (context management & logic)
 */
object AggrActor {
  sealed trait Msg
  case class SetSensor[T](v: SensorValue[T]) extends Msg
  case class GetSensor[T](name: String, replyTo: ActorRef[SensorValue[T]]) extends Msg
  case class SetNeighbourValue[T](name: String, nbr: ActorRef[Msg], value: T) extends Msg
  case class GetNeighbourValue[T](name: String, replyTo: ActorRef[NbrValue[T]]) extends Msg

  case class SensorValue[T](name: String, value: T)
  case class NbrValue[T](name: String, value: T)

  case object Round extends Msg

  val deviceSK = ServiceKey[SensorBehavior]("devices")

  def apply(id: Int, position: Point3D): Behavior[Any] = Behaviors.setup { ctx =>
    Behaviors.withTimers { timers =>
      implicit val ec: ExecutionContext = ctx.system.executionContext
      implicit val sched: Scheduler = ctx.system.scheduler // for ask pattern
      implicit val timeout: Timeout = Timeout(3.seconds) // for ask pattern

      val stateActor = ctx.spawn(state(Map.empty, Map.empty), "state")
      val locationSensor = ctx.spawn(sensor[Int,Point3D]("position", 0, position, (s,t,c) => (s,t)), "sensor-position")
      locationSensor ! ProduceTo(stateActor)

      val rec = ctx.system.receptionist

      val topologyManager = ctx.spawn(sensor[Int,Set[ActorRef[Msg]]]("topology-manager", id, Set.empty, (devId,nbrs,ctx) => {
        val f: Future[Listing] = rec.ask(Find(deviceSK))
        f.onComplete(r => ctx.self ! SetValue(r.get.getAllServiceInstances(deviceSK)))
        (devId, nbrs)
      }), "sensor-topology-manager")
      rec ! Receptionist.register(deviceSK, topologyManager)

      Behaviors.empty
  } }

  def state(sensors: Map[String,Any],
            nbrValues: Map[String,Map[ActorRef[Msg],Any]]): Behavior[Msg] = Behaviors.setup { ctx =>
    Behaviors.withTimers { timers =>
      Behaviors.receive { case (ctx, msg) =>
        msg match {
          case GetSensor(name, replyTo) =>
            replyTo ! SensorValue(name, sensors(name))
            Behaviors.same
          case SetSensor(SensorValue(name,value)) =>
            state(sensors + (name -> value), nbrValues)
          case GetNeighbourValue(name, replyTo) =>
            nbrValues.get(name).flatMap(_.get(ctx.self)).foreach(v =>
              replyTo ! NbrValue(name, v)
            )
            Behaviors.same
          case SetNeighbourValue(name, nbr, value) =>
            state(sensors, nbrValues + (name -> (nbrValues.getOrElse(name, Map.empty) ++ Map(nbr -> value))))
        }
      }
    }
  }

  case class Compute[T](replyTo: ActorRef[ComputeResults[T]])
  case class ComputeResults[T](name: String, value: T)
  case class State(sensors: Map[String,Any],
                   nbrValues: Map[String,Map[ActorRef[Msg],Any]])

  def compute[T](parent: ActorRef[Any], name: String, state: State, logic: State => ComputeResults[T]): Behavior[Compute[T]] = Behaviors.setup { ctx =>
    Behaviors.withTimers { timers =>
      Behaviors.receive { case (ctx, Compute(replyTo)) =>
        val results = logic(state)
        replyTo ! results
        compute(parent, name, state.copy(nbrValues =
          state.nbrValues + (name -> (state.nbrValues.getOrElse(name, Map.empty) ++ Map(parent -> results.value)))
        ), logic)
      }
    }
  }

  trait SensorBehavior
  case object Sample extends SensorBehavior
  case class ProduceTo(recipient: ActorRef[Msg]) extends SensorBehavior
  case class SetValue[T](v: T) extends SensorBehavior

  def sensor[S,T](name: String, state: S, value: T, evolve: (S,T,ActorContext[SensorBehavior]) => (S,T)): Behaviors.Receive[SensorBehavior] = Behaviors.receive {
    case (ctx, ProduceTo(recipient)) =>
      recipient ! SetSensor(SensorValue(name, value))
      Behaviors.same
    case (ctx, Sample) =>
      val (newState, newValue) = evolve(state, value, ctx)
      sensor(name, newState, newValue, evolve)
    case (ctx, SetValue(v: T)) =>
      sensor[S,T](name, state, v, evolve)
  }
}


object Actors1 extends App {
  println("Actors implementation")

  val system = ActorSystem[C.Start.type](Behaviors.setup { ctx =>
    var map = Map[Int, ActorRef[Gradient.Msg]]()
    for(i <- 1 to 10) {
      map += i -> ctx.spawn(Gradient(false, Double.PositiveInfinity, Set.empty, Map.empty, Map.empty), s"device-${i}")
    }
    map.keys.foreach(d => {
      map(d) ! Gradient.SetPosition(Point3D(d,0,0))
      if(d>1) map(d) ! Gradient.AddNeighbour(map(d - 1))
      if(d<10) map(d) ! Gradient.AddNeighbour(map(d + 1))
    })

    map(3) ! Gradient.SetSource(true)

    map.values.foreach(_ ! Gradient.Round)

    Behaviors.ignore
  }, "ActorBasedChannel")
}

object Actors2 extends App {
  println("Actors implementation")

  val system = ActorSystem[C.Start.type](Behaviors.setup { ctx =>
    var map = Map[Int, ActorRef[Any]]()
    for(i <- 1 to 10) {
      map += i -> ctx.spawn(AggrActor(i, Point3D(i,0,0)), s"device-${i}")
    }

    Behaviors.ignore
  }, "ActorBasedChannel")
}