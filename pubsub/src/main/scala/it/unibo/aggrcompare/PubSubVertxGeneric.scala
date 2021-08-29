package it.unibo.aggrcompare

import io.vertx.lang.scala.ScalaVerticle
import io.vertx.scala.core.eventbus.Message
import io.vertx.scala.core.{DeploymentOptions, Vertx}
import it.unibo.scafi.space.Point3D

import scala.concurrent.duration.DurationInt

trait Msg extends Serializable
case class NbrExport(id: Int, exports: Map[String,Any]) extends Msg
case class SensorChange[T](name: String, value: T) extends Msg

trait DeviceContext {
  def sense[T](name: String): T

  def mid: Int = sense(Sensors.id)
  def pos: Point3D = sense(Sensors.pos)
  def nbrs: Set[Int] = sense(Sensors.nbrs)

  def nbrsense[T](name: String): Map[Int,T]

  def export[T](name: String, value: T): Unit
}

object Computations {
  implicit class RichObj[T](value: T) {
    def let(f: T => Unit): T = { f(value); value }
  }

  implicit class RichStr(s: String) {
    def /(t: String): String = s + "/" + t
  }

  def gradient(t: String, c: DeviceContext, src: Boolean): Double = {
    val gradients: Map[Int,Double] = c.nbrsense[Double](t)
    val distances: Map[Int,Double] = c.nbrsense[Double](Sensors.nbrRange)
    (
      if(src) { 0.0 }
      else if(gradients.isEmpty) { Double.PositiveInfinity }
      else {
        gradients.map(ng => ng._1 -> (ng._2 + distances.getOrElse(ng._1, Double.PositiveInfinity))).minBy(_._2)._2
      }
    ).let(c.export(t, _))
  }

  def broadcast[T](t: String, c: DeviceContext, src: Boolean, datum: T): T = {
    val g = gradient(t / "g", c, src)
    val gradients: Map[Int,Double] = c.nbrsense[Double](t + "/g") + (c.mid -> Double.PositiveInfinity)
    val broadcasted: Map[Int,T] = c.nbrsense[T](t) + (c.mid -> datum)
    val bvalueByMinNbr = broadcasted(gradients.minBy(_._2)._1)
    (if(src) datum else bvalueByMinNbr).let(c.export(t, _))
  }

  def distanceBetween(t: String, c: DeviceContext, src: Boolean, dest: Boolean): Double = {
    val distToDest = gradient(t / "g", c, dest)
    broadcast(t / "broadcast", c, src, distToDest).let(c.export(t, _))
  }

  def channel(t: String, c: DeviceContext, src: Boolean, target: Boolean, width: Double): Boolean = {
    val distanceToSource = gradient(t / "gradient1", c, src)
    val distanceToTarget = gradient(t / "gradient2", c, target)
    val distanceBetweenSourceAndTarget = distanceBetween(t / "distbetween", c, src, target)
    (distanceToSource + distanceToTarget <= distanceBetweenSourceAndTarget + Parameters.channelTolerance).let(c.export(t, _))
  }
}

trait BasicDeviceContext extends DeviceContext {
  var sensors: Map[String, Any] = Map.empty
  var nbrValues: Map[String, Map[Int,Any]] = Map.empty
  var exports: Map[String,Any] = Map.empty

  def sense[T](name: String): T = sensors(name).asInstanceOf[T]

  def nbrsense[T](name: String): Map[Int,T] = nbrValues.getOrElse(name, Map.empty).asInstanceOf[Map[Int,T]]

  def export[T](name: String, value: T): Unit = {
    exports += name -> value
  }
}

class DeviceVerticle(initialSensors: Map[String, Any], computation: DeviceContext => Any) extends ScalaVerticle with BasicDeviceContext {
  sensors = initialSensors

  override def start(): Unit = {
    println(s"DEVICE ${mid} AT POS ${pos}")

    val eb = vertx.eventBus()
    eb.consumer[SensorChange[_]](Topics.sensorChanged(mid), (nbrMsg: Message[SensorChange[_]]) => {
      val SensorChange(name, value) = nbrMsg.body()
      sensors += name -> value
    })

    eb.consumer[NbrExport](Topics.exportTo(mid), (nbrMsg: Message[NbrExport]) => {
      val NbrExport(nbr, nbrExports) = nbrMsg.body()
      nbrExports.foreach{ case (k,v) => nbrValues += k -> (nbrValues.getOrElse(k, Map.empty) + (nbr -> v)) }
    })


    vertx.setPeriodic(1.seconds.toMillis, timerId => {
      exports = Map.empty // resed exports
      val result = computation(this)
      println(s"${mid} => ${result}") // CONTEXT:   ${sensors} ${nbrValues} COMPUTED => ${result} EXPORT => ${exports}
    })

    vertx.setPeriodic(500.millis.toMillis, timerId => {
      nbrs.foreach(nbr => {
        sensors.keySet.foreach(s => exports += s -> sensors(s))
        exports += Sensors.nbrRange -> (nbrsense(Sensors.pos).getOrElse(nbr, Point3D(Double.PositiveInfinity, Double.PositiveInfinity, Double.PositiveInfinity)).distance(pos))
        eb.send(Topics.exportTo(nbr), Some(NbrExport(mid, exports)))
      })
    })

    println(s"STARTING ${mid}.")
  }
}

object PubSubVertx extends App {
  val vertx = Vertx.vertx()
  val eb = vertx.eventBus()
  for(i <- 0 to 10) {
    vertx.deployVerticle(new DeviceVerticle(Map(
      Sensors.id -> i,
      Sensors.pos -> Point3D(i,0,0),
      Sensors.source -> false,
      Sensors.target -> (i == 8),
      Sensors.nbrs -> Set.empty
    ),
    c => {
      // Computations.gradient("global-g", c, c.sense[Boolean](Sensors.source))
      Computations.channel("chan", c, c.sense[Boolean](Sensors.source), c.sense[Boolean](Sensors.target), 0.0)
    }), DeploymentOptions())
  }

  def regCodec[T](klass: Class[T]): Unit = eb.registerDefaultCodec(klass, new GenericCodec[T](klass))
  regCodec(classOf[NbrExport])
  regCodec(classOf[SensorChange[_]])

  Thread.sleep(2000)

  for(i <- 0 to 10) {
    eb.publish(Topics.sensorChanged(i), Some(SensorChange(Sensors.pos, Point3D(i,0,0))))
    eb.publish(Topics.sensorChanged(i), Some(SensorChange(Sensors.source, i == 3)))
    eb.publish(Topics.sensorChanged(i), Some(SensorChange(Sensors.nbrs, Set(i-1,i,i+1))))
  }

  println("SETUP DONE.")

  Thread.sleep(5000)
}
