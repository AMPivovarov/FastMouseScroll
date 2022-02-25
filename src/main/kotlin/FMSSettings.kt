// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package com.jetbrains.fastmousescroll

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.options.UiDslUnnamedConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.EnumComboBoxModel
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.layout.*

enum class ScrollMode(private val visibleName: String,
                      val horizontal: Boolean,
                      val vertical: Boolean) {
  NONE("Disabled", false, false),
  VERTICAL("Vertical", false, true),
  BOTH("Both", true, true);

  override fun toString(): String = visibleName
}

@Service(Service.Level.APP)
@State(name = "FastMouseScrollSettings", storages = [Storage("other.xml")])
class FMSSettings : BaseState(), PersistentStateComponent<FMSSettings> {
  companion object {
    val instance: FMSSettings get() = ApplicationManager.getApplication().getService(FMSSettings::class.java)
  }

  override fun getState(): FMSSettings = this
  override fun loadState(state: FMSSettings) {
    copyFrom(state)
  }

  var scrollMode by enum(ScrollMode.VERTICAL)
  var enableClickToDragToggle by property(true)
  var delayMs by property(10)
}

class FMSConfigurable : UiDslUnnamedConfigurable.Simple() {
  override fun Panel.createContent() {
    val settings = FMSSettings.instance

    lateinit var comboBox: ComboBox<ScrollMode>
    row("Fast mouse scrolling:") {
      comboBox = comboBox(EnumComboBoxModel(ScrollMode::class.java))
        .bindItem({ settings.scrollMode }, { settings.scrollMode = it ?: ScrollMode.VERTICAL })
        .component
    }

    indent {
      row {
        checkBox("Enable click-to-scroll toggle mode")
          .bindSelected(settings::enableClickToDragToggle)
      }
      row {
        label("Refresh delay, ms:")
        spinner(1..500, 10)
          .bindIntValue(settings::delayMs)
      }
    }.enabledIf(comboBox.selectedValueIs(ScrollMode.NONE).not())
  }
}
