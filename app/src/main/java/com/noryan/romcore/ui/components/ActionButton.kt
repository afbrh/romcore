package com.noryan.romcore.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A call-to-action that reads as a button: solid orange plate with dark text, a border, and a "PRESS A" hint.
 * Focused = brighter fill, white border and a slight grow, so the controller focus is obvious too.
 */
@Composable@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

fun ActionButton(text: String, modifier: Modifier = Modifier, hint: String? = "PRESS A", onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val plate = if (focused) Color(0xFFFF8A6B) else Color(0xFFFF6B4A)
    val border = if (focused) Color.White else Color(0xFF8A3A28)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = modifier
                .scale(if (focused) 1.04f else 1f)
                .clip(RoundedCornerShape(8.dp))
                .background(plate)
                .border(BorderStroke(3.dp, border), RoundedCornerShape(8.dp))
                .combinedClickable(interactionSource = src, indication = null, onClick = onClick)
                .padding(horizontal = 28.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.headlineSmall, color = Color(0xFF14080A), textAlign = TextAlign.Center, maxLines = 1)
        }
        if (hint != null) {
            Spacer(Modifier.height(8.dp))
            Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun tabPlate(selected: Boolean) = if (selected) Color(0xFFFF6B4A) else Color(0xFF0B0B0E)
private fun tabBorder(selected: Boolean, focused: Boolean) = when {
    focused && selected -> Color.White
    focused -> Color(0xFFFF6B4A)
    selected -> Color(0xFF8A3A28)
    else -> Color(0xFF2A2A31)
}

/** X / Y / Menu (key up) on the focused element, or a long press: the controller's "options" gesture. */
private fun Modifier.altKeys(onAlt: (() -> Unit)?): Modifier = if (onAlt == null) this else onKeyEvent { e ->
    if (e.type == KeyEventType.KeyUp && (e.key == Key.ButtonX || e.key == Key.ButtonY || e.key == Key.Menu)) { onAlt(); true } else false
}

/**
 * Top-bar tab as a button: the selected tab is a solid orange plate, the others are mostly-black plates with a
 * faint border. Controller focus shows as a brighter border (white on the selected tab, orange on the others).
 * [onAlt] (X / Menu / long press) opens the tab's inline options.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TabButton(text: String, selected: Boolean, modifier: Modifier = Modifier, onAlt: (() -> Unit)? = null, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val label = if (selected) Color(0xFF14080A) else Color(0xFFE6E6EA)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(tabPlate(selected))
            .border(BorderStroke(if (focused) 3.dp else 2.dp, tabBorder(selected, focused)), RoundedCornerShape(8.dp))
            .altKeys(onAlt)
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onAlt)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = label, textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
    }
}

/** Pixel art for the small header buttons: '#' = lit cell. Drawn as crisp squares (no antialiased curves, no assets). */
object PixelIcons {
    val GEAR = listOf(
        "....###....",
        ".##.###.##.",
        ".#########.",
        "..#######..",
        "#####.#####",
        "####...####",
        "#####.#####",
        "..#######..",
        ".#########.",
        ".##.###.##.",
        "....###....",
    )
    val PLUS = listOf(
        "...###...", "...###...", "...###...",
        "#########", "#########", "#########",
        "...###...", "...###...", "...###...",
)
}

/**
 * A pixel icon with no plate or border (the settings gear). The glyph is drawn at the tab-label font size so it reads as
 * part of the text line; a 40 dp touch target is kept. Off-white when idle, orange on controller focus or while [selected].
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PixelIconButton(icon: List<String>, selected: Boolean, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val ink = if (selected || focused) Color(0xFFFF6B4A) else Color(0xFFE6E6EA)
    val glyph = with(androidx.compose.ui.platform.LocalDensity.current) { MaterialTheme.typography.titleMedium.fontSize.toDp() }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
            .semantics { contentDescription = description; role = Role.Button }
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(glyph)) {
            val n = icon.size
            val cell = kotlin.math.floor(this.size.minDimension / n).coerceAtLeast(1f)
            val ox = kotlin.math.floor((this.size.width - cell * n) / 2f); val oy = kotlin.math.floor((this.size.height - cell * n) / 2f)
            icon.forEachIndexed { y, row -> row.forEachIndexed { x, c ->
                if (c == '#') drawRect(ink, Offset(ox + x * cell, oy + y * cell), Size(cell, cell))
            } }
        }
    }
}

/**
 * A plain text control with no plate or border (the "+" next to the tabs). Normal tab-label text size; the text turns
 * orange on controller focus (or while [selected]) so focus stays visible without a border. Keeps a 40 dp touch target.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlainTextButton(text: String, selected: Boolean, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val ink = if (selected || focused) Color(0xFFFF6B4A) else Color(0xFFE6E6EA)
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 40.dp, minHeight = 40.dp)
            .semantics { contentDescription = description; role = Role.Button }
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = ink, maxLines = 1, softWrap = false)
    }
}
