package com.noryan.romcore.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noryan.romcore.ui.theme.HighlightColor

/**
 * One dense, focusable text line (RomRunner's list style): label left, optional value right. While focused the text
 * turns brand-orange and blooms. [onAlt] fires on long-press and on the controller's X / Y buttons (opens the
 * inline fold-down options — no popups anywhere).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FocusLine(
    text: String,
    modifier: Modifier = Modifier,
    right: String? = null,
    indent: Dp = 0.dp,
    marked: Boolean = false,
    dim: Boolean = false,
    center: Boolean = false,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    onAlt: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val glow = if (focused) Shadow(HighlightColor, blurRadius = with(LocalDensity.current) { 24.dp.toPx() }) else null
    val base = if (dim) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurface
    val sub = if (dim) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.onSurfaceVariant
    val c1: Color = if (focused) HighlightColor else if (marked) MaterialTheme.colorScheme.secondary else base
    val c2: Color = if (focused) HighlightColor else sub
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onKeyEvent { e ->
                if (onAlt != null && e.type == KeyEventType.KeyUp &&
                    (e.key == Key.ButtonX || e.key == Key.ButtonY || e.key == Key.Menu)) { onAlt(); true } else false
            }
            .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onAlt)
            .padding(start = 16.dp + indent, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (center) Arrangement.Center else Arrangement.Start
    ) {
        Text(text, style = style.copy(shadow = glow), color = c1, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = if (center) TextAlign.Center else TextAlign.Start, modifier = if (center) Modifier else Modifier.weight(1f))
        if (right != null) {
            Spacer(Modifier.width(12.dp))
            Text(right, style = style.copy(shadow = glow), color = c2, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
        }
    }
}
