// SPDX-License-Identifier: GPL-2.0-or-later

package com.noryan.romcore.core.dolphin.jni.features.input.model.controlleremu

import androidx.annotation.Keep

/**
 * Represents a C++ ControllerEmu::Control.
 *
 * The lifetime of this class is managed by C++ code. Calling methods on it after it's destroyed
 * in C++ is undefined behavior!
 */
@Keep
class Control private constructor(private val pointer: Long) {
    external fun getUiName(): String

    external fun getControlReference(): ControlReference
}
