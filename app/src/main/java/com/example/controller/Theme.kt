package com.example.controller

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Instrument-panel palette: graphite chassis, recessed wells, Xbox green as the only
// system accent. Face-button colours are functional (they are how you find a button
// without looking), so they stay canonical.
val Chassis = Color(0xFF06070A)
val Panel = Color(0xFF13171E)
val Recess = Color(0xFF0A0C10)
val Edge = Color(0xFF262C36)
val EdgeLit = Color(0xFF3E4854)
val Ink = Color(0xFFE8ECF2)
val Muted = Color(0xFF6B7686)

val XGreen = Color(0xFF6CC24A)
val XGreenDim = Color(0xFF2A4A22)

val BtnA = Color(0xFF6CC24A)
val BtnB = Color(0xFFD33A2C)
val BtnX = Color(0xFF2C6FD1)
val BtnY = Color(0xFFF2B01E)

val Mono = FontFamily.Monospace

/** Sizes derived from the actual screen so the default layout never overlaps. */
data class Metrics(
    val stick: Dp,
    val cluster: Dp,
    val dpad: Dp,
    val bumperW: Dp,
    val bumperH: Dp,
    val gap: Dp,
    val margin: Dp,
) {
    companion object {
        fun of(w: Dp, h: Dp): Metrics {
            val hp = h.value
            return Metrics(
                stick = (hp * 0.34f).coerceIn(104f, 168f).dp,
                cluster = (hp * 0.36f).coerceIn(108f, 176f).dp,
                dpad = (hp * 0.30f).coerceIn(96f, 148f).dp,
                bumperW = (w.value * 0.11f).coerceIn(72f, 116f).dp,
                bumperH = (hp * 0.105f).coerceIn(36f, 50f).dp,
                gap = (hp * 0.028f).coerceIn(6f, 14f).dp,
                margin = (hp * 0.035f).coerceIn(10f, 20f).dp,
            )
        }
    }
}
