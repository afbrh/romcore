// SPDX-License-Identifier: GPL-2.0-or-later

package com.noryan.romcore.core.dolphin.jni.features.input.model

object MappingCommon {
    external fun getExpressionForControl(
        control: String,
        device: String,
        defaultDevice: String
    ): String

    external fun save()
}
