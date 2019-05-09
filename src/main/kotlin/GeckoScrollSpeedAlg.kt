/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */
package com.jetbrains.fastmousescroll

/**
 *  @see <a href="https://github.com/mozilla/gecko-dev/blob/007e1e4a2fbe8e7d53b1dcf7ec513bbd70e2a4a3/toolkit/modules/AutoScrollController.jsm#L260">Gecko sources</a>
 */
object GeckoScrollSpeedAlg : ScrollSpeedAlg {
  override val name: String get() = "Like Gecko"

  override fun invoke(delta: Int): Double {
    val timeCompensation = 1000 / 20
    val speed = 12
    val value = delta.toDouble() / speed

    if (value > 1)
      return (value * Math.sqrt(value) - 1) * timeCompensation
    if (value < -1)
      return (value * Math.sqrt(-value) + 1) * timeCompensation
    return 0.0
  }
}