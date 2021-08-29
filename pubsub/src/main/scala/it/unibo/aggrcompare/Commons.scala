package it.unibo.aggrcompare

object Parameters {
  val channelTolerance = 0.1

  val neighbouringThreshold = 1.5
}

object Topics {
  val world: String = "world"
  def compute(id: Int) = s"compute-${id}"
  val positionChange = "positionChange"
  val sourceChange = "sourceChange"
  def deviceAddress(id: Int): String = s"device-${id}"
  def exportTo(nbr: Int): String = s"mailbox-${nbr}"
  def sensorChanged(mid: Int): String = s"sensor-${mid}"
}

object Sensors {
  val nbrs: String = "nbrs"
  val nbrRange: String = "nbrRange"
  val id = "id"
  val pos = "pos"
  val source: String = "source"
}

/*
object DeviceVerticle {
  val neighbouringThreshold = 1.5

  object Topics {
    def exportTo(nbr: Int): String = s"mailbox-${nbr}"

    def sensorChanged(mid: Int): String = s"sensor-${mid}"

    val world: String = "world"

    def compute(id: Int) = s"compute-${id}"
    val positionChange = "positionChange"
    val sourceChange = "sourceChange"
    def deviceAddress(id: Int): String = s"device-${id}"
  }

  object Sensors {
    val source: String = "source"
    val id = "id"
    val pos = "pos"
    val nbrs = "nbrs"
  }
}

 */