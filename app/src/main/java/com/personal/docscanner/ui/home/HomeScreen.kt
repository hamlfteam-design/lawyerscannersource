package com.personal.docscanner.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MergeType
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.personal.docscanner.R
import com.personal.docscanner.data.model.DocumentSummary
import com.personal.docscanner.data.model.FolderSummary
import com.personal.docscanner.data.model.SortMode
import com.personal.docscanner.ui.common.ColorPickerRow
import com.personal.docscanner.ui.common.ConfirmDialog
import com.personal.docscanner.ui.common.Biometrics
import com.personal.docscanner.ui.common.EmptyState
import com.personal.docscanner.ui.common.FolderLockViewModel
import com.personal.docscanner.ui.common.LoadingOverlay
import com.personal.docscanner.ui.common.TextPromptDialog
import com.personal.docscanner.ui.importer.ImportDialog
import com.personal.docscanner.ui.importer.ImportViewModel
import com.personal.docscanner.ui.importer.rememberImportViewModel
import com.personal.docscanner.ui.common.rememberActivityViewModel
import com.personal.docscanner.ui.theme.FolderColors
import com.personal.docscanner.ui.theme.findActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    folderId: String?,
    onOpenFolder: (String) -> Unit,
    onOpenDocument: (String) -> Unit,
    onScan: () -> Unit,
    onOpenFields: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenIndex: () -> Unit,
    onBack: () -> Unit,
    canGoBack: Boolean,
    viewModel: HomeViewModel = viewModel(),
    importViewModel: ImportViewModel = rememberImportViewModel(),
    folderLocks: FolderLockViewModel = rememberActivityViewModel()
) {
    val activity = LocalContext.current.findActivity() as? FragmentActivity
    val lockTitle = stringResource(R.string.unlock)
    val lockSubtitle = stringResource(R.string.unlock_prompt)

    /**
     * Opens a folder, asking for identity first when it is locked and has not
     * already been opened since the app was foregrounded.
     */
    fun openFolder(summary: FolderSummary) {
        val id = summary.folder.id
        val needsAuth = summary.folder.locked && !folderLocks.isUnlocked(id)
        when {
            !needsAuth -> onOpenFolder(id)
            activity == null -> onOpenFolder(id)
            else -> Biometrics.prompt(activity, lockTitle, lockSubtitle) {
                folderLocks.markUnlocked(id)
                onOpenFolder(id)
            }
        }
    }

    LaunchedEffect(folderId) { viewModel.setFolder(folderId) }

    val folders by viewModel.folders.collectAsState()
    val documents by viewModel.documents.collectAsState()
    val crumbs by viewModel.crumbs.collectAsState()
    val query by viewModel.query.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val message by viewModel.message.collectAsState()
    val work by viewModel.work.collectAsState()

    val importBusy by importViewModel.busy.collectAsState()
    val importProgress by importViewModel.progress.collectAsState()
    val importLabel by importViewModel.label.collectAsState()
    val importMessage by importViewModel.message.collectAsState()

    val snackbar = remember { SnackbarHostState() }
    var showImport by remember { mutableStateOf(false) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showNewClient by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FolderSummary?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<FolderSummary?>(null) }
    var deleteDocTarget by remember { mutableStateOf<DocumentSummary?>(null) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(importMessage) {
        importMessage?.let {
            snackbar.showSnackbar(it)
            importViewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = crumbs.lastOrNull()?.name?.takeIf { it != "/" }
                                ?: stringResource(R.string.home_title),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (crumbs.size > 2) {
                            Text(
                                text = crumbs.dropLast(1).joinToString(" / ") { it.name }
                                    .replace("/ /", "/"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, stringResource(R.string.search_hint))
                    }
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.more))
                    }
                    HomeOverflowMenu(
                        expanded = overflowOpen,
                        onDismiss = { overflowOpen = false },
                        sortMode = settings.sortMode,
                        onSort = viewModel::setSort,
                        onExportTree = { viewModel.exportTree(folderId) },
                        onMerge = { viewModel.mergeFolder(folderId) },
                        onFields = onOpenFields,
                        onSettings = onOpenSettings,
                        onIndex = onOpenIndex
                    )
                }
            )
        },
        floatingActionButton = {
            // Scanning is the constant action, so it keeps the big button.
            // Everything that *creates* something is one tap away above it,
            // rather than buried among nine unrelated entries in the menu.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                SmallFloatingActionButton(
                    onClick = { showCreateSheet = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.Add, stringResource(R.string.create))
                }
                Spacer(Modifier.height(12.dp))
                ExtendedFloatingActionButton(
                    onClick = onScan,
                    icon = { Icon(Icons.Default.PhotoCamera, null) },
                    text = { Text(stringResource(R.string.scan)) }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (showSearch) {
                    item {
                        OutlinedTextField(
                            value = query,
                            onValueChange = viewModel::setQuery,
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                }

                if (folders.isNotEmpty() && query.isBlank()) {
                    item {
                        SectionLabel(stringResource(R.string.folders), folders.size)
                    }
                    items(folders, key = { it.folder.id }) { summary ->
                        FolderRow(
                            summary = summary,
                            onOpen = { openFolder(summary) },
                            onToggleLock = {
                                viewModel.setFolderLocked(
                                    summary.folder.id,
                                    !summary.folder.locked
                                )
                            },
                            onRename = { renameTarget = summary },
                            onDelete = { deleteFolderTarget = summary },
                            onExport = { viewModel.exportTree(summary.folder.id) },
                            onMerge = { viewModel.mergeFolder(summary.folder.id) }
                        )
                    }
                }

                if (documents.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(4.dp))
                        SectionLabel(stringResource(R.string.documents), documents.size)
                    }
                    items(documents, key = { it.doc.id }) { summary ->
                        DocumentRow(
                            summary = summary,
                            onOpen = { onOpenDocument(summary.doc.id) },
                            onToggleFavorite = { viewModel.toggleFavorite(summary) },
                            onDelete = { deleteDocTarget = summary }
                        )
                    }
                }

                if (folders.isEmpty() && documents.isEmpty()) {
                    item {
                        Spacer(Modifier.height(40.dp))
                        EmptyState(
                            message = when {
                                query.isNotBlank() -> stringResource(R.string.ocr_empty)
                                folderId == null -> stringResource(R.string.empty_library)
                                else -> stringResource(R.string.empty_folder)
                            },
                            icon = when {
                                query.isNotBlank() -> Icons.Default.Search
                                else -> Icons.Default.DocumentScanner
                            },
                            hint = if (query.isBlank()) stringResource(R.string.empty_hint) else null,
                            modifier = Modifier.height(400.dp),
                            action = if (query.isBlank()) {
                                {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Button(onClick = onScan) {
                                            Icon(Icons.Default.PhotoCamera, null)
                                            Spacer(Modifier.width(8.dp))
                                            Text(stringResource(R.string.scan))
                                        }
                                        Spacer(Modifier.height(10.dp))
                                        TextButton(onClick = { showCreateSheet = true }) {
                                            Icon(Icons.Default.Add, null)
                                            Spacer(Modifier.width(6.dp))
                                            Text(stringResource(R.string.create))
                                        }
                                    }
                                }
                            } else {
                                null
                            }
                        )
                    }
                }
            }

            work?.let { state ->
                LoadingOverlay(
                    label = state.label.ifEmpty { stringResource(R.string.export) },
                    progress = state.progress
                )
            }

            if (importBusy) {
                // The label carries the file being read, so a long import shows
                // which document it is on rather than a bare spinner.
                LoadingOverlay(
                    label = importLabel.ifEmpty { stringResource(R.string.import_running) },
                    progress = importProgress
                )
            }
        }
    }

    if (showCreateSheet) {
        CreateSheet(
            onNewClient = { showNewClient = true },
            onNewFolder = { showNewFolder = true },
            onImport = { showImport = true },
            onScan = onScan,
            onDismiss = { showCreateSheet = false }
        )
    }

    if (showImport) {
        ImportDialog(
            folderId = folderId,
            viewModel = importViewModel,
            onDismiss = { showImport = false }
        )
    }

    if (showNewFolder) {
        NewFolderDialog(
            onCreate = { name, color -> viewModel.createFolder(name, color) },
            onDismiss = { showNewFolder = false }
        )
    }

    if (showNewClient) {
        NewClientDialog(
            onCreate = { client, case, color ->
                viewModel.createClientWithCase(client, case, color) { targetFolderId ->
                    onOpenFolder(targetFolderId)
                }
            },
            onDismiss = { showNewClient = false }
        )
    }

    renameTarget?.let { target ->
        TextPromptDialog(
            title = stringResource(R.string.rename),
            label = stringResource(R.string.folder_name),
            initialValue = target.folder.name,
            onConfirm = { viewModel.renameFolder(target.folder.id, it) },
            onDismiss = { renameTarget = null }
        )
    }

    deleteFolderTarget?.let { target ->
        ConfirmDialog(
            title = target.folder.name,
            message = stringResource(R.string.confirm_delete_folder),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = { viewModel.deleteFolder(target.folder.id) },
            onDismiss = { deleteFolderTarget = null }
        )
    }

    deleteDocTarget?.let { target ->
        ConfirmDialog(
            title = target.doc.title,
            message = stringResource(R.string.confirm_delete_doc),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = { viewModel.deleteDocument(target.doc.id) },
            onDismiss = { deleteDocTarget = null }
        )
    }
}

@Composable
private fun SectionLabel(text: String, count: Int) {
    Text(
        text = "$text · $count",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderRow(
    summary: FolderSummary,
    onOpen: () -> Unit,
    onToggleLock: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onMerge: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(summary.folder.colorArgb).copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (summary.folder.locked) {
                        Icons.Default.Lock
                    } else {
                        Icons.Default.Folder
                    },
                    contentDescription = null,
                    tint = Color(summary.folder.colorArgb)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = summary.folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val parts = buildList {
                    add(dateFormat.format(Date(summary.folder.createdAt)))
                    if (summary.subfolderCount > 0) {
                        add(stringResource(R.string.folder_count, summary.subfolderCount))
                    }
                    add(stringResource(R.string.doc_count, summary.docCount))
                }
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (summary.folder.locked) {
                                        R.string.unlock_folder
                                    } else {
                                        R.string.lock_folder
                                    }
                                )
                            )
                        },
                        leadingIcon = {
                            Icon(
                                if (summary.folder.locked) {
                                    Icons.Default.LockOpen
                                } else {
                                    Icons.Default.Lock
                                },
                                null
                            )
                        },
                        onClick = { menuOpen = false; onToggleLock() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.rename)) },
                        leadingIcon = { Icon(Icons.Default.Edit, null) },
                        onClick = { menuOpen = false; onRename() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_tree)) },
                        leadingIcon = { Icon(Icons.Default.Upload, null) },
                        onClick = { menuOpen = false; onExport() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.merge_pdf)) },
                        leadingIcon = { Icon(Icons.Default.MergeType, null) },
                        onClick = { menuOpen = false; onMerge() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentRow(
    summary: DocumentSummary,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        onClick = onOpen,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (summary.thumbPath != null) {
                    AsyncImage(
                        model = summary.thumbPath,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = summary.doc.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.pages_count, summary.pageCount) +
                        " · " + dateFormat.format(Date(summary.doc.updatedAt)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // The saved fields are the point of the app, so show the filled
                // ones right on the row instead of hiding them behind a tap.
                if (summary.fields.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(summary.fields.take(4)) { entry ->
                            FieldChip("${entry.def.name}: ${entry.display()}")
                        }
                    }
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (summary.doc.favorite) Icons.Default.Star
                    else Icons.Default.StarBorder,
                    contentDescription = stringResource(R.string.favorite),
                    tint = if (summary.doc.favorite) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Default.Delete, null) },
                        onClick = { menuOpen = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun FieldChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun HomeOverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    sortMode: SortMode,
    onSort: (SortMode) -> Unit,
    onExportTree: () -> Unit,
    onMerge: () -> Unit,
    onFields: () -> Unit,
    onSettings: () -> Unit,
    onIndex: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.index)) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
            onClick = { onDismiss(); onIndex() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.export_tree)) },
            leadingIcon = { Icon(Icons.Default.Upload, null) },
            onClick = { onDismiss(); onExportTree() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.merge_pdf)) },
            leadingIcon = { Icon(Icons.Default.MergeType, null) },
            onClick = { onDismiss(); onMerge() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.custom_fields)) },
            leadingIcon = { Icon(Icons.Default.Tune, null) },
            onClick = { onDismiss(); onFields() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.settings)) },
            leadingIcon = { Icon(Icons.Default.Settings, null) },
            onClick = { onDismiss(); onSettings() }
        )
        // The sort options are a different kind of thing from the actions above
        // them, and running the two together as one list of nine is what made
        // this menu unreadable.
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text(
            text = stringResource(R.string.sort_by),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        SortMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(mode.labelRes),
                        fontWeight = if (mode == sortMode) FontWeight.Bold else FontWeight.Normal
                    )
                },
                trailingIcon = {
                    if (mode == sortMode) {
                        Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                    }
                },
                onClick = { onDismiss(); onSort(mode) }
            )
        }
    }
}

/**
 * Everything that creates something, in one sheet.
 *
 * These were four entries inside a nine-item overflow menu, mixed in with
 * exports and settings and sort order. Creating a client is a different kind of
 * act from changing how a list is sorted, and burying it made the common case —
 * "new client, new case, start scanning" — feel like an administrative errand.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateSheet(
    onNewClient: () -> Unit,
    onNewFolder: () -> Unit,
    onImport: () -> Unit,
    onScan: () -> Unit,
    onDismiss: () -> Unit
) {
    val state = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state) {
        Text(
            text = stringResource(R.string.create),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
        )
        SheetAction(
            icon = Icons.Default.PersonAdd,
            title = stringResource(R.string.new_client),
            subtitle = stringResource(R.string.new_client_desc),
            tint = MaterialTheme.colorScheme.primary
        ) { onDismiss(); onNewClient() }
        SheetAction(
            icon = Icons.Default.CreateNewFolder,
            title = stringResource(R.string.new_folder),
            subtitle = stringResource(R.string.new_folder_desc),
            tint = MaterialTheme.colorScheme.secondary
        ) { onDismiss(); onNewFolder() }
        SheetAction(
            icon = Icons.Default.PhotoCamera,
            title = stringResource(R.string.scan),
            subtitle = stringResource(R.string.scan_desc),
            tint = MaterialTheme.colorScheme.primary
        ) { onDismiss(); onScan() }
        SheetAction(
            icon = Icons.Default.FileDownload,
            title = stringResource(R.string.import_documents),
            subtitle = stringResource(R.string.import_desc_short),
            tint = MaterialTheme.colorScheme.tertiary
        ) { onDismiss(); onImport() }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SheetAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    tint: Color,
    onClick: () -> Unit
) {
    ListItem(
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = tint)
            }
        },
        headlineContent = { Text(title, style = MaterialTheme.typography.titleSmall) },
        supportingContent = {
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun NewFolderDialog(
    onCreate: (String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var color by remember { mutableStateOf(FolderColors.first().toArgb()) }
    TextPromptDialog(
        title = stringResource(R.string.new_folder),
        label = stringResource(R.string.folder_name),
        onConfirm = { onCreate(it, color) },
        onDismiss = onDismiss,
        extraContent = {
            ColorPickerRow(selected = color, onSelect = { color = it })
        }
    )
}

/**
 * The client → case shortcut. Creating both levels in one dialog matches how the
 * material actually arrives: a new client always turns up with a first matter.
 */
@Composable
private fun NewClientDialog(
    onCreate: (String, String, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var client by remember { mutableStateOf("") }
    var case by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(FolderColors.first().toArgb()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_client)) },
        text = {
            Column {
                OutlinedTextField(
                    value = client,
                    onValueChange = { client = it },
                    label = { Text(stringResource(R.string.client_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = case,
                    onValueChange = { case = it },
                    label = { Text(stringResource(R.string.case_name) + " " + stringResource(R.string.case_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                ColorPickerRow(selected = color, onSelect = { color = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(client.trim(), case.trim(), color); onDismiss() },
                enabled = client.isNotBlank()
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
