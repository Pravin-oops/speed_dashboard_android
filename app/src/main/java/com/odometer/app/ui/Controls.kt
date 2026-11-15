package com.odometer.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odometer.app.GpsState
import com.odometer.app.ui.theme.Amber
import com.odometer.app.ui.theme.Chalk
import com.odometer.app.ui.theme.EcoGreen
import com.odometer.app.ui.theme.Label
import com.odometer.app.ui.theme.Signal
import kotlin.math.roundToInt

/** A small glass button for things that are safe to do with a single tap. */
@Composable
fun GlassChip(text: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .liquid(PillShape)
            .glass(PillShape)
            // The light under the finger is the press feedback, so no ripple on top of it.
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/**
 * A glass button that only acts on a long press, for anything that ends or deletes
 * something, so a glove brushing the screen cannot do it.
 */
@Composable
fun HoldChip(text: String, color: Color, onHold: () -> Unit, modifier: Modifier = Modifier) {
    val hold by rememberUpdatedState(onHold)
    val haptics = LocalHapticFeedback.current
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .liquid(PillShape)
            .glass(PillShape)
            .pointerInput(Unit) {
                detectTapGestures(
                    onLongPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        hold()
                    },
                )
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** A lamp and a line of text for the state of the GPS. Tappable when the rider can fix it. */
@Composable
fun GpsPill(
    gpsState: GpsState,
    accuracyMeters: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (text, color) = when (gpsState) {
        GpsState.NoPermission -> "TAP TO ALLOW LOCATION" to Signal
        GpsState.Disabled -> "GPS IS OFF · TAP" to Signal
        GpsState.Searching -> "FINDING GPS" to Amber
        GpsState.Fix -> "GPS ±${accuracyMeters.roundToInt()} M" to EcoGreen
    }
    val lamp by animateColorAsState(color, label = "gpsLamp")
    val actionable = gpsState == GpsState.NoPermission || gpsState == GpsState.Disabled
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = actionable, onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(lamp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            color = lamp,
            fontSize = 12.sp,
            letterSpacing = 1.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A labelled figure with its unit, as on a trip computer. */
@Composable
fun Stat(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(text = label, fontSize = 9.sp, letterSpacing = 1.5.sp, color = Label, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Chalk, maxLines = 1)
            Text(
                text = " $unit",
                fontSize = 10.sp,
                color = Label,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(bottom = 3.dp),
            )
        }
    }
}
