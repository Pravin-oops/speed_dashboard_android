package com.odometer.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF0B0C10)
val Panel = Color(0xFF14161C)
val PanelEdge = Color(0xFF262A33)
val Amber = Color(0xFFFFB238)
val Signal = Color(0xFFD32F2F)
val Chalk = Color(0xFFF3F1EA)
val Muted = Color(0xFF8B919E)

private val OdometerColorScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF231600),
    secondary = Signal,
    onSecondary = Color.White,
    background = Ink,
    onBackground = Chalk,
    surface = Panel,
    onSurface = Chalk,
    surfaceVariant = PanelEdge,
    onSurfaceVariant = Muted,
    outline = Color(0xFF3A3F49),
)

/** The instrument cluster is always dark, whatever the system theme. */
@Composable
fun OdometerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = OdometerColorScheme, content = content)
}
