package com.odometer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odometer.app.TripSummary
import com.odometer.app.ui.theme.Chalk
import com.odometer.app.ui.theme.Label
import kotlin.math.max
import kotlin.math.roundToInt

/** A held trip, and where its row sits on the screen so the menu can open out of it. */
class TripMenuRequest(val trip: TripSummary, val anchor: Rect)

private val MenuShape = RoundedCornerShape(26.dp)
private val ItemShape = RoundedCornerShape(18.dp)
private val ItemHeight = 52.dp
private val MenuWidth = 236.dp
private val DeleteRed = Color(0xFFFF6B6B)

private class MenuItem(val label: String, val color: Color, val action: (TripSummary) -> Unit)

/**
 * The menu that springs out of a held trip. A slab of glass follows the finger from one
 * choice to the next and the whole panel leans after it; lifting the finger picks whatever
 * the slab is on. A touch anywhere else closes it.
 *
 * [shade] runs from 0 to 1 as the screen behind frosts over, and back.
 */
@Composable
fun TripMenu(
    request: TripMenuRequest?,
    shade: () -> Float,
    onDismiss: () -> Unit,
    onOpen: (TripSummary) -> Unit,
    onDelete: (TripSummary) -> Unit,
) {
    // The last request is kept so the menu can still be drawn while it closes.
    var last by remember { mutableStateOf(request) }
    SideEffect { if (request != null) last = request }
    val open by animateFloatAsState(
        targetValue = if (request != null) 1f else 0f,
        animationSpec = if (request != null) spring(dampingRatio = 0.52f, stiffness = 340f) else tween(170),
        label = "menuOpen",
    )
    val shown = request ?: last ?: return
    if (request == null && open <= 0.01f) return
    BackHandler(enabled = request != null, onBack = onDismiss)

    val dismiss by rememberUpdatedState(onDismiss)
    val items = remember(onOpen, onDelete) {
        listOf(MenuItem("Open trip", Chalk, onOpen), MenuItem("Delete trip", DeleteRed, onDelete))
    }
    val context = LocalContext.current
    val density = LocalDensity.current
    val itemPx = with(density) { ItemHeight.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }
    val marginPx = with(density) { 12.dp.toPx() }
    val leanPx = with(density) { 9.dp.toPx() }

    // Which choice the finger is on (-1 for none), and the last one it was on, where the
    // slab stays while it fades.
    var hot by remember { mutableIntStateOf(-1) }
    var slabOn by remember { mutableIntStateOf(0) }
    var lean by remember { mutableFloatStateOf(0f) }
    val slabY by animateFloatAsState(slabOn * itemPx, spring(dampingRatio = 0.5f, stiffness = 520f), label = "slabY")
    val slabGlow by animateFloatAsState(if (hot >= 0) 1f else 0f, tween(if (hot >= 0) 80 else 300), label = "slabGlow")
    val nudge by animateFloatAsState(lean * leanPx, spring(dampingRatio = 0.45f, stiffness = 260f), label = "menuLean")

    var screen by remember { mutableStateOf(IntSize.Zero) }
    var panel by remember { mutableStateOf(IntSize.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { screen = it }
            .drawBehind { drawRect(Color.Black.copy(alpha = 0.4f * shade())) }
            .pointerInput(Unit) { detectTapGestures { dismiss() } },
    ) {
        // Under the row if there is room, otherwise above it; tucked against its right edge.
        val anchor = shown.anchor
        val below = anchor.bottom + gapPx + panel.height <= screen.height - marginPx
        val x = (anchor.right - panel.width).coerceIn(marginPx, max(marginPx, screen.width - panel.width - marginPx))
        val y = if (below) anchor.bottom + gapPx else max(marginPx, anchor.top - gapPx - panel.height)

        Column(
            Modifier
                .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .width(MenuWidth)
                .onSizeChanged { panel = it }
                .graphicsLayer {
                    // Grows out of the corner nearest the row it belongs to.
                    transformOrigin = TransformOrigin(0.85f, if (below) 0f else 1f)
                    scaleX = open
                    scaleY = open
                    alpha = (open * 1.6f).coerceIn(0f, 1f)
                    translationY = nudge
                }
                .glass(MenuShape)
                .padding(8.dp),
        ) {
            Text(
                text = "${shortDateText(shown.trip.startedAt)}, ${context.clockText(shown.trip.startedAt)} · " +
                    "${formatKm(shown.trip.distanceKm)} km",
                color = Label,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
            )
            Box(
                Modifier.fillMaxWidth().pointerInput(items, shown.trip.id) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        fun follow(x: Float, y: Float) {
                            val inside = x in 0f..size.width.toFloat() && y in 0f..size.height.toFloat()
                            hot = if (inside) (y / itemPx).toInt().coerceIn(0, items.lastIndex) else -1
                            if (hot >= 0) slabOn = hot
                            lean = (y / size.height - 0.5f).coerceIn(-1.5f, 1.5f)
                        }
                        follow(down.position.x, down.position.y)
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            follow(change.position.x, change.position.y)
                            change.consume()
                            if (!change.pressed) break
                        }
                        val chosen = hot
                        hot = -1
                        lean = 0f
                        if (chosen >= 0) {
                            dismiss()
                            items[chosen].action(shown.trip)
                        }
                    }
                },
            ) {
                Box(
                    Modifier
                        .offset { IntOffset(0, slabY.roundToInt()) }
                        .fillMaxWidth()
                        .height(ItemHeight)
                        .graphicsLayer {
                            alpha = slabGlow
                            scaleX = 1f + 0.03f * slabGlow
                            scaleY = 0.9f + 0.1f * slabGlow
                        }
                        .clip(ItemShape)
                        .background(Color.White.copy(alpha = 0.17f)),
                )
                Column {
                    for (item in items) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .height(ItemHeight)
                                .semantics {
                                    role = Role.Button
                                    onClick(item.label) {
                                        dismiss()
                                        item.action(shown.trip)
                                        true
                                    }
                                }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = item.label,
                                color = item.color,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
