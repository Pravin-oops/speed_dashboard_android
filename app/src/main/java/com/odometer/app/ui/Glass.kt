package com.odometer.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.odometer.app.ui.theme.Ink

/** Generous, soft corners in the ColorOS style. */
val GlassShape: Shape = RoundedCornerShape(28.dp)
val PillShape: Shape = RoundedCornerShape(50)

/**
 * A pane of dark liquid glass: a smoky translucent body that lets the backdrop's colour
 * through, a sheen across the top as if lit from above, and a bright rim that catches the
 * light at the top-left and again faintly at the bottom-right.
 *
 * The backdrop behind it is made of soft light already, so the pane reads as frosted
 * without a real blur pass, which keeps it cheap and identical on every Android version.
 */
fun Modifier.glass(shape: Shape = GlassShape): Modifier = this
    .clip(shape)
    .background(Color(0x59090B10))
    .background(
        Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.04f)),
        ),
    )
    .drawBehind {
        // Sheen: a band of light on the upper part of the pane.
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.10f),
                0.4f to Color.Transparent,
            ),
        )
    }
    .border(
        width = 1.dp,
        brush = Brush.linearGradient(
            0f to Color.White.copy(alpha = 0.55f),
            0.35f to Color.White.copy(alpha = 0.10f),
            0.7f to Color.White.copy(alpha = 0.04f),
            1f to Color.White.copy(alpha = 0.26f),
        ),
        shape = shape,
    )

/** Glass gives slightly under a finger and springs back, like pressing a drop of liquid. */
@Composable
fun Modifier.liquidPress(pressed: Boolean, pressedScale: Float = 0.94f): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 380f),
        label = "liquidPress",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * The scene behind the glass: near-black with a few large pools of soft coloured light,
 * so the panes have something to tint and refract.
 */
@Composable
fun AmbientBackdrop(modifier: Modifier = Modifier) {
    Spacer(
        modifier
            .background(Ink)
            .drawBehind {
                val reach = size.maxDimension
                fun pool(color: Color, x: Float, y: Float, radius: Float) {
                    val center = Offset(size.width * x, size.height * y)
                    drawRect(
                        Brush.radialGradient(
                            listOf(color, Color.Transparent),
                            center = center,
                            radius = reach * radius,
                        ),
                    )
                }
                pool(Color(0xFF2F6BFF).copy(alpha = 0.40f), x = 0.10f, y = 0.05f, radius = 0.55f)
                pool(Color(0xFF8A3FFC).copy(alpha = 0.34f), x = 0.98f, y = 0.30f, radius = 0.50f)
                pool(Color(0xFF00BFA6).copy(alpha = 0.26f), x = 0.50f, y = 1.05f, radius = 0.50f)
            },
    )
}
