// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.jetbrains.fastmousescroll

import com.intellij.openapi.components.*
import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.EnumComboBoxModel
import com.intellij.ui.JBIntSpinner
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import javax.swing.*

enum class ScrollMode(private val visibleName: String,
                      val horizontal: Boolean,
                      val vertical: Boolean) {
  NONE("Disabled", false, false),
  VERTICAL("Vertical", false, true),
  BOTH("Both", true, true);

  override fun toString(): String = visibleName
}

@State(name = "FastMouseScrollSettings", storages = [Storage("other.xml")])
class FMSSettings : BaseState(), PersistentStateComponent<FMSSettings> {
  companion object {
    val instance: FMSSettings get() = ServiceManager.getService(FMSSettings::class.java)
  }

  override fun getState(): FMSSettings = this
  override fun loadState(state: FMSSettings) {
    copyFrom(state)
  }

  var scrollMode by enum(ScrollMode.VERTICAL)
  var enableClickToDragToggle by property(true)
  var delayMs by property(10)
}

class FMSConfigurable : UnnamedConfigurable {
  private val panel: JPanel = JPanel()
  private val scrollModeCombobox = ComboBox<ScrollMode>(EnumComboBoxModel(ScrollMode::class.java))
  private val enableToggleMode = JCheckBox("Enable click-to-scroll toggle mode")
  private val delayMsSpinner = JBIntSpinner(10, 5, 200)

  init {
    panel.layout = FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)
    panel.add(JLabel("Fast mouse scrolling:"))
    panel.add(scrollModeCombobox)
    panel.add(Box.createHorizontalStrut(JBUI.scale(8)))
    panel.add(enableToggleMode)
    panel.add(Box.createHorizontalStrut(JBUI.scale(8)))
    panel.add(JLabel("Refresh delay, ms:"))
    panel.add(delayMsSpinner)
  }

  override fun createComponent(): JComponent = panel

  override fun isModified(): Boolean = with(FMSSettings.instance) {
    scrollMode != scrollModeCombobox.selectedItem ||
    enableClickToDragToggle != enableToggleMode.isSelected ||
    delayMs != delayMsSpinner.number
  }

  override fun reset() = with(FMSSettings.instance) {
    scrollModeCombobox.selectedItem = scrollMode
    enableToggleMode.isSelected = enableClickToDragToggle
    delayMsSpinner.number = delayMs
  }

  override fun apply() = with(FMSSettings.instance) {
    scrollMode = scrollModeCombobox.selectedItem as ScrollMode
    enableClickToDragToggle = enableToggleMode.isSelected
    delayMs = delayMsSpinner.number
  }
}
