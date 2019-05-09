// Copyright 2019 Aleksey Pivovarov. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.jetbrains.fastmousescroll

import com.intellij.util.ui.JBUI
import kotlin.math.absoluteValue
import kotlin.math.sign

object SimpleScrollSpeedAlg : ScrollSpeedAlg {
  override val name: String get() = "Default"

  override fun invoke(delta: Int): Double {
    val value = delta.absoluteValue - JBUI.scale(10)
    if (value < 1) return 0.0

    val square = value.toDouble()
    val scrollSpeed = square * square / 40 / JBUI.scale(1f)
    if (scrollSpeed < 1) return 0.0

    return scrollSpeed * delta.sign
  }
}