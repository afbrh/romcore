// SPDX-License-Identifier: GPL-2.0-or-later

package com.noryan.romcore.core.dolphin.jni.features.settings.model

interface AbstractBooleanSetting : AbstractSetting {
    val boolean: Boolean

    fun setBoolean(settings: Settings, newValue: Boolean)
}
