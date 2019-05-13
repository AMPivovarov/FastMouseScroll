// Copyright 2019 Aleksey Pivovarov. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
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
import java.awt.AWTEvent
import java.awt.Cursor
import java.awt.Point
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

class FastMouseScrollComponent : IdeEventQueue.EventDispatcher {
  companion object {
    private const val DELAY_MS: Int = 30
  }

  private var handler: Handler? = null

  init {
    IdeEventQueue.getInstance().addDispatcher(this, ApplicationManager.getApplication())
  }

  @ExperimentalContracts
  override fun dispatch(event: AWTEvent): Boolean {
    if (isEscapeKey(event) && event.id == KeyEvent.KEY_PRESSED) {
      return disposeHandler()
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

        if (editor != null) {
          handler = EditorHandler(editor, event).start()
          return true
        }

        if (scrollPane != null) {
          handler = ScrollPaneHandler(scrollPane, event).start()
          return true
        }
      }
      if (event.id == MouseEvent.MOUSE_RELEASED) {
        disposeHandler(300)
        return true
      }
      return true // suppress shortcuts
    }

    if (event is MouseEvent && (event.id == MouseEvent.MOUSE_MOVED || event.id == MouseEvent.MOUSE_DRAGGED)) {
      handler?.mouseMoved(event)
      return false
    }

    return false
  }

  private fun disposeHandler(minDelay: Int = 0): Boolean {
    if (handler == null) return false
    if (System.currentTimeMillis() - handler!!.startTimestamp < minDelay) return false
    Disposer.dispose(handler!!)
    handler = null
    return true
  }

  private inner class EditorHandler(val editor: EditorEx, startEvent: MouseEvent)
    : Handler(editor.component, startEvent) {

    override fun scrollComponent(delta: Int) {
      editor.scrollingModel.disableAnimation()
      editor.scrollingModel.scrollVertically(editor.scrollingModel.verticalScrollOffset + delta)
      editor.scrollingModel.enableAnimation()
    }

    override fun setCursor(cursor: Cursor?) {
      editor.setCustomCursor(this, cursor)
    }
  }

  private inner class ScrollPaneHandler(val scrollPane: JScrollPane, startEvent: MouseEvent)
    : Handler(scrollPane, startEvent) {

    override fun scrollComponent(delta: Int) {
      scrollPane.verticalScrollBar.value = scrollPane.verticalScrollBar.value + delta
    }

    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    override fun setCursor(cursor: Cursor?) {
      scrollPane.cursor = cursor
    }
  }

  private abstract inner class Handler(val component: JComponent, startEvent: MouseEvent) : Disposable {
    private val calcSpeed: ScrollSpeedAlg = GeckoScrollSpeedAlg

    val startTimestamp: Long = System.currentTimeMillis()
    private val startPoint: Point = RelativePoint(startEvent).getPoint(component)
    private val alarm = Alarm()

    private var currentSpeed: Double = 0.0 // pixels to scroll per second
    private var lastEventTimestamp: Long = Long.MAX_VALUE
    private var lastEventRemainder: Double = 0.0

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
      currentSpeed = calcSpeed(currentPoint.y - startPoint.y)

      if (currentSpeed != 0.0 && lastEventTimestamp == Long.MAX_VALUE) {
        lastEventTimestamp = System.currentTimeMillis()
        lastEventRemainder = 0.0
      }
      if (currentSpeed == 0.0) {
        lastEventTimestamp = Long.MAX_VALUE
        lastEventRemainder = 0.0
      }

      val cursor = when {
        currentSpeed > 0 -> Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR)
        currentSpeed < 0 -> Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR)
        else -> Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
      }
      setCursor(cursor)
    }

    private fun doScroll() {
      if (currentSpeed != 0.0) {
        val timestamp = System.currentTimeMillis()
        val timeDelta = (timestamp - lastEventTimestamp).coerceAtLeast(0)
        val pixels = lastEventRemainder + currentSpeed * timeDelta / 1000
        val delta = pixels.roundToInt()

        lastEventTimestamp = timestamp
        lastEventRemainder = pixels - delta

        scrollComponent(delta)
      }

      scheduleScrollEvent()
    }

    private fun scheduleScrollEvent() {
      alarm.addRequest(this@Handler::doScroll, DELAY_MS)
    }

    protected abstract fun scrollComponent(delta: Int)
    protected abstract fun setCursor(cursor: Cursor?)
  }
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