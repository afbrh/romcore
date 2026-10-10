package com.noryan.romcore.api.menu

// RomRunner menu style reference — DO NOT EDIT A COPY DIRECTLY.
// See MenuStyle.kt in this same package (or GameShelf/menu-style-reference/STYLE_SPEC.md) for how
// to change this for every Compose-based embedded core at once.
//
// These composables own ONLY visual rendering (layout, spacing, colors, font) — no click/focus/
// key handling of their own. Each core's own existing input-handling modifier chain (e.g.
// ARMSX2's controllerFocusable, WatermelonDS's FocusRequester, Cemu's FocusRequester + key remap)
// is passed in via [modifier] and applied OUTERMOST, before this file's own layout/padding
// modifiers, so a caller's clickable/focusable region always wraps the whole row.
//
// Text rendering uses foundation's BasicText, not a Material Text composable, on purpose: the 3
// Compose-based cores don't all have the same Material version available (WatermelonDS only pulls
// in the material3 *adaptive* artifact, not full material3 components) — BasicText lives in
// androidx.compose.foundation, a dependency every Compose app already has, so this file compiles
// unmodified against any of them.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

/** The in-game menu panel: fixed width, black background, top-anchored (Compose's own
 *  Arrangement.Top/Alignment.Start defaults — no override here on purpose), scrollable. */
@Composable
fun MenuPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(MenuStyle.PanelWidth)
            .background(MenuStyle.Background)
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = MenuStyle.PanelPaddingHorizontal,
                vertical = MenuStyle.PanelPaddingVertical,
            ),
        content = content,
    )
}

/** A single plain menu row (e.g. "Save State", "Exit"). [modifier] carries the caller's own
 *  click/focus/selected-background handling. */
@Composable
fun MenuRow(
    label: String,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
) {
    BasicText(
        text = label,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = MenuStyle.RowPaddingHorizontal,
                vertical = MenuStyle.RowPaddingVertical,
            ),
        style = TextStyle(
            color = if (danger) MenuStyle.Danger else Color.White,
            fontFamily = fontFamily,
            fontSize = MenuStyle.FontSize,
        ),
        maxLines = 1,
    )
}

/** A menu row with an ON/OFF value on the trailing edge (e.g. "Show FPS"). [modifier] carries the
 *  caller's own click/focus/selected-background handling. */
@Composable
fun MenuToggleRow(
    label: String,
    checked: Boolean,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = MenuStyle.RowPaddingHorizontal,
                vertical = MenuStyle.RowPaddingVertical,
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        BasicText(
            text = label,
            style = TextStyle(
                color = Color.White,
                fontFamily = fontFamily,
                fontSize = MenuStyle.FontSize,
            ),
            maxLines = 1,
        )
        BasicText(
            text = if (checked) "ON" else "OFF",
            style = TextStyle(
                color = if (checked) Color.White else Color.White.copy(alpha = 0.5f),
                fontFamily = fontFamily,
                fontSize = MenuStyle.FontSize,
            ),
        )
    }
}
