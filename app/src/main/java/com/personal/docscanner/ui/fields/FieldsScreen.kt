package com.personal.docscanner.ui.fields

import android.app.Application
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.docscanner.DocScannerApp
import com.personal.docscanner.R
import com.personal.docscanner.data.db.FieldDefEntity
import com.personal.docscanner.data.db.FolderEntity
import com.personal.docscanner.data.model.FieldType
import com.personal.docscanner.ui.common.ConfirmDialog
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FieldsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as DocScannerApp).repository

    val fields = repo.observeFieldDefs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val folders = repo.observeAllFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(
        id: String?,
        name: String,
        type: FieldType,
        options: String,
        required: Boolean,
        defaultValue: String,
        folderId: String?
    ) {
        viewModelScope.launch {
            repo.saveFieldDef(id, name, type, options, required, defaultValue, folderId)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.deleteFieldDef(id) }
    }
}

/**
 * Where the user defines what "saving a document" means for them: which fields
 * exist, what type each one is, and whether it shows up everywhere or only
 * inside one folder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FieldsScreen(
    onBack: () -> Unit,
    viewModel: FieldsViewModel = viewModel()
) {
    val fields by viewModel.fields.collectAsState()
    val folders by viewModel.folders.collectAsState()

    var editing by remember { mutableStateOf<FieldDefEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FieldDefEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.custom_fields)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, stringResource(R.string.new_field))
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.custom_fields_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            items(fields, key = { it.id }) { def ->
                Card(
                    onClick = { editing = def },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = def.name + if (def.required) " *" else "",
                                style = MaterialTheme.typography.titleMedium
                            )
                            val scope = folders.firstOrNull { it.id == def.folderId }?.name
                                ?: stringResource(R.string.field_scope_all)
                            Text(
                                text = stringResource(FieldType.fromName(def.type).labelRes) +
                                    " · " + scope,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { deleting = def }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete))
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(72.dp)) }
        }
    }

    if (creating || editing != null) {
        FieldEditorDialog(
            existing = editing,
            folders = folders,
            onSave = { name, type, options, required, default, folderId ->
                viewModel.save(editing?.id, name, type, options, required, default, folderId)
            },
            onDismiss = { creating = false; editing = null }
        )
    }

    deleting?.let { def ->
        ConfirmDialog(
            title = def.name,
            message = stringResource(R.string.confirm_delete_field),
            confirmLabel = stringResource(R.string.delete),
            destructive = true,
            onConfirm = { viewModel.delete(def.id) },
            onDismiss = { deleting = null }
        )
    }
}

@Composable
private fun FieldEditorDialog(
    existing: FieldDefEntity?,
    folders: List<FolderEntity>,
    onSave: (String, FieldType, String, Boolean, String, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var type by remember {
        mutableStateOf(existing?.type?.let(FieldType::fromName) ?: FieldType.TEXT)
    }
    var options by remember { mutableStateOf(existing?.options.orEmpty()) }
    var required by remember { mutableStateOf(existing?.required ?: false) }
    var defaultValue by remember { mutableStateOf(existing?.defaultValue.orEmpty()) }
    var folderId by remember { mutableStateOf(existing?.folderId) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) stringResource(R.string.new_field) else existing.name) },
        text = {
            Column(Modifier.verticalScrollIfNeeded()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.field_type),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FieldType.entries.forEach { candidate ->
                        FilterChip(
                            selected = type == candidate,
                            onClick = { type = candidate },
                            label = { Text(stringResource(candidate.labelRes)) }
                        )
                    }
                }

                if (type == FieldType.SELECT) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = options,
                        onValueChange = { options = it },
                        label = { Text(stringResource(R.string.field_options)) },
                        placeholder = { Text(stringResource(R.string.field_options_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = defaultValue,
                    onValueChange = { defaultValue = it },
                    label = { Text(stringResource(R.string.field_default)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.field_scope),
                    style = MaterialTheme.typography.labelMedium
                )
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(
                        selected = folderId == null,
                        onClick = { folderId = null },
                        label = { Text(stringResource(R.string.field_scope_all)) }
                    )
                    folders.forEach { folder ->
                        FilterChip(
                            selected = folderId == folder.id,
                            onClick = { folderId = folder.id },
                            label = { Text(folder.name) }
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = required, onCheckedChange = { required = it })
                    Text(stringResource(R.string.field_required))
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(name.trim(), type, options, required, defaultValue, folderId)
                    onDismiss()
                },
                enabled = name.isNotBlank()
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** The editor has more rows than a short phone can show; let it scroll. */
@Composable
private fun Modifier.verticalScrollIfNeeded(): Modifier =
    this.verticalScroll(rememberScrollState())
