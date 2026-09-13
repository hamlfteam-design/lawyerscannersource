package com.personal.docscanner.ui.doc

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.personal.docscanner.R
import com.personal.docscanner.data.db.PageEntity

/**
 * Full-screen page view.
 *
 * A scanner's whole output is the page, and until now the only way to see one
 * was a thumbnail the size of a postage stamp — too small to tell a good crop
 * from a bad one, which is exactly the judgement the app asks you to make.
 *
 * Swiping moves between pages; pinching zooms into the one in front of you,
 * because the reason to open a scan at full size is usually to read something
 * small on it. Zoom resets when the page changes, so a swipe never lands you
 * somewhere magnified and lost.
 */
@Composable
fun PageViewer(
    pages: List<PageEntity>,
    startIndex: Int,
    pathOf: (PageEntity) -> String,
    onEditCrop: (String) -> Unit,
    onRotate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (pages.isEmpty()) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val pagerState = rememberPagerState(
            initialPage = startIndex.coerceIn(0, pages.lastIndex),
            pageCount = { pages.size }
        )

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
                ZoomableImage(path = pathOf(pages[index]))
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, stringResource(R.string.back), tint = Color.White)
                }
                Text(
                    text = "${pagerState.currentPage + 1} / ${pages.size}",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            }

            // The page in view is the one these act on, which is what makes
            // them worth having here rather than back in the thumbnail strip.
            val current = pages.getOrNull(pagerState.currentPage)
            if (current != null) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 12.dp)
                ) {
                    IconButton(onClick = { onEditCrop(current.id) }) {
                        Icon(
                            Icons.Default.Crop,
                            stringResource(R.string.edit_crop),
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { onRotate(current.id) }) {
                        Icon(
                            Icons.Default.RotateRight,
                            stringResource(R.string.rotate_right),
                            tint = Color.White
                        )
                    }
                    IconButton(onClick = { onDelete(current.id) }) {
                        Icon(
                            Icons.Default.Delete,
                            stringResource(R.string.delete_page),
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(path: String) {
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offsetX by remember(path) { mutableFloatStateOf(0f) }
    var offsetY by remember(path) { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(path) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    if (scale > 1f) {
                        // Panning only makes sense while zoomed in; letting it
                        // drift at 1x would fight the pager's own swipe.
                        offsetX += pan.x
                        offsetY += pan.y
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = path,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        )
    }
}
