package com.example.controller

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reports press/release once per transition and fires a haptic tick on press.
 *
 * `enabled = false` detaches the gesture entirely rather than just ignoring input —
 * this control's own live-play touch handling must not compete with the layout
 * editor's select/drag handler on the Box wrapping it in edit mode.
 */
@Composable
private fun Modifier.pressable(enabled: Boolean = true, onChange: (Boolean) -> Unit): Modifier {
    if (!enabled) return this
    val haptic = LocalHapticFeedback.current
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            var down = false
            while (true) {
                val pressed = awaitPointerEvent().changes.any { it.pressed }
                if (pressed != down) {
                    down = pressed
                    if (pressed) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onChange(pressed)
                }
            }
        }
    }
}

// ── Analog stick ─────────────────────────────────────────────────────────────
// Signature element: an arc on the rim traces the stick's angle, so direction is
// readable in peripheral vision while the eyes stay on the monitor.
@Composable
fun AnalogStick(label: String, dim: Dp, enabled: Boolean = true, onMove: (Float, Float) -> Unit) {
    var offset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val travel = with(density) { (dim * 0.26f).toPx() }

    // enabled = false while the layout editor is open: this stick's own drag handling
    // would otherwise consume every move and starve the editor's reposition-drag.
    LaunchedEffect(enabled) { if (!enabled) { offset = Offset.Zero; onMove(0f, 0f) } }

    Box(
        modifier = Modifier
            .size(dim)
            .then(
                if (enabled) Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val mid = Offset(size.width / 2f, size.height / 2f)

                        fun apply(p: Offset) {
                            val d = p - mid
                            val dist = d.getDistance()
                            val clamped = if (dist <= travel) d else d * (travel / dist)
                            offset = clamped
                            onMove(clamped.x / travel, clamped.y / travel)
                        }

                        apply(down.position)
                        drag(down.id) { change ->
                            change.consume()
                            apply(change.position)
                        }
                        offset = Offset.Zero
                        onMove(0f, 0f)
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(dim)) {
            val c = center
            val r = size.minDimension / 2f
            val ring = 2.dp.toPx()

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Recess, Chassis),
                    center = c,
                    radius = r
                ),
                radius = r
            )
            drawCircle(color = Edge, radius = r - ring / 2, style = Stroke(width = ring))

            // Cardinal ticks — the well reads as machined, and gives a neutral reference.
            val tickR = r - ring * 3
            for (i in 0..3) {
                val a = Math.toRadians(i * 90.0)
                val dx = kotlin.math.cos(a).toFloat()
                val dy = kotlin.math.sin(a).toFloat()
                drawLine(
                    color = Edge,
                    start = Offset(c.x + dx * (tickR - r * 0.10f), c.y + dy * (tickR - r * 0.10f)),
                    end = Offset(c.x + dx * tickR, c.y + dy * tickR),
                    strokeWidth = ring
                )
            }

            val mag = (offset.getDistance() / travel).coerceIn(0f, 1f)
            if (mag > 0.06f) {
                val deg = Math.toDegrees(atan2(offset.y.toDouble(), offset.x.toDouble())).toFloat()
                val rr = r - ring / 2
                drawArc(
                    color = XGreen.copy(alpha = 0.25f + 0.75f * mag),
                    startAngle = deg - 24f,
                    sweepAngle = 48f,
                    useCenter = false,
                    topLeft = Offset(c.x - rr, c.y - rr),
                    size = Size(rr * 2, rr * 2),
                    style = Stroke(width = ring * 1.8f, cap = StrokeCap.Round)
                )
            }
        }

        Text(
            text = label,
            color = Muted.copy(alpha = 0.5f),
            fontSize = (dim.value * 0.10f).sp,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.offset { IntOffset(0, (-dim.value * 0.30f * density.density).roundToInt()) }
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                .size(dim * 0.46f)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(Color(0xFF39414D), Color(0xFF10141A)),
                        center = Offset(0.35f, 0.30f)
                    )
                )
        )
    }
}

// ── D-pad ────────────────────────────────────────────────────────────────────
// Xbox 360 disc: a round plate with the cross raised out of it.
@Composable
fun DPad(dim: Dp, enabled: Boolean = true, onPress: (Int, Boolean) -> Unit) {
    val up = remember { mutableStateOf(false) }
    val down = remember { mutableStateOf(false) }
    val left = remember { mutableStateOf(false) }
    val right = remember { mutableStateOf(false) }

    Box(modifier = Modifier.size(dim), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(dim)) {
            val s = size.minDimension
            val c = center
            val arm = s / 3.05f
            val corner = androidx.compose.ui.geometry.CornerRadius(arm * 0.22f)

            drawCircle(
                brush = Brush.radialGradient(listOf(Panel, Recess), center = c, radius = s / 2),
                radius = s / 2
            )
            drawCircle(color = Edge, radius = s / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))

            fun plate(color: Color) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(c.x - arm / 2, c.y - arm * 1.5f),
                    size = Size(arm, arm * 3),
                    cornerRadius = corner
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(c.x - arm * 1.5f, c.y - arm / 2),
                    size = Size(arm * 3, arm),
                    cornerRadius = corner
                )
            }
            plate(Color(0xFF1B212A))

            // Lit segment per pressed direction.
            fun lit(dx: Float, dy: Float) {
                drawRoundRect(
                    color = XGreen.copy(alpha = 0.85f),
                    topLeft = Offset(
                        c.x - arm / 2 + dx * arm,
                        c.y - arm / 2 + dy * arm
                    ),
                    size = Size(arm, arm),
                    cornerRadius = corner
                )
            }
            if (up.value) lit(0f, -1.15f)
            if (down.value) lit(0f, 1.15f)
            if (left.value) lit(-1.15f, 0f)
            if (right.value) lit(1.15f, 0f)

            // Arrow glyphs.
            val g = arm * 0.22f
            fun arrow(dx: Float, dy: Float, lit: Boolean) {
                val ox = c.x + dx * arm * 1.15f
                val oy = c.y + dy * arm * 1.15f
                val col = if (lit) Chassis else Muted.copy(alpha = 0.65f)
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(ox + dx * g, oy + dy * g)
                    lineTo(ox - dy * g - dx * g * 0.4f, oy + dx * g - dy * g * 0.4f)
                    lineTo(ox + dy * g - dx * g * 0.4f, oy - dx * g - dy * g * 0.4f)
                    close()
                }
                drawPath(p, col)
            }
            arrow(0f, -1f, up.value)
            arrow(0f, 1f, down.value)
            arrow(-1f, 0f, left.value)
            arrow(1f, 0f, right.value)
        }

        val armDp = dim / 3.05f
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(armDp)
                .height(armDp * 1.5f)
                .pressable(enabled) { up.value = it; onPress(BTN_DPAD_UP, it) })
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .width(armDp)
                .height(armDp * 1.5f)
                .pressable(enabled) { down.value = it; onPress(BTN_DPAD_DOWN, it) })
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(armDp * 1.5f)
                .height(armDp)
                .pressable(enabled) { left.value = it; onPress(BTN_DPAD_LEFT, it) })
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(armDp * 1.5f)
                .height(armDp)
                .pressable(enabled) { right.value = it; onPress(BTN_DPAD_RIGHT, it) })
    }
}

// ── Face buttons ─────────────────────────────────────────────────────────────
// Xbox 360 arrangement and colours: Y top, X left, B right, A bottom.
@Composable
fun FaceCluster(dim: Dp, enabled: Boolean = true, onPress: (Int, Boolean) -> Unit) {
    val d = dim * 0.40f
    Box(modifier = Modifier.size(dim)) {
        FaceButton("Y", BtnY, d, enabled, Modifier.align(Alignment.TopCenter)) { onPress(BTN_Y, it) }
        FaceButton("X", BtnX, d, enabled, Modifier.align(Alignment.CenterStart)) { onPress(BTN_X, it) }
        FaceButton("B", BtnB, d, enabled, Modifier.align(Alignment.CenterEnd)) { onPress(BTN_B, it) }
        FaceButton("A", BtnA, d, enabled, Modifier.align(Alignment.BottomCenter)) { onPress(BTN_A, it) }
    }
}

@Composable
private fun FaceButton(
    letter: String,
    color: Color,
    dim: Dp,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onPress: (Boolean) -> Unit
) {
    var down by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(dim)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    listOf(
                        if (down) color else color.copy(alpha = 0.92f),
                        if (down) color else color.copy(alpha = 0.62f)
                    )
                )
            )
            .pressable(enabled) { down = it; onPress(it) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = letter,
            color = Chassis.copy(alpha = 0.88f),
            fontSize = (dim.value * 0.44f).sp,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )
    }
}

// ── Bumpers and triggers ─────────────────────────────────────────────────────
@Composable
fun ShoulderButton(
    label: String,
    w: Dp,
    h: Dp,
    trigger: Boolean,
    enabled: Boolean = true,
    onPress: (Boolean) -> Unit
) {
    var down by remember { mutableStateOf(false) }
    val shape = if (trigger)
        RoundedCornerShape(topStart = h / 2, topEnd = h / 2, bottomStart = 6.dp, bottomEnd = 6.dp)
    else
        RoundedCornerShape(h / 3)

    Box(
        modifier = Modifier
            .width(w)
            .height(h)
            .clip(shape)
            .background(if (down) XGreen.copy(alpha = 0.22f) else Panel)
            .pressable(enabled) { down = it; onPress(it) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (down) XGreen else Muted,
            fontSize = (h.value * 0.34f).sp,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
    }
}

// ── Centre cluster: Back / Guide / Start ─────────────────────────────────────
// The ring of light is the 360's most recognisable detail. The emulated pad has no
// wire-protocol bit for it (XUSB has none either), so here it does something equally
// true to the hardware: it is the "connected" indicator, and tapping it opens layout
// editing — the nearest thing this app has to a guide menu.
@Composable
fun GuideButton(dim: Dp, onTap: () -> Unit) {
    var down by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .size(dim)
            .pressable { pressed -> down = pressed; if (!pressed) onTap() },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(dim)) {
            val r = size.minDimension / 2f
            drawCircle(color = Panel, radius = r)
            drawCircle(color = Edge, radius = r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
            val ringR = r * 0.62f
            val w = r * 0.24f
            for (i in 0..3) {
                drawArc(
                    color = if (down) XGreen else XGreen.copy(alpha = 0.55f),
                    startAngle = 45f + i * 90f + 6f,
                    sweepAngle = 78f,
                    useCenter = false,
                    topLeft = Offset(center.x - ringR, center.y - ringR),
                    size = Size(ringR * 2, ringR * 2),
                    style = Stroke(width = w, cap = StrokeCap.Butt)
                )
            }
        }
    }
}

@Composable
fun MenuButton(label: String, dim: Dp, onPress: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    // "BACK"/"START" inside a small circle clipped by CircleShape: at the system's default
    // font scale (dim.value * 0.30f).sp fits, but a larger accessibility text-scale setting
    // inflates it past the circle, and the clip silently truncates it to "BAC"/"STA". Convert
    // through Dp so the label's physical size stays constant regardless of font scale — this
    // is a fixed-size instrument label, not scalable body text.
    val fontSize = with(LocalDensity.current) { (dim.value * 0.30f).dp.toSp() }
    Box(
        modifier = Modifier
            .size(dim)
            .clip(CircleShape)
            .background(if (down) XGreen.copy(alpha = 0.22f) else Panel)
            .pressable { down = it; onPress(it) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (down) XGreen else Muted,
            fontSize = fontSize,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Shared helper so custom buttons added in edit mode look like the built-ins. */
@Composable
fun GenericButton(label: String, w: Dp, h: Dp, enabled: Boolean = true, onPress: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .width(w)
            .height(h)
            .clip(RoundedCornerShape(h / 3))
            .background(if (down) XGreen.copy(alpha = 0.22f) else Panel)
            .pressable(enabled) { down = it; onPress(it) },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (down) XGreen else Muted,
            fontSize = (min(h.value * 0.34f, 16f)).sp,
            fontFamily = Mono,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}
