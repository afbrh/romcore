package com.noryan.romcore.core.retroarch

/**
 * Game Boy picture placement: the 160x144 screen is scaled (not integer scaled) to fill the screen height at its own 10:9 shape,
 * centred across the width. Super Game Boy games arrive from mGBA as a 256x224 frame with a border; the DMG shader crops that
 * frame to the 160x144 screen, so the viewport is forced to 10:9 here instead of following the frame's own 8:7.
 * (mGBA's own "SGB borders" / model core options had no effect through the RetroArch host, so the crop is done in the shader.)
 */
internal object GbViewport {
    /** RetroArch's ASPECT_RATIO_CONFIG (gfx/video_defines.h): the aspect comes from `video_aspect_ratio`. */
    const val ASPECT_CONFIG = 20
    const val ASPECT = 160f / 144f

    val configKeys: Map<String, String> = linkedMapOf(
        "aspect_ratio_index" to ASPECT_CONFIG.toString(),
        "video_aspect_ratio" to ASPECT.toString(),
        "video_aspect_ratio_auto" to "false",
        "video_scale_integer" to "false",
        "video_viewport_bias_x" to "0.5",
        "video_viewport_bias_y" to "0.5",
        "video_viewport_bias_portrait_x" to "0.5",
        "video_viewport_bias_portrait_y" to "0.5",
    )
}
