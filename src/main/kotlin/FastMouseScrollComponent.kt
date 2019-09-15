// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.fastmousescroll

import com.intellij.ide.DataManager
import com.intellij.ide.IdeEventQueue
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Alarm
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.update.Activatable
import com.intellij.util.ui.update.UiNotifyConnector
import java.awt.AWTEvent
import java.awt.Cursor
import java.awt.Point
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
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
  return event is MouseEvent &&
         event.button == MouseEvent.BUTTON2 &&
         !(event.isControlDown || event.isShiftDown || event.isMetaDown)
}

@ExperimentalContracts
class FastMouseScrollComponent : IdeEventQueue.EventDispatcher {
  companion object {
    private const val DELAY_MS: Int = 30
  }

  private var handler: Handler? = null

  init {
    IdeEventQueue.getInstance().addDispatcher(this, ApplicationManager.getApplication())
  }

  override fun dispatch(event: AWTEvent): Boolean {
    if (event !is InputEvent || event.isConsumed) return false

    val mode = FMSSettings.instance.scrollMode
    if (mode == ScrollMode.NONE) {
      return false
    }

    if (isToggleMouseButton(event)) {
      val component = SwingUtilities.getDeepestComponentAt(event.component, event.x, event.y) as? JComponent
      val editor = DataManager.getInstance().getDataContext(component).getData(CommonDataKeys.EDITOR) as? EditorEx
      val scrollPane = UIUtil.getParentOfType(JScrollPane::class.java, component)

      if (handler == null && editor == null && scrollPane == null) return false

      if (event.id == MouseEvent.MOUSE_PRESSED) {
        if (disposeHandler()) {
          return true
        }

        val newHandler = when {
          editor != null -> EditorHandler(editor, event, mode)
          scrollPane != null -> ScrollPaneHandler(scrollPane, event, mode)
          else -> null
        }
        if (newHandler != null) {
          installHandler(newHandler)
        }
        return true
      }
      if (event.id == MouseEvent.MOUSE_RELEASED) {
        disposeHandler(300)
        return true
      }
      return true // suppress shortcuts
    }

    if (isEscapeKey(event) && event.id == KeyEvent.KEY_PRESSED) {
      return disposeHandler()
    }

    if (event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED) {
      return disposeHandler()
    }

    if (event is MouseEvent && (event.id == MouseEvent.MOUSE_MOVED || event.id == MouseEvent.MOUSE_DRAGGED)) {
      handler?.let { handler ->
        handler.mouseMoved(event)
        return event.id == MouseEvent.MOUSE_DRAGGED
      }
    }

    return false
  }

  private fun installHandler(newHandler: Handler) {
    handler = newHandler
    Disposer.register(newHandler, UiNotifyConnector(newHandler.component, object : Activatable.Adapter() {
      override fun hideNotify() {
        if (handler == newHandler) {
          disposeHandler()
        }
      }
    }))
    newHandler.start()
  }

  private fun disposeHandler(minDelay: Int = 0): Boolean {
    if (handler == null) return false
    if (System.currentTimeMillis() - handler!!.startTimestamp < minDelay) return false
    Disposer.dispose(handler!!)
    handler = null
    return true
  }

  private inner class EditorHandler(val editor: EditorEx, startEvent: MouseEvent, mode: ScrollMode)
    : Handler(editor.component, startEvent, mode) {

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

  private inner class ScrollPaneHandler(val scrollPane: JScrollPane, startEvent: MouseEvent, mode: ScrollMode)
    : Handler(scrollPane, startEvent, mode) {

    override fun scrollComponent(deltaX: Int, deltaY: Int) {
      val hBar = scrollPane.horizontalScrollBar
      val vBar = scrollPane.verticalScrollBar

      if (hBar.isVisible) hBar.value = hBar.value + deltaX
      if (vBar.isVisible) vBar.value = vBar.value + deltaY
    }

    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    override fun setCursor(cursor: Cursor?) {
      scrollPane.cursor = cursor
    }
  }

  private abstract inner class Handler(val component: JComponent, startEvent: MouseEvent, val mode: ScrollMode) : Disposable {
    private val scrollSpeedAlg: ScrollSpeedAlg = GeckoScrollSpeedAlg

    val startTimestamp: Long = System.currentTimeMillis()
    private val startPoint: Point = RelativePoint(startEvent).getPoint(component)
    private val alarm = Alarm()

    private var deltaX: DeltaState = DeltaState()
    private var deltaY: DeltaState = DeltaState()
    private var lastEventTimestamp: Long = Long.MAX_VALUE

    fun start(): Handler {
      setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR))
      scheduleScrollEvent()
      return this
    }

    override fun dispose() {
      setCursor(null)
      Disposer.dispose(alarm)
    }

    fun mouseMoved(event: MouseEvent) {
      val currentPoint = RelativePoint(event).getPoint(component)

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

      setCursor(getCursor(deltaX.currentSpeed, deltaY.currentSpeed))
    }

    private fun doScroll() {
      if (deltaX.isActive || deltaY.isActive) {
        val timestamp = System.currentTimeMillis()
        val timeDelta = (timestamp - lastEventTimestamp).coerceAtLeast(0)
        lastEventTimestamp = timestamp

        val stepX = deltaX.step(timeDelta)
        val stepY = deltaY.step(timeDelta)
        scrollComponent(stepX, stepY)
      }

      scheduleScrollEvent()
    }

    private fun scheduleScrollEvent() {
      alarm.addRequest(this@Handler::doScroll, DELAY_MS)
    }

    private fun calcSpeed(delta: Int, isEnabled: Boolean): Double {
      if (!isEnabled) return 0.0
      return scrollSpeedAlg(delta)
    }

    protected abstract fun scrollComponent(deltaX: Int, deltaY: Int)
    protected abstract fun setCursor(cursor: Cursor?)
  }
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

private fun getCursor(speedX: Double, speedY: Double): Cursor = when {
  speedX == 0.0 && speedY > 0 -> Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
  speedX == 0.0 && speedY < 0 -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
  speedY == 0.0 && speedX > 0 -> Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR)
  speedY == 0.0 && speedX < 0 -> Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR)
  speedX > 0 && speedY > 0 -> Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR)
  speedX > 0 && speedY < 0 -> Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR)
  speedX < 0 && speedY > 0 -> Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR)
  speedX < 0 && speedY < 0 -> Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR)
  else -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
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