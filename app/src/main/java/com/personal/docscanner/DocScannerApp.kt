package com.personal.docscanner

import android.app.Application
import com.personal.docscanner.data.db.AppDatabase
import com.personal.docscanner.data.backup.BackupManager
import com.personal.docscanner.data.importer.LibraryImporter
import com.personal.docscanner.data.prefs.AppPrefs
import com.personal.docscanner.data.repo.CaseExporter
import com.personal.docscanner.data.repo.DocumentRepository
import com.personal.docscanner.data.repo.Seeder
import com.personal.docscanner.scan.OcrEngine
import com.personal.docscanner.scan.OpenCvLoader
import com.personal.docscanner.scan.TessDataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Holds the handful of long-lived objects the app needs. Small enough that a DI
 * framework would be more ceremony than it is worth.
 */
class DocScannerApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.get(this) }
    val repository: DocumentRepository by lazy { DocumentRepository.get(this) }
    val prefs: AppPrefs by lazy { AppPrefs(this) }
    val ocr: OcrEngine by lazy { OcrEngine(this) }
    val tessData: TessDataManager by lazy { TessDataManager(this) }
    val caseExporter: CaseExporter by lazy { CaseExporter(database, repository.storage) }
    val backup: BackupManager by lazy { BackupManager(this, database, repository.storage) }
    val importer: LibraryImporter by lazy { LibraryImporter(this, repository) }

    override fun onCreate() {
        super.onCreate()

        appScope.launch {
            // Loading the native library here keeps the first capture from
            // stalling on it.
            OpenCvLoader.ensureLoaded()
            tessData.installFromAssets()
            Seeder.seedIfEmpty(database)
            // Anything left in the share cache belongs to a previous session.
            repository.storage.clearShareCache()
        }
    }
}
