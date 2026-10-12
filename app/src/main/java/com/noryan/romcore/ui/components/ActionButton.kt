package com.noryan.romcore.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
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
