// SPDX-License-Identifier: GPL-2.0-or-later

package com.noryan.romcore.core.dolphin.jni.features.settings.model

interface AbstractSetting {
    val isOverridden: Boolean
    val isRuntimeEditable: Boolean

    fun delete(settings: Settings): Boolean
}
