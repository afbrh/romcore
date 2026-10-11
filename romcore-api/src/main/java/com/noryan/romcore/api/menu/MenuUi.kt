package com.noryan.romcore.api.menu

import android.text.format.DateFormat
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.itemsIndexed
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
    LaunchedEffect(model.cursor, rows.size) { listState.animateScrollToItem((model.cursor - 2).coerceAtLeast(0)) }
    Row(Modifier.fillMaxHeight().width(MenuStyle.PanelWidth).background(MenuStyle.Background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = MenuStyle.PanelPaddingHorizontal, vertical = MenuStyle.PanelPaddingVertical),
        ) {
            itemsIndexed(rows) { i, row ->
                @Suppress("UNUSED_EXPRESSION") model.revision
                RowItem(model, row, i == model.cursor, Modifier.clickable { model.select(i) })
            }
            if (model.status.isNotEmpty() || model.stateUnavailableReason != null) item {
                BasicText(model.status.ifEmpty { model.stateUnavailableReason.orEmpty() }, Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    style = TextStyle(color = Dim, fontFamily = Silkscreen, fontSize = 11.sp))
            }
        }
    }
}

@Composable
private fun RowItem(model: MenuModel, row: MenuRowModel, focused: Boolean, click: Modifier) {
    val bg = if (focused) Modifier.background(Focus) else Modifier
    when (row) {
        is MenuRowModel.Top -> {
            val open = model.expanded == row.action
            Row(click.then(bg).fillMaxWidth().padding(MenuStyle.RowPaddingHorizontal, MenuStyle.RowPaddingVertical),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                BasicText(if (row.action == MenuAction.CHANGE_CONTROLLER_MAPPING) "Controller Mapping" else MenuModel.title(row.action),
                    Modifier.weight(1f), style = style(if (model.isDisabled(row.action)) Dim else if (row.action == MenuAction.CLOSE_GAME) MenuStyle.Danger else Color.White), maxLines = 2)
                if (model.isDisabled(row.action)) BasicText("N/A", style = style(Dim))
                else if (row.action != MenuAction.CLOSE_GAME || open) BasicText(if (open) "v" else ">", style = style(Dim))
            }
        }
        is MenuRowModel.Confirm -> Sub(click, bg, if (row.yes) "Yes, close game" else "Cancel", "", if (row.yes) MenuStyle.Danger else Color.White)
        is MenuRowModel.Slot -> SlotRow(model, row.slot, click, bg)
        is MenuRowModel.Bind -> {
            val cap = model.capturingPad == row.pad
            Sub(click, bg, model.padLabel(row.pad), if (cap) "PRESS..." else model.mapping.describe(row.pad), Color.White)
        }
        is MenuRowModel.Hot -> {
            val cap = model.capturingHotkey == row.action
            Sub(click, bg, model.hotkeys.title(row.action) ?: MenuModel.title(row.action).replace("Change Controller Mapping", "Mapping"),
                if (cap) "HOLD..." else model.hotkeys.describe(row.action), Color.White)
        }
        MenuRowModel.Reset -> Sub(click, bg, "Reset defaults", "", MenuStyle.Danger)
        MenuRowModel.ClearHotkey -> Sub(click, bg, "Clear", "", Color.White)
    }
}

@Composable
private fun Sub(click: Modifier, bg: Modifier, label: String, value: String, color: Color) {
    Row(click.then(bg).fillMaxWidth().padding(start = 28.dp, end = MenuStyle.RowPaddingHorizontal, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        BasicText(label, Modifier.weight(1f), style = style(color, 12.sp), maxLines = 1)
        if (value.isNotEmpty()) BasicText(value, style = style(Dim, 10.sp), maxLines = 1)
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

private fun style(color: Color, size: androidx.compose.ui.unit.TextUnit = MenuStyle.FontSize) =
    TextStyle(color = color, fontFamily = Silkscreen, fontSize = size)
