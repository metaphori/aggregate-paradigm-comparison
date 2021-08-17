package it.unibo.aggrcompare

import akka.actor.typed.receptionist.Receptionist.{Find, Listing}
import akka.actor.typed.receptionist.{Receptionist, ServiceKey}
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.util.Timeout
import it.unibo.aggrcompare.Gradient.{NbrGradient, SetNeighbourGradient}
import it.unibo.scafi.space.Point3D

import scala.jdk.CollectionConverters._
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Success, Try}

/**
 * 2nd attempt: generalisation and separation of concerns (context management & logic)
 * Notes:
 * - this attempt evolved into a too complicated and fragile design
 * - main design issues are related to the computation of sensor values and states
 *   as well as the propagation of state to the actors needing it
 * Moving on:
 * - I suggest avoiding splitting the individual actor of an aggregate into several sub-actors:
 *   this would require complex coordination between the individual sub-actors.
 * - Consider using a more traditional OO design and/or inheritance for reusing behaviour
 */
object AggrActor {
  sealed trait Msg
  case class SetSensor[T](v: SensorValue[T]) extends Msg
  case class GetSensor[T](name: String, replyTo: ActorRef[SensorValue[T]]) extends Msg
  case class SetNeighbourValue[T](name: String, nbr: ActorRef[Msg], value: T) extends Msg
  case class GetNeighbourValue[T](name: String, replyTo: ActorRef[NbrValue[T]]) extends Msg
  case class GetState[T](replyTo: ActorRef[State]) extends Msg

  case class SensorValue[T](name: String, value: T)
  case class NbrValue[T](name: String, value: T)

  case object Round extends Msg

  val nbrvGradient = "gradient"
  val nbrvDistance = "nbrRange"
  val sensorTopologyManager = "topology-manager"

  val deviceSK = ServiceKey[Msg]("devices")

  def apply(id: Int, position: Point3D): Behavior[Any] = Behaviors.setup { ctx =>
    Behaviors.withTimers { timers =>
      implicit val ec: ExecutionContext = ctx.system.executionContext
      implicit val sched: Scheduler = ctx.system.scheduler // for ask pattern
      implicit val timeout: Timeout = Timeout(3.seconds) // for ask pattern

      ctx.log.info(s"Spawning actor ${ctx.self.path.name}")

      val stateActor = ctx.spawn(state(Map.empty, Map.empty), "state")
      val locationSensor = ctx.spawn(sensor[Int,Point3D]("position", 0, position, (s,t,c) => (s,t)), "sensor-position")
      locationSensor ! ProduceTo(stateActor)

      val sourceSensor = ctx.spawn(sensor[Int,Boolean]("source", 0, false, (s,t,c) => (s,t)), "sensor-source")
      if(id==3) sourceSensor ! SetValue(true)
      sourceSensor ! ProduceTo(stateActor)

      /*
      val nbrRangeSensor = ctx.spawn(sensor[State,Double](nbrvDistance, State(), 0.0, (s,t,c) => {
        (s, 1.0) // TODO: we could deduce nbr-to-nbr distance from local "position" and "neighbours" sensors
      }), s"sensor-${nbrvDistance}")
      nbrRangeSensor ! ProduceTo(stateActor)
       */

      val rec = ctx.system.receptionist

      val topologyManager = ctx.spawn(sensor[Int,Set[ActorRef[Msg]]](sensorTopologyManager, id, Set.empty, (devId,nbrs,ctx) => {
        val f: Future[Listing] = rec.ask(Find(deviceSK))
        val selfRef = ctx.self
        ctx.scheduleOnce(3.seconds, ctx.self, Sample)
        ctx.scheduleOnce(4.seconds, ctx.self, ProduceTo(stateActor))
        // TODO: the following is a shortcut for impl the nbrRange neighbouring sensor
        nbrs.foreach(nbr =>
          stateActor ! SetNeighbourValue(nbrvDistance, nbr, if(nbr==selfRef) 0.0 else 1.0)
        )

        f.onComplete(r => {
          val discoveredNbrs = r.get.allServiceInstances(deviceSK).filter(s =>
            s.path.toString.contains(s"device-${id-1}") || s.path.toString.contains(s"device-${id+1}"))
          selfRef ! SetValue(discoveredNbrs + stateActor)
        })
        (devId, nbrs)
      }), s"sensor-${sensorTopologyManager}")
      rec ! Receptionist.register(deviceSK, stateActor)
      topologyManager ! Sample

      val computeActor = ctx.spawn(compute(ctx.self, nbrvGradient, stateActor, State(Map.empty, Map.empty), logic = s => {
        val (nbr -> nbrg) = s.nbrValues.getOrElse(nbrvGradient, Map.empty)
          .minByOption(_._2.asInstanceOf[Double])
          .getOrElse(ctx.system.ignoreRef -> Double.PositiveInfinity)
        val g: Double = nbrg.asInstanceOf[Double] + s.nbrValues.get(nbrvDistance)
          .flatMap(_.get(nbr).map(_.asInstanceOf[Double]))
          .getOrElse(Double.PositiveInfinity)
        ComputeResults(nbrvGradient, if(s.sensors.getOrElse("source", "false").toString.toBoolean) 0.0 else g)
      }), "compute-"+nbrvGradient)

      Behaviors.empty
  } }

  def state(sensors: Map[String,Any],
            nbrValues: Map[String,Map[ActorRef[Msg],Any]]): Behavior[Msg] = Behaviors.setup { ctx =>
    Behaviors.withTimers { timers =>
      Behaviors.receive { case (ctx, msg) =>
        msg match {
          case GetState(replyTo) =>
            replyTo ! State(sensors, nbrValues)
            Behaviors.same
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

  trait ComputeProtocol[T]
  case class Compute[T](replyTo: ActorRef[ComputeResults[T]]) extends ComputeProtocol[T]
  case class SetState[T](state: State) extends ComputeProtocol[T]
  case class ComputeResults[T](name: String, value: T)
  case class State(sensors: Map[String,Any] = Map.empty,
                   nbrValues: Map[String,Map[ActorRef[Msg],Any]] = Map.empty)

  def compute[T](parent: ActorRef[Any], name: String, stateRef: ActorRef[Msg], state: State, logic: State => ComputeResults[T]): Behavior[ComputeProtocol[T]] = Behaviors.setup { ctx =>
    Behaviors.withTimers { timers =>
      val stateMediator: ActorRef[State] = ctx.messageAdapter(m => SetState(m))

      timers.startTimerWithFixedDelay(Compute(ctx.system.ignoreRef), 2.seconds)

      Behaviors.receive {
        case (ctx, Compute(replyTo)) =>
          val results = logic(state)
          ctx.log.info(s"${parent.path.name} > COMPUTE ON ${state}\n=> $results")
          replyTo ! results
          stateRef ! GetState(stateMediator)
          stateRef ! SetNeighbourValue(name, stateRef, results.value)
          state.sensors.getOrElse(sensorTopologyManager, Set.empty).asInstanceOf[Set[ActorRef[Msg]]].foreach(_ ! SetNeighbourValue(name, stateRef, results.value))
          compute(parent, name, stateRef, state.copy(nbrValues =
            state.nbrValues + (name -> (state.nbrValues.getOrElse(name, Map.empty) ++ Map(parent -> results.value)))
          ), logic)
        case (ctx, SetState(s)) =>
          compute(parent, name, stateRef, state = s, logic)
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

  def nbrSensor[S,T](name: String, state: S, value: T, evolve: (S,T,ActorContext[SensorBehavior]) => (S,T)): Behaviors.Receive[SensorBehavior] = Behaviors.receive {
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