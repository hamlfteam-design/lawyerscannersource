package com.personal.docscanner.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.personal.docscanner.data.model.PageFilter
import com.personal.docscanner.data.model.PdfPageSize
import com.personal.docscanner.data.model.SortMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

data class Settings(
    val appLockEnabled: Boolean = false,
    val defaultFilter: PageFilter = PageFilter.AUTO,
    val pdfPageSize: PdfPageSize = PdfPageSize.FIT,
    val pdfQuality: Int = 85,
    val ocrLanguages: String = "ara+eng",
    val sortMode: SortMode = SortMode.DATE_DESC,
    val gridView: Boolean = true,
    /** On by default: continuous scanning is the point of the camera screen. */
    val autoCapture: Boolean = true,

    /**
     * The file the last backup was written to. Android hands back a permission
     * that survives a reboot, so remembering the URI is what lets a later backup
     * overwrite the same cloud file instead of piling up copies next to it.
     */
    val lastBackupUri: String = "",
    val lastBackupName: String = "",
    val lastBackupAt: Long = 0L,
    /** Goes into the file name, so backups from two phones stay apart. */
    val backupLabel: String = ""
)

class AppPrefs(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings() = Settings(
        appLockEnabled = this[KEY_APP_LOCK] ?: false,
        defaultFilter = PageFilter.fromName(this[KEY_DEFAULT_FILTER] ?: PageFilter.AUTO.name),
        pdfPageSize = runCatching {
            PdfPageSize.valueOf(this[KEY_PDF_SIZE] ?: PdfPageSize.FIT.name)
        }.getOrDefault(PdfPageSize.FIT),
        pdfQuality = this[KEY_PDF_QUALITY] ?: 85,
        ocrLanguages = this[KEY_OCR_LANGS] ?: "ara+eng",
        sortMode = runCatching {
            SortMode.valueOf(this[KEY_SORT] ?: SortMode.DATE_DESC.name)
        }.getOrDefault(SortMode.DATE_DESC),
        gridView = this[KEY_GRID] ?: true,
        autoCapture = this[KEY_AUTO_CAPTURE] ?: true,
        lastBackupUri = this[KEY_BACKUP_URI] ?: "",
        lastBackupName = this[KEY_BACKUP_NAME] ?: "",
        lastBackupAt = this[KEY_BACKUP_AT] ?: 0L,
        backupLabel = this[KEY_BACKUP_LABEL] ?: ""
    )

    suspend fun setAppLock(enabled: Boolean) = put(KEY_APP_LOCK, enabled)
    suspend fun setDefaultFilter(filter: PageFilter) = put(KEY_DEFAULT_FILTER, filter.name)
    suspend fun setPdfPageSize(size: PdfPageSize) = put(KEY_PDF_SIZE, size.name)
    suspend fun setPdfQuality(quality: Int) = put(KEY_PDF_QUALITY, quality)
    suspend fun setOcrLanguages(langs: String) = put(KEY_OCR_LANGS, langs)
    suspend fun setSortMode(mode: SortMode) = put(KEY_SORT, mode.name)
    suspend fun setGridView(grid: Boolean) = put(KEY_GRID, grid)
    suspend fun setAutoCapture(auto: Boolean) = put(KEY_AUTO_CAPTURE, auto)
    suspend fun setBackupLabel(label: String) = put(KEY_BACKUP_LABEL, label.trim())

    suspend fun rememberBackup(uri: String, name: String, at: Long) {
        context.dataStore.edit {
            it[KEY_BACKUP_URI] = uri
            it[KEY_BACKUP_NAME] = name
            it[KEY_BACKUP_AT] = at
        }
    }

    /** Called when the remembered file turns out to be gone or unwritable. */
    suspend fun forgetBackup() {
        context.dataStore.edit {
            it.remove(KEY_BACKUP_URI)
            it.remove(KEY_BACKUP_NAME)
            it.remove(KEY_BACKUP_AT)
        }
    }

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }

    private companion object {
        val KEY_APP_LOCK = booleanPreferencesKey("app_lock")
        val KEY_DEFAULT_FILTER = stringPreferencesKey("default_filter")
        val KEY_PDF_SIZE = stringPreferencesKey("pdf_size")
        val KEY_PDF_QUALITY = intPreferencesKey("pdf_quality")
        val KEY_OCR_LANGS = stringPreferencesKey("ocr_langs")
        val KEY_SORT = stringPreferencesKey("sort_mode")
        val KEY_GRID = booleanPreferencesKey("grid_view")
        val KEY_AUTO_CAPTURE = booleanPreferencesKey("auto_capture")
        val KEY_BACKUP_URI = stringPreferencesKey("last_backup_uri")
        val KEY_BACKUP_NAME = stringPreferencesKey("last_backup_name")
        val KEY_BACKUP_AT = longPreferencesKey("last_backup_at")
        val KEY_BACKUP_LABEL = stringPreferencesKey("backup_label")
    }
}
