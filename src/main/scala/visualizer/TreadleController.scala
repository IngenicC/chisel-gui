package visualizer

import java.io.File

import firrtl.FileUtils
import firrtl.ir.ClockType
import firrtl.stage.FirrtlSourceAnnotation
import treadle.executable.{Symbol, SymbolTable}
import treadle.{TreadleTester, WriteVcdAnnotation}
import visualizer.components.MainWindow
import visualizer.models._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.swing.{Dimension, Publisher, SwingApplication}

object TreadleController extends SwingApplication with Publisher {
  var testerOpt: Option[TreadleTester] = None
  private val clkSteps = 2

  val dataModel = new DataModel
  val selectionModel = new SelectionModel
  val displayModel = new DisplayModel
  lazy val mainWindow = new MainWindow(dataModel, selectionModel, displayModel)

  override def startup(args: Array[String]): Unit = {
    if (mainWindow.size == new Dimension(0, 0)) mainWindow.pack()
    mainWindow.visible = true

    // TODO: determine if args is info from treadle or vcd
    args.toList match {

      case firrtlFileName :: vcdFileName :: Nil =>
        val vcd = treadle.vcd.VCD.read(vcdFileName)
        val firrtlString = FileUtils.getText(firrtlFileName)
        setupTreadle(firrtlString)
        testerOpt match {
          case Some(tester) =>
            seedFromVcd(vcd, stopAtTime = Long.MaxValue)
            dataModel.setMaxTimestamp(vcd.valuesAtTime.keys.max)
          case _ =>
        }
        setupWaveforms(testerOpt.get)
        selectionModel.updateTreeModel()
        loadSaveFileOnStartUp()
        mainWindow.signalSelector.updateModel()

      case firrtlFileName :: Nil =>
        val firrtlString = FileUtils.getText(firrtlFileName)
        setupTreadle(firrtlString)
        setupWaveforms(testerOpt.get)
        selectionModel.updateTreeModel()
        loadSaveFileOnStartUp()
        mainWindow.signalSelector.updateModel()

      case Nil =>
        hackySetup()
        setupWaveforms(testerOpt.get)
        selectionModel.updateTreeModel()
        mainWindow.signalSelector.updateModel()

      case _ =>
        println("Usage: chisel-gui firrtlFile [vcdFile]")
        System.exit(1)
    }
  }

  def seedFromVcd(vcd: treadle.vcd.VCD, stopAtTime: Long = Long.MaxValue): Unit = {
    val engine = testerOpt.get.engine
    val wallTime = testerOpt.get.wallTime

    var lastClockTransitionTime = 0L
    var clockHalfPeriodGuess = 0L
    var lastClockValue = 0L

    vcd.valuesAtTime.keys.toSeq.sorted.foreach { time =>
      for (change <- vcd.valuesAtTime(time)) {
        if (time <= stopAtTime) {
          wallTime.setTime(time)

          engine.symbolTable.get(change.wire.fullName) match {
            case Some(symbol) =>
              engine.setValue(symbol.name, change.value, force = true)
              if (symbol.firrtlType == ClockType) {
                clockHalfPeriodGuess = time - lastClockTransitionTime
                lastClockTransitionTime = time
                lastClockValue = change.value.toLong
                val prevName = SymbolTable.makePreviousValue(symbol)
                engine.setValue(prevName, change.value)
              }

            case _ =>
              println(s"Could not find symbol for $change")
          }
        }
      }
    }
    if (lastClockValue == 0L) {
      testerOpt.get.advanceTime(clockHalfPeriodGuess)
    }
  }

  /**
    * looks through the save file and populates the inspection container
    */
  def loadSaveFileOnStartUp(): Unit = {
    testerOpt.foreach { tester =>
      val fileNameGuess = new File(tester.topName + ".save")
      if (fileNameGuess.exists()) {
        FileUtils.getLines(fileNameGuess).foreach { line =>
          val fields = line.split(",").map(_.trim).toList
          fields match {
            case "node" :: signalName :: formatName :: Nil =>
              dataModel.nameToSignal.get(signalName) match {
                case Some(pureSignal: PureSignal) =>
                  val node = WaveFormNode(signalName, pureSignal)
                  displayModel.addFromDirectoryToInspected(node, mainWindow.signalSelector)
                  displayModel.waveDisplaySettings(signalName) = {
                    WaveDisplaySetting(None, Some(Format.deserialize(formatName)))
                  }
                case Some(combinedSignal: CombinedSignal) =>
              }
            case "marker" :: time =>
            case _ =>
              println(s"Invalid line $line in save file")

          }
        }
      }
    }
  }

  def loadFile(fileName: String): String = {
    var file = new File(fileName)
    if (!file.exists()) {
      file = new File(fileName + ".fir")
      if (!file.exists()) {
        throw new Exception(s"file $fileName does not exist")
      }
    }
    FileUtils.getText(file)
  }

  ///////////////////////////////////////////////////////////////////////////
  // Treadle specific methods
  ///////////////////////////////////////////////////////////////////////////
  def setupTreadle(firrtlString: String): Unit = {
    val treadleTester = loadFirrtl(firrtlString)
    setupClock(treadleTester)
    setupSignals(treadleTester)
  }

  def loadFirrtl(firrtlString: String): TreadleTester = {
    val t = treadle.TreadleTester(
      Seq(
        FirrtlSourceAnnotation(firrtlString),
        WriteVcdAnnotation
      )
    )
    testerOpt = Some(t)
    t
  }

  def setupClock(t: TreadleTester): Unit = {
    if (t.clockInfoList.nonEmpty) {
      displayModel.setClock(t.clockInfoList.head)
    }
  }

  def setupSignals(tester: TreadleTester): Unit = {
    tester.engine.symbolTable.nameToSymbol.foreach {
      case (name, symbol) =>
        if (!name.contains("/")) {
          val sortGroup = Util.sortGroup(name, tester)
          val arrayBuffer = new ArrayBuffer[Transition[BigInt]]()
          arrayBuffer += Transition(0L, BigInt(0))
          val signal = new PureSignal(name, Some(symbol), Some(new Waveform(arrayBuffer)), sortGroup)

          dataModel.addSignal(name, signal)
        }
    }
    selectionModel.updateTreeModel()
  }

  def setupWaveforms(t: TreadleTester): Unit = {
    t.engine.vcdOption match {
      case Some(vcd) =>
        Util.vcdToTransitions(vcd, initializing = true).foreach {
          case (fullName, transitions) =>
            dataModel.nameToSignal.get(fullName) match {
              case Some(pureSignal: PureSignal) =>
                pureSignal.addNewValues(transitions)
              case Some(combinedSignal: CombinedSignal) =>
              //TODO: figure out if anything needs to happen here
              case _ =>
            }
        }
        dataModel.setMaxTimestamp(vcd.valuesAtTime.keys.max)
      case _ =>
    }

    publish(new PureSignalsChanged)
    mainWindow.repaint()
  }

  def loadDrivingSignals(signal: PureSignal): Unit = {
    val maxDepth = 3
    testerOpt.foreach { tester =>
      val engine = tester.engine
      val digraph = engine.symbolTable.parentsOf

      val table = engine.symbolTable
      val symbol = engine.symbolTable(signal.name)
      val symbolsSeen = new mutable.HashSet[String]()
      val symbolsAtDepth = Array.fill(maxDepth + 1) {
        new mutable.HashSet[Symbol]
      }

      symbolsSeen += signal.name
      walkGraph(symbol, depth = 0)

      def walkGraph(symbol: Symbol, depth: Int): Unit = {
        symbolsAtDepth(depth) += symbol

        if (depth < maxDepth) {
          digraph.getEdges(symbol).toSeq.sortBy(_.name).foreach { childSymbol =>
            walkGraph(childSymbol, depth + 1)

            if (table.isRegister(symbol.name)) {
              walkGraph(table(SymbolTable.makeRegisterInputName(symbol)), depth + 1)
            }
          }
        }

        val showDepth = symbolsAtDepth.count(_.nonEmpty)
        for (depth <- 0 until showDepth) {
          var added = 0

          println(s"driving symbols at distance $depth")
          symbolsAtDepth(depth).toSeq.map(_.name).filterNot(symbolsSeen.contains).sorted.foreach { signalName =>
            dataModel.nameToSignal.get(signalName).foreach { drivingSignal =>
              print(signalName)
              added += 1
              symbolsSeen += signalName
              val otherNode = WaveFormNode(signalName, drivingSignal)
              displayModel.addFromDirectoryToInspected(otherNode, mainWindow.signalSelector)
            }
          }
          if (added > 0) println()
        }
      }
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  // Hard coded things
  ///////////////////////////////////////////////////////////////////////////
  def runSomeTreadle(t: TreadleTester): Unit = {
    for {
      a <- 10 to 20
      b <- 20 to 22
    } {
      t.poke("io_e", 1)
      t.poke("io_a", a)
      t.poke("io_b", b)
      t.step()
      t.poke("io_e", 0)
      t.step(clkSteps)
    }
  }

  def makeBinaryTransitions(times: ArrayBuffer[Int]): Waveform[BigInt] = {
    val transitions = times.zipWithIndex.map {
      case (time, index) =>
        Transition[BigInt](time, index % 2)
    }
    new Waveform(transitions)
  }

  def hackySetup(): Unit = {
    val firrtlString = loadFile("samples/gcd.fir")
    val treadleTester = loadFirrtl(firrtlString)
    setupClock(treadleTester)
    runSomeTreadle(treadleTester)
    setupWaveforms(treadleTester)

    val waveformReady = makeBinaryTransitions(ArrayBuffer[Int](0, 16, 66, 106, 136, 176, 306, 386, 406, 496, 506))
    val signalReady = new PureSignal("ready", None, Some(waveformReady), 0)
    val fakeReady = "module.io_fake_ready"
    dataModel.addSignal(fakeReady, signalReady)
    selectionModel.addSignalToSelectionList(fakeReady, signalReady)

    val waveformValid = makeBinaryTransitions(ArrayBuffer[Int](0, 36, 66, 96, 116, 146, 206, 286, 396, 406, 506))
    val signalValid = new PureSignal("valid", None, Some(waveformValid), 0)
    dataModel.addSignal("module.io_fake_valid", signalValid)

    val signalRV = ReadyValidCombiner(Array[PureSignal](signalReady, signalValid))
    dataModel.addSignal("module.io_rv", signalRV)

    publish(new PureSignalsChanged)
  }
}
