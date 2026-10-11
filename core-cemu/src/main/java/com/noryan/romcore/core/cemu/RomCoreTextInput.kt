package com.noryan.romcore.core.cemu

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noryan.romcore.api.R as ApiR
import info.cemu.cemu.nativeinterface.NativeSwkbd

private val Silk = FontFamily(Font(ApiR.font.romcore_silkscreen))
private val Orange = Color(0xFFFF6B4A)

/** RomCore-styled replacement for Cemu's Material text-input dialog (Wii U software keyboard requests). */
@Composable
fun RomCoreTextInput() {
    val st by NativeSwkbd.SwkbdState.data.collectAsState()
    val s = st ?: return
    val focus = remember { FocusRequester() }
    fun done() { if (s.text.isNotEmpty()) NativeSwkbd.onFinishedInputEdit() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(enabled = false) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(0.6f).background(Color.Black).border(2.dp, Orange).padding(16.dp)) {
            Text("INPUT TEXT", Orange, 14)
            BasicTextField(
                value = s.text,
                onValueChange = { NativeSwkbd.SwkbdState.updateText(it) },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontFamily = Silk, fontSize = 16.sp),
                cursorBrush = SolidColor(Orange),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { done() }),
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp).focusRequester(focus),
            )
            if (s.maxLength > 0) Text("${s.text.length} / ${s.maxLength}", Color(0xFF8A8A8A), 11)
            Box(Modifier.align(Alignment.End).clickable(enabled = s.text.isNotEmpty()) { done() }.padding(8.dp)) {
                Text("DONE", if (s.text.isNotEmpty()) Orange else Color(0xFF555555), 14)
            }
        }
    }
}

@Composable
private fun Text(t: String, c: Color, sp: Int) =
    androidx.compose.foundation.text.BasicText(t, style = TextStyle(color = c, fontFamily = Silk, fontSize = sp.sp))
