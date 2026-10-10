// SPDX-License-Identifier: GPL-2.0-or-later

package com.noryan.romcore.core.dolphin.jni.features.input.model

import com.noryan.romcore.core.dolphin.jni.features.input.model.controlleremu.NumericSetting
import com.noryan.romcore.core.dolphin.jni.features.settings.model.AbstractFloatSetting
import com.noryan.romcore.core.dolphin.jni.features.settings.model.Settings

// Yes, floats are not the same thing as doubles... They're close enough, though
class InputMappingDoubleSetting(private val numericSetting: NumericSetting) : AbstractFloatSetting {
    override val float: Float
        get() = numericSetting.getDoubleValue().toFloat()

    override fun setFloat(settings: Settings, newValue: Float) =
        numericSetting.setDoubleValue(newValue.toDouble())

    override val isOverridden: Boolean = false

    override val isRuntimeEditable: Boolean = true

    override fun delete(settings: Settings): Boolean {
        numericSetting.setDoubleValue(numericSetting.getDoubleDefaultValue())
        return true
    }
}
