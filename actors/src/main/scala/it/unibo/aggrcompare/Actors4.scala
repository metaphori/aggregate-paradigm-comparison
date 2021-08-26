package it.unibo.aggrcompare

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.actor.typed.scaladsl.Behaviors

/**
 * 4th attempt: focus on algorithmic logic
 */
object ChannelLogic {
  type Nbr = String

  case class GradientContext (
    val isSource: Boolean = false,
    val neighboursGradients: Map[Nbr,Double] = Map.empty,
    val neighboursDistances: Map[Nbr,Double] = Map.empty
  )

  trait GradientProtocol
  case class ComputeGradient(c: GradientContext, replyTo: ActorRef[Double]) extends GradientProtocol

  def gradient(): Behavior[GradientProtocol] = Behaviors.receiveMessage {
    case ComputeGradient(c, replyTo) =>
      val g = if(c.isSource) 0.0 else {
        c.neighboursGradients.minByOption(_._2).map {
          case (nbr,nbrg) => nbrg + c.neighboursDistances(nbr)
        }.getOrElse(Double.PositiveInfinity)
      }
      replyTo ! g
      Behaviors.same
  }

  trait GradientContextProtocol
  case class SetSource(source: Boolean) extends GradientContextProtocol
  case class SetNeighbourGradient(nbr: Nbr, gradient: Double) extends GradientContextProtocol
  case class SetNeighbourDistance(nbr: Nbr, distance: Double) extends GradientContextProtocol
  case class Get(replyTo: ActorRef[GradientContext])

  def gradientContext(c: GradientContext): Behavior[GradientContextProtocol] = Behaviors.receiveMessage[GradientContextProtocol] {
    case SetSource(s) => gradientContext(c.copy(isSource = s))
    case SetNeighbourGradient(nbr, g) => gradientContext(c.copy(neighboursGradients = c.neighboursGradients + (nbr -> g)))
    case SetNeighbourDistance(nbr, d) => gradientContext(c.copy(neighboursDistances = c.neighboursDistances + (nbr -> d)))
    // TODO: must also consider that neighbours may be removed together their data
  }

  trait ChannelContext {
    val distanceToSource: Double
    val distanceToTarget: Double
    val distanceBetweenSourceAndTarget: Double
    val tolerance: Double
  }
  trait ChannelProtocol
  case class ComputeChannel(c: ChannelContext, replyTo: ActorRef[Boolean]) extends ChannelProtocol

  def channel(): Behavior[ChannelProtocol] = Behaviors.receiveMessage {
    case ComputeChannel(c, replyTo) =>
      val channel = c.distanceToSource + c.distanceToTarget <= c.distanceBetweenSourceAndTarget + c.tolerance
      replyTo ! channel
      Behaviors.same
  }
}


object Actors4 extends App {
  println("Actors implementation")

  val system = ActorSystem[C.Start.type](Behaviors.setup { ctx =>
    // TODO
    Behaviors.ignore
  }, "ActorBasedChannel")
}