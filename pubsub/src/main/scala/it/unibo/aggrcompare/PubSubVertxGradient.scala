package it.unibo.aggrcompare

import io.vertx.lang.scala.ScalaVerticle
import io.vertx.scala.core.eventbus.Message
import io.vertx.scala.core.{DeploymentOptions, Vertx}
import it.unibo.scafi.space.Point3D

import scala.concurrent.duration.DurationInt

class DeviceVerticleGradient(val mid: Int, var pos: Point3D) extends ScalaVerticle {
  // def getConfig[T](name: String) = config.getValue(name).asInstanceOf[T]

  // def getField[T](obj: JsonObject, name: String): T = obj.getValue(name).asInstanceOf[T]

  // def getMsgField[T](msg: Message[JsonObject], name: String): T = getField(msg.body(), name).asInstanceOf[T]

  var src: Boolean = false
  var gradient: Double = Double.PositiveInfinity

  var sensors: Map[String, Any] = Map.empty
  var nbrValues: Map[String, Map[Int,Any]] = Map.empty
  var nbrData: Map[Int, NbrData] = Map.empty

  implicit class RichObj[T](value: T) {
    def mapTo[V](f: T => V): V = f(value)
  }

  def myData = NbrData(mid, src, pos, gradient)

  override def start(): Unit = {
    println(s"DEVICE ${mid} AT POS ${pos}")

    val eb = vertx.eventBus()
    eb.consumer[NbrData](Topics.deviceAddress(mid), (nbrMsg: Message[NbrData]) => {
      // println(s"${mid}: RECEIVED ${nbrMsg.body()}")
      val NbrData(nbr, src, nbrPos, grad) = nbrMsg.body()
      if(nbrPos.distance(this.pos) <= Parameters.neighbouringThreshold){ nbrData += nbr -> nbrMsg.body() } else { nbrData -= nbr}
    })
    eb.consumer[NbrData](Topics.world, (nbrMsg: Message[NbrData]) => {
      val NbrData(nbr, _, nbrPos, _) = nbrMsg.body()
      if(nbrPos.distance(this.pos) <= Parameters.neighbouringThreshold){ nbrData += nbr -> nbrMsg.body() } else { nbrData -= nbr}
    })

    eb.consumer(Topics.positionChange, (m: Message[PositionChange]) => { if(m.body().id == mid) { pos = m.body().newPos } })
    eb.consumer(Topics.sourceChange, (m: Message[SourceChange]) => { src = mid == m.body().id })

    vertx.setPeriodic(1.seconds.toMillis, timerId => {
      gradient = if(src) 0.0 else scala.util.Try(nbrData.values.map(n => (n.id, n.gradient + n.pos.distance(this.pos))).minBy(_._2)._2).getOrElse(Double.PositiveInfinity)
      println(s"${mid}: COMPUTED GRADIENT => ${gradient}")
    })
    vertx.setPeriodic(500.millis.toMillis, timerId => {
      // println(s"${mid}: AWAKEN FROM TIMER #${timerId}")
      nbrData.keySet.foreach(nbr => eb.send(Topics.deviceAddress(nbr), Some(myData)))
    })

    eb.publish(Topics.world, Some(myData))

    println(s"STARTING ${mid}.")
  }
}

trait Evt extends Serializable
case class NbrData(id: Int, src: Boolean, pos: Point3D, gradient: Double) extends Evt
case class PositionChange(id: Int, newPos: Point3D) extends Evt
case class SourceChange(id: Int) extends Evt

object PubSubVertxGradient extends App {
  val vertx = Vertx.vertx()
  val eb = vertx.eventBus()
  for(i <- 0 to 10) {
    vertx.deployVerticle(new DeviceVerticleGradient(i, Point3D(i,0,0)), DeploymentOptions())
  }

  def regCodec[T](klass: Class[T]): Unit = eb.registerDefaultCodec(klass, new GenericCodec[T](klass))
  regCodec(classOf[SourceChange])
  regCodec(classOf[PositionChange])
  regCodec(classOf[NbrData])

  Thread.sleep(2000)

  //eb.registerDefaultCodec(classOf[SourceChange], new GenericCodec[SourceChange](classOf[SourceChange]))

  eb.publish(Topics.sourceChange, Some(SourceChange(8)))
  eb.publish(Topics.sourceChange, Some(SourceChange(3)))
  eb.publish(Topics.positionChange, Some(PositionChange(2, Point3D(1.5,0,0))))
  println("SETUP DONE.")

  Thread.sleep(5000)
}
