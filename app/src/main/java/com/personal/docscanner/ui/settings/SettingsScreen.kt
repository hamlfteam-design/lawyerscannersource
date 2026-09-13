package com.personal.docscanner.ui.settings

import android.app.Application
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.docscanner.DocScannerApp
import com.personal.docscanner.R
import com.personal.docscanner.data.model.PageFilter
import com.personal.docscanner.data.model.PdfPageSize
import com.personal.docscanner.data.prefs.Settings
import com.personal.docscanner.data.storage.StorageManager
import com.personal.docscanner.scan.TessDataManager
import com.personal.docscanner.ui.common.LoadingOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = (app as DocScannerApp).prefs
    private val repo = (app as DocScannerApp).repository
    private val tessData = (app as DocScannerApp).tessData

    val settings = prefs.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    suspend fun storageUsed(): Long = withContext(Dispatchers.IO) { repo.storageUsed() }

    fun installedOcrLanguages(): List<String> = tessData.installedLanguages()

    fun setAppLock(enabled: Boolean) = launch { prefs.setAppLock(enabled) }
    fun setDefaultFilter(filter: PageFilter) = launch { prefs.setDefaultFilter(filter) }
    fun setPdfPageSize(size: PdfPageSize) = launch { prefs.setPdfPageSize(size) }
    fun setPdfQuality(quality: Int) = launch { prefs.setPdfQuality(quality) }
    fun setOcrLanguages(langs: String) = launch { prefs.setOcrLanguages(langs) }
    fun setAutoCapture(enabled: Boolean) = launch { prefs.setAutoCapture(enabled) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenFields: () -> Unit,
    viewModel: SettingsViewModel = viewModel(),
    backupViewModel: BackupViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()
    var storageBytes by remember { mutableLongStateOf(0L) }

    val backupBusy by backupViewModel.busy.collectAsState()
    val backupProgress by backupViewModel.progress.collectAsState()
    val backupMessage by backupViewModel.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { storageBytes = viewModel.storageUsed() }

    // Backup results are long and worth reading, so they get a snackbar rather
    // than a toast that disappears mid-sentence.
    LaunchedEffect(backupMessage) {
        backupMessage?.let {
            snackbar.showSnackbar(it)
            backupViewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionTitle(stringResource(R.string.security))
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_lock))
                        Text(
                            text = stringResource(R.string.app_lock_desc),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.appLockEnabled,
                        onCheckedChange = viewModel::setAppLock
                    )
                }
            }

            SectionTitle(stringResource(R.string.scan_defaults))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.default_filter))
                    Text(
                        text = stringResource(R.string.auto_enhance_desc),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PageFilter.entries.forEach { filter ->
                            FilterChip(
                                selected = settings.defaultFilter == filter,
                                onClick = { viewModel.setDefaultFilter(filter) },
                                label = { Text(stringResource(filter.labelRes)) }
                            )
                        }
                    }
                }
            }

            SectionTitle("PDF")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.pdf_page_size))
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PdfPageSize.entries.forEach { size ->
                            FilterChip(
                                selected = settings.pdfPageSize == size,
                                onClick = { viewModel.setPdfPageSize(size) },
                                label = { Text(stringResource(size.labelRes)) }
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("${stringResource(R.string.pdf_quality)}: ${settings.pdfQuality}")
                    Slider(
                        value = settings.pdfQuality.toFloat(),
                        onValueChange = { viewModel.setPdfQuality(it.toInt()) },
                        valueRange = 40f..100f,
                        steps = 11
                    )
                }
            }

            SectionTitle(stringResource(R.string.ocr))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(stringResource(R.string.ocr_lang))
                    Spacer(Modifier.height(6.dp))
                    val installed = remember { viewModel.installedOcrLanguages() }
                    val selected = TessDataManager.parseLanguages(settings.ocrLanguages)
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TessDataManager.SUPPORTED.forEach { (code, label) ->
                            FilterChip(
                                selected = code in selected,
                                onClick = {
                                    // Tesseract takes several languages at once
                                    // ("ara+eng"), which is exactly right for
                                    // bilingual paperwork.
                                    val next = if (code in selected) {
                                        selected - code
                                    } else {
                                        selected + code
                                    }
                                    if (next.isNotEmpty()) {
                                        viewModel.setOcrLanguages(next.joinToString("+"))
                                    }
                                },
                                label = {
                                    Text(if (code in installed) label else "$label ↓")
                                }
                            )
                        }
                    }
                }
            }

            SectionTitle(stringResource(R.string.custom_fields))
            Card(onClick = onOpenFields, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.custom_fields))
                        Text(
                            text = stringResource(R.string.custom_fields_desc),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }

            SectionTitle(stringResource(R.string.backup))
            BackupSection(viewModel = backupViewModel)

            SectionTitle(stringResource(R.string.storage))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(
                            R.string.storage_used,
                            StorageManager.humanSize(storageBytes)
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = stringResource(R.string.storage_desc),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    Text(
                        text = "${stringResource(R.string.export_folder)}: " +
                            "Documents/${StorageManager.EXPORT_DIR}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.about_desc),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }

        backupBusy?.let { state ->
            LoadingOverlay(
                label = when (state) {
                    BackupViewModel.BACKING_UP -> stringResource(R.string.backup_running)
                    BackupViewModel.RESTORING -> stringResource(R.string.restore_running)
                    else -> null
                },
                progress = backupProgress
            )
        }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 10.dp)
    )
}
