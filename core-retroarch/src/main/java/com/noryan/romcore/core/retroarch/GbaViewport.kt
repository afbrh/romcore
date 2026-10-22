package com.noryan.romcore.core.retroarch

/**
 * Game Boy Advance picture placement: scaled to fill the screen at the game's own 3:2 shape (NOT integer scaled: the picture takes
 * the full width or height, whichever limits it) and pinned to the top edge. RetroArch places content that is smaller than its
 * viewport by the "viewport bias" (0.0 = top/left, 0.5 = centre, 1.0 = bottom/right), so only the vertical bias moves.
 * On the 1280x960 Nova that is 1280x853 at y=0.
 *
 * (An earlier version forced a whole-number scale with a custom viewport; it looked worse and was removed on request.)
 */
internal object GbaViewport {
    /** RetroArch's ASPECT_RATIO_CORE (gfx/video_defines.h): the picture keeps the core's own shape. */
    const val ASPECT_CORE = 22

    val configKeys: Map<String, String> = linkedMapOf(
        "aspect_ratio_index" to ASPECT_CORE.toString(),
        "video_scale_integer" to "false",
        "video_viewport_bias_x" to "0.5",              // centred across the screen
        "video_viewport_bias_y" to "0.0",              // pinned to the top edge
        "video_viewport_bias_portrait_x" to "0.5",
        "video_viewport_bias_portrait_y" to "0.0",
    )
}
