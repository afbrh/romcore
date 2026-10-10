// SPDX-License-Identifier: GPL-2.0-or-later

package com.noryan.romcore.core.dolphin.jni.features.settings.model

interface AbstractFloatSetting : AbstractSetting {
    val float: Float

    fun setFloat(settings: Settings, newValue: Float)
}
