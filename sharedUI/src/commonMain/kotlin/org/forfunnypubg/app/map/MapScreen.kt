package org.forfunnypubg.app.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt
import org.jetbrains.compose.resources.painterResource

private const val ZOOM_MIN = 0.8f
private const val ZOOM_MAX = 8f
private const val ZOOM_STEP = 1.3f

@Composable
fun MapScreen() {
    var selectedMap by remember { mutableStateOf(PubgMap.ERANGEL) }
    var zoom by remember { mutableStateOf(1f) }
    var panX by remember { mutableStateOf(0f) }
    var panY by remember { mutableStateOf(0f) }
    // Size in pixels of the square map display area — used for coordinate math
    var squarePx by remember { mutableStateOf(0) }
    var pointA by remember { mutableStateOf<Offset?>(null) }
    var pointB by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(selectedMap) {
        zoom = 1f; panX = 0f; panY = 0f
        pointA = null; pointB = null
    }

    Column(modifier = Modifier.fillMaxSize()) {

        // ── Tab row ──────────────────────────────────────────────────────────
        PrimaryScrollableTabRow(
            selectedTabIndex = PubgMap.entries.indexOf(selectedMap),
            modifier = Modifier.fillMaxWidth().statusBarsPadding(),
            edgePadding = 0.dp,
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.93f),
        ) {
            PubgMap.entries.forEach { map ->
                Tab(
                    selected = selectedMap == map,
                    onClick = { selectedMap = map },
                    text = { Text(map.displayName) },
                )
            }
        }

        // ── Map area (remaining space) ────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                // Square whose side = min(available width, available height)
                // This ensures the image is always displayed 1:1 (square grid)
                val squareDp = minOf(maxWidth, maxHeight)

                Box(
                    modifier = Modifier
                        .size(squareDp)
                        .align(Alignment.Center)
                        .onSizeChanged { squarePx = it.width }
                        .pointerInput(Unit) {
                            // Mouse / trackpad scroll → zoom
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Scroll) {
                                        val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                        val factor = if (dy > 0f) 1f / ZOOM_STEP else ZOOM_STEP
                                        zoom = (zoom * factor).coerceIn(ZOOM_MIN, ZOOM_MAX)
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            // Pinch-to-zoom + drag-to-pan
                            detectTransformGestures { _, pan, zoomChange, _ ->
                                zoom = (zoom * zoomChange).coerceIn(ZOOM_MIN, ZOOM_MAX)
                                panX += pan.x
                                panY += pan.y
                            }
                        }
                        .pointerInput(Unit) {
                            // Single tap → place distance-measurement points A then B
                            detectTapGestures { tapPos ->
                                val s = squarePx.toFloat()
                                if (s <= 0f) return@detectTapGestures

                                // Convert screen tap to normalized image coords [0,1]
                                // graphicsLayer pivot = center of the square Box
                                // nx = (tapX - panX - s/2) / (zoom * s) + 0.5
                                val nx = (tapPos.x - panX - s / 2f) / (zoom * s) + 0.5f
                                val ny = (tapPos.y - panY - s / 2f) / (zoom * s) + 0.5f
                                val norm = Offset(nx.coerceIn(0f, 1f), ny.coerceIn(0f, 1f))

                                when {
                                    pointA == null -> pointA = norm
                                    pointB == null -> pointB = norm
                                    else -> { pointA = norm; pointB = null }
                                }
                            }
                        },
                ) {
                    // Map image forced into the square — FillBounds means every pixel
                    // of the square exactly corresponds to a game-coordinate cell.
                    Image(
                        painter = painterResource(selectedMap.resource),
                        contentDescription = selectedMap.displayName,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = zoom,
                                scaleY = zoom,
                                translationX = panX,
                                translationY = panY,
                            ),
                    )

                    // Overlay: dots + line drawn in the square's screen-space coords
                    val a = pointA
                    val b = pointB
                    if (a != null) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val s = size.width  // == size.height (square)

                            // Inverse graphicsLayer: normalized → screen coords inside square
                            fun norm2screen(n: Offset) = Offset(
                                x = (n.x - 0.5f) * zoom * s + s / 2f + panX,
                                y = (n.y - 0.5f) * zoom * s + s / 2f + panY,
                            )

                            val sa = norm2screen(a)
                            drawCircle(color = Color.Black, radius = 14f, center = sa)
                            drawCircle(color = Color(0xFFFFD700), radius = 10f, center = sa)
                            drawCircle(
                                color = Color.Black, radius = 10f, center = sa,
                                style = Stroke(width = 2f),
                            )

                            if (b != null) {
                                val sb = norm2screen(b)
                                drawLine(
                                    color = Color(0xFFFFD700),
                                    start = sa, end = sb, strokeWidth = 3f,
                                )
                                drawCircle(color = Color.Black, radius = 14f, center = sb)
                                drawCircle(color = Color(0xFFFF4444), radius = 10f, center = sb)
                                drawCircle(
                                    color = Color.Black, radius = 10f, center = sb,
                                    style = Stroke(width = 2f),
                                )
                            }
                        }
                    }
                }

                // ── Zoom +/− buttons (bottom-end of the available map area) ──
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    ZoomButton("+") { zoom = (zoom * ZOOM_STEP).coerceAtMost(ZOOM_MAX) }
                    ZoomButton("−") { zoom = (zoom / ZOOM_STEP).coerceAtLeast(ZOOM_MIN) }
                }

                // ── Distance readout (bottom-start) ──────────────────────────
                val currentA = pointA
                val currentB = pointB
                val labelText = when {
                    currentA == null -> null
                    currentB == null -> "Tap to place point B"
                    else -> {
                        val dx = (currentA.x - currentB.x) * selectedMap.sizeKm * 1000f
                        val dy = (currentA.y - currentB.y) * selectedMap.sizeKm * 1000f
                        "Distance: ${formatDistance(sqrt(dx * dx + dy * dy))}"
                    }
                }
                if (labelText != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(16.dp),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.93f),
                        shadowElevation = 4.dp,
                    ) {
                        Text(
                            text = labelText,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.titleLarge)
    }
}

private fun formatDistance(meters: Float): String {
    return if (meters >= 1000f) {
        val tenths = (meters / 100f).toInt()
        "${tenths / 10}.${tenths % 10} km"
    } else {
        "${meters.toInt()} m"
    }
}
