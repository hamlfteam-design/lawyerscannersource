package com.personal.docscanner.data.repo

import com.personal.docscanner.data.db.AppDatabase
import com.personal.docscanner.data.db.DocumentEntity
import com.personal.docscanner.data.model.PdfPageSize
import com.personal.docscanner.data.storage.StorageManager
import com.personal.docscanner.scan.PdfExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Gets a whole client or case out of the app and onto the phone's storage,
 * where a file manager can pick it up and drop it into an existing case file.
 *
 * Two shapes, because the two jobs are different:
 *  - [exportTree] mirrors the folder hierarchy as real directories under
 *    `Documents/قضايا العملاء/`, one PDF per document — client folder, case
 *    folder inside it, every document scanned into that case as its own file.
 *    Exporting the same case again after adding pages later is the normal
 *    flow, not a one-time snapshot: new documents land in the same folder
 *    alongside the old ones, and [freeName] dates a colliding title instead
 *    of overwriting it. Use it when the material still needs sorting on the
 *    other side.
 *  - [mergeFolder] flattens everything under one folder into a single PDF, in
 *    folder order. Use it when a case file should read as one bundle.
 *
 * Both go through [StorageManager.exportToDocuments], which uses MediaStore on
 * Android 10 and up — the only route that can create nested public folders under
 * scoped storage.
 */
class CaseExporter(
    db: AppDatabase,
    private val storage: StorageManager
) {
    private val folders = db.folderDao()
    private val documents = db.documentDao()
    private val pages = db.pageDao()

    data class Progress(val done: Int, val total: Int, val label: String)

    data class TreeResult(
        val destination: String,
        val exported: Int,
        val skipped: List<String>
    )

    // ------------------------------------------------------------------ tree

    suspend fun exportTree(
        rootFolderId: String?,
        pageSize: PdfPageSize,
        quality: Int,
        includeOcrText: Boolean = true,
        onProgress: (Progress) -> Unit = {}
    ): TreeResult = withContext(Dispatchers.IO) {
        val rootName = rootFolderId
            ?.let { folders.byId(it)?.name }
            ?.let { StorageManager.sanitizeFileName(it) }
            ?: LIBRARY_DIR

        val plan = buildPlan(rootFolderId, rootName)
        require(plan.isNotEmpty()) { "nothing to export" }

        val skipped = ArrayList<String>()
        var exported = 0

        plan.forEachIndexed { index, item ->
            coroutineContext.ensureActive()
            onProgress(Progress(index, plan.size, item.doc.title))
            runCatching { writeDocument(item, pageSize, quality, includeOcrText) }
                .onSuccess { exported++ }
                .onFailure { skipped.add("${item.doc.title} — ${it.message ?: "failed"}") }
        }
        onProgress(Progress(plan.size, plan.size, ""))

        TreeResult(
            destination = "Documents/${StorageManager.EXPORT_DIR}/$rootName",
            exported = exported,
            skipped = skipped
        )
    }

    private suspend fun writeDocument(
        item: PlanItem,
        pageSize: PdfPageSize,
        quality: Int,
        includeOcrText: Boolean
    ) {
        val pageFiles = pages.forDoc(item.doc.id)
            .map { storage.pageFile(item.doc.id, it.fileName) }
            .filter { it.exists() }
        check(pageFiles.isNotEmpty()) { "no pages" }

        val base = StorageManager.sanitizeFileName(item.doc.title)
        val name = freeName(item.subPath, base, ".pdf", item.doc.createdAt)

        val staged = PdfExporter.export(
            pageFiles,
            storage.tempFile("stage_${item.doc.id}.pdf"),
            pageSize,
            quality
        )
        try {
            storage.exportToDocuments(staged, name, "application/pdf", item.subPath)
        } finally {
            staged.delete()
        }

        // A document that has been through OCR carries its text alongside the
        // PDF, so the bundle stays searchable once it is off the phone.
        if (includeOcrText && item.doc.ocrText.isNotBlank()) {
            val textName = freeName(item.subPath, base, ".txt", item.doc.createdAt)
            val textFile = PdfExporter.exportText(
                item.doc.ocrText,
                storage.tempFile("stage_${item.doc.id}.txt")
            )
            try {
                storage.exportToDocuments(textFile, textName, "text/plain", item.subPath)
            } finally {
                textFile.delete()
            }
        }
    }

    /**
     * Never silently collide — and never silently overwrite either, which
     * matters here specifically: exporting the same case folder again after
     * scanning new documents into it is the normal way this gets used, not an
     * edge case. The first export of a document keeps a clean name; a second
     * one that collides (same case, another document with the same title —
     * "توكيل.pdf" showing up twice is the common one) is disambiguated with
     * the date it was *scanned*, so the case folder on the phone reads as a
     * timeline rather than a pile of "_2", "_3" suffixes with no meaning.
     */
    private fun freeName(subPath: String, base: String, extension: String, capturedAt: Long): String {
        val plain = "$base$extension"
        if (!storage.existsInExport(subPath, plain)) return plain

        val dated = "${base}_${DATE_FORMAT.format(Date(capturedAt))}$extension"
        if (!storage.existsInExport(subPath, dated)) return dated

        // Two documents with the same title scanned on the same day: the date
        // alone can't tell them apart, so fall back to a counter after it.
        var counter = 2
        var candidate = dated
        while (storage.existsInExport(subPath, candidate) && counter < 500) {
            candidate = "${base}_${DATE_FORMAT.format(Date(capturedAt))}_$counter$extension"
            counter++
        }
        return candidate
    }

    // ----------------------------------------------------------------- merge

    suspend fun mergeFolder(
        folderId: String?,
        pageSize: PdfPageSize,
        quality: Int,
        onProgress: (Progress) -> Unit = {}
    ): String = withContext(Dispatchers.IO) {
        val name = folderId?.let { folders.byId(it)?.name } ?: LIBRARY_DIR
        val plan = buildPlan(folderId, "")

        val pageFiles = ArrayList<File>()
        plan.forEachIndexed { index, item ->
            coroutineContext.ensureActive()
            onProgress(Progress(index, plan.size, item.doc.title))
            pages.forDoc(item.doc.id).forEach { page ->
                val file = storage.pageFile(item.doc.id, page.fileName)
                if (file.exists()) pageFiles.add(file)
            }
        }
        require(pageFiles.isNotEmpty()) { "no pages to merge" }

        val base = StorageManager.sanitizeFileName(name) + "_merged"
        // Not one document's date — this bundle is a snapshot of the whole
        // case at the moment of merging, so the merge time is what disambiguates.
        val fileName = freeName("", base, ".pdf", System.currentTimeMillis())
        val merged = PdfExporter.export(
            pageFiles,
            storage.tempFile(fileName),
            pageSize,
            quality
        )
        try {
            storage.exportToDocuments(merged, fileName, "application/pdf")
        } finally {
            merged.delete()
            onProgress(Progress(plan.size, plan.size, ""))
        }
    }

    /** Documents under a folder, counting nested folders. Drives the confirm dialog. */
    suspend fun countDocuments(folderId: String?): Int =
        withContext(Dispatchers.IO) { buildPlan(folderId, "").size }

    // ------------------------------------------------------------------ plan

    private data class PlanItem(val doc: DocumentEntity, val subPath: String)

    /**
     * Walks the folder tree once into a flat, ordered work list.
     *
     * Doing the traversal up front means progress reports against a real total,
     * and the visited set means a cycle in the data (which the move guard should
     * prevent, but which a manual DB edit could still create) terminates instead
     * of recursing forever.
     */
    private suspend fun buildPlan(rootId: String?, rootPath: String): List<PlanItem> {
        val plan = ArrayList<PlanItem>()
        val visited = HashSet<String>()

        suspend fun walk(folderId: String?, path: String) {
            if (folderId != null && !visited.add(folderId)) return
            coroutineContext.ensureActive()

            documents.inFolderOnce(folderId).forEach { doc ->
                plan.add(PlanItem(doc, path))
            }
            folders.childrenOnce(folderId).forEach { child ->
                val childPath = listOf(path, StorageManager.sanitizeFileName(child.name))
                    .filter { it.isNotEmpty() }
                    .joinToString("/")
                walk(child.id, childPath)
            }
        }

        walk(rootId, rootPath)
        return plan
    }

    private companion object {
        const val LIBRARY_DIR = "Library"

        /**
         * Locale.US deliberately, same reasoning as the PDF page numbers
         * (see JpegPdfWriter): the app's default locale is Arabic, and a date
         * formatted in it comes out in Arabic-Indic digits — fine to read on
         * screen, but a landmine as a piece of a file name once it leaves the
         * phone and meets a file manager or a PC that expects ASCII digits.
         */
        val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}
