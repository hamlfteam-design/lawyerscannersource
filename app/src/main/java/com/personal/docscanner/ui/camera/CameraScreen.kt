package com.personal.docscanner.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.personal.docscanner.R
import com.personal.docscanner.ui.common.LoadingOverlay
import com.personal.docscanner.ui.scan.ScanViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The capture screen.
 *
 * The preview is watched continuously and the page outline is drawn as it is
 * found, but the shutter only fires on a deliberate press — there is no more
 * auto-fire on a held-still page. Each press crops, deskews and enhances the
 * shot automatically and files it straight into the same document without
 * ever showing the crop screen, and the camera stays up — so a twenty-page
 * file is shot by pressing the shutter once per sheet. «تم» ends the run and
 * that is the point the whole document becomes exportable as one PDF; nothing
 * is exported page by page.
 *
 * A capture can still be re-cropped by hand afterwards from the document
 * screen, for the odd page detection got wrong.
 */
@Composable
fun CameraScreen(
    scanViewModel: ScanViewModel,
    onCaptured: () -> Unit,
    onFinish: (String?) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current

    val session by scanViewModel.session.collectAsState()
    val busy by scanViewModel.busy.collectAsState()
    val saving by scanViewModel.saving.collectAsState()
    val pending by scanViewModel.pending.collectAsState()
    val lastThumb by scanViewModel.lastPageThumb.collectAsState()
    val idCardMode by scanViewModel.idCardMode.collectAsState()
    val idCardFrontPending by scanViewModel.idCardFrontPending.collectAsState()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    val imageCapture = remember { mutableStateOf<ImageCapture?>(null) }

    // The analyser writes from its own thread, so its output travels as a flow
    // rather than as Compose state written off the main thread.
    val frames = remember { MutableStateFlow<PageFinder.Frame?>(null) }
    val frame by frames.collectAsState()

    val finder = remember { PageFinder(onFrame = { frames.value = it }) }
    val analyzerExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val flash = remember { Animatable(0f) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(scanViewModel::onImported) }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Only a gallery import lands here: it goes to the crop screen so a page
    // that wasn't shot through this camera can still be placed by hand. A
    // shutter press never sets `pending` any more, so it never triggers this.
    LaunchedEffect(pending) {
        if (pending != null) onCaptured()
    }

    // A page landing is confirmed by a quick white pulse, since the page is
    // filed straight from the shutter press without any other screen to
    // confirm it on.
    LaunchedEffect(session.savedPages) {
        if (session.savedPages > 0) {
            flash.snapTo(0.55f)
            flash.animateTo(0f, androidx.compose.animation.core.tween(320))
        }
    }

    if (!hasPermission) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(stringResource(R.string.camera_permission_needed))
            Spacer(Modifier.size(16.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text(stringResource(R.string.grant_permission))
            }
        }
        return
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(previewView, flashMode) {
        bindCamera(previewView, lifecycleOwner, flashMode, analyzerExecutor, finder) { capture ->
            imageCapture.value = capture
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { previewView })

        PageOutline(frame = frame)

        if (flash.value > 0f) {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flash.value)))
        }

        // Top bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, stringResource(R.string.cancel), tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            // Toggles ID-card mode: the next two shutter presses become the
            // front then the back of one card instead of two separate pages.
            IdCardToggle(
                active = idCardMode,
                onClick = { scanViewModel.setIdCardMode(!idCardMode) }
            )
            Spacer(Modifier.size(4.dp))
            IconButton(
                onClick = {
                    flashMode = when (flashMode) {
                        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                        ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                        else -> ImageCapture.FLASH_MODE_OFF
                    }
                    imageCapture.value?.flashMode = flashMode
                }
            ) {
                Icon(
                    imageVector = when (flashMode) {
                        ImageCapture.FLASH_MODE_ON -> Icons.Default.FlashOn
                        ImageCapture.FLASH_MODE_AUTO -> Icons.Default.FlashAuto
                        else -> Icons.Default.FlashOff
                    },
                    contentDescription = stringResource(R.string.flash),
                    tint = Color.White
                )
            }
        }

        // Status line: how many pages are in this document so far.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (session.savedPages > 0) {
                Pill(stringResource(R.string.pages_count, session.savedPages))
                Spacer(Modifier.size(6.dp))
            }
            if (idCardMode) {
                Pill(
                    text = if (idCardFrontPending) {
                        "صوّر الوجه الخلفي للبطاقة"
                    } else {
                        "صوّر الوجه الأمامي للبطاقة"
                    }
                )
                Spacer(Modifier.size(6.dp))
            }
            if (saving) {
                Pill(text = stringResource(R.string.saving_page))
            }
        }

        // Bottom controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 28.dp, start = 32.dp, end = 32.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // The page just filed, or the gallery button when nothing has been
            // shot yet. Seeing the crop the moment it happens is what turns a
            // shutter press without a review step into something you can trust.
            Box(
                modifier = Modifier.size(56.dp),
                contentAlignment = Alignment.Center
            ) {
                val thumb = lastThumb
                if (thumb != null) {
                    AsyncImage(
                        model = thumb,
                        contentDescription = stringResource(R.string.pages),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    )
                } else {
                    IconButton(
                        onClick = {
                            galleryLauncher.launch(
                                androidx.activity.result.PickVisualMediaRequest(
                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                )
                            )
                        }
                    ) {
                        Icon(
                            Icons.Default.PhotoLibrary,
                            stringResource(R.string.import_from_gallery),
                            tint = Color.White
                        )
                    }
                }
            }

            ShutterButton(
                enabled = !busy && !saving,
                onClick = {
                    val capture = imageCapture.value ?: return@ShutterButton
                    // The quad the live preview is currently showing, carried
                    // along as a fallback in case detection on the full-size
                    // photo comes up empty (a dim shot, a low-contrast page).
                    val fired = frame
                    val previewAspect = if (fired != null && fired.frameHeight > 0) {
                        fired.frameWidth.toFloat() / fired.frameHeight
                    } else {
                        0f
                    }
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    capture.takePicture(
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val bitmap = image.toRotatedBitmap()
                                image.close()
                                if (bitmap == null) return
                                // Detect, crop, enhance and file the page right
                                // away — the shutter is the only action needed
                                // per page; the camera stays open for the next
                                // one until «تم» is pressed. In ID-card mode the
                                // first two presses become one page instead.
                                if (idCardMode) {
                                    scanViewModel.captureIdCardShot(
                                        bitmap = bitmap,
                                        onNeedBack = {}
                                    )
                                } else {
                                    scanViewModel.capturePage(
                                        bitmap = bitmap,
                                        previewQuad = fired?.quad,
                                        previewAspect = previewAspect
                                    )
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                // Surfaced through the shared error channel.
                            }
                        }
                    )
                }
            )

            // Finishing is only meaningful once something has been saved. This is
            // also the point where the whole session's pages — every camera shot
            // and every outside file brought in while the camera was open — get
            // swept through edge detection and enhancement together, so `busy`
            // covers that sweep before the document is handed off.
            IconButton(
                onClick = { scanViewModel.finish(onFinish) },
                enabled = session.savedPages > 0 && !busy
            ) {
                Icon(
                    Icons.Default.Check,
                    stringResource(R.string.done),
                    tint = if (session.savedPages > 0) Color.White else Color.White.copy(alpha = 0.3f)
                )
            }
        }

        if (busy) LoadingOverlay()
    }

    DisposableEffect(Unit) {
        onDispose {
            imageCapture.value = null
            analyzerExecutor.shutdown()
        }
    }
}

/**
 * Draws the page the analyser is currently looking at, so you can see what
 * will be cropped before pressing the shutter.
 */
@Composable
private fun PageOutline(frame: PageFinder.Frame?) {
    val quad = frame?.quad ?: return
    val frameWidth = frame.frameWidth.toFloat()
    val frameHeight = frame.frameHeight.toFloat()
    if (frameWidth <= 0f || frameHeight <= 0f) return

    val colour = Color(0xFF4ADE80)

    Canvas(Modifier.fillMaxSize()) {
        // PreviewView is FILL_CENTER: the stream is scaled up until it covers
        // the view and the overflow is cropped evenly on both sides. The overlay
        // has to repeat that exactly or the outline sits off the page.
        val scale = maxOf(size.width / frameWidth, size.height / frameHeight)
        val offsetX = (size.width - frameWidth * scale) / 2f
        val offsetY = (size.height - frameHeight * scale) / 2f

        val points = quad.points.map { point ->
            Offset(
                x = offsetX + point.x * frameWidth * scale,
                y = offsetY + point.y * frameHeight * scale
            )
        }

        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }

        drawPath(path, color = colour.copy(alpha = 0.3f))
        drawPath(
            path = path,
            color = colour,
            style = Stroke(width = 5.dp.toPx())
        )
        points.forEach { drawCircle(colour, radius = 7.dp.toPx(), center = it) }
    }
}

@Composable
private fun Pill(text: String) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

/**
 * Turns ID-card mode on or off. Deliberately a plain "ID" badge rather than a
 * Material icon — the extended icon pack this app depends on has no card
 * glyph, and a two-letter badge reads clearly enough at this size.
 */
@Composable
private fun IdCardToggle(active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Color(0xFF4ADE80) else Color.Black.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
            Text(
                text = "ID",
                color = if (active) Color.Black else Color.White,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun ShutterButton(enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(76.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.25f else 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(62.dp)) {
            Box(
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(if (enabled) Color.White else Color.Gray)
            )
        }
    }
}

private fun bindCamera(
    previewView: PreviewView,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    flashMode: Int,
    analyzerExecutor: ExecutorService,
    analyzer: ImageAnalysis.Analyzer,
    onReady: (ImageCapture) -> Unit
) {
    val context = previewView.context
    val providerFuture = ProcessCameraProvider.getInstance(context)
    providerFuture.addListener({
        val provider = providerFuture.get()

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val capture = ImageCapture.Builder()
            // Documents need detail far more than they need a fast shutter.
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            // Left to itself CameraX handed back about 1.5 megapixels, which is
            // roughly 130 dpi across a sheet of A4 — soft on small print and poor
            // material for OCR. Ask for about 8, which is around 290 dpi and past
            // the point where a scan gets visibly better.
            //
            // Deliberately not the sensor's maximum: a 50-megapixel frame decodes
            // to a 200 MB bitmap, and the rotate and the perspective warp each
            // want another copy of it. That is an out-of-memory crash on the way
            // to detail nobody can see.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(3264, 2448),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
            )
            .setFlashMode(flashMode)
            .build()
        val analysis = ImageAnalysis.Builder()
            // Only the newest frame is interesting; a queue of stale ones would
            // make the outline lag behind the page.
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            // Small and 4:3 on purpose: fast to analyse, and the same shape as
            // the capture, which is what lets the corners found here be reused
            // on the photograph.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .build()
            .also { it.setAnalyzer(analyzerExecutor, analyzer) }

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                capture,
                analysis
            )
            onReady(capture)
        }.onFailure {
            // Some cameras refuse preview + capture + analysis together. Losing
            // automatic detection is much better than losing the camera.
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    capture
                )
                onReady(capture)
            }
        }
    }, ContextCompat.getMainExecutor(context))
}

/**
 * Longest edge a captured page is decoded at.
 *
 * A backstop rather than a preference: the resolution selector already asks for
 * about 8 megapixels, but a device is free to hand back more, and the pipeline
 * that follows — rotate, then perspective warp — holds two or three copies of
 * the bitmap at once. 3400 pixels is roughly 290 dpi across A4, which is more
 * than a scan of printed text can use.
 */
private const val CAPTURE_MAX_EDGE = 3400

/**
 * CameraX hands back JPEG bytes in a single plane. Rotation is carried in
 * metadata rather than applied to the pixels, so it has to be applied here or
 * every landscape scan comes out sideways.
 */
private fun ImageProxy.toRotatedBitmap(): Bitmap? {
    val plane = planes.firstOrNull() ?: return null
    val buffer: ByteBuffer = plane.buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longest > 0 && longest / (sample * 2) >= CAPTURE_MAX_EDGE) sample *= 2

    val decoded = BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return null

    val degrees = imageInfo.rotationDegrees
    if (degrees == 0) return decoded

    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    val rotated = runCatching {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
    }.getOrNull() ?: return decoded
    if (rotated !== decoded) decoded.recycle()
    return rotated
}
