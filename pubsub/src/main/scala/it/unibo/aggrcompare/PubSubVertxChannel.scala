package it.unibo.aggrcompare

import io.vertx.lang.scala.ScalaVerticle
import io.vertx.scala.core.eventbus.Message
import io.vertx.scala.core.{DeploymentOptions, Vertx}
import it.unibo.scafi.space.Point3D

import scala.concurrent.duration.DurationInt

trait PubSubBasicDeviceContext {
  // State for sensors and data from neighbours
  var sensors: Map[String, Any] = Map.empty
  // var exports: Map[String,Any] = Map.empty // NB: no data structure for exports
  var nbrRange: Map[Int, Double] = Map.empty
  var nbrPos: Map[Int, Point3D] = Map.empty
  var nbrDistToSource: Map[Int, Double] = Map.empty // NB: needed for choosing which contribution from a nbr to use for own distBetween value
  // State for local values of the target "computational fields" to be computed
  var distToSource: Double = Double.PositiveInfinity
  var distToTarget: Double = Double.PositiveInfinity
  var distBetween: Double = Double.PositiveInfinity
  var channel: Boolean = false
  // Methods for getting sensor data
  def sense[T](name: String): T = sensors(name).asInstanceOf[T]
  def mid: Int = sense(Sensors.id)
  def pos: Point3D = sense(Sensors.pos)
  def nbrs: Set[Int] = sense(Sensors.nbrs)
  def isSource(): Boolean = sense(Sensors.source)
  def isTarget(): Boolean = sense(Sensors.target)
}

class PubSubDeviceVerticle(initialSensors: Map[String, Any])
  extends ScalaVerticle with PubSubBasicDeviceContext {
  import PubSubVertxChannel.{ Topics, NbrDatum }

  sensors = initialSensors

  override def start(): Unit = {
    println(s"DEVICE ${mid} AT POS ${pos}")

    val eb = vertx.eventBus()

    def publishToNbrs(topic: Int => String, v: Any): Unit = {
      for(nbr <- nbrs) {
        // println(s"${mid} => publishing to $nbr => ${v}")
        eb.send(topic(nbr), Some(NbrDatum(mid, v)))
      }
    }

    def computeChannel(): Unit = {
      channel = distToSource + distToTarget <= distBetween
    }

    def setDistToSource(to: Double): Unit = {
      if(distToSource != to) {
        distToSource = to
        publishToNbrs(Topics.changedDistToSource, to)
      }
      computeChannel()
    }

    def setDistToTarget(to: Double): Unit = {
      if(distToSource != to) {
        distToTarget = to
        publishToNbrs(Topics.changeDistToTarget, to)
      }
      computeChannel()
    }

    def setDistBetween(to: Double): Unit = {
      if(distBetween != to) {
        distBetween = to
        publishToNbrs(Topics.changeDistBetween, to)
      }
      computeChannel()
    }

    eb.consumer[NbrDatum[_]](Topics.changedDistToSource(mid), (msg: Message[NbrDatum[_]]) => {
      val NbrDatum(devId, value: Double) = msg.body()
      //println(s"I am ${mid} and I got from $devId the msg ${msg.body()} ... ${nbrs} - ${isSource()} -- ${nbrRange}")
      nbrDistToSource += devId -> value
      if(nbrs.contains(devId) && devId != mid){
        if(!isSource()) {
          println("UPDATE GRADIENT")
          setDistToSource(Math.min(distToSource, value + nbrRange.getOrElse(devId, Double.PositiveInfinity)))
        }
      }
    })

    eb.consumer[NbrDatum[_]](Topics.changeDistToTarget(mid), (msg: Message[NbrDatum[_]]) => {
      val NbrDatum(devId, value: Double) = msg.body()
      // println(s"(DIST-TO-TARGET) I am ${mid} and I got from $devId the msg ${msg.body()} ... ${nbrs} - ${isSource()} -- ${nbrRange}")
      if(nbrs.contains(devId) && devId != mid){
        if(!isTarget()) {
          val newVal = Math.min(distToTarget, value + nbrRange.getOrElse(devId, Double.PositiveInfinity))
          if(isSource() && newVal != distToTarget){ // the source should start distBetween calculation once it perceives the distance to the target
            // println(s"${mid} source and setting a new value for distToTarget = ${newVal}, starting distBetweenComputation then")
            setDistBetween(newVal)
          }
          setDistToTarget(newVal)
        }
      }
    })

    eb.consumer[NbrDatum[_]](Topics.changeDistBetween(mid), (msg: Message[NbrDatum[_]]) => {
      val NbrDatum(devId, value: Double) = msg.body()
      if(nbrs.contains(devId) && devId != mid){
        if(!nbrDistToSource.isEmpty && devId == nbrDistToSource.minBy(_._2)._1) {
          println(s"${mid}: got from ${devId} its value of distBetween = $value")
          setDistBetween(value)
        }
      }
    })

    eb.consumer[SensorChange[_]](Topics.sensorChanged(mid), (nbrMsg: Message[SensorChange[_]]) => {
      val SensorChange(name, value) = nbrMsg.body()
      sensors += name -> value
      nbrMsg.body() match {
        case SensorChange(Sensors.source, false) =>
          setDistToSource(Double.PositiveInfinity)
        case SensorChange(Sensors.source, true) =>
          setDistToSource(0)
        case SensorChange(Sensors.target, false) =>
          setDistToTarget(Double.PositiveInfinity)
        case SensorChange(Sensors.target, true) =>
          setDistToTarget(0)
        case SensorChange(Sensors.pos, myPos: Point3D) => {
          nbrPos += mid -> myPos
          setDistToSource(Double.PositiveInfinity)
          nbrs.foreach(nbr => eb.send(Topics.sensorChanged(nbr), Some(SensorChange(Sensors.nbrRange, mid -> pos))))
        }
        case SensorChange(Sensors.nbrRange, npos: (Int,Point3D)) => {
          nbrPos += npos
          nbrRange += npos._1 -> (npos._2.distance(pos))
        }
        case _ => { }
      }
    })

    vertx.setPeriodic(2.seconds.toMillis, _ => {
      println(s"device ${mid}: distToSource=${distToSource} -- distToTarget=${distToTarget} -- distBetween=${distBetween} -- channel=${channel}")
    })

    println(s"STARTING ${mid}.")
  }
}

object PubSubVertxChannel extends App {
  object Topics {
    // def exportTo(nbr: Int): String = s"mailbox-${nbr}"
    def changedDistToSource(nbr: Int): String = s"changedDistToSource-${nbr}"
    def changeDistToTarget(nbr: Int): String = s"changeDistToTarget-${nbr}"
    def changeDistBetween(nbr: Int): String = s"changeDistBetween-${nbr}"
    def sensorChanged(nbr: Int): String = s"sensorChanged-${nbr}"
  }
  case class NbrDatum[T](mid: Int, datum: T)

  val vertx = Vertx.vertx()
  val eb = vertx.eventBus()
  for(i <- 0 to 10) {
    vertx.deployVerticle(new PubSubDeviceVerticle(Map(
      Sensors.id -> i,
      Sensors.pos -> Point3D(i,0,0),
      Sensors.source -> false,
      Sensors.target -> (i == 8),
      Sensors.nbrs -> Set.empty
    )), DeploymentOptions())
  }

  def regCodec[T](klass: Class[T]): Unit = eb.registerDefaultCodec(klass, new GenericCodec[T](klass))
  // regCodec(classOf[NbrExport])
  regCodec(classOf[SensorChange[_]])
  regCodec(classOf[NbrDatum[_]])

  Thread.sleep(2000)

  for(i <- 0 to 10) {
    eb.publish(Topics.sensorChanged(i), Some(SensorChange(Sensors.nbrs, Set(i-1,i,i+1))))
  }
  for(i <- 0 to 10){
    eb.publish(Topics.sensorChanged(i), Some(SensorChange(Sensors.pos, Point3D(i,0,0))))
    eb.publish(Topics.sensorChanged(i), Some(SensorChange(Sensors.source, i == 3)))
    eb.publish(Topics.sensorChanged(i), Some(SensorChange(Sensors.target, i == 8)))
  }

  println("SETUP DONE.")

  Thread.sleep(5000)
}
