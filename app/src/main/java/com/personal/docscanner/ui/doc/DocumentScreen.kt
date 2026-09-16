package com.personal.docscanner.ui.doc

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.personal.docscanner.R
import com.personal.docscanner.data.db.PageEntity
import com.personal.docscanner.data.model.FieldEntry
import com.personal.docscanner.data.model.FieldType
import com.personal.docscanner.data.model.PageFilter
import com.personal.docscanner.scan.PdfCompressor
import com.personal.docscanner.ui.common.ConfirmDialog
import com.personal.docscanner.ui.common.LoadingOverlay
import com.personal.docscanner.ui.common.ShareHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentScreen(
    documentId: String,
    onBack: () -> Unit,
    onAddPage: (String?) -> Unit,
    onEditPage: (String) -> Unit,
    onDeleted: () -> Unit,
    viewModel: DocumentViewModel = viewModel()
) {
    LaunchedEffect(documentId) { viewModel.load(documentId) }

    val context = LocalContext.current
    val detail by viewModel.detail.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val message by viewModel.message.collectAsState()
    val shareFile by viewModel.shareFile.collectAsState()
    val missingLangs by viewModel.ocrMissingLangs.collectAsState()
    val summary by viewModel.summary.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    var menuOpen by remember { mutableStateOf(false) }
    var showCompress by remember { mutableStateOf(false) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var showShare by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var showReadingMode by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // A file becomes available only after an explicit share/compress action, so
    // opening the sheet the moment one lands is what the user is waiting for.
    LaunchedEffect(shareFile) {
        if (shareFile != null) showShare = true
    }

    val doc = detail
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = doc?.doc?.title ?: stringResource(R.string.doc_untitled),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.prepareForSharing() }) {
                        Icon(Icons.Default.Share, stringResource(R.string.share))
                    }
                    IconButton(onClick = { showCompress = true }) {
                        Icon(Icons.Default.Compress, stringResource(R.string.compress))
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.more))
                    }
                    DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_pdf)) },
                            leadingIcon = { Icon(Icons.Default.PictureAsPdf, null) },
                            onClick = { menuOpen = false; viewModel.exportPdf() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_jpg)) },
                            onClick = { menuOpen = false; viewModel.exportImages() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ocr)) },
                            leadingIcon = { Icon(Icons.Default.TextFields, null) },
                            onClick = { menuOpen = false; viewModel.runOcr() }
                        )
                        if (!doc?.doc?.ocrText.isNullOrBlank()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.export_text)) },
                                onClick = { menuOpen = false; viewModel.exportText() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.summarize)) },
                                onClick = { menuOpen = false; viewModel.summarize() }
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete)) },
                            leadingIcon = { Icon(Icons.Default.Delete, null) },
                            onClick = { menuOpen = false; showDelete = true }
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (doc == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }

        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    var title by remember(doc.doc.id) { mutableStateOf(doc.doc.title) }
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(stringResource(R.string.doc_title)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LaunchedEffect(title) {
                        // Debounce so every keystroke is not a database write.
                        kotlinx.coroutines.delay(500)
                        if (title.isNotBlank() && title != doc.doc.title) viewModel.setTitle(title)
                    }
                }

                // ---- pages ----
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.pages) + " · ${doc.pages.size}",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onAddPage(doc.doc.folderId) }) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(4.dp))
                            Text(stringResource(R.string.add_page))
                        }
                    }
                }

                item {
                    ReorderablePagesRow(
                        pages = doc.pages,
                        thumbPath = viewModel::pageThumbPath,
                        onOpen = { index -> viewerIndex = index },
                        onEditCrop = onEditPage,
                        onRotate = { pageId -> viewModel.rotatePage(pageId, 90) },
                        onDelete = viewModel::deletePage,
                        onMoveUp = { pageId -> viewModel.movePage(pageId, true) },
                        onMoveDown = { pageId -> viewModel.movePage(pageId, false) },
                        onReorder = viewModel::reorderPages
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.apply_to_all),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PageFilter.entries.forEach { filter ->
                            AssistChip(
                                onClick = { viewModel.applyFilterToAll(filter) },
                                label = { Text(stringResource(filter.labelRes)) }
                            )
                        }
                    }
                }

                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }

                // ---- the save fields ----
                item {
                    Text(
                        text = stringResource(R.string.details),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                items(doc.fields, key = { it.def.id }) { entry ->
                    FieldEditor(
                        entry = entry,
                        onChange = { viewModel.setField(entry.def.id, it) }
                    )
                }

                item {
                    var tags by remember(doc.doc.id) { mutableStateOf(doc.doc.tags) }
                    OutlinedTextField(
                        value = tags,
                        onValueChange = { tags = it },
                        label = { Text(stringResource(R.string.tags)) },
                        placeholder = { Text(stringResource(R.string.tags_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LaunchedEffect(tags) {
                        kotlinx.coroutines.delay(500)
                        if (tags != doc.doc.tags) viewModel.setTags(tags)
                    }
                }

                item {
                    var note by remember(doc.doc.id) { mutableStateOf(doc.doc.note) }
                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it },
                        label = { Text(stringResource(R.string.note)) },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LaunchedEffect(note) {
                        kotlinx.coroutines.delay(600)
                        if (note != doc.doc.note) viewModel.setNote(note)
                    }
                }

                if (doc.doc.ocrText.isNotBlank()) {
                    item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.ocr),
                                style = MaterialTheme.typography.titleMedium
                            )
                            TextButton(onClick = { showReadingMode = true }) {
                                Text(stringResource(R.string.reading_mode))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showReadingMode = true }
                        ) {
                            Text(
                                text = doc.doc.ocrText,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.updated_at) + ": " +
                            dateTimeFormat.format(Date(doc.doc.updatedAt)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(48.dp))
                }
            }

            busy?.let { label ->
                LoadingOverlay(
                    label = when (label) {
                        DocumentViewModel.COMPRESSING -> stringResource(R.string.compress_running)
                        DocumentViewModel.OCR -> stringResource(R.string.ocr_running)
                        DocumentViewModel.DOWNLOADING -> stringResource(R.string.ocr_downloading)
                        DocumentViewModel.SUMMARIZE -> stringResource(R.string.summarize_running)
                        else -> stringResource(R.string.export)
                    },
                    progress = progress
                )
            }
        }
    }

    viewerIndex?.let { start ->
        PageViewer(
            pages = doc?.pages.orEmpty(),
            startIndex = start,
            pathOf = { viewModel.pageImagePath(it) },
            onEditCrop = { pageId -> viewerIndex = null; onEditPage(pageId) },
            onRotate = { pageId -> viewModel.rotatePage(pageId, 90) },
            onDelete = { pageId ->
                viewModel.deletePage(pageId)
                // Closing is the honest response to deleting what was on screen.
                viewerIndex = null
            },
            onDismiss = { viewerIndex = null }
        )
    }

    if (showCompress) {
        CompressDialog(
            onPick = { bytes -> viewModel.compressTo(bytes) },
            onDismiss = { showCompress = false }
        )
    }

    if (showShare) {
        val file = shareFile
        ShareDialog(
            hasWhatsApp = ShareHelper.isWhatsAppInstalled(context),
            onWhatsApp = {
                file?.let {
                    if (!ShareHelper.sendToWhatsApp(context, it)) {
                        ShareHelper.share(context, listOf(it))
                    }
                }
            },
            onOther = { file?.let { ShareHelper.share(context, listOf(it)) } },
            onDismiss = {
                showShare = false
                viewModel.consumeShareFile()
            }
        )
    }

    if (missingLangs.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::dismissOcrPrompt,
            title = { Text(stringResource(R.string.ocr)) },
            text = { Text(stringResource(R.string.ocr_lang_missing)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.dismissOcrPrompt()
                    viewModel.downloadOcrLanguages()
                }) { Text(stringResource(R.string.ocr_download)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissOcrPrompt) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showReadingMode && doc != null) {
        ReadingModeScreen(text = doc.doc.ocrText, onDismiss = { showReadingMode = false })
    }

    summary?.let { state ->
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        AlertDialog(
            onDismissRequest = viewModel::dismissSummary,
            title = { Text(stringResource(R.string.summarize)) },
            text = {
                Text(
                    when (state) {
                        is DocumentViewModel.SummaryState.Done -> state.text
                        DocumentViewModel.SummaryState.NeedsOcr -> stringResource(R.string.summarize_needs_ocr)
                        DocumentViewModel.SummaryState.Empty -> stringResource(R.string.summarize_empty)
                    }
                )
            },
            confirmButton = {
                if (state is DocumentViewModel.SummaryState.Done) {
                    TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.text))
                        viewModel.dismissSummary()
                    }) { Text(stringResource(R.string.summarize_copy)) }
                } else {
                    TextButton(onClick = viewModel::dismissSummary) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            }
        )
    }

    if (showDelete) {
        ConfirmDialog(
            title = doc?.doc?.title.orEmpty(),
            message = stringResource(R.string.confirm_delete_doc),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = { viewModel.deleteDocument(onDeleted) },
            onDismiss = { showDelete = false }
        )
    }
}

/**
 * Renders the right control for a field's declared type. This is the payoff of
 * storing values as text with a type tag: one editor covers every field the
 * user will ever define, without a schema change.
 */
@Composable
private fun FieldEditor(entry: FieldEntry, onChange: (String) -> Unit) {
    var local by remember(entry.def.id, entry.value) { mutableStateOf(entry.value) }

    when (entry.type) {
        FieldType.BOOL -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = local == "1",
                    onCheckedChange = {
                        local = if (it) "1" else "0"
                        onChange(local)
                    }
                )
                Text(entry.def.name, style = MaterialTheme.typography.bodyLarge)
            }
        }

        FieldType.SELECT -> {
            Column {
                Text(
                    text = entry.def.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    entry.options.forEach { option ->
                        FilterChip(
                            selected = local == option,
                            onClick = {
                                // Tapping the selected chip clears it, so a
                                // mis-tap does not need a separate "none" chip.
                                local = if (local == option) "" else option
                                onChange(local)
                            },
                            label = { Text(option) }
                        )
                    }
                }
            }
        }

        FieldType.DATE -> {
            val display = entry.dateValue?.let { FieldEntry.dateFormat.format(Date(it)) }.orEmpty()
            var text by remember(entry.def.id, entry.value) { mutableStateOf(display) }
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    text = raw
                    // Accept a typed yyyy-MM-dd; anything else is left alone
                    // until it parses, so partial typing is not destroyed.
                    parseDate(raw)?.let { onChange(it.toString()) }
                    if (raw.isEmpty()) onChange("")
                },
                label = { Text(entry.def.name + REQUIRED_MARK.takeIf { entry.def.required }.orEmpty()) },
                placeholder = { Text("yyyy-MM-dd") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        FieldType.NUMBER, FieldType.TEXT -> {
            OutlinedTextField(
                value = local,
                onValueChange = { local = it },
                label = { Text(entry.def.name + REQUIRED_MARK.takeIf { entry.def.required }.orEmpty()) },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (entry.type == FieldType.NUMBER) {
                        KeyboardType.Number
                    } else {
                        KeyboardType.Text
                    }
                ),
                modifier = Modifier.fillMaxWidth()
            )
            LaunchedEffect(local) {
                kotlinx.coroutines.delay(500)
                if (local != entry.value) onChange(local)
            }
        }
    }
}

private val PAGE_THUMB_WIDTH = 132.dp
private val PAGE_THUMB_HEIGHT = 176.dp
private val PAGE_THUMB_SPACING = 10.dp

/**
 * The page strip, reorderable by a long-press-and-drag on any thumbnail —
 * the same gesture CamScanner uses for its page list, and the one thing our
 * own reordering (until now, one-step "move earlier"/"move later" menu
 * entries only) was missing.
 *
 * The drag is tracked as a local id order, separate from [pages] itself, so
 * dragging feels instant instead of waiting on a database round trip for
 * every slot crossed. [onReorder] is only called once, when the finger
 * lifts — that is the one write, not one per slot.
 */
@Composable
private fun ReorderablePagesRow(
    pages: List<PageEntity>,
    thumbPath: (PageEntity) -> String,
    onOpen: (Int) -> Unit,
    onEditCrop: (String) -> Unit,
    onRotate: (String) -> Unit,
    onDelete: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onReorder: (List<String>) -> Unit
) {
    val pageIds = remember(pages) { pages.map { it.id } }
    // Resets to the real order whenever the document's own page list changes
    // from outside a drag (an add, a delete, or the write from a previous
    // drag finally landing).
    var order by remember(pageIds) { mutableStateOf(pageIds) }
    val byId = remember(pages) { pages.associateBy { it.id } }

    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffsetX by remember { mutableFloatStateOf(0f) }

    val density = LocalDensity.current
    val slotWidthPx = with(density) { (PAGE_THUMB_WIDTH + PAGE_THUMB_SPACING).toPx() }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(PAGE_THUMB_SPACING)) {
        itemsIndexed(order, key = { _, id -> id }) { index, id ->
            val page = byId[id] ?: return@itemsIndexed
            val isDragging = id == draggingId
            Box(
                modifier = Modifier
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationX = if (isDragging) dragOffsetX else 0f
                        shadowElevation = if (isDragging) 8f else 0f
                    }
                    .pointerInput(id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { draggingId = id; dragOffsetX = 0f },
                            onDragEnd = {
                                draggingId = null
                                dragOffsetX = 0f
                                if (order != pageIds) onReorder(order)
                            },
                            onDragCancel = { draggingId = null; dragOffsetX = 0f },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetX += dragAmount.x
                                val currentIndex = order.indexOf(id)
                                val slotsMoved = (dragOffsetX / slotWidthPx).roundToInt()
                                val targetIndex = (currentIndex + slotsMoved)
                                    .coerceIn(0, order.lastIndex)
                                if (targetIndex != currentIndex) {
                                    order = order.toMutableList().apply {
                                        add(targetIndex, removeAt(currentIndex))
                                    }
                                    // Keep the finger's own offset continuous — only
                                    // the part spent crossing into the new slot is
                                    // consumed, so the thumbnail does not jump.
                                    dragOffsetX -= (targetIndex - currentIndex) * slotWidthPx
                                }
                            }
                        )
                    }
            ) {
                PageThumb(
                    path = thumbPath(page),
                    index = index + 1,
                    onOpen = { onOpen(order.indexOf(id)) },
                    onEditCrop = { onEditCrop(id) },
                    onRotate = { onRotate(id) },
                    onDelete = { onDelete(id) },
                    onMoveUp = { onMoveUp(id) },
                    onMoveDown = { onMoveDown(id) }
                )
            }
        }
    }
}

@Composable
private fun PageThumb(
    path: String,
    index: Int,
    onOpen: () -> Unit,
    onEditCrop: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(width = PAGE_THUMB_WIDTH, height = PAGE_THUMB_HEIGHT)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onOpen)
        ) {
            AsyncImage(
                model = path,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = { menuOpen = true },
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(Icons.Default.MoreVert, null, tint = MaterialTheme.colorScheme.onSurface)
            }
            DropdownMenu(menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.edit_crop)) },
                    leadingIcon = { Icon(Icons.Default.Crop, null) },
                    onClick = { menuOpen = false; onEditCrop() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rotate_right)) },
                    leadingIcon = { Icon(Icons.Default.RotateRight, null) },
                    onClick = { menuOpen = false; onRotate() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.move_earlier)) },
                    leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                    onClick = { menuOpen = false; onMoveUp() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.move_later)) },
                    leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                    onClick = { menuOpen = false; onMoveDown() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete_page)) },
                    leadingIcon = { Icon(Icons.Default.Delete, null) },
                    onClick = { menuOpen = false; onDelete() }
                )
            }
        }
        Text("$index", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun CompressDialog(onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.compress_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.compress_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                PdfCompressor.Preset.entries.forEach { preset ->
                    OutlinedButton(
                        onClick = { onPick(preset.bytes); onDismiss() },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) { Text(preset.labelAr) }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = custom,
                    onValueChange = { custom = it.filter(Char::isDigit) },
                    label = { Text(stringResource(R.string.compress_custom)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    custom.toLongOrNull()?.let { onPick(it * 1024) }
                    onDismiss()
                },
                enabled = custom.toLongOrNull()?.let { it > 0 } == true
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun ShareDialog(
    hasWhatsApp: Boolean,
    onWhatsApp: () -> Unit,
    onOther: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share)) },
        text = {
            Column {
                if (hasWhatsApp) {
                    Button(
                        onClick = { onWhatsApp(); onDismiss() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.send_whatsapp)) }
                    Spacer(Modifier.height(8.dp))
                } else {
                    Text(
                        text = stringResource(R.string.whatsapp_missing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(
                    onClick = { onOther(); onDismiss() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.share_other)) }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private fun parseDate(raw: String): Long? = runCatching {
    val parsed = FieldEntry.dateFormat.parse(raw) ?: return null
    Calendar.getInstance().apply { time = parsed }.timeInMillis
}.getOrNull()

private const val REQUIRED_MARK = " *"
private val dateTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
