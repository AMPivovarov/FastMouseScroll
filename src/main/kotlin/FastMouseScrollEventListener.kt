// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.fastmousescroll

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.IdeEventQueue
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.MouseShortcut
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.keymap.KeymapManager
import com.intellij.openapi.project.Project
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
  if (event !is MouseEvent) return false
  val shortcuts = KeymapManager.getInstance().activeKeymap.getShortcuts("FastMouseScroll.Toggle").filterIsInstance<MouseShortcut>()
  return shortcuts.contains(MouseShortcut(event.button, event.modifiersEx, 1))
}

@ExperimentalContracts
class FastMouseScrollStarter : AppLifecycleListener, DynamicPluginListener {
  companion object {
    private const val ourPluginId = "com.jetbrains.fast.mouse.scroll"
  }

  private var disposable: Disposable? = null

  private fun startListen() {
    if (disposable != null) return
    disposable = Disposer.newDisposable()
    IdeEventQueue.getInstance().addDispatcher(FastMouseScrollEventListener(), disposable)
  }

  private fun stopListen() {
    disposable?.let { Disposer.dispose(it) }
    disposable = null
  }

  override fun appStarting(project: Project?) = startListen()
  override fun appClosing() = stopListen()

  override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
    if (pluginDescriptor.pluginId.idString == ourPluginId) startListen()
  }

  override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
    if (pluginDescriptor.pluginId.idString == ourPluginId) stopListen()
  }
}

@ExperimentalContracts
class FastMouseScrollEventListener : IdeEventQueue.EventDispatcher {
  private var handler: Handler? = null

  override fun dispatch(event: AWTEvent): Boolean {
    if (event !is InputEvent || event.isConsumed) return false

    // can be 'null' or FMSSettings from a different classloader in some cases (IDE bug?)
    @Suppress("USELESS_CAST")
    val settings = ServiceManager.getService(FMSSettings::class.java) as? FMSSettings ?: return false

    val mode = settings.scrollMode
    val enableToggle = settings.enableClickToDragToggle
    if (mode == ScrollMode.NONE) {
      return false
    }

    if (isToggleMouseButton(event)) {
      val component = UIUtil.getDeepestComponentAt(event.component, event.x, event.y) as? JComponent
      val editor = findEditor(component)
      val scrollPane = findScrollPane(component)

      if (handler == null && editor == null && scrollPane == null) return false

      if (event.id == MouseEvent.MOUSE_PRESSED) {
        handler?.let { handler ->
          Disposer.dispose(handler)
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

  private fun findEditor(component: JComponent?): EditorEx? {
    val editorComponent = UIUtil.getParentOfType(EditorComponentImpl::class.java, component) ?: return null
    return editorComponent.editor
  }

  private fun findScrollPane(component: JComponent?): JScrollPane? {
    return UIUtil.getParentOfType(JScrollPane::class.java, component)
  }

  private fun installHandler(newHandler: Handler) {
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
      Disposer.register(newHandler, Disposable { window.removeWindowFocusListener(listener) })
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

      if (hBar != null && hBar.isVisible) hBar.value = hBar.value + deltaX
      if (vBar != null && vBar.isVisible) vBar.value = vBar.value + deltaY
    }

    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    override fun setCursor(cursor: Cursor?) {
      scrollPane.cursor = cursor
    }
  }

  private abstract inner class Handler(val component: JComponent, startEvent: MouseEvent, val mode: ScrollMode) : Disposable {
    private val delayMs = FMSSettings.instance.delayMs
    private val scrollSpeedAlg: ScrollSpeedAlg = GeckoScrollSpeedAlg

    val startTimestamp: Long = System.currentTimeMillis()
    private val startPoint: Point = RelativePoint(startEvent).getPoint(component)
    private val alarm = Alarm()

    var wasMoved: Boolean = false
      private set

    private var deltaX: DeltaState = DeltaState()
    private var deltaY: DeltaState = DeltaState()
    private var lastEventTimestamp: Long = Long.MAX_VALUE

    var isDisposed = false
      private set

    fun start(): Handler {
      setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR))
      scheduleScrollEvent()
      return this
    }

    override fun dispose() {
      isDisposed = true
      setCursor(null)
      Disposer.dispose(alarm)
    }

    fun mouseMoved(event: MouseEvent) {
      if (isDisposed) return
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

      if (deltaX.currentSpeed != 0.0 || deltaY.currentSpeed != 0.0) wasMoved = true
      setCursor(getCursor(deltaX.currentSpeed, deltaY.currentSpeed))
    }

    private fun doScroll() {
      if (isDisposed) return

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
      alarm.addRequest(this@Handler::doScroll, delayMs)
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