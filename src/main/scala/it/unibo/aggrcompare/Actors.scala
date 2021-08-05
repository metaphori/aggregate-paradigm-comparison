package it.unibo.aggrcompare

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import it.unibo.scafi.space.Point3D

import scala.concurrent.duration.DurationInt

object C {
  case object Start
}

object Gradient {
  sealed trait Msg
  case class SetSource(isSource: Boolean) extends Msg
  case class SetPosition(point: Point3D) extends Msg
  case class SetDistance(distance: Double, from: ActorRef[Msg]) extends Msg
  case class ComputeGradient(gradient: Double, from: ActorRef[Msg]) extends Msg
  case class QueryGradient(replyTo: ActorRef[Double]) extends Msg
  case class AddNeighbour(nbr: ActorRef[Msg]) extends Msg
  case class RemoveNeighbour(nbr: ActorRef[Msg]) extends Msg
  case object Round extends Msg

  def apply(source: Boolean,
            gradient: Double,
            nbrs: Set[ActorRef[Msg]],
            distances: Map[ActorRef[Msg],Double],
            nbrGradients: Map[ActorRef[Msg],Double],
            position: Point3D = Point3D(0,0,0)): Behavior[Msg] = Behaviors.withTimers{ timers => Behaviors.receive { case (ctx,msg) =>
    msg match {
      case SetSource(s) =>
        Gradient(s, 0, nbrs, distances, nbrGradients, position)
      case AddNeighbour(nbr) =>
        Gradient(source, gradient, nbrs + nbr, distances, nbrGradients, position)
      case RemoveNeighbour(nbr) =>
        Gradient(source, gradient, nbrs - nbr, distances, nbrGradients, position)
      case SetPosition(p) =>
        Gradient(source, gradient, nbrs, distances, nbrGradients, p)
      case ComputeGradient(g, from) => {
        val newNbrGradients = distances + (ctx.self -> gradient) + (from -> g)

        // Once gradient is computed, start the next round in a second
        timers.startSingleTimer(Round, 1.second)

        if(source){
          ctx.log.info(s"GRADIENT: ${ctx.self.path.name} -> ${gradient}")
          Gradient(source, 0, nbrs, distances, newNbrGradients, position)
        } else {
          val minNbr = newNbrGradients.minByOption(_._2)
          val updatedG = minNbr.map(_._2).getOrElse(Double.PositiveInfinity) + minNbr.flatMap(n => distances.get(n._1)).getOrElse(Double.PositiveInfinity)
          ctx.log.info(s"GRADIENT: ${ctx.self.path.name} -> ${updatedG}")
          Gradient(source, updatedG, nbrs, distances, nbrGradients + (ctx.self -> updatedG), position)
        }
      }
      case QueryGradient(replyTo) => {
        replyTo ! gradient
        Behaviors.same
      }
      case Round => {
        ctx.log.info(s"ROUND!")
        nbrs.foreach(nbr => {
          // TODO: query neighbours
        })
        Behaviors.same
      }
    }
  } }
}

object Actors extends App {
  println("Actors implementation")

  val system = ActorSystem[C.Start.type](Behaviors.setup { ctx =>
    Behaviors.empty[C.Start.type]
    var map = Map[Int, ActorRef[Gradient.Msg]]()
    for(i <- 1 to 10) {
      map += i -> ctx.spawn(Gradient(false, Double.PositiveInfinity, Set.empty, Map.empty, Map.empty), s"device-${i}")
    }
    map.keys.foreach(d => {
      map(d) ! Gradient.SetPosition(Point3D(d,0,0))
      if(d>1) map(d) ! Gradient.AddNeighbour(map(d - 1))
      if(d<10) map(d) ! Gradient.AddNeighbour(map(d + 1))
    })

    map(1) ! Gradient.SetSource(true)

    map.values.foreach(_ ! Gradient.Round)

    Behaviors.ignore
  }, "ActorBasedChannel")
}