// SPDX-License-Identifier: GPL-2.0-or-later

package com.noryan.romcore.core.dolphin.jni.features.input.model

import com.noryan.romcore.core.dolphin.jni.features.input.model.controlleremu.NumericSetting
import com.noryan.romcore.core.dolphin.jni.features.settings.model.AbstractBooleanSetting
import com.noryan.romcore.core.dolphin.jni.features.settings.model.Settings

class InputMappingBooleanSetting(private val numericSetting: NumericSetting) :
    AbstractBooleanSetting {
    override val boolean: Boolean
        get() = numericSetting.getBooleanValue()

    override fun setBoolean(settings: Settings, newValue: Boolean) =
        numericSetting.setBooleanValue(newValue)

    override val isOverridden: Boolean = false

    override val isRuntimeEditable: Boolean = true

    override fun delete(settings: Settings): Boolean {
        numericSetting.setBooleanValue(numericSetting.getBooleanDefaultValue())
        return true
    }
}
