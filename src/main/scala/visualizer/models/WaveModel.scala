// See README.md for license details.

package visualizer.models

import treadle.vcd.{Change, VCD}

import scala.collection.mutable
import scala.collection.Searching._

/** Represents the segments of a wave. Each segment has a start, an end and a value
  * starts and ends must be in strictly increasing order
  * end must be greater than the start at the same index
  * starts(0) must be 0
  *
  */
class Wave {
  val starts: mutable.ArrayBuffer[Long] = new mutable.ArrayBuffer()
  val ends: mutable.ArrayBuffer[Long] = new mutable.ArrayBuffer()
  val values: mutable.ArrayBuffer[BigInt] = new mutable.ArrayBuffer()

  starts += 0L
  ends += Long.MaxValue
  values += BigInt(0)

  /** Use system binary search to find index of start time less than or equal to time
    *
    * @param time find the index in starts for this time
    */
  def findStartIndex(time: Long): Int = {
    assert(time >= 0)
    starts.search(time) match {
      case InsertionPoint(insertionPointIndex) => insertionPointIndex - 1
      case Found(index) => index
      case _ =>
        throw new Exception(s"Searching for time $time, could not find index")
    }
  }

  def start(index: Int): Long = starts(index)

  def end(index: Int): Long = ends(index)

  def value(index: Int): BigInt = values(index)

  def length: Int = starts.length

  /** Adds a series of changes to this Wave
    *
    * @param transitions list of transitions for this wave
    */
  def addChanges(transitions: Seq[Transition[BigInt]]): Unit = {
    val newLength = transitions.length
    val lengthDelta = transitions.length - length

    if (lengthDelta < 0) { // data has been shortened, truncate things
      starts.remove(newLength, -lengthDelta)
      ends.remove(newLength, -lengthDelta)
      values.remove(newLength, -lengthDelta)
    } else if (lengthDelta > 0) {
      starts ++= Seq.fill(lengthDelta)(0L)
      ends ++= Seq.fill(lengthDelta)(0L)
      values ++= Seq.fill(lengthDelta)(BigInt(0))
    }

    var index = 0
    while (index < transitions.length) {
      val transition = transitions(index)
      starts(index) = if (index == 0) 0L else transition.timestamp
      ends(index) = if (index < length - 1) transitions(index + 1).timestamp else Long.MaxValue
      values(index) = transition.value
      index += 1
    }
  }

  def addOneTransition(transition: Transition[BigInt]): Unit = {
    val lastIndex = length - 1
    assert(ends(lastIndex) == Long.MaxValue)
    ends(lastIndex) = transition.timestamp

    starts += transition.timestamp
    ends += Long.MaxValue
    values += transition.value
  }
}

object Waves {
  var vcd: VCD = new VCD("", "", "", "", "", false)
  private var times = vcd.valuesAtTime.keys.toSeq.sorted

  val nameToWave: mutable.HashMap[String, Wave] = new mutable.HashMap[String, Wave]() {
    override def default(key: String): Wave = {
      this (key) = new Wave()
      this (key)
    }
  }

  def apply(name: String): Wave = {
    nameToWave(name)
  }

  def setVcd(vcd: VCD): Unit = {
    nameToWave.clear()
  }

  /** This will scan the VCD for all change events associated with name
    * Use this function when a new wave is created,
    * typically when a signal is moved to the signal and wave panel
    *
    * @param name the wave to be updated
    */
  def updateWave(name: String): Unit = {
    val wave = nameToWave(name)
    val changes = vcd.valuesAtTime.flatMap {
      case (time, changeSet) =>
        changeSet.find { change =>
          change.wire.fullName == name
        } match {
          case Some(change) => Some(Transition(time, change.value))
          case _ => None
        }
    }.toSeq.sortBy(transition => transition.timestamp)
    wave.addChanges(changes)
  }

  /** This will update only the existing waves with all the
    * relevant values in the VCD
    *
    */
  def refreshWaves(): Unit = {
    vcd.events.foreach { time =>
      vcd.valuesAtTime(time).foreach { change =>
        nameToWave.get(change.wire.fullName).foreach { wave =>
          wave.addOneTransition(Transition(time, change.value))
        }
      }
    }
  }
}