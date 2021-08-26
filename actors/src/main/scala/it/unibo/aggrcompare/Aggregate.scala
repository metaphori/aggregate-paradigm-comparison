package it.unibo.aggrcompare

import it.unibo.scafi.config.GridSettings
import it.unibo.scafi.incarnations.BasicSimulationIncarnation._

object Aggregate extends App {
  println("Aggregate implementation")

  val net = simulatorFactory.gridLike(GridSettings(6, 4, stepx = 1, stepy = 1), rng = 1.5)

  net.addSensor(name = "source", value = false)
  net.chgSensorValue(name = "source", ids = Set(0), value = true)
  net.addSensor(name = "target", value = false)
  net.chgSensorValue(name = "target", ids = Set(23), value = true)

  var v = java.lang.System.currentTimeMillis()

  net.executeMany(
    node = new ChannelProgram,
    size = 1000000,
    action = (n,i) => {
      if (i % 1000 == 0) {
        println(net)
        val newv = java.lang.System.currentTimeMillis()
        println(newv-v)
        // println(net.context(4))
        v = newv
      }
    })
}

class ChannelProgram extends AggregateProgram with StandardSensors with BlockG {
  val width = 0.0
  def source = sense[Boolean]("source")
  def target = sense[Boolean]("target")

  override def main(): Any = {
    val c = channel(source, target, width)
    f"[${mid()}%2d${if(source) "X" else if(target) "O" else " " }${if(c) "=" else " " }]"
  }
}