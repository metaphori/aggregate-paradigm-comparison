package it.unibo.aggrcompare

import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonObject
import io.vertx.core.{AbstractVerticle, Context, DeploymentOptions, Vertx, VertxOptions}
import it.unibo.scafi.space.Point3D

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters._

class DeviceVerticle extends AbstractVerticle {
  import DeviceVerticle._

  def getConfig[T](name: String) = config.getValue(name) .asInstanceOf[T]
  def getField[T](obj: JsonObject, name: String): T = obj.getValue(name).asInstanceOf[T]
  def getMsgField[T](msg: Message[JsonObject], name: String): T = getField(msg.body(),name).asInstanceOf[T]

  var mid: Int = -1
  var pos: Point3D = Point3D(0,0,0)
  var src: Boolean = false

  var sensors: Map[String,Any] = Map.empty
  var nbrData: Map[Int,NbrData] = Map.empty

  implicit class RichObj[T](value: T){
    def let[V](f: T=>V): V = f(value)
  }

  override def start(): Unit = {
    mid = config.getInteger(Sensors.id)
    pos = getConfig[JsonObject](Sensors.pos).let(o => Point3D(getField(o,"x"),getField(o,"y"),getField(o,"z")))
    println(s"DEVICE ${mid} AT POS ${pos}")

    val eb = getVertx.eventBus()
    eb.consumer[Unit](Topics.compute(mid), (_:Message[Unit]) => {
      println(s"${mid}: COMPUTE")
    })
    eb.consumer[NbrData](Topics.deviceAddress(mid), (nbrdata: Message[NbrData]) => {
      println(s"${mid}: RECEIVED ${nbrdata.body()}")
    } )
    eb.consumer[JsonObject](Topics.positionChange, (m: Message[JsonObject]) => {
      val dev: Int = getMsgField(m, "id")
      val pos: Point3D = getMsgField(m, "pos")

    } )
    eb.consumer(Topics.sourceChange, (m: Message[JsonObject]) => {
      if(getMsgField(m,"id") == mid){ println(s"${mid} IS THE NEW SOURCE"); src = true } else { src = false }
    } )

    getVertx.setPeriodic(1.seconds.toMillis, timerId => {
      println(s"${mid}: AWAKEN FROM TIMER #${timerId}")
    })

    println(s"STARTING ${mid}.")
  }
}
object DeviceVerticle {
  case class NbrData(id: Int)
  case class PositionChange(id: Int, newPos: Point3D)
  case class SourceChange(id: Int) extends Serializable

  object Topics {
    def compute(id: Int) = s"compute-${id}"
    val positionChange = "positionChange"
    val sourceChange = "sourceChange"
    def deviceAddress(id: Int): String = s"device-${id}"
  }

  object Sensors {
    val id = "id"
    val pos = "pos"
  }
}

object JsonUtils {
  def obj(elems: (String,Any)*): JsonObject = {
    JsonObject.mapFrom(Map(elems:_*).asJava)
  }
}

object PubSubVertx extends App {
  import DeviceVerticle._

  val vertx = Vertx.vertx(new VertxOptions())
  val eb = vertx.eventBus()
  for(i <- 0 to 10) {
    vertx.deployVerticle(s"it.unibo.aggrcompare.DeviceVerticle", new DeploymentOptions().setConfig(
      JsonUtils.obj(
        Sensors.id -> i,
        Sensors.pos -> JsonUtils.obj("x"->i.toDouble, "y"->0.0, "z"->0.0)
      )
    )).onComplete { res => println(res)}
  }

  Thread.sleep(2000)

  eb.publish(Topics.sourceChange, JsonUtils.obj("id" -> 8))
  eb.publish(Topics.sourceChange, JsonUtils.obj("id" -> 3))
  println("SETUP DONE.")
}
