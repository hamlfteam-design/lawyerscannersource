package com.personal.docscanner.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.docscanner.DocScannerApp
import com.personal.docscanner.data.model.Crumb
import com.personal.docscanner.data.prefs.Settings
import com.personal.docscanner.data.model.DocumentSummary
import com.personal.docscanner.data.model.FolderSummary
import com.personal.docscanner.data.model.PdfPageSize
import com.personal.docscanner.data.model.SortMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as DocScannerApp).repository
    private val prefs = (app as DocScannerApp).prefs
    private val exporter = (app as DocScannerApp).caseExporter

    private val _folderId = MutableStateFlow<String?>(null)
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _crumbs = MutableStateFlow<List<Crumb>>(emptyList())
    val crumbs: StateFlow<List<Crumb>> = _crumbs.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _work = MutableStateFlow<WorkState?>(null)
    val work: StateFlow<WorkState?> = _work.asStateFlow()

    data class WorkState(val label: String, val progress: Float?)

    @OptIn(ExperimentalCoroutinesApi::class)
    val folders: StateFlow<List<FolderSummary>> = _folderId
        .flatMapLatest { repo.observeFolders(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every folder in the library, flat — the source list for the move/copy folder picker. */
    val allFolders: StateFlow<List<com.personal.docscanner.data.db.FolderEntity>> =
        repo.observeAllFolders()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val documents: StateFlow<List<DocumentSummary>> =
        combine(_folderId, _query, prefs.settings) { folderId, query, settings ->
            Triple(folderId, query, settings.sortMode)
        }.flatMapLatest { (folderId, query, sort) ->
            val source = if (query.isBlank()) {
                repo.observeDocuments(folderId)
            } else {
                repo.searchDocuments(query.trim())
            }
            source.map { list -> list.sortedWith(comparator(sort)) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val settings = prefs.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        Settings()
    )

    private fun comparator(sort: SortMode): Comparator<DocumentSummary> = when (sort) {
        SortMode.DATE_DESC -> compareByDescending { it.doc.updatedAt }
        SortMode.DATE_ASC -> compareBy { it.doc.updatedAt }
        SortMode.NAME -> compareBy { it.doc.title.lowercase() }
    }

    fun setFolder(folderId: String?) {
        if (_folderId.value == folderId && _crumbs.value.isNotEmpty()) return
        _folderId.value = folderId
        viewModelScope.launch { _crumbs.value = repo.breadcrumbs(folderId) }
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    // --------------------------------------------------------------- folders

    fun createFolder(name: String, colorArgb: Int) {
        viewModelScope.launch {
            repo.createFolder(name, _folderId.value, colorArgb)
        }
    }

    /**
     * The client → case shortcut: makes the client folder and its first case
     * folder in one step, and lands the user inside the case ready to scan.
     */
    fun createClientWithCase(clientName: String, caseName: String, colorArgb: Int, onReady: (String) -> Unit) {
        viewModelScope.launch {
            val clientId = repo.createFolder(clientName, _folderId.value, colorArgb)
            val target = if (caseName.isBlank()) {
                clientId
            } else {
                repo.createFolder(caseName, clientId, colorArgb)
            }
            onReady(target)
        }
    }

    fun renameFolder(id: String, name: String) {
        viewModelScope.launch { repo.renameFolder(id, name) }
    }

    fun setFolderLocked(id: String, locked: Boolean) {
        viewModelScope.launch { repo.setFolderLocked(id, locked) }
    }

    fun deleteFolder(id: String) {
        viewModelScope.launch { repo.deleteFolder(id) }
    }

    // ------------------------------------------------------------- documents

    fun deleteDocument(id: String) {
        viewModelScope.launch { repo.deleteDocument(id) }
    }

    fun moveDocument(id: String, folderId: String?) {
        viewModelScope.launch { repo.moveDocument(id, folderId) }
    }

    /** Copies a document in place — same folder, a second independent record. */
    fun duplicateDocument(id: String, folderId: String?) {
        viewModelScope.launch { repo.duplicateDocument(id, folderId) }
    }

    fun toggleFavorite(summary: DocumentSummary) {
        viewModelScope.launch { repo.setFavorite(summary.doc.id, !summary.doc.favorite) }
    }

    fun setSort(mode: SortMode) {
        viewModelScope.launch { prefs.setSortMode(mode) }
    }

    fun setGridView(grid: Boolean) {
        viewModelScope.launch { prefs.setGridView(grid) }
    }

    // --------------------------------------------------------------- export

    /** Mirrors this folder (or the whole library) onto the device as real folders. */
    fun exportTree(folderId: String?) {
        viewModelScope.launch {
            val settings = prefs.settings.first()
            _work.value = WorkState("", 0f)
            runCatching {
                exporter.exportTree(
                    rootFolderId = folderId,
                    pageSize = settings.pdfPageSize,
                    quality = settings.pdfQuality
                ) { progress ->
                    _work.value = WorkState(
                        label = progress.label,
                        progress = if (progress.total == 0) null
                        else progress.done.toFloat() / progress.total
                    )
                }
            }.onSuccess { result ->
                _message.value = buildString {
                    append("${result.exported} → ${result.destination}")
                    if (result.skipped.isNotEmpty()) {
                        append("\n(${result.skipped.size} skipped)")
                    }
                }
            }.onFailure { _message.value = it.message ?: "export failed" }
            _work.value = null
        }
    }

    /** One combined PDF for everything under this folder. */
    fun mergeFolder(folderId: String?) {
        viewModelScope.launch {
            val settings = prefs.settings.first()
            _work.value = WorkState("", 0f)
            runCatching {
                exporter.mergeFolder(
                    folderId = folderId,
                    pageSize = if (settings.pdfPageSize == PdfPageSize.FIT) PdfPageSize.A4
                    else settings.pdfPageSize,
                    quality = settings.pdfQuality
                ) { progress ->
                    _work.value = WorkState(
                        label = progress.label,
                        progress = if (progress.total == 0) null
                        else progress.done.toFloat() / progress.total
                    )
                }
            }.onSuccess { _message.value = it }
                .onFailure { _message.value = it.message ?: "merge failed" }
            _work.value = null
        }
    }

    suspend fun documentCount(folderId: String?): Int = exporter.countDocuments(folderId)

    fun consumeMessage() {
        _message.value = null
    }
}
