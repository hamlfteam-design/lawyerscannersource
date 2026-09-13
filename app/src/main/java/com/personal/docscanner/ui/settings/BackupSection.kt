package com.personal.docscanner.ui.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.personal.docscanner.DocScannerApp
import com.personal.docscanner.R
import com.personal.docscanner.data.backup.BackupCrypto
import com.personal.docscanner.data.backup.BackupManager
import com.personal.docscanner.data.prefs.Settings
import com.personal.docscanner.data.storage.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val backup = (app as DocScannerApp).backup
    private val prefs = (app as DocScannerApp).prefs
    private val resolver = app.contentResolver

    val settings = prefs.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _pendingRestore = MutableStateFlow<Uri?>(null)
    val pendingRestore: StateFlow<Uri?> = _pendingRestore.asStateFlow()

    private val _pendingEncrypted = MutableStateFlow(false)
    val pendingEncrypted: StateFlow<Boolean> = _pendingEncrypted.asStateFlow()

    suspend fun suggestedName(encrypted: Boolean): String =
        backup.suggestedFileName(
            SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()),
            encrypted,
            prefs.settings.first().backupLabel
        )

    fun setLabel(label: String) {
        viewModelScope.launch { prefs.setBackupLabel(label) }
    }

    // ---------------------------------------------------------------- backup

    /** A brand new file chosen from the system picker. */
    fun backupToNew(target: Uri, password: CharArray?) {
        // Holding on to the grant is what makes "update in place" possible later.
        runCatching {
            resolver.takePersistableUriPermission(
                target,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        run(target, password, remember = true)
    }

    /**
     * Writes over the file the last backup went to, so the copy already sitting
     * in the user's cloud folder is updated instead of gaining a sibling.
     */
    fun updateLastBackup(password: CharArray?) {
        viewModelScope.launch {
            val stored = prefs.settings.first().lastBackupUri
            if (stored.isEmpty()) {
                _message.value = MSG_NO_PREVIOUS
                return@launch
            }
            val uri = Uri.parse(stored)
            if (!backup.canStillWrite(uri)) {
                // Deleted, moved, or the grant was revoked — say so plainly
                // rather than failing halfway through writing.
                prefs.forgetBackup()
                _message.value = MSG_GONE
                return@launch
            }
            run(uri, password, remember = true)
        }
    }

    private fun run(target: Uri, password: CharArray?, remember: Boolean) {
        viewModelScope.launch {
            _busy.value = BACKING_UP
            _progress.value = 0f
            runCatching {
                backup.backupTo(target, password) { p ->
                    _progress.value = if (p.total == 0) null else p.done.toFloat() / p.total
                }
            }.onSuccess { s ->
                if (remember) {
                    prefs.rememberBackup(
                        uri = target.toString(),
                        name = displayName(target),
                        at = System.currentTimeMillis()
                    )
                }
                _message.value = "✓ ${s.documents} مستند · ${s.pages} صفحة · " +
                    StorageManager.humanSize(s.bytes) +
                    if (s.encrypted) " · مشفّر" else ""
            }.onFailure { _message.value = "فشل: ${it.message ?: "خطأ غير معروف"}" }
            _busy.value = null
            _progress.value = null
        }
    }

    private fun displayName(uri: Uri): String = runCatching {
        resolver.query(uri, null, null, null, null)?.use { c ->
            val index = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && c.moveToFirst()) c.getString(index) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    // --------------------------------------------------------------- restore

    fun prepareRestore(source: Uri) {
        viewModelScope.launch {
            _busy.value = INSPECTING
            val info = backup.inspect(source)
            _busy.value = null
            if (info == null) {
                _message.value = MSG_NOT_A_BACKUP
                return@launch
            }
            _pendingEncrypted.value = info.optBoolean(BackupManager.KEY_ENCRYPTED, false)
            _pendingRestore.value = source
        }
    }

    fun runRestore(password: CharArray?) {
        val source = _pendingRestore.value ?: return
        _pendingRestore.value = null
        viewModelScope.launch {
            _busy.value = RESTORING
            _progress.value = 0f
            runCatching {
                backup.restoreFrom(source, password) { p ->
                    _progress.value = if (p.total == 0) null else p.done.toFloat() / p.total
                }
            }.onSuccess { s ->
                _message.value = "✓ رجّعنا ${s.documents} مستند · ${s.pages} صفحة"
            }.onFailure {
                _message.value = when (it) {
                    is BackupCrypto.WrongPassword -> MSG_WRONG_PASSWORD
                    else -> "فشلت الاستعادة: ${it.message ?: "خطأ غير معروف"}"
                }
            }
            _busy.value = null
            _progress.value = null
        }
    }

    fun cancelRestore() {
        _pendingRestore.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    companion object {
        const val BACKING_UP = "backup"
        const val RESTORING = "restore"
        const val INSPECTING = "inspect"

        const val MSG_NO_PREVIOUS = "مفيش نسخة سابقة محفوظة. اعمل «نسخة جديدة» الأول."
        const val MSG_GONE =
            "النسخة القديمة مش موجودة في مكانها (اتمسحت أو اتنقلت). اعمل «نسخة جديدة»."
        const val MSG_NOT_A_BACKUP = "الملف ده مش نسخة احتياطية صالحة."
        const val MSG_WRONG_PASSWORD = "كلمة السر غلط، أو الملف تالف."
    }
}

/**
 * Adds the flags a persistable grant needs.
 *
 * Without them the write permission on the chosen file lasts only as long as
 * the task, so a later "update in place" would fail after a reboot — exactly
 * when it matters, since backups are weeks apart.
 */
private class CreatePersistableDocument(private val mime: String) :
    ActivityResultContracts.CreateDocument(mime) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
        }
}

/**
 * The backup block on the settings screen.
 *
 * Both actions go through the system file picker, so the destination is the
 * user's choice — Drive, a memory card, anywhere a provider is installed. The
 * app never holds a cloud credential of its own.
 */
@Composable
fun BackupSection(
    viewModel: BackupViewModel,
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsState()

    var askPassword by remember { mutableStateOf(false) }
    var pendingTarget by remember { mutableStateOf<Uri?>(null) }
    var updatingInPlace by remember { mutableStateOf(false) }
    var encryptChoice by remember { mutableStateOf(true) }
    var confirmRestore by remember { mutableStateOf(false) }
    var suggested by remember { mutableStateOf("") }

    LaunchedEffect(encryptChoice, settings.backupLabel) {
        suggested = viewModel.suggestedName(encryptChoice)
    }

    val createLauncher = rememberLauncherForActivityResult(
        CreatePersistableDocument("application/octet-stream")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (encryptChoice) {
            pendingTarget = uri
            updatingInPlace = false
            askPassword = true
        } else {
            viewModel.backupToNew(uri, null)
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::prepareRestore) }

    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = stringResource(R.string.backup_desc),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = settings.backupLabel,
                onValueChange = viewModel::setLabel,
                label = { Text(stringResource(R.string.backup_label)) },
                placeholder = { Text(stringResource(R.string.backup_label_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = suggested,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = encryptChoice, onCheckedChange = { encryptChoice = it })
                Column {
                    Text(
                        text = stringResource(R.string.backup_encrypt),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = stringResource(R.string.backup_encrypt_warning),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { createLauncher.launch(suggested) },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.backup_new)) }

                OutlinedButton(
                    onClick = { confirmRestore = true },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.restore)) }
            }

            // The update button only appears once there is something to update.
            if (settings.lastBackupUri.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text(
                    text = stringResource(
                        R.string.backup_last,
                        settings.lastBackupName.ifEmpty { "—" },
                        dateFormat.format(Date(settings.lastBackupAt))
                    ),
                    style = MaterialTheme.typography.labelMedium
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        if (encryptChoice) {
                            updatingInPlace = true
                            pendingTarget = null
                            askPassword = true
                        } else {
                            viewModel.updateLastBackup(null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.backup_update)) }
                Text(
                    text = stringResource(R.string.backup_update_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (askPassword) {
        PasswordDialog(
            title = stringResource(R.string.backup_password),
            confirmTwice = true,
            onConfirm = { pw ->
                if (updatingInPlace) {
                    viewModel.updateLastBackup(pw)
                } else {
                    pendingTarget?.let { viewModel.backupToNew(it, pw) }
                }
                pendingTarget = null
                updatingInPlace = false
            },
            onDismiss = { askPassword = false; pendingTarget = null; updatingInPlace = false }
        )
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(stringResource(R.string.restore)) },
            text = { Text(stringResource(R.string.restore_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    openLauncher.launch(BackupManager.MIME_TYPES)
                }) { Text(stringResource(R.string.restore_pick)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    RestorePasswordGate(viewModel)
}

@Composable
private fun RestorePasswordGate(viewModel: BackupViewModel) {
    val pending by viewModel.pendingRestore.collectAsState()
    val encrypted by viewModel.pendingEncrypted.collectAsState()

    if (pending == null) return

    if (encrypted) {
        PasswordDialog(
            title = stringResource(R.string.restore_password),
            confirmTwice = false,
            onConfirm = { pw -> viewModel.runRestore(pw) },
            onDismiss = viewModel::cancelRestore
        )
    } else {
        LaunchedEffect(pending) { viewModel.runRestore(null) }
    }
}

@Composable
private fun PasswordDialog(
    title: String,
    confirmTwice: Boolean,
    onConfirm: (CharArray) -> Unit,
    onDismiss: () -> Unit
) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }

    val mismatch = confirmTwice && second.isNotEmpty() && first != second
    val valid = first.length >= MIN_PASSWORD && (!confirmTwice || first == second)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = first,
                    onValueChange = { first = it },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (confirmTwice) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = second,
                        onValueChange = { second = it },
                        label = { Text(stringResource(R.string.password_again)) },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (mismatch) {
                            stringResource(R.string.password_mismatch)
                        } else {
                            stringResource(R.string.password_hint)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (mismatch) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(first.toCharArray()); onDismiss() },
                enabled = valid
            ) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

private const val MIN_PASSWORD = 6
private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
