package com.personal.docscanner.ui.importer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.docscanner.DocScannerApp
import com.personal.docscanner.R
import com.personal.docscanner.data.importer.LibraryImporter
import com.personal.docscanner.ui.theme.findActivity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ImportViewModel(app: Application) : AndroidViewModel(app) {

    private val importer = (app as DocScannerApp).importer

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    private val _label = MutableStateFlow("")
    val label: StateFlow<String> = _label.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun importFiles(uris: List<Uri>, folderId: String?) {
        if (uris.isEmpty()) return
        launchImport { importer.importPdfs(uris, folderId, ::report) }
    }

    fun importFolder(treeUri: Uri, parentFolderId: String?) {
        launchImport { importer.importTree(treeUri, parentFolderId, ::report) }
    }

    private fun launchImport(block: suspend () -> LibraryImporter.Result) {
        viewModelScope.launch {
            _busy.value = true
            _progress.value = 0f
            runCatching { block() }
                .onSuccess { result -> _message.value = summarize(result) }
                .onFailure { _message.value = string(R.string.import_failed, it.readableMessage()) }
            _busy.value = false
            _progress.value = null
            _label.value = ""
        }
    }

    private fun summarize(result: LibraryImporter.Result): String = buildString {
        append(
            if (result.folders > 0) {
                string(
                    R.string.import_done_folders,
                    result.documents,
                    result.pages,
                    result.folders
                )
            } else {
                string(R.string.import_done, result.documents, result.pages)
            }
        )
        // Moving a document is not something to do quietly. Saying how many
        // were attached to a client is what lets a wrong one be noticed.
        if (result.linkedToClients > 0) {
            append(" · ")
            append(string(R.string.import_linked, result.linkedToClients))
        }
        // Skipped files are named in the count only: a snackbar cannot carry a
        // list, and the ones that failed are usually password-protected PDFs.
        if (result.skipped.isNotEmpty()) {
            append(" · ")
            append(string(R.string.import_skipped, result.skipped.size))
        }
    }

    private fun Throwable.readableMessage(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    private fun string(resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)

    private fun report(p: LibraryImporter.Progress) {
        _label.value = p.currentName
        // Page progress inside the current document refines the document-level
        // bar, so a single long PDF does not look frozen.
        val base = if (p.documentsTotal == 0) 0f else p.documentsDone.toFloat() / p.documentsTotal
        val step = if (p.documentsTotal == 0) 0f else 1f / p.documentsTotal
        val within = if (p.pageTotal == 0) 0f else p.pageDone.toFloat() / p.pageTotal
        _progress.value = (base + within * step).coerceIn(0f, 1f)
    }

    fun consumeMessage() {
        _message.value = null
    }
}

/**
 * Held by the activity rather than by the navigation entry: importing a whole
 * exported library takes minutes, and stepping into a folder while it runs would
 * otherwise clear the view model and cancel the work half-way.
 */
@Composable
fun rememberImportViewModel(): ImportViewModel {
    val activity = requireNotNull(LocalContext.current.findActivity()) {
        "ImportViewModel needs an activity to be scoped to"
    }
    return viewModel(viewModelStoreOwner = activity as ViewModelStoreOwner)
}

/**
 * Persistable read access on the picked tree, so a long import survives the
 * activity being recreated part-way through.
 */
private class OpenPersistableTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
}

/**
 * Asks which shape of import to run, then hands off to the system picker.
 *
 * Kept as a dialog rather than a screen: the choice is one question, and the
 * work that follows happens on the screen the user was already on.
 */
@Composable
fun ImportDialog(
    folderId: String?,
    viewModel: ImportViewModel,
    onDismiss: () -> Unit
) {
    val filesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        onDismiss()
        viewModel.importFiles(uris, folderId)
    }

    val treeLauncher = rememberLauncherForActivityResult(OpenPersistableTree()) { uri ->
        onDismiss()
        uri?.let { viewModel.importFolder(it, folderId) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.import_desc),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(14.dp))
                OutlinedButton(
                    onClick = { filesLauncher.launch(arrayOf("application/pdf")) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.import_files)) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { treeLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.import_folder)) }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.import_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
