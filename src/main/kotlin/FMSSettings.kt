// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.jetbrains.fastmousescroll

import com.intellij.openapi.components.*
import com.intellij.openapi.options.UnnamedConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.EnumComboBoxModel
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

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

  var scrollMode by property(ScrollMode.VERTICAL)
}

class FMSConfigurable : UnnamedConfigurable {
  private val panel: JPanel = JPanel()
  private val scrollModeCombobox = ComboBox<ScrollMode>(EnumComboBoxModel(ScrollMode::class.java))

  init {
    panel.layout = BorderLayout()
    panel.add(JLabel("Fast mouse scrolling: "), BorderLayout.WEST)
    panel.add(scrollModeCombobox, BorderLayout.CENTER)
  }

  override fun createComponent(): JComponent = panel

  override fun isModified(): Boolean =
    FMSSettings.instance.scrollMode != scrollModeCombobox.selectedItem

  override fun reset() {
    scrollModeCombobox.selectedItem = FMSSettings.instance.scrollMode
  }

  override fun apply() {
    FMSSettings.instance.scrollMode = scrollModeCombobox.selectedItem as ScrollMode
  }
}
