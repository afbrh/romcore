package com.noryan.romcore.api.menu

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noryan.romcore.api.MenuAction
import com.noryan.romcore.api.R
import java.util.Date

private val Silkscreen = FontFamily(Font(R.font.romcore_silkscreen))
private val Focus = Color(0xFF2A2A2A)
private val Dim = Color.White.copy(alpha = 0.5f)

/**
 * Pure Compose menu. Shows nothing while [MenuModel.isOpen] is false. Input comes from the model (host feeds keys);
 * rows are also touch-clickable.
 */
@Composable
fun RomCoreMenuContent(model: MenuModel, modifier: Modifier = Modifier) {
    if (!model.isOpen) return
    Box(modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(enabled = false) {}) {
        MenuPanelContent(model)
    }
}

@Composable
private fun MenuPanelContent(model: MenuModel) {
    val rows = model.rows
    val listState = rememberLazyListState()
    val order = model.displayOrder
    LaunchedEffect(model.cursor, rows.size) { listState.animateScrollToItem((order.indexOf(if (model.cursor < 2) 0 else model.cursor).coerceAtLeast(0) - 2).coerceAtLeast(0)) }
    // Composition restarts on every open (RomCoreMenuContent returns early while closed), so the slide plays each time.
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    // Comes out of the bottom-right corner (slides in from the right edge); Close Game on top, Save / Load State at the bottom.
    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
        AnimatedVisibility(shown, enter = slideInHorizontally(tween(180)) { it } + fadeIn(tween(120)), exit = fadeOut(tween(80))) {
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = MenuStyle.SheetMaxWidth).fillMaxWidth().heightIn(max = maxHeight)
                    .clip(RoundedCornerShape(16.dp)).background(MenuStyle.Background)
                    .border(BorderStroke(2.dp, Plate), RoundedCornerShape(16.dp)),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Row 1 (Load State) is drawn on row 0's line, so it is not in the display order.
                items(order) { i ->
                    val row = rows[i]
                    @Suppress("UNUSED_EXPRESSION") model.revision
                    val click = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { model.select(i) }
                    when {
                        i == 0 && rows.size > 1 && row is MenuRowModel.Top && rows[1] is MenuRowModel.Top -> {
                            // Load State and Save State share one line (their own rows 0 and 1), half the width each.
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f)) { RowItem(model, row, model.cursor == 0, click) }
                                Box(Modifier.weight(1f)) { RowItem(model, rows[1], model.cursor == 1,
                                    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { model.select(1) }) }
                            }
                        }
                        else -> RowItem(model, row, i == model.cursor, click)
                    }
                }
                if (model.status.isNotEmpty() || model.stateUnavailableReason != null) item {
                    BasicText(model.status.ifEmpty { model.stateUnavailableReason.orEmpty() }, Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                        style = TextStyle(color = Dim, fontFamily = Silkscreen, fontSize = 11.sp))
                }
            }
        }
    }
}

private fun style(color: Color, size: androidx.compose.ui.unit.TextUnit = MenuStyle.FontSize) =
    TextStyle(color = color, fontFamily = Silkscreen, fontSize = size)

/** Home-screen "Games" button look: dark plate + grey border; focused = orange plate, dark label. */
private fun Modifier.button(focused: Boolean, indent: Boolean = false, on: Boolean = false): Modifier {
    val shape = RoundedCornerShape(8.dp)
    return this.padding(start = if (indent) 12.dp else 0.dp).fillMaxWidth().clip(shape)
        .background(if (focused) Accent else Plate0)
        .border(BorderStroke(if (focused) 3.dp else 2.dp, if (focused) Color.White else if (on) Accent else Plate), shape)
}
private val Accent = Color(0xFFFF6B4A)
private val Plate0 = Color(0xFF0B0B0E)
private val Plate = Color(0xFF2A2A31)
private val OnAccent = Color(0xFF14080A)

@Composable
private fun RowItem(model: MenuModel, row: MenuRowModel, focused: Boolean, click: Modifier) {
    when (row) {
        is MenuRowModel.Top -> {
            val open = model.expanded == row.action
            val disabled = model.isDisabled(row.action)
            val ffOn = row.action == MenuAction.FAST_FORWARD && model.isFastForwarding()
            val label = if (focused) OnAccent else if (disabled) Dim else if (row.action == MenuAction.CLOSE_GAME) MenuStyle.Danger else if (ffOn) Accent else Color(0xFFE6E6EA)
            val pair = row.action == MenuAction.LOAD_STATE || row.action == MenuAction.SAVE_STATE
            Box(click.button(focused, on = ffOn).padding(if (pair) 6.dp else MenuStyle.RowPaddingHorizontal, 12.dp)) {
                BasicText(when (row.action) {
                    MenuAction.CHANGE_CONTROLLER_MAPPING -> "Controller Mapping"
                    MenuAction.FAST_FORWARD -> "Fast Forward"
                    MenuAction.SAVE_STATE -> "Save State"
                    MenuAction.LOAD_STATE -> "Load State"
                    else -> MenuModel.title(row.action) },
                    Modifier.align(if (pair) Alignment.Center else Alignment.CenterStart), style = style(label, if (pair) 13.sp else MenuStyle.FontSize).copy(textAlign = if (pair) TextAlign.Center else TextAlign.Start, textDecoration = if (focused) TextDecoration.Underline else TextDecoration.None), maxLines = 2)
                // The half-width Save / Load buttons have no room for a tail: when disabled they are just dimmed, and the note under the
                // menu says why (an "N/A" there was drawn on top of the label).
                val tail = if (pair) ""                                                                           // plain actions, no arrow
                    else if (disabled) "N/A"
                    else if (row.action == MenuAction.FAST_FORWARD) ""
                    else if (row.action != MenuAction.CLOSE_GAME || open) (if (open) "v" else ">") else ""
                if (tail.isNotEmpty()) BasicText(tail, Modifier.align(Alignment.CenterEnd), style = style(if (focused) OnAccent else Dim))
            }
        }
        is MenuRowModel.Confirm -> Sub(click, focused, if (row.yes) "Yes, close game" else "Cancel", "", if (row.yes) MenuStyle.Danger else Color(0xFFE6E6EA))
        is MenuRowModel.Slot -> SlotRow(model, row.slot, click, if (focused) Modifier.background(Accent) else Modifier)
        is MenuRowModel.Bind -> {
            val cap = model.capturingPad == row.pad
            Sub(click, focused, model.padLabel(row.pad), if (cap) "PRESS..." else model.mapping.describe(row.pad), Color(0xFFE6E6EA))
        }
        is MenuRowModel.Hot -> {
            val cap = model.capturingHotkey == row.action
            Sub(click, focused, model.hotkeys.title(row.action) ?: MenuModel.title(row.action).replace("Change Controller Mapping", "Mapping"),
                if (cap) "HOLD..." else model.hotkeys.describe(row.action), Color(0xFFE6E6EA))
        }
        MenuRowModel.Undo -> Box(click.button(focused).padding(MenuStyle.RowPaddingHorizontal, 12.dp)) {
            BasicText("Undo Save State", Modifier.align(Alignment.CenterStart),
                style = style(if (focused) OnAccent else Color(0xFFE6E6EA)).copy(textAlign = TextAlign.Start, textDecoration = if (focused) TextDecoration.Underline else TextDecoration.None), maxLines = 2)
        }
        MenuRowModel.Option -> {
            @Suppress("UNUSED_EXPRESSION") model.revision
            Box(click.button(focused).padding(MenuStyle.RowPaddingHorizontal, 12.dp)) {
                BasicText(model.optionText.orEmpty(), Modifier.align(Alignment.CenterStart),
                    style = style(if (focused) OnAccent else Color(0xFFE6E6EA)).copy(textAlign = TextAlign.Start, textDecoration = if (focused) TextDecoration.Underline else TextDecoration.None), maxLines = 2)
            }
        }
        MenuRowModel.Toggle -> {
            @Suppress("UNUSED_EXPRESSION") model.revision
            Box(click.button(focused).padding(MenuStyle.RowPaddingHorizontal, 12.dp)) {
                BasicText(model.toggleText.orEmpty(), Modifier.align(Alignment.CenterStart),
                    style = style(if (focused) OnAccent else Color(0xFFE6E6EA)).copy(textAlign = TextAlign.Start, textDecoration = if (focused) TextDecoration.Underline else TextDecoration.None), maxLines = 2)
            }
        }
        MenuRowModel.Reset -> Sub(click, focused, "Reset defaults", "", MenuStyle.Danger)
        MenuRowModel.ClearHotkey -> Sub(click, focused, "Clear", "", Color(0xFFE6E6EA))
    }
}

@Composable
private fun Sub(click: Modifier, focused: Boolean, label: String, value: String, color: Color) {
    Row(click.button(focused, indent = true).padding(horizontal = MenuStyle.RowPaddingHorizontal, vertical = 9.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, style = style(if (focused) OnAccent else color, 12.sp).copy(textAlign = TextAlign.Center, textDecoration = if (focused) TextDecoration.Underline else TextDecoration.None), maxLines = 1)
        if (value.isNotEmpty()) { Box(Modifier.width(10.dp)); BasicText(value, style = style(if (focused) OnAccent else Dim, 10.sp), maxLines = 1) }
    }
}

@Composable
private fun SlotRow(model: MenuModel, slot: Int, click: Modifier, bg: Modifier) {
    val ctx = LocalContext.current
    val info = (model.session as? StateSlotProvider)?.slotInfo(slot)
    val stamp = if (info != null && info.exists) DateFormat.format("MM/dd HH:mm", Date(info.timestampMs)).toString()
        else if (info == null) "" else "EMPTY"
    Row(click.then(bg).fillMaxWidth().padding(start = 28.dp, end = MenuStyle.RowPaddingHorizontal, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val thumb = info?.thumbnail
        if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.width(48.dp).height(32.dp), contentScale = ContentScale.Crop)
        else Box(Modifier.width(48.dp).height(32.dp).background(Color.White.copy(alpha = 0.08f)))
        Box(Modifier.width(10.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
            BasicText("Slot $slot", style = style(Color.White, 12.sp))
        }
        BasicText(stamp, style = style(Dim, 9.sp), maxLines = 1)
    }
}

