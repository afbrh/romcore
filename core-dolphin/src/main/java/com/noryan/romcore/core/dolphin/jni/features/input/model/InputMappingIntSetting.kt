// SPDX-License-Identifier: GPL-2.0-or-later

package com.noryan.romcore.core.dolphin.jni.features.input.model

import com.noryan.romcore.core.dolphin.jni.features.input.model.controlleremu.NumericSetting
import com.noryan.romcore.core.dolphin.jni.features.settings.model.AbstractIntSetting
import com.noryan.romcore.core.dolphin.jni.features.settings.model.Settings

class InputMappingIntSetting(private val numericSetting: NumericSetting) : AbstractIntSetting {
    override val int: Int
        get() = numericSetting.getIntValue()

    override fun setInt(settings: Settings, newValue: Int) = numericSetting.setIntValue(newValue)

    override val isOverridden: Boolean = false

    override val isRuntimeEditable: Boolean = true

    override fun delete(settings: Settings): Boolean {
        numericSetting.setIntValue(numericSetting.getIntDefaultValue())
        return true
    }
}
