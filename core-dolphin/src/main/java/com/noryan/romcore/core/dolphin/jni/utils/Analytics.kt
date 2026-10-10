// SPDX-License-Identifier: GPL-2.0-or-later

package com.noryan.romcore.core.dolphin.jni.utils

import android.os.Build
import androidx.annotation.Keep

// RomCore: UI + opt-in flow removed; analytics are forced off through Dolphin.ini defaults (see DolphinDefaults).
object Analytics {
    private const val DEVICE_MANUFACTURER = "DEVICE_MANUFACTURER"
    private const val DEVICE_OS = "DEVICE_OS"
    private const val DEVICE_MODEL = "DEVICE_MODEL"
    private const val DEVICE_TYPE = "DEVICE_TYPE"

    @Keep
    @JvmStatic
    fun getValue(key: String?): String {
        return when (key) {
            DEVICE_MODEL -> Build.MODEL
            DEVICE_MANUFACTURER -> Build.MANUFACTURER
            DEVICE_OS -> Build.VERSION.SDK_INT.toString()
            DEVICE_TYPE -> "android-mobile"
            else -> ""
        }
    }
}
