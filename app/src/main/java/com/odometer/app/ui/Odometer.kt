package com.odometer.app.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin

@Immutable
data class OdometerColors(
    val housing: Color,
    val wheel: Color,
    val wheelEdge: Color,
    val digit: Color,
    val fractionWheel: Color,
    val fractionWheelEdge: Color,
    val fractionDigit: Color,
)

object OdometerDefaults {
    /** Slow, slightly under-damped spring: wheels glide into place with a soft settle. */
    val FluidSpring: AnimationSpec<Float> = spring(dampingRatio = 0.8f, stiffness = 60f)

    /** Stiff, critically damped spring used to track a continuously changing value. */
    val TrackingSpring: AnimationSpec<Float> =
        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)

    fun colors(
        housing: Color = Color(0xFF07080B),
        wheel: Color = Color(0xFF1C1F26),
        wheelEdge: Color = Color(0xFF0D0F13),
        digit: Color = Color(0xFFF3F1EA),
        fractionWheel: Color = Color(0xFFD32F2F),
        fractionWheelEdge: Color = Color(0xFF7A1212),
        fractionDigit: Color = Color(0xFFFFFFFF),
    ) = OdometerColors(housing, wheel, wheelEdge, digit, fractionWheel, fractionWheelEdge, fractionDigit)
}

/**
 * A mechanical odometer.
 *
 * Every wheel is driven by its own spring. A wheel's target comes from the mechanical
 * carry rule of a real odometer: it only turns while all the wheels to its right are
 * rolling over from 9 to 0. Because each spring keeps its velocity when a new target
 * arrives, rapid or continuous changes blend into one fluid motion instead of restarting.
 *
 * @param value the reading to show. Readings wider than the wheels wrap around, like the real thing.
 * @param integerDigits number of whole-unit wheels.
 * @param fractionDigits number of fractional wheels (drawn in [OdometerColors.fractionWheel]).
 * @param animationSpec how wheels travel to a new reading.
 * @param staggerMillis delay between neighbouring wheels when they start from rest,
 *        so a change ripples from the rightmost wheel to the left.
 */
@Composable
fun Odometer(
    value: Double,
    modifier: Modifier = Modifier,
    integerDigits: Int = 6,
    fractionDigits: Int = 1,
    maxDigitWidth: Dp = 48.dp,
    animationSpec: AnimationSpec<Float> = OdometerDefaults.FluidSpring,
    staggerMillis: Long = 40L,
    colors: OdometerColors = OdometerDefaults.colors(),
) {
    val wheelCount = integerDigits + fractionDigits
    val reading = (value * 10.0.pow(fractionDigits)).coerceAtLeast(0.0)

    val scope = rememberCoroutineScope()
    val spec by rememberUpdatedState(animationSpec)
    val wheels = remember(wheelCount) {
        List(wheelCount) { i -> Wheel((mechanicalPosition(reading, i) % 10.0).toFloat()) }
    }
    val lastReading = remember(wheelCount) { doubleArrayOf(reading) }

    LaunchedEffect(reading, wheels) {
        val previous = lastReading[0]
        if (previous == reading) return@LaunchedEffect
        lastReading[0] = reading

        wheels.forEachIndexed { index, wheel ->
            var delta = mechanicalPosition(reading, index) - mechanicalPosition(previous, index)
            if (abs(delta) < 1e-6) return@forEachIndexed
            // Big jumps would spin the low wheels hundreds of times. Keep the landing digit
            // and the direction, but cap the trip to about one extra revolution.
            if (abs(delta) > MaxTravelCells) {
                delta = sign(delta) * (SpinCells + abs(delta) % 10.0)
            }
            wheel.target += delta.toFloat()

            val startsFromRest = !wheel.position.isRunning
            // Launched in the composable's scope, not the effect's, so a newer reading
            // retargets the running animation (keeping its velocity) instead of cancelling it.
            scope.launch {
                if (startsFromRest && staggerMillis > 0) delay(staggerMillis * index)
                val velocity = wheel.position.velocity
                // Keep the float small so it never loses precision; a whole number of
                // turns is invisible because only position mod 10 is drawn.
                if (abs(wheel.position.value) > RebaseLimit) {
                    val shift = round(wheel.position.value / 10f) * 10f
                    wheel.position.snapTo(wheel.position.value - shift)
                    wheel.target -= shift
                }
                wheel.position.animateTo(wheel.target, spec, initialVelocity = velocity)
            }
        }
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val gap = 4.dp
        val housingPadding = 10.dp
        val dividerGap = if (fractionDigits > 0) 6.dp else 0.dp
        val available = maxWidth - housingPadding * 2 - gap * (wheelCount - 1) - dividerGap
        val digitWidth = min(maxDigitWidth, available / wheelCount)
        val digitHeight = digitWidth * 1.45f

        val density = LocalDensity.current
        val measurer = rememberTextMeasurer(cacheSize = 20)
        val textStyle = remember(digitHeight, density) {
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                fontSize = with(density) { (digitHeight * 0.6f).toSp() },
            )
        }
        val glyphs = remember(textStyle) { List(10) { d -> measurer.measure(d.toString(), textStyle) } }

        Box(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(colors.housing)
                .border(
                    width = 1.5.dp,
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF5A606C), Color(0xFF22252C), Color(0xFF3A3F49)),
                    ),
                    shape = RoundedCornerShape(14.dp),
                )
                .padding(housingPadding)
                .drawWithContent {
                    drawContent()
                    // Glass reflection across the whole window.
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.10f),
                            0.45f to Color.White.copy(alpha = 0.02f),
                            0.5f to Color.Transparent,
                        ),
                    )
                },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                for (index in wheelCount - 1 downTo 0) {
                    val isFraction = index < fractionDigits
                    if (isFraction && index == fractionDigits - 1) Spacer(Modifier.width(dividerGap - gap))
                    WheelView(
                        wheel = wheels[index],
                        glyphs = glyphs,
                        digitWidth = digitWidth,
                        digitHeight = digitHeight,
                        face = if (isFraction) colors.fractionWheel else colors.wheel,
                        edge = if (isFraction) colors.fractionWheelEdge else colors.wheelEdge,
                        digitColor = if (isFraction) colors.fractionDigit else colors.digit,
                    )
                }
            }
        }
    }
}

@Composable
private fun WheelView(
    wheel: Wheel,
    glyphs: List<TextLayoutResult>,
    digitWidth: Dp,
    digitHeight: Dp,
    face: Color,
    edge: Color,
    digitColor: Color,
) {
    val faceBrush = remember(face, edge) {
        Brush.verticalGradient(0f to edge, 0.5f to face, 1f to edge)
    }
    Box(
        Modifier
            .size(digitWidth, digitHeight)
            .clip(RoundedCornerShape(5.dp))
            .background(faceBrush)
            .drawWithContent {
                drawContent()
                // Curvature shading: the drum falls away into darkness at the top and bottom.
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.75f),
                        0.28f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.75f),
                    ),
                )
            },
    ) {
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Reading the value subscribes this layer to every animation frame.
                    wheel.position.value
                    renderEffect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wheel.position.isRunning) {
                        // Vertical motion blur proportional to how fast the drum spins.
                        val pxPerSecond = abs(wheel.position.velocity) * size.height
                        val radius = (pxPerSecond / 140f).coerceAtMost(size.height * 0.12f)
                        if (radius > 0.75f) BlurEffect(0f, radius, TileMode.Decal) else null
                    } else {
                        null
                    }
                }
                .drawBehind { drawDrum(wheel.position.value, glyphs, digitColor) },
        )
    }
}

/** Draws the digits around [position] as if printed on a rotating cylinder. */
private fun DrawScope.drawDrum(position: Float, glyphs: List<TextLayoutResult>, color: Color) {
    val centerY = size.height / 2f
    val radius = size.height * 0.95f / sin(AnglePerDigit)
    val first = floor(position).toInt()
    for (k in first - 1..first + 2) {
        val angle = (k - position) * AnglePerDigit
        if (abs(angle) >= HalfPi) continue
        val glyph = glyphs[Math.floorMod(k, 10)]
        val foreshortening = cos(angle)
        val y = centerY + radius * sin(angle)
        withTransform({
            translate(
                left = (size.width - glyph.size.width) / 2f,
                top = y - glyph.size.height / 2f,
            )
            scale(
                scaleX = 1f,
                scaleY = foreshortening,
                pivot = Offset(glyph.size.width / 2f, glyph.size.height / 2f),
            )
        }) {
            drawText(glyph, color = color, alpha = foreshortening * foreshortening)
        }
    }
}

private class Wheel(initial: Float) {
    val position = Animatable(initial)
    var target: Float = initial
}

/**
 * Position, in digit steps, of wheel [index] (0 = rightmost) for a counter reading [reading]
 * expressed in units of the rightmost wheel. The integer part is the digit the wheel has
 * passed; the fraction is how far it has rolled toward the next one. A wheel only rolls
 * while every wheel to its right is between 9 and 0, exactly like gears with a carry tooth.
 */
internal fun mechanicalPosition(reading: Double, index: Int): Double {
    val unit = 10.0.pow(index)
    val whole = floor(reading / unit)
    val lower = reading - whole * unit
    val carry = (lower - (unit - 1.0)).coerceAtLeast(0.0)
    return whole + carry
}

private const val MaxTravelCells = 20.0
private const val SpinCells = 10.0
private const val RebaseLimit = 10_000f
private const val AnglePerDigit = (PI / 5.0).toFloat() // 10 digits around the drum
private const val HalfPi = (PI / 2.0).toFloat()
