// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.fastmousescroll

import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.MouseShortcut
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ComponentUtil
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Alarm
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.update.Activatable
import com.intellij.util.ui.update.UiNotifyConnector
import java.awt.AWTEvent
import java.awt.Cursor
import java.awt.Point
import java.awt.event.*
import javax.swing.JComponent
import javax.swing.JScrollPane
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.math.roundToInt

@ExperimentalContracts
private fun isEscapeKey(event: AWTEvent): Boolean {
  contract { returns(true) implies (event is KeyEvent) }
  return event is KeyEvent &&
         event.keyCode == KeyEvent.VK_ESCAPE
}

@ExperimentalContracts
private fun isToggleMouseButton(event: AWTEvent): Boolean {
  contract { returns(true) implies (event is MouseEvent) }
  return isActionMouseButton(event, "FastMouseScroll.Toggle")
}

@ExperimentalContracts
private fun isPanningMouseButton(event: AWTEvent): Boolean {
  contract { returns(true) implies (event is MouseEvent) }
  return isActionMouseButton(event, "FastMouseScroll.Panning")
}

@ExperimentalContracts
private fun isActionMouseButton(event: AWTEvent, actionId: String): Boolean {
  contract { returns(true) implies (event is MouseEvent) }
  if (event !is MouseEvent) return false
  val shortcuts = KeymapManager.getInstance().activeKeymap.getShortcuts(actionId).filterIsInstance<MouseShortcut>()
  return shortcuts.contains(MouseShortcut(event.button, event.modifiersEx, 1))
}

@ExperimentalContracts
class FastMouseScrollEventListener : IdeEventQueue.EventDispatcher {
  private var handler: Handler? = null

  override fun dispatch(event: AWTEvent): Boolean {
    if (event !is InputEvent || event.isConsumed) return false

    // can be 'null' or FMSSettings from a different classloader in some cases (IDE bug?)
    @Suppress("USELESS_CAST")
    val settings = ApplicationManager.getApplication().getService(FMSSettings::class.java) as? FMSSettings ?: return false

    val mode = settings.scrollMode
    val enableToggle = settings.enableClickToDragToggle
    if (mode == ScrollMode.NONE) {
      return false
    }

    // NB: On macOS event reports all buttons/modifiers pressed and `isToggleMouseButton(event) == true`
    if (event is MouseEvent && (event.id == MouseEvent.MOUSE_MOVED || event.id == MouseEvent.MOUSE_DRAGGED)) {
      handler?.let { handler ->
        handler.mouseMoved(event)
        return event.id == MouseEvent.MOUSE_DRAGGED
      }
    }

    if (isToggleMouseButton(event)) {
      val scrollable = findScrollableFor(event)
      if (handler == null && scrollable == null) return false

      if (event.id == MouseEvent.MOUSE_PRESSED) {
        handler?.let { handler ->
          Disposer.dispose(handler)
          return true
        }

        if (scrollable != null) {
          installHandler(ScrollHandler(scrollable, event, mode, enableToggle))
        }
        return enableToggle
      }
      if (event.id == MouseEvent.MOUSE_RELEASED) {
        val wasMoved = handler?.wasMoved == true
        handler?.let { handler ->
          if (handler.isDisposed ||
              (System.currentTimeMillis() - handler.startTimestamp) > 300 ||
              !enableToggle) {
            disposeHandler()
          }
        }
        return enableToggle || wasMoved
      }
      return true // suppress shortcuts
    }

    if (isPanningMouseButton(event)) {
      val scrollable = findScrollableFor(event)
      if (handler == null && scrollable == null) return false

      if (event.id == MouseEvent.MOUSE_PRESSED) {
        handler?.let { handler ->
          Disposer.dispose(handler)
          return true
        }

        if (scrollable != null) {
          installHandler(PanningHandler(scrollable, event))
        }
        return false
      }
      if (event.id == MouseEvent.MOUSE_RELEASED) {
        val wasMoved = handler?.wasMoved == true
        handler?.let { handler ->
          disposeHandler()
        }
        return wasMoved
      }
      return true // suppress shortcuts
    }

    if (isEscapeKey(event) && event.id == KeyEvent.KEY_PRESSED) {
      return disposeHandler()
    }

    if (event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED) {
      return disposeHandler()
    }

    return false
  }

  private fun findScrollableFor(event: MouseEvent): ScrollableComponent? {
    val component = UIUtil.getDeepestComponentAt(event.component, event.x, event.y) as? JComponent
    val editor = findEditor(component)
    if (editor != null) return EditorScrollable(editor)

    val scrollPane = findScrollPane(component)
    if (scrollPane != null) return ScrollPaneScrollable(scrollPane)

    return null
  }

  private fun findEditor(component: JComponent?): EditorEx? {
    val editorComponent = UIUtil.getParentOfType(EditorComponentImpl::class.java, component) ?: return null
    return editorComponent.editor
  }

  private fun findScrollPane(component: JComponent?): JScrollPane? {
    return UIUtil.getParentOfType(JScrollPane::class.java, component)
  }

  private fun installHandler(newHandler: Handler) {
    @Suppress("DEPRECATION")
    Disposer.register(newHandler, UiNotifyConnector.Once(newHandler.component, object : Activatable.Adapter() {
      override fun hideNotify() {
        if (handler == newHandler) {
          disposeHandler()
        }
      }
    }))

    val window = ComponentUtil.getWindow(newHandler.component)
    if (window != null) {
      val listener = object : WindowFocusListener {
        override fun windowGainedFocus(e: WindowEvent?) = Unit

        override fun windowLostFocus(e: WindowEvent?) {
          if (handler == newHandler) {
            disposeHandler()
          }
        }
      }
      window.addWindowFocusListener(listener)
      Disposer.register(newHandler) { window.removeWindowFocusListener(listener) }
    }

    handler = newHandler
    newHandler.start()
  }

  private fun disposeHandler(): Boolean {
    if (handler == null) return false
    Disposer.dispose(handler!!)
    handler = null
    return true
  }

  private inner class EditorScrollable(val editor: EditorEx) : ScrollableComponent {
    override val component: JComponent get() = editor.component

    override fun scrollComponent(deltaX: Int, deltaY: Int) {
      editor.scrollingModel.disableAnimation()
      editor.scrollingModel.scroll(editor.scrollingModel.horizontalScrollOffset + deltaX,
                                   editor.scrollingModel.verticalScrollOffset + deltaY)
      editor.scrollingModel.enableAnimation()
    }

    override fun setCursor(cursor: Cursor?) {
      editor.setCustomCursor(this, cursor)
    }
  }

  private inner class ScrollPaneScrollable(val scrollPane: JScrollPane) : ScrollableComponent {
    override val component: JComponent get() = scrollPane

    override fun scrollComponent(deltaX: Int, deltaY: Int) {
      val hBar = scrollPane.horizontalScrollBar
      val vBar = scrollPane.verticalScrollBar

      if (hBar != null && hBar.isVisible) hBar.value = hBar.value + deltaX
      if (vBar != null && vBar.isVisible) vBar.value = vBar.value + deltaY
    }

    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    override fun setCursor(cursor: Cursor?) {
      scrollPane.cursor = cursor
    }
  }

  private inner class ScrollHandler(val scrollable: ScrollableComponent,
                                    startEvent: MouseEvent,
                                    val mode: ScrollMode,
                                    val enableToggle: Boolean) : Handler {
    override val component: JComponent get() = scrollable.component

    private val delayMs = FMSSettings.instance.delayMs
    private val scrollSpeedAlg: ScrollSpeedAlg = GeckoScrollSpeedAlg

    override val startTimestamp: Long = System.currentTimeMillis()
    private val startPoint: Point = RelativePoint(startEvent).getPoint(scrollable.component)
    private val alarm = Alarm()

    override var wasMoved: Boolean = false

    private var deltaX: DeltaState = DeltaState()
    private var deltaY: DeltaState = DeltaState()
    private var lastEventTimestamp: Long = Long.MAX_VALUE

    override var isDisposed = false

    override fun start() {
      if (enableToggle) scrollable.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR))
      scheduleScrollEvent()
    }

    override fun dispose() {
      isDisposed = true
      scrollable.setCursor(null)
      Disposer.dispose(alarm)
    }

    override fun mouseMoved(event: MouseEvent) {
      if (isDisposed) return
      val currentPoint = RelativePoint(event).getPoint(scrollable.component)

      deltaX.currentSpeed = calcSpeed(currentPoint.x - startPoint.x, mode.horizontal)
      deltaY.currentSpeed = calcSpeed(currentPoint.y - startPoint.y, mode.vertical)

      val isActive = deltaX.isActive || deltaY.isActive
      val wasActive = lastEventTimestamp != Long.MAX_VALUE
      if (isActive != wasActive) {
        lastEventTimestamp = when {
          isActive -> System.currentTimeMillis()
          else -> Long.MAX_VALUE
        }

        deltaX.lastEventRemainder = 0.0
        deltaY.lastEventRemainder = 0.0
      }

      if (deltaX.currentSpeed != 0.0 || deltaY.currentSpeed != 0.0) wasMoved = true
      scrollable.setCursor(getCursor(deltaX.currentSpeed, deltaY.currentSpeed, enableToggle || wasMoved))
    }

    private fun doScroll() {
      if (isDisposed) return

      if (deltaX.isActive || deltaY.isActive) {
        val timestamp = System.currentTimeMillis()
        val timeDelta = (timestamp - lastEventTimestamp).coerceAtLeast(0)
        lastEventTimestamp = timestamp

        val stepX = deltaX.step(timeDelta)
        val stepY = deltaY.step(timeDelta)
        scrollable.scrollComponent(stepX, stepY)
      }

      scheduleScrollEvent()
    }

    private fun scheduleScrollEvent() {
      alarm.addRequest(this::doScroll, delayMs)
    }

    private fun calcSpeed(delta: Int, isEnabled: Boolean): Double {
      if (!isEnabled) return 0.0
      return scrollSpeedAlg(delta)
    }
  }

  /**
   * ImageEditor/MapViewer-like drag-to-scroll mode.
   */
  private inner class PanningHandler(val scrollable: ScrollableComponent,
                                     startEvent: MouseEvent) : Handler {
    override val component: JComponent get() = scrollable.component

    override val startTimestamp: Long = System.currentTimeMillis()
    override var wasMoved: Boolean = false

    private var lastPoint: Point = RelativePoint(startEvent).getPoint(scrollable.component)

    override var isDisposed = false

    override fun start() {
      scrollable.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR))
    }

    override fun dispose() {
      isDisposed = true
      scrollable.setCursor(null)
    }

    override fun mouseMoved(event: MouseEvent) {
      if (isDisposed) return
      val currentPoint = RelativePoint(event).getPoint(scrollable.component)

      val deltaX = lastPoint.x - currentPoint.x
      val deltaY = lastPoint.y - currentPoint.y
      lastPoint = currentPoint

      if (deltaX != 0 || deltaY != 0) {
        wasMoved = true
        scrollable.scrollComponent(deltaX, deltaY)
      }
    }
  }
}

private interface Handler : Disposable {
  val component: JComponent

  val isDisposed: Boolean
  val wasMoved: Boolean
  val startTimestamp: Long

  fun start()
  fun mouseMoved(event: MouseEvent)
}

private interface ScrollableComponent {
  val component: JComponent

  fun scrollComponent(deltaX: Int, deltaY: Int)
  fun setCursor(cursor: Cursor?)
}

private class DeltaState {
  var currentSpeed: Double = 0.0 // pixels to scroll per second
  var lastEventRemainder: Double = 0.0

  val isActive get() = currentSpeed != 0.0

  fun step(timeDelta: Long): Int {
    val pixels = lastEventRemainder + currentSpeed * timeDelta / 1000
    val delta = pixels.roundToInt()

    lastEventRemainder = pixels - delta
    return delta
  }
}

private fun getCursor(speedX: Double, speedY: Double, isActive: Boolean): Cursor? = when {
  speedX == 0.0 && speedY > 0 -> Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
  speedX == 0.0 && speedY < 0 -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
  speedY == 0.0 && speedX > 0 -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
  speedY == 0.0 && speedX < 0 -> Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
  speedX > 0 && speedY > 0 -> Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
  speedX > 0 && speedY < 0 -> Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR)
  speedX < 0 && speedY > 0 -> Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR)
  speedX < 0 && speedY < 0 -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
  isActive -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
  else -> null
}

/**
 * Other notable implementations:
 *  Chromium - AutoscrollController::HandleMouseMoveForMiddleClickAutoscroll
 *  WebKit - RenderBox::calculateAutoscrollDirection
 */
interface ScrollSpeedAlg {
  val name: String

  /**
   * @param delta distance from original mouse position, in pixels
   * @return scroll speed, pixels per second
   */
  operator fun invoke(delta: Int): Double
}