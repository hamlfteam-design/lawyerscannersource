package com.personal.docscanner.ui.index

import android.app.Application
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.docscanner.DocScannerApp
import com.personal.docscanner.R
import com.personal.docscanner.data.db.FolderEntity
import com.personal.docscanner.data.model.DocumentSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/** How the flat list of documents is bucketed on screen. */
enum class IndexMode(val labelRes: Int) {
    FOLDER(R.string.index_by_folder),
    FIELD(R.string.index_by_field),
    TAG(R.string.index_by_tag)
}

class IndexViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as DocScannerApp).repository

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _mode = MutableStateFlow(IndexMode.FOLDER)
    val mode: StateFlow<IndexMode> = _mode.asStateFlow()

    private val folders = repo.observeAllFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    data class Group(val heading: String, val documents: List<DocumentSummary>)

    @OptIn(ExperimentalCoroutinesApi::class)
    val groups: StateFlow<List<Group>> =
        combine(_query, _mode) { query, mode -> query to mode }
            .flatMapLatest { (query, mode) ->
                // An empty query becomes LIKE '%%', which matches every row —
                // so the same query powers both "browse all" and "search".
                val source = repo.searchDocuments(query.trim())
                combine(source, folders) { docs, folderList ->
                    group(docs, folderList, mode)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setMode(mode: IndexMode) {
        _mode.value = mode
    }

    /**
     * Buckets documents for display.
     *
     * FOLDER uses the full client → case path as the heading, which is what
     * makes the index read like a case list rather than a flat pile.
     * FIELD and TAG deliberately let one document appear under several
     * headings — a document with three filled fields is findable under all
     * three, which is the entire point of an index.
     */
    private fun group(
        docs: List<DocumentSummary>,
        folderList: List<FolderEntity>,
        mode: IndexMode
    ): List<Group> {
        val byId = folderList.associateBy { it.id }

        fun pathOf(folderId: String?): String {
            if (folderId == null) return UNFILED
            val parts = ArrayList<String>()
            var current = folderId
            var guard = 0
            while (current != null && guard++ < 64) {
                val folder = byId[current] ?: break
                parts.add(0, folder.name)
                current = folder.parentId
            }
            return if (parts.isEmpty()) UNFILED else parts.joinToString(" / ")
        }

        return when (mode) {
            IndexMode.FOLDER ->
                docs.groupBy { pathOf(it.doc.folderId) }
                    .toSortedMap()
                    .map { (heading, list) -> Group(heading, list) }

            IndexMode.FIELD ->
                docs.flatMap { summary ->
                    summary.fields.map { entry ->
                        "${entry.def.name}: ${entry.display()}" to summary
                    }
                }
                    .groupBy({ it.first }, { it.second })
                    .toSortedMap()
                    .map { (heading, list) -> Group(heading, list.distinctBy { it.doc.id }) }

            IndexMode.TAG ->
                docs.flatMap { summary -> summary.tagList.map { it to summary } }
                    .groupBy({ it.first }, { it.second })
                    .toSortedMap()
                    .map { (heading, list) -> Group(heading, list.distinctBy { it.doc.id }) }
        }
    }

    private companion object {
        const val UNFILED = "—"
    }
}

/**
 * A flat, searchable view across the whole library — the counterpart to the
 * folder tree on the home screen. Same data, indexed instead of nested.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndexScreen(
    onBack: () -> Unit,
    onOpenDocument: (String) -> Unit,
    viewModel: IndexViewModel = viewModel()
) {
    val query by viewModel.query.collectAsState()
    val mode by viewModel.mode.collectAsState()
    val groups by viewModel.groups.collectAsState()

    val total = remember(groups) { groups.sumOf { it.documents.size } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.index)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text(stringResource(R.string.search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IndexMode.entries.forEach { candidate ->
                    FilterChip(
                        selected = mode == candidate,
                        onClick = { viewModel.setMode(candidate) },
                        label = { Text(stringResource(candidate.labelRes)) }
                    )
                }
            }

            Text(
                text = stringResource(R.string.results_count, total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (groups.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.index_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                groups.forEach { group ->
                    item(key = "h_${group.heading}") {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "${group.heading}  (${group.documents.size})",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    items(
                        items = group.documents,
                        key = { "${group.heading}_${it.doc.id}" }
                    ) { summary ->
                        Card(
                            onClick = { onOpenDocument(summary.doc.id) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    text = summary.doc.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = stringResource(
                                        R.string.pages_count,
                                        summary.pageCount
                                    ),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
