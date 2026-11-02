package com.xnvalabs.smarteyex.data.vision

import androidx.compose.runtime.mutableStateOf

/**
 * Feature map #23 (Camera Privacy Indicator): a physical LED on real
 * SmartEyeX hardware lights up whenever the camera is capturing. This is
 * the software-side equivalent for the phone-companion app — [isActive]
 * mirrors camera state 1:1, so nothing else in the app can turn the
 * camera on without this reflecting it. VisionScreen shows [isActive] as
 * an on-screen indicator, and any future hardware bridge should read
 * this same flag to drive the real LED.
 *
 * Deliberately dumb — [setActive] is called only from VisionScreen's own
 * camera bind/unbind lifecycle, never toggled independently, so it can
 * never show "off" while the camera is actually capturing, or vice versa.
 */
object CaptureLedController {
    var isActive = mutableStateOf(false)
        private set

    fun setActive(active: Boolean) {
        isActive.value = active
    }
}
