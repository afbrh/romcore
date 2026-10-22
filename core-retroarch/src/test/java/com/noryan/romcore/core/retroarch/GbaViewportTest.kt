package com.noryan.romcore.core.retroarch

import org.junit.Assert.*
import org.junit.Test

class GbaViewportTest {
    private val k = GbaViewport.configKeys

    @Test fun notIntegerScaledAndKeepsTheCoreShape() {
        assertEquals("false", k["video_scale_integer"])
        assertEquals("22", k["aspect_ratio_index"])            // ASPECT_RATIO_CORE, not the custom viewport (23)
        assertFalse(k.keys.any { it.startsWith("custom_viewport") })
    }

    @Test fun pinnedToTheTopAndCentredAcross() {
        assertEquals("0.0", k["video_viewport_bias_y"]); assertEquals("0.5", k["video_viewport_bias_x"])
        assertEquals("0.0", k["video_viewport_bias_portrait_y"])
    }
}
