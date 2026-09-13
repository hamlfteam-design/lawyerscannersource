package com.personal.docscanner.ui.crop

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.personal.docscanner.R
import com.personal.docscanner.scan.DocumentFormat
import com.personal.docscanner.scan.Quad
import com.personal.docscanner.ui.common.LoadingOverlay
import com.personal.docscanner.ui.scan.ScanViewModel
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Corner adjustment.
 *
 * The image is drawn letterboxed inside the available space, and the quad is
 * kept in *image* pixel coordinates throughout — only the drawing and the touch
 * handling convert to screen space. Keeping one source of truth is what stops
 * the corners drifting when the screen rotates or the image is re-fit.
 */
@Composable
fun CropScreen(
    scanViewModel: ScanViewModel,
    onSaved: (String, Boolean) -> Unit,
    onEdited: () -> Unit,
    onCancel: () -> Unit
) {
    val pending by scanViewModel.pending.collectAsState()
    val busy by scanViewModel.busy.collectAsState()
    val detectHint by scanViewModel.detectHint.collectAsState()
    val editingPage by scanViewModel.editingPage.collectAsState()
    val scope = rememberCoroutineScope()

    val capture = pending
    if (capture == null) {
        // The session was cleared underneath us (process death, or a back press
        // that beat the state update) — there is nothing to crop.
        LoadingOverlay()
        return
    }

    val bitmap = capture.bitmap
    var quad by remember(capture) {
        mutableStateOf(capture.quad ?: Quad.inset(bitmap.width, bitmap.height))
    }
    var activeCorner by remember { mutableStateOf(-1) }

    val detectedFormat = remember(quad) {
        DocumentFormat.detect(quad.outputWidth, quad.outputHeight)
    }

    /**
     * Hands the corners being dragged back to the view model.
     *
     * Dragging only moves local state, and anything that edits the pending
     * capture replaces it — which re-keys the `remember` below and resets the
     * corners to whatever the view model last knew. Without this, rotating or
     * naming a format after adjusting the corners silently threw the adjustment
     * away.
     */
    fun commitCorners() {
        scanViewModel.updateQuad(quad)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, stringResource(R.string.cancel), tint = Color.White)
            }
            Text(
                text = stringResource(R.string.crop_title),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f)
            )
            if (capture.snapTo == null && detectedFormat.isKnown) {
                Text(
                    text = stringResource(R.string.detected_format, detectedFormat.labelAr),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(end = 12.dp)
                )
            }
        }

        BoxWithConstraints(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            val boxWidth = constraints.maxWidth.toFloat()
            val boxHeight = constraints.maxHeight.toFloat()

            // One scale for both axes keeps the aspect ratio; the offsets centre it.
            val scale = minOf(boxWidth / bitmap.width, boxHeight / bitmap.height)
            val drawnWidth = bitmap.width * scale
            val drawnHeight = bitmap.height * scale
            val offsetX = (boxWidth - drawnWidth) / 2f
            val offsetY = (boxHeight - drawnHeight) / 2f

            fun toScreen(p: PointF) = Offset(offsetX + p.x * scale, offsetY + p.y * scale)
            fun toImage(o: Offset) = PointF(
                ((o.x - offsetX) / scale).coerceIn(0f, bitmap.width.toFloat()),
                ((o.y - offsetY) / scale).coerceIn(0f, bitmap.height.toFloat())
            )

            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(bitmap, scale) {
                        detectDragGestures(
                            onDragStart = { start ->
                                // Grab the nearest corner, but only if the touch
                                // landed near one — otherwise a stray drag would
                                // yank a distant corner across the page.
                                val screenPoints = quad.points.map(::toScreen)
                                val nearest = screenPoints
                                    .mapIndexed { index, point ->
                                        index to hypot(point.x - start.x, point.y - start.y)
                                    }
                                    .minByOrNull { it.second }
                                activeCorner =
                                    if (nearest != null && nearest.second <= TOUCH_SLOP_PX) {
                                        nearest.first
                                    } else {
                                        -1
                                    }
                            },
                            onDragEnd = {
                                activeCorner = -1
                                scanViewModel.updateQuad(quad)
                            },
                            onDragCancel = { activeCorner = -1 }
                        ) { change, _ ->
                            if (activeCorner >= 0) {
                                change.consume()
                                quad = quad
                                    .withCorner(activeCorner, toImage(change.position))
                                    .clampTo(bitmap.width, bitmap.height)
                            }
                        }
                    }
            ) {
                val screenPoints = quad.points.map(::toScreen)

                val path = Path().apply {
                    moveTo(screenPoints[0].x, screenPoints[0].y)
                    screenPoints.drop(1).forEach { lineTo(it.x, it.y) }
                    close()
                }

                // Dim everything outside the selection so the page pops.
                // EvenOdd is what punches the quad out of the full-screen rect;
                // the default NonZero rule would just fill the whole thing.
                drawPath(
                    path = Path().apply {
                        fillType = androidx.compose.ui.graphics.PathFillType.EvenOdd
                        addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                        addPath(path)
                    },
                    color = Color.Black.copy(alpha = 0.45f),
                    style = androidx.compose.ui.graphics.drawscope.Fill
                )

                drawPath(
                    path = path,
                    color = OverlayColor,
                    style = Stroke(
                        width = 3.dp.toPx(),
                        pathEffect = PathEffect.cornerPathEffect(4f)
                    )
                )

                screenPoints.forEachIndexed { index, point ->
                    val active = index == activeCorner
                    drawCircle(
                        color = Color.White,
                        radius = if (active) 18.dp.toPx() else 12.dp.toPx(),
                        center = point
                    )
                    drawCircle(
                        color = OverlayColor,
                        radius = if (active) 12.dp.toPx() else 7.dp.toPx(),
                        center = point
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { commitCorners(); scanViewModel.rotate(-90) }) {
                Icon(Icons.Default.RotateLeft, stringResource(R.string.rotate_left), tint = Color.White)
            }
            OutlinedButton(onClick = {
                quad = Quad.full(bitmap.width, bitmap.height)
                scanViewModel.updateQuad(quad)
            }) {
                Icon(Icons.Default.CropFree, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.select_all))
            }
            OutlinedButton(onClick = {
                scanViewModel.autoDetect()
                scanViewModel.pending.value?.quad?.let { quad = it }
            }) {
                Text(stringResource(R.string.auto_detect))
            }
            IconButton(onClick = { commitCorners(); scanViewModel.rotate(90) }) {
                Icon(Icons.Default.RotateRight, stringResource(R.string.rotate_right), tint = Color.White)
            }
        }

        // Shape alone cannot tell an A4 page from a passport data page — they are
        // 0.44% apart, finer than a photograph can be measured — so detection
        // always answers A4 in that band. This row is how the other answer is
        // reachable at all: the user is the only one who knows.
        FormatPicker(
            selected = capture.snapTo,
            detected = detectedFormat,
            onSelect = { format ->
                commitCorners()
                scanViewModel.updateSnapFormat(format)
            }
        )

        // Says why the corners are a plain rectangle instead of the page, so a
        // detection that never ran is distinguishable from one that found nothing.
        detectHint?.let { hint ->
            Text(
                text = stringResource(hint),
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Re-cropping a page that is already saved has nothing to add to.
            if (!editingPage) {
                OutlinedButton(
                    onClick = {
                        scanViewModel.updateQuad(quad)
                        scope.launch {
                            val docId = scanViewModel.commitPending()
                            if (docId != null) onSaved(docId, true)
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.add_page))
                }
            }
            Button(
                onClick = {
                    scanViewModel.updateQuad(quad)
                    // Read before committing: the commit is what clears the flag.
                    val wasEditing = editingPage
                    scope.launch {
                        val docId = scanViewModel.commitPending()
                        when {
                            // Re-cropping came *from* the document, so go back to
                            // it rather than pushing a second copy on the stack.
                            wasEditing -> onEdited()
                            docId != null -> onSaved(docId, false)
                        }
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.done))
            }
        }
    }

    if (busy) LoadingOverlay()
}

/**
 * Paper format for this page: automatic, or one the user names.
 *
 * Only the formats worth snapping to appear. A receipt has no standard length
 * and freeform is the automatic answer when nothing matches, so neither is
 * offered as a thing to choose.
 */
@Composable
private fun FormatPicker(
    selected: DocumentFormat?,
    detected: DocumentFormat,
    onSelect: (DocumentFormat?) -> Unit
) {
    val options = listOf(
        DocumentFormat.A4,
        DocumentFormat.LETTER,
        DocumentFormat.ID_CARD,
        DocumentFormat.PASSPORT
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FormatChip(
            label = if (detected.isKnown) {
                stringResource(R.string.format_auto_detected, detected.labelAr)
            } else {
                stringResource(R.string.format_auto)
            },
            selected = selected == null,
            onClick = { onSelect(null) }
        )
        options.forEach { format ->
            FormatChip(
                label = format.labelAr,
                selected = selected == format,
                onClick = { onSelect(format) }
            )
        }
    }
}

@Composable
private fun FormatChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = Color.White,
            selectedContainerColor = OverlayColor,
            selectedLabelColor = Color.White
        )
    )
}

private val OverlayColor = Color(0xFFE9622F)

/** Roughly a fingertip; corners further than this are not grabbed. */
private const val TOUCH_SLOP_PX = 140f
