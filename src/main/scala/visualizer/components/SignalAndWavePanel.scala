package visualizer.components

import firrtl.ir.FileInfo
import javax.swing.event.{TreeExpansionEvent, TreeExpansionListener}
import javax.swing.tree.{DefaultMutableTreeNode, TreePath}
import javax.swing.{BorderFactory, DropMode, SwingUtilities}
import scalaswingcontrib.tree.Tree
import visualizer.config.DrawMetrics
import visualizer.models._
import visualizer.{ChiselGUI, SignalsChanged, WaveFormatChanged}

import scala.swing.BorderPanel.Position._
import scala.swing._
import scala.swing.event.{Key, KeyReleased, MouseClicked}
import scala.sys.process._
import scala.util.matching.Regex

/** This manages the signals selected for the waveform display
  * signals can be added, moved, removed, and have their display formats changed
  *
  * @param dataModel           underlying data model
  * @param selectedSignalModel model used by tree to describe selected signals
  */
class SignalAndWavePanel(dataModel: DataModel, selectedSignalModel: SelectedSignalModel) extends BorderPanel {

  val SourceInfoPattern: Regex = """([^ ]*) (\d*).*""".r

  // Popup menu when a signal name is right-clicked
  private def popupMenu(signal: Option[Signal]): PopupMenu = new PopupMenu {
    signal match {
      case Some(pureSignal: PureSignal) =>
        contents += new Menu("Data Format") {
          contents += new MenuItem(Action("Binary") {
            selectedSignalModel.setWaveFormat(this, tree.selection.cellValues, BinFormat)
          })
          contents += new MenuItem(Action("Decimal") {
            selectedSignalModel.setWaveFormat(this, tree.selection.cellValues, DecFormat)
          })
          contents += new MenuItem(Action("Hexadecimal") {
            selectedSignalModel.setWaveFormat(this, tree.selection.cellValues, HexFormat)
          })
          contents += new MenuItem(Action("X/Y Plot") {
            selectedSignalModel.setWaveFormat(this, tree.selection.cellValues, PlotFormat)
          })
        }
        contents += new MenuItem(Action("Add Driving Signals") {
          Dialog.showInput(
            this,
            "How deep to follow driving signals: ",
            title = "How deep in driving stack",
            initial = "2"
          ) match {
            case Some(depthString) =>
              try {
                val depth = depthString.toInt
                ChiselGUI.loadDrivingSignals(pureSignal, depth)
              } catch {
                case _: Throwable =>
              }
            case _ =>
          }
        })

        pureSignal.symbolOpt.foreach { symbol =>
          ChiselGUI.testerOpt.foreach { tester =>
            val dataFormat = selectedSignalModel.waveDisplaySettings.getOrElse(pureSignal.name, WaveDisplaySetting())
            contents += new MenuItem(Action("Show driving logic") {
              val logic =
                tester.engine.expressionViewRenderer.render(symbol,
                                                            ChiselGUI.selectedSignalModel.cursorPosition,
                                                            dataFormat.dataFormat.getOrElse(DecFormat).radixChar,
                                                            showValues = false)
              Dialog.showMessage(this, logic, s"Logic for ${symbol.name}")
            })
          }

          symbol.info match {
            case s: FileInfo =>
              s.info.string match {
                case SourceInfoPattern(file, line) =>
                  ChiselGUI.sourceInfoMap.get(file).foreach { targetFile =>
                    val command = Seq(
                      "/Applications/IntelliJ IDEA.app/Contents/MacOS/idea",
                      "--line",
                      s"$line",
                      s"$targetFile"
                    )

                    println(command.mkString(" "))
                    contents += new MenuItem(Action(s"Jump to source $file : $line") {
                      command.!!
                    })
                  }
                case _ =>
              }
            case _ =>
          }
        }
      case Some(decoupledSignalGroup: DecoupledSignalGroup) =>
        val (message, menu2) = if (selectedSignalModel.timeSieveOpt.isEmpty) {
          ("Compress time to Fire", "Only show time during FIRE for this group?")
        } else {
          ("Turn Off Time compression", "Turn of compressed time")
        }

        contents += new MenuItem(Action(message) {
          val result = Dialog.showConfirmation(
            this,
            "Only show time during FIRE for this group?",
            title = "Time Compress",
          )
          if (result == Dialog.Result.Ok) {
            if (selectedSignalModel.timeSieveOpt.isDefined) {
              selectedSignalModel.timeSieveOpt = None
            } else {
              println("Git fired up")
              val timeSieve = new TimeSieve
              selectedSignalModel.timeSieveOpt = Some(timeSieve)
              Waves.get(decoupledSignalGroup.name).foreach { wave =>
                var accumulatedTime = -1L
                println(s"Wave:      $wave")
                wave.indices.foreach {
                  case index if wave.value(index) == DecoupledSignalGroup.Fired =>
                    val (start, end) = (wave.start(index), wave.end(index))
                    if (accumulatedTime < 0) accumulatedTime = start
                    timeSieve.add(start, end)
                    accumulatedTime += start
                  case _ => None
                }
                println(s"TimeSieve: $timeSieve")
                publish(WaveFormatChanged(this))
              }
            }
          }
        })
      case _ =>
    }
  }

  val tree: Tree[GenericTreeNode] = new Tree[GenericTreeNode] {

    model = selectedSignalModel.treeModel
    renderer = Tree.Renderer(show)
    showsRootHandles = true

    def show(a: GenericTreeNode): String = {
      def hasFileInfoGlyph(signal: Signal): String = {
        signal match {
          case pureSignal: PureSignal =>
            pureSignal.symbolOpt match {
              case Some(symbol) =>
                symbol.info match {
                  case s: FileInfo =>
                    "⇡ "
                  case _ =>
                    ""
                }
              case _ => ""
            }
          case _ => ""
        }
      }

      val s = a match {
        case _: DirectoryNode =>
          a.name
        case signalTreeNode: SignalTreeNode =>
          dataModel.nameToSignal.get(signalTreeNode.name) match {
            case Some(signal) =>
              Waves.get(signal.name) match {
                case Some(wave) if wave.length > 0 =>
                  val time = selectedSignalModel.cursorPosition
                  val index = wave.findStartIndex(time)
                  val value = wave.value(index)

                  val txt = signal match {
                    case pureSignal: PureSignal if value.asInstanceOf[BigInt] != null =>
                      selectedSignalModel.waveDisplaySettings.get(pureSignal.name) match {
                        case Some(setting) =>
                          setting.dataFormat.getOrElse(DecFormat)(value.asInstanceOf[BigInt])
                        case _ =>
                          value.asInstanceOf[BigInt]
                      }
                    case _: DecoupledSignalGroup | _: ValidSignalGroup =>
                      value match {
                        case DecoupledSignalGroup.Fired => "Fired"
                        case DecoupledSignalGroup.Ready => "Ready"
                        case DecoupledSignalGroup.Valid => "Wait"
                        case DecoupledSignalGroup.Busy  => "Busy"
                      }
                    case _ => ""
                  }
                  s"${hasFileInfoGlyph(signal)}${a.name} = $txt"
                case _ =>
                  a.name
              }

            case _ =>
              a.name
          }
        case _ =>
          a.name
      }
      // The 50 is kind of ugly but it forces the underlying JLabel to be bigger so it doesn't go to ellipses
      // There is, no doubt, a better way to do this
      s + " " * 50
    }

    protected val expansionListener: TreeExpansionListener = new TreeExpansionListener {
      override def treeExpanded(event: TreeExpansionEvent): Unit = {
        publish(SignalsChanged(SignalAndWavePanel.this.tree))
      }

      override def treeCollapsed(event: TreeExpansionEvent): Unit = {
        publish(SignalsChanged(SignalAndWavePanel.this.tree))
      }
    }

    peer.addTreeExpansionListener(expansionListener)

    peer.setRowHeight(DrawMetrics.WaveformVerticalSpacing)

    // Make it re-arrangeable
    peer.setDragEnabled(true)
    peer.setDropMode(DropMode.ON_OR_INSERT)
    peer.setTransferHandler(new TreeTransferHandler(selectedSignalModel))

    def isPointInNode(point: Point): Boolean = {
      val row = getClosestRowForLocation(point.x, point.y)
      if (row >= 0) {
        val rect = peer.getRowBounds(row)
        rect.contains(point)
      } else {
        false
      }
    }

    def handleMouseClick(e: MouseClicked): Unit = {
      println(s"mouse clicked in signalAndWave ${e.clicks}")
      if (SwingUtilities.isRightMouseButton(e.peer)) {
        println("It was a right click")
        if (isPointInNode(e.point)) {
          val path = tree.peer.getClosestPathForLocation(e.point.x, e.point.y)
          val peerNode = path.getLastPathComponent.asInstanceOf[DefaultMutableTreeNode]
          val node = peerNode.getUserObject.asInstanceOf[GenericTreeNode]
          node match {
            case waveFormNode: WaveFormNode =>
              popupMenu(Some(waveFormNode.signal)).show(this, e.point.x, e.point.y)
            case _ =>
          }
        } else {
          println("Doesn't seem to be in a node a")
        }
      } else {
        if (e.clicks == 1) {
          if (!isPointInNode(e.point)) {
            selection.clear()
          }
        }
      }
    }

    listenTo(mouse.clicks)
    listenTo(selectedSignalModel)
    reactions += {
      case KeyReleased(_, key, modifiers, _) =>
        if (key == Key.Left && (modifiers & Key.Modifier.Shift) > 0) {
          ChiselGUI.mainWindow.signalSelectorPanel.tree.requestFocus()
          ChiselGUI.mainWindow.signalSelectorPanel.tree.requestFocusInWindow()
        }

      case _: SignalsChanged =>
        tree.peer.expandPath(new TreePath(model.peer.getRoot))
      case e: MouseClicked =>
        handleMouseClick(e)
    }
  }

  ///////////////////////////////////////////////////////////////////////////
  // View
  ///////////////////////////////////////////////////////////////////////////
  preferredSize = new Dimension(500, 700)

  val timelineComponent = new TimelineComponent(dataModel, selectedSignalModel)
  val wavePanel = new WavePanel(dataModel, selectedSignalModel, tree)
  val selectedSignalPanel = new SelectedSignalPanel(dataModel, selectedSignalModel, tree)

  val signalScrollPane: ScrollPane = new ScrollPane(selectedSignalPanel) {
    minimumSize = new Dimension(150, 300)
    preferredSize = new Dimension(150, 300)

    border = BorderFactory.createEmptyBorder()

    verticalScrollBar.unitIncrement = 16
    horizontalScrollBar.unitIncrement = 16

    // prevents apple trackpad jittering
    horizontalScrollBarPolicy = ScrollPane.BarPolicy.Always
  }
  val waveScrollPane: ScrollPane = new ScrollPane(wavePanel) {
    preferredSize = new Dimension(550, 700)
    horizontalScrollBar.unitIncrement = 16
    verticalScrollBar.unitIncrement = 16
    horizontalScrollBarPolicy = ScrollPane.BarPolicy.Always
    verticalScrollBarPolicy = ScrollPane.BarPolicy.AsNeeded
    columnHeaderView = timelineComponent
  }
  signalScrollPane.verticalScrollBar.peer.setModel(
    waveScrollPane.verticalScrollBar.peer.getModel
  )

  val splitPane: SplitPane = new SplitPane(
    Orientation.Vertical,
    new BoxPanel(Orientation.Vertical) {
      contents += Swing.VStrut(timelineComponent.preferredSize.height)
      contents += signalScrollPane
    },
    waveScrollPane
  ) {
    border = BorderFactory.createEmptyBorder()
  }
  add(splitPane, Center)

  ///////////////////////////////////////////////////////////////////////////
  // Controller
  ///////////////////////////////////////////////////////////////////////////
  def setScaleKeepCentered(newScale: Double, source: Component): Unit = {
    val oldVisibleRect = wavePanel.peer.getVisibleRect
    val centerTimestamp = ((oldVisibleRect.x + oldVisibleRect.width / 2) / selectedSignalModel.scale).toLong

    selectedSignalModel.setScale(newScale, source)

    val centerX = (centerTimestamp * newScale).toInt
    val newVisibleRect = wavePanel.peer.getVisibleRect
    newVisibleRect.x = centerX - newVisibleRect.width / 2
    wavePanel.peer.scrollRectToVisible(newVisibleRect)
  }

  def setScaleAndVisible(newScale: Double, newVisibleX: Int): Unit = {
    selectedSignalModel.setScale(newScale, null)

    val newVisibleRect = wavePanel.peer.getVisibleRect
    newVisibleRect.x = newVisibleX
    wavePanel.peer.scrollRectToVisible(newVisibleRect)
  }

  def updateWaveView(): Unit = {
    setScaleKeepCentered(selectedSignalModel.scale, this)
  }

  def zoomIn(source: Component): Unit = {
    setScaleKeepCentered(selectedSignalModel.scale * 1.25, source)
  }

  def zoomOut(source: Component): Unit = {
    setScaleKeepCentered(selectedSignalModel.scale * 0.8, source)
  }

  /**
    * move wave view to start, keeping the current scale
    *
    * @param source component to scroll
    */
  def zoomToStart(source: Component): Unit = {
    val newVisibleRect = wavePanel.peer.getVisibleRect
    newVisibleRect.x = 0
    wavePanel.peer.scrollRectToVisible(newVisibleRect)
  }

  /**
    * move wave view to end, keeping the current scale
    *
    * @param source component to scroll
    */
  def zoomToEnd(source: Component): Unit = {
    val oldVisibleRect = wavePanel.peer.getVisibleRect
    val maxTimestamp = dataModel.maxTimestamp

    val clockTickWidth = oldVisibleRect.width / selectedSignalModel.scale

    val minTimestamp = (maxTimestamp - clockTickWidth).max(0)

    val centerTimestamp = (maxTimestamp - minTimestamp) / 2 + minTimestamp

    val centerX = (centerTimestamp * selectedSignalModel.scale).toInt

    val newVisibleRect = wavePanel.peer.getVisibleRect
    newVisibleRect.x = centerX - newVisibleRect.width / 2
    wavePanel.peer.scrollRectToVisible(newVisibleRect)
  }

  def removeSignals(source: Component): Unit = {
    selectedSignalModel.removeSelectedSignals(source, tree.selection.paths.iterator)
  }

  def goToEnd(source: Component, steps: Int): Unit = {
    val oldVisibleRect = wavePanel.peer.getVisibleRect

    val newVisibleRect = wavePanel.peer.getVisibleRect
    newVisibleRect.x = (oldVisibleRect.x + steps / selectedSignalModel.scale).toInt

    wavePanel.peer.scrollRectToVisible(newVisibleRect)
  }
}
