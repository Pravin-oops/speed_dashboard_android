package com.odometer.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.odometer.app.GpsState
import com.odometer.app.TripSummary
import com.odometer.app.ui.theme.Chalk
import com.odometer.app.ui.theme.EcoGreen
import com.odometer.app.ui.theme.Muted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** How much of a tall screen the history sheet takes while it is only peeking. */
private const val PeekFraction = 0.42f
private val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
private val RowShape = RoundedCornerShape(20.dp)

/**
 * Where the app opens: a greeting and the button that starts a trip, with the trips already
 * ridden underneath.
 *
 * On a tall screen the history is a sheet peeking up from the bottom; dragging it up (or
 * tapping the arrow) pulls it over the whole screen. On a wide screen there is room for the
 * start button and the full history side by side.
 */
@Composable
fun HomeScreen(
    trips: List<TripSummary>,
    totalKm: Double,
    gpsState: GpsState,
    accuracyMeters: Float,
    onGpsClick: () -> Unit,
    /** Called with the play button's bounds in the window. */
    onStartTrip: (Rect) -> Unit,
    onOpenTrip: (TripSummary) -> Unit,
    onDeleteTrip: (TripSummary) -> Unit,
) {
    // Holding a trip opens a menu out of its row, and the screen behind frosts over.
    var menu by remember { mutableStateOf<TripMenuRequest?>(null) }
    val shade by animateFloatAsState(if (menu != null) 1f else 0f, tween(260), label = "menuShade")
    val frostPx = with(LocalDensity.current) { 22.dp.toPx() }

    Box(Modifier.fillMaxSize()) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                // Read here, so frosting over redraws the screen without recomposing it.
                val radius = frostPx * shade
                renderEffect = if (radius > 0.5f) BlurEffect(radius, radius, TileMode.Clamp) else null
            }
            .safeDrawingPadding(),
    ) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StartPanel(gpsState, accuracyMeters, onGpsClick, onStartTrip, Modifier.weight(1f).fillMaxHeight())
                History(
                    trips = trips,
                    totalKm = totalKm,
                    onOpenTrip = onOpenTrip,
                    onHoldTrip = { trip, row -> menu = TripMenuRequest(trip, row) },
                    handle = false,
                    bottomSpace = 12.dp,
                    modifier = Modifier.weight(1f).fillMaxHeight().glass(),
                )
            }
        } else {
            val scope = rememberCoroutineScope()
            val sheet = remember { HistorySheetState(scope) }
            val startHeight = maxHeight * (1f - PeekFraction)
            with(LocalDensity.current) {
                sheet.travelPx = startHeight.toPx()
                sheet.flingPx = 300.dp.toPx()
            }
            val travelPx = sheet.travelPx
            val covering by remember { derivedStateOf { sheet.expansion > 0.5f } }
            BackHandler(enabled = covering) { sheet.collapse() }

            StartPanel(
                gpsState, accuracyMeters, onGpsClick, onStartTrip,
                // Fades out as the sheet rises over it, so it never shows through the glass.
                Modifier.fillMaxWidth().height(startHeight).graphicsLayer { alpha = 1f - sheet.expansion },
            )
            History(
                trips = trips,
                totalKm = totalKm,
                onOpenTrip = onOpenTrip,
                onHoldTrip = { trip, row -> menu = TripMenuRequest(trip, row) },
                handle = true,
                bottomSpace = 80.dp,
                modifier = Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, (travelPx * (1f - sheet.expansion)).roundToInt()) }
                    .glass(SheetShape)
                    .nestedScroll(sheet.connection)
                    .draggable(
                        state = rememberDraggableState { sheet.dragBy(it) },
                        orientation = Orientation.Vertical,
                        onDragStopped = { velocity -> sheet.settle(velocity) },
                    ),
            )
            ArrowButton(
                label = if (covering) "Close history" else "Show all trips",
                onClick = sheet::toggle,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            )
        }
    }
    TripMenu(
        request = menu,
        shade = { shade },
        onDismiss = { menu = null },
        onOpen = onOpenTrip,
        onDelete = onDeleteTrip,
    )
    }
}

/**
 * Position of the history sheet, and the rules for moving it by finger: an upward drag
 * raises the sheet before the list inside starts to scroll, and a downward drag lowers it
 * once the list is back at its top.
 */
private class HistorySheetState(private val scope: CoroutineScope) {

    /** 0 while the sheet peeks from the bottom, 1 once it covers the screen. */
    var expansion by mutableFloatStateOf(0f)
        private set

    /** How far the sheet moves between those two positions. */
    var travelPx = 1f

    /** A release faster than this goes the way it was thrown, wherever the sheet is. */
    var flingPx = 1f

    private var settling: Job? = null

    /** Moves the sheet with the finger and returns how much of [dy] it used. */
    fun dragBy(dy: Float): Float {
        settling?.cancel()
        val before = expansion
        expansion = (before - dy / travelPx).coerceIn(0f, 1f)
        return (before - expansion) * travelPx
    }

    fun settle(velocityPx: Float) {
        val target = when {
            velocityPx < -flingPx -> 1f
            velocityPx > flingPx -> 0f
            expansion >= 0.5f -> 1f
            else -> 0f
        }
        animateTo(target, -velocityPx / travelPx)
    }

    fun toggle() = animateTo(if (expansion < 0.5f) 1f else 0f, 0f)

    fun collapse() = animateTo(0f, 0f)

    private fun animateTo(target: Float, velocity: Float) {
        settling?.cancel()
        settling = scope.launch {
            animate(
                initialValue = expansion,
                targetValue = target,
                initialVelocity = velocity,
                animationSpec = spring(dampingRatio = 0.85f, stiffness = 300f),
            ) { value, _ -> expansion = value.coerceIn(0f, 1f) }
        }
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
            if (source == NestedScrollSource.UserInput && available.y < 0f && expansion < 1f) {
                Offset(0f, dragBy(available.y))
            } else {
                Offset.Zero
            }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
            if (source == NestedScrollSource.UserInput && available.y > 0f) {
                Offset(0f, dragBy(available.y))
            } else {
                Offset.Zero
            }

        override suspend fun onPreFling(available: Velocity): Velocity =
            if (expansion > 0f && expansion < 1f) {
                // Let go part-way: the sheet takes the throw, the list stays put.
                settle(available.y)
                available
            } else {
                Velocity.Zero
            }
    }
}

@Composable
private fun StartPanel(
    gpsState: GpsState,
    accuracyMeters: Float,
    onGpsClick: () -> Unit,
    onStartTrip: (Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Re-read now and then, so a screen left open overnight does not still say good evening.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis()
        }
    }
    Column(modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GpsPill(gpsState, accuracyMeters, onGpsClick)
        Spacer(Modifier.weight(1f))
        Text(
            text = greeting(now),
            color = Chalk,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(text = longDateText(now), color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
        Text(
            text = "START THE TRIP",
            color = Chalk,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 4.sp,
        )
        Spacer(Modifier.height(16.dp))
        PlayButton(onStartTrip)
        Spacer(Modifier.weight(1f))
    }
}

/** [onClick] is told where the button sits in the window, so the dashboard can open out of it. */
@Composable
private fun PlayButton(onClick: (Rect) -> Unit, modifier: Modifier = Modifier) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Box(
        modifier = modifier
            .size(96.dp)
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .liquid(CircleShape)
            .drawBehind {
                // A pool of green light under the glass, so the button reads as "go".
                drawCircle(
                    Brush.radialGradient(
                        listOf(EcoGreen.copy(alpha = 0.38f), Color.Transparent),
                        radius = size.minDimension * 0.85f,
                    ),
                    radius = size.minDimension * 0.85f,
                )
            }
            .glass(CircleShape)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = { onClick(bounds) },
            )
            .semantics { contentDescription = "Start the trip" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(34.dp)) {
            // Nudged right of centre: a triangle's visual weight sits towards its base.
            val left = size.width * 0.22f
            val triangle = Path().apply {
                moveTo(left, size.height * 0.1f)
                lineTo(size.width * 0.95f, size.height / 2f)
                lineTo(left, size.height * 0.9f)
                close()
            }
            drawPath(triangle, EcoGreen)
            // The same outline again as a stroke rounds the three corners off.
            drawPath(triangle, EcoGreen, style = Stroke(width = size.width * 0.14f, join = StrokeJoin.Round))
        }
    }
}

/** The list of ridden trips under a header. [handle] adds the grip of a draggable sheet. */
@Composable
private fun History(
    trips: List<TripSummary>,
    totalKm: Double,
    onOpenTrip: (TripSummary) -> Unit,
    onHoldTrip: (TripSummary, Rect) -> Unit,
    handle: Boolean,
    bottomSpace: Dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (handle) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 10.dp)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(PillShape)
                    .background(Color.White.copy(alpha = 0.3f)),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "PREVIOUS TRIPS",
                modifier = Modifier.weight(1f),
                color = Muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )
            // All the distance ever ridden with the app, trips or not.
            Text(
                text = "${formatKm(totalKm)} KM IN ALL",
                color = Muted.copy(alpha = 0.7f),
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
            )
        }
        if (trips.isEmpty()) {
            Text(
                text = "No trips yet.\nStart one and it will be saved here when you end it.",
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
                color = Muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = bottomSpace),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(trips, key = { it.id }) { trip ->
                    TripRow(
                        trip = trip,
                        onClick = { onOpenTrip(trip) },
                        onHold = { row -> onHoldTrip(trip, row) },
                        // The rows left behind slide up into the gap when one is deleted.
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TripRow(trip: TripSummary, onClick: () -> Unit, onHold: (Rect) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .liquid(RowShape, pressedScale = 0.97f, pull = 0.dp)
            .clip(RowShape)
            .background(Color.White.copy(alpha = 0.06f))
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onClick = onClick,
                onLongClickLabel = "Trip options",
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onHold(bounds)
                },
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = shortDateText(trip.startedAt),
                color = Chalk,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Text(
                text = "${context.clockText(trip.startedAt)} · ${formatKm(trip.distanceKm)} km · " +
                    formatDuration(trip.movingMillis),
                color = Muted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${trip.averageKmh.roundToInt()}",
                color = Chalk,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(text = "KM/H AVG", color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp)
        }
    }
}

/**
 * The round arrow under the history. Down reads right both ways: "there is more below" while
 * the sheet peeks, "send it back down" once it covers the screen.
 */
@Composable
private fun ArrowButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .liquid(CircleShape)
            .glass(CircleShape)
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(18.dp)) {
            val width = size.width * 0.13f
            val middle = size.width / 2f
            val tip = size.height * 0.95f
            val barb = size.width * 0.38f
            drawLine(Chalk, Offset(middle, size.height * 0.05f), Offset(middle, tip), width, StrokeCap.Round)
            drawLine(Chalk, Offset(middle - barb, tip - barb), Offset(middle, tip), width, StrokeCap.Round)
            drawLine(Chalk, Offset(middle + barb, tip - barb), Offset(middle, tip), width, StrokeCap.Round)
        }
    }
}
