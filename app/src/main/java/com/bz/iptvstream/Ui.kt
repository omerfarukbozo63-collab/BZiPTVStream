package com.bz.iptvstream

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val bg = Color(0xFF0B0E14)
    val rail = Color(0xFF0F131C)
    val card = Color(0xFF151B28)
    val card2 = Color(0xFF1B2232)
    val line = Color(0xFF232C40)
    val text = Color(0xFFF2F5FA)
    val sub = Color(0xFFC9D2E3)
    val mute = Color(0xFF9AA6BD)
    val accent = Color(0xFFE9E32B)      // logodaki sarı
    val onAccent = Color(0xFF14130A)
    val teal = Color(0xFF1FB5A0)        // logodaki turkuaz
    val live = Color(0xFFFF4D5E)
    val onLive = Color(0xFF1A0509)
}

/** D-pad ile odaklanabilen, odakta beyaz çerçeve çizen kutu. */
@Composable
fun Fx(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    bg: Color = Color.Transparent,
    requester: FocusRequester? = null,
    onFocus: (Boolean) -> Unit = {},
    content: @Composable BoxScope.(Boolean) -> Unit
) {
    var f by remember { mutableStateOf(false) }
    var m = modifier.clip(shape).background(bg, shape)
    if (f) m = m.border(2.dp, Color.White, shape)
    if (requester != null) m = m.focusRequester(requester)
    Box(m.onFocusChanged { f = it.isFocused; onFocus(it.isFocused) }.clickable(onClick = onClick)) { content(f) }
}

@Composable
fun Pill(text: String, selected: Boolean = false, requester: FocusRequester? = null, onClick: () -> Unit) {
    Fx(onClick, shape = RoundedCornerShape(50), bg = if (selected) C.accent else C.card, requester = requester) {
        Text(
            text, color = if (selected) C.onAccent else C.sub, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun Btn(text: String, primary: Boolean = false, danger: Boolean = false, modifier: Modifier = Modifier,
        requester: FocusRequester? = null, onClick: () -> Unit) {
    val bg = if (primary) C.accent else if (danger) Color(0xFF2A1519) else C.card2
    val fg = if (primary) C.onAccent else if (danger) Color(0xFFFF8A94) else C.text
    Fx(onClick, modifier, RoundedCornerShape(12.dp), bg, requester) {
        Text(text, color = fg, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp))
    }
}
