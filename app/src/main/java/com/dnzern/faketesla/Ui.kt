package com.dnzern.faketesla

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/** Colours from the design. */
object Pal {
    val Bg = Color(0xFF16191D)
    val Surface = Color(0xFF1F2328)
    val Surface2 = Color(0xFF2A2F35)
    val Chip = Color(0xFF24282E)
    val Line = Color(0xFF2A2F35)
    val Text = Color(0xFFF2F4F7)
    val Soft = Color(0xFFC9CFD6)
    val Muted = Color(0xFFA8B0BA)
    val Label = Color(0xFF8C95A0)
    val Blue = Color(0xFF4C8DFF)
    val OnBlue = Color(0xFF0B1730)
    val Green = Color(0xFF2FBF71)
    val GreenText = Color(0xFF5FE39A)
    val GreenDark = Color(0xFF173826)
    val Red = Color(0xFFE5484D)
    val Warn = Color(0xFFFFB74D)
}

val clock = SimpleDateFormat("HH:mm", Locale.getDefault())

fun fmt(v: Double?, decimals: Int, unit: String): String =
    if (v == null) "—" else String.format("%.${decimals}f %s", v, unit).trim()

fun duration(min: Double): String {
    val m = min.roundToInt()
    return if (m >= 60) "${m / 60} sa ${"%02d".format(m % 60)} dk" else "$m dk"
}

enum class Ic { Back, Menu, Search, Bolt, Locate, Home, Work, Pin, Clock, Chevron, Refresh }

/** Simple line icons drawn in code (no icon library needed). */
@Composable
fun Glyph(kind: Ic, color: Color, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        val stroke = Stroke(width = 2f * s, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            Ic.Back -> drawPath(Path().apply { moveTo(15 * s, 5 * s); lineTo(8 * s, 12 * s); lineTo(15 * s, 19 * s) }, color, style = stroke)
            Ic.Chevron -> drawPath(Path().apply { moveTo(9 * s, 6 * s); lineTo(15 * s, 12 * s); lineTo(9 * s, 18 * s) }, color, style = stroke)
            Ic.Menu -> for (y in listOf(7f, 12f, 17f)) {
                drawLine(color, Offset(4 * s, y * s), Offset(20 * s, y * s), 2 * s, StrokeCap.Round)
            }
            Ic.Search -> {
                drawCircle(color, 7 * s, Offset(11 * s, 11 * s), style = stroke)
                drawLine(color, Offset(16.5f * s, 16.5f * s), Offset(20 * s, 20 * s), 2 * s, StrokeCap.Round)
            }
            Ic.Bolt -> drawPath(
                Path().apply {
                    moveTo(13 * s, 2 * s); lineTo(4 * s, 14 * s); lineTo(11 * s, 14 * s)
                    lineTo(10 * s, 22 * s); lineTo(19 * s, 10 * s); lineTo(12 * s, 10 * s); close()
                },
                color
            )
            Ic.Locate -> drawPath(
                Path().apply { moveTo(3 * s, 11 * s); lineTo(21 * s, 3 * s); lineTo(13 * s, 21 * s); lineTo(11 * s, 13 * s); close() },
                color,
                style = stroke
            )
            Ic.Home -> drawPath(
                Path().apply {
                    moveTo(3 * s, 11 * s); lineTo(12 * s, 4 * s); lineTo(21 * s, 11 * s); lineTo(21 * s, 20 * s)
                    lineTo(15 * s, 20 * s); lineTo(15 * s, 14 * s); lineTo(9 * s, 14 * s); lineTo(9 * s, 20 * s)
                    lineTo(3 * s, 20 * s); close()
                },
                color,
                style = stroke
            )
            Ic.Work -> {
                drawRoundRect(color, Offset(3 * s, 7 * s), Size(18 * s, 13 * s), CornerRadius(2 * s), style = stroke)
                drawPath(Path().apply { moveTo(8 * s, 7 * s); lineTo(8 * s, 4 * s); lineTo(16 * s, 4 * s); lineTo(16 * s, 7 * s) }, color, style = stroke)
            }
            Ic.Pin -> {
                drawPath(
                    Path().apply {
                        moveTo(12 * s, 22 * s)
                        cubicTo(12 * s, 22 * s, 19 * s, 15.5f * s, 19 * s, 10 * s)
                        cubicTo(19 * s, 6 * s, 16 * s, 3 * s, 12 * s, 3 * s)
                        cubicTo(8 * s, 3 * s, 5 * s, 6 * s, 5 * s, 10 * s)
                        cubicTo(5 * s, 15.5f * s, 12 * s, 22 * s, 12 * s, 22 * s)
                        close()
                    },
                    color,
                    style = stroke
                )
                drawCircle(color, 2.5f * s, Offset(12 * s, 10 * s), style = stroke)
            }
            Ic.Clock -> {
                drawCircle(color, 9 * s, Offset(12 * s, 12 * s), style = stroke)
                drawPath(Path().apply { moveTo(12 * s, 7 * s); lineTo(12 * s, 12 * s); lineTo(15 * s, 14 * s) }, color, style = stroke)
            }
            Ic.Refresh -> {
                drawArc(color, 40f, 290f, false, Offset(4 * s, 4 * s), Size(16 * s, 16 * s), style = stroke)
                drawPath(Path().apply { moveTo(20 * s, 3 * s); lineTo(20 * s, 9 * s); lineTo(14 * s, 9 * s) }, color, style = stroke)
            }
        }
    }
}

/** Battery outline with a filled level. */
@Composable
fun BatteryGlyph(fraction: Float, fill: Color) {
    Canvas(Modifier.size(width = 26.dp, height = 14.dp)) {
        val w = size.width
        val h = size.height
        val u = h / 14f
        drawRoundRect(Pal.Soft, Offset(1 * u, 1 * u), Size(w - 5 * u, h - 2 * u), CornerRadius(3 * u), style = Stroke(1.6f * u))
        val inner = (w - 10 * u) * fraction.coerceIn(0f, 1f)
        if (inner > 0) drawRoundRect(fill, Offset(3.5f * u, 3.5f * u), Size(inner, h - 7 * u), CornerRadius(1.5f * u))
        drawRoundRect(Pal.Soft, Offset(w - 3 * u, 4.5f * u), Size(2 * u, 5 * u), CornerRadius(1 * u))
    }
}

/** Round floating button used over the map. */
@Composable
fun RoundButton(label: String, size: Dp = 50.dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(Pal.Chip)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text.uppercase(Locale("tr")),
        color = Pal.Label,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 2.dp)
    )
}

@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Pal.Surface)
    ) { content() }
}

@Composable
fun DataRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Pal.Muted, fontSize = 14.sp)
        Text(value, color = Pal.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
