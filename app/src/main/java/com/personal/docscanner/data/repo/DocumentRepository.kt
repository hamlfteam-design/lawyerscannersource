package com.personal.docscanner.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.personal.docscanner.data.db.AppDatabase
import com.personal.docscanner.data.db.DocumentEntity
import com.personal.docscanner.data.db.FieldDefEntity
import com.personal.docscanner.data.db.FieldValueEntity
import com.personal.docscanner.data.db.FolderEntity
import com.personal.docscanner.data.db.PageEntity
import com.personal.docscanner.data.model.Crumb
import com.personal.docscanner.data.model.DocumentDetail
import com.personal.docscanner.data.model.DocumentSummary
import com.personal.docscanner.data.model.FieldEntry
import com.personal.docscanner.data.model.FieldType
import com.personal.docscanner.data.model.FolderSummary
import com.personal.docscanner.data.model.PageFilter
import com.personal.docscanner.data.model.PdfPageSize
import com.personal.docscanner.data.storage.StorageManager
import com.personal.docscanner.scan.DocumentFormat
import com.personal.docscanner.scan.EdgeDetector
import com.personal.docscanner.scan.ImageProcessor
import com.personal.docscanner.scan.PdfExporter
import com.personal.docscanner.scan.Quad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * The single place that keeps the database and the files on disk in agreement.
 * Nothing else writes to either.
 */
class DocumentRepository(
    context: Context,
    private val db: AppDatabase,
    val storage: StorageManager
) {
    private val appContext = context.applicationContext
    private val folders = db.folderDao()
    private val documents = db.documentDao()
    private val pages = db.pageDao()
    private val fields = db.fieldDao()

    // ------------------------------------------------------------- observing

    fun observeFolders(parentId: String?): Flow<List<FolderSummary>> =
        combine(folders.observeChildren(parentId), folders.observeAll(), documents.observeAll()) {
                children, all, docs ->
            children.map { folder ->
                FolderSummary(
                    folder = folder,
                    docCount = docs.count { it.folderId == folder.id },
                    subfolderCount = all.count { it.parentId == folder.id }
                )
            }
        }

    fun observeAllFolders(): Flow<List<FolderEntity>> = folders.observeAll()

    fun observeDocuments(folderId: String?): Flow<List<DocumentSummary>> =
        documents.observeInFolder(folderId).map { list -> list.map { summarize(it) } }

    fun searchDocuments(query: String): Flow<List<DocumentSummary>> =
        documents.search(query).map { list -> list.map { summarize(it) } }

    fun observeDocument(id: String): Flow<DocumentDetail?> =
        combine(
            documents.observe(id),
            pages.observeForDoc(id),
            fields.observeAllDefs(),
            fields.observeValues(id)
        ) { doc, pageList, defs, values ->
            if (doc == null) return@combine null
            val valueMap = values.associate { it.fieldId to it.value }
            val applicable = defs.filter { it.folderId == null || it.folderId == doc.folderId }
            DocumentDetail(
                doc = doc,
                pages = pageList,
                fields = applicable.map { def ->
                    FieldEntry(def, valueMap[def.id] ?: def.defaultValue)
                },
                folderPath = emptyList()
            )
        }

    fun observeFieldDefs(): Flow<List<FieldDefEntity>> = fields.observeAllDefs()

    fun observeFieldDefsForFolder(folderId: String?): Flow<List<FieldDefEntity>> =
        fields.observeDefsForFolder(folderId)

    private suspend fun summarize(doc: DocumentEntity): DocumentSummary {
        val pageList = pages.forDoc(doc.id)
        val first = pageList.firstOrNull()
        val defs = fields.defsForFolder(doc.folderId)
        val values = fields.values(doc.id).associate { it.fieldId to it.value }
        return DocumentSummary(
            doc = doc,
            pageCount = pageList.size,
            thumbPath = first?.let { storage.pagePath(doc.id, it.thumbName) },
            fields = defs.mapNotNull { def ->
                val value = values[def.id]
                if (value.isNullOrEmpty()) null else FieldEntry(def, value)
            }
        )
    }

    /** Root-to-here breadcrumbs for the folder header. */
    suspend fun breadcrumbs(folderId: String?): List<Crumb> {
        val trail = ArrayList<Crumb>()
        var current = folderId
        var guard = 0
        while (current != null && guard++ < MAX_FOLDER_DEPTH) {
            val folder = folders.byId(current) ?: break
            trail.add(0, Crumb(folder.id, folder.name))
            current = folder.parentId
        }
        trail.add(0, Crumb(null, ROOT_CRUMB))
        return trail
    }

    // --------------------------------------------------------------- folders

    suspend fun createFolder(name: String, parentId: String?, colorArgb: Int): String {
        val id = UUID.randomUUID().toString()
        folders.upsert(
            FolderEntity(
                id = id,
                name = name.trim().ifEmpty { "New folder" },
                parentId = parentId,
                colorArgb = colorArgb,
                sortOrder = 0,
                locked = false,
                createdAt = System.currentTimeMillis()
            )
        )
        return id
    }

    suspend fun renameFolder(id: String, name: String) = folders.rename(id, name.trim())

    suspend fun setFolderLocked(id: String, locked: Boolean) = folders.setLocked(id, locked)

    suspend fun deleteFolder(id: String) = folders.deleteAndReparent(id)

    suspend fun folder(id: String): FolderEntity? = folders.byId(id)

    /**
     * Guards against making a folder its own ancestor, which would detach a whole
     * subtree from the root and make it unreachable.
     */
    suspend fun canMoveFolder(folderId: String, newParentId: String?): Boolean {
        if (newParentId == null) return true
        if (folderId == newParentId) return false
        var current: String? = newParentId
        var guard = 0
        while (current != null && guard++ < MAX_FOLDER_DEPTH) {
            if (current == folderId) return false
            current = folders.byId(current)?.parentId
        }
        return true
    }

    // ------------------------------------------------------------- documents

    suspend fun createDocument(folderId: String?, title: String? = null): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        documents.upsert(
            DocumentEntity(
                id = id,
                title = title?.trim().orEmpty().ifEmpty { defaultTitle(now) },
                folderId = folderId,
                note = "",
                tags = "",
                ocrText = "",
                favorite = false,
                createdAt = now,
                updatedAt = now
            )
        )
        // Seed the document with the default value of every applicable field so
        // the detail screen opens pre-filled rather than blank.
        val defs = fields.defsForFolder(folderId).filter { it.defaultValue.isNotEmpty() }
        if (defs.isNotEmpty()) {
            fields.upsertValues(defs.map { FieldValueEntity(id, it.id, it.defaultValue) })
        }
        return id
    }

    suspend fun updateDocument(
        id: String,
        title: String? = null,
        note: String? = null,
        tags: String? = null
    ) {
        val existing = documents.byId(id) ?: return
        documents.update(
            existing.copy(
                title = title?.trim()?.ifEmpty { existing.title } ?: existing.title,
                note = note ?: existing.note,
                tags = tags?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?.joinToString(",")
                    ?: existing.tags,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun moveDocument(id: String, folderId: String?) =
        documents.move(id, folderId, System.currentTimeMillis())

    suspend fun setFavorite(id: String, favorite: Boolean) =
        documents.setFavorite(id, favorite, System.currentTimeMillis())

    suspend fun deleteDocument(id: String) {
        documents.deleteById(id)
        withContext(Dispatchers.IO) { storage.deleteDoc(id) }
    }

    suspend fun document(id: String): DocumentEntity? = documents.byId(id)

    // ----------------------------------------------------------------- pages

    /**
     * Persists a captured page: keeps the untouched original for later re-cropping,
     * writes the processed image the user will actually see, and a thumbnail.
     */
    suspend fun addPage(
        documentId: String,
        original: Bitmap,
        quad: Quad?,
        filter: PageFilter,
        rotation: Int = 0,
        brightness: Int = 0,
        contrast: Int = 0,
        snapTo: DocumentFormat? = null
    ): PageEntity = withContext(Dispatchers.IO) {
        val pageId = UUID.randomUUID().toString()
        val position = pages.nextPosition(documentId)
        val stamp = System.currentTimeMillis()

        val originalName = "orig_$pageId.jpg"
        val fileName = "page_$pageId.jpg"
        val thumbName = "thumb_$pageId.jpg"

        storage.writeJpeg(storage.pageFile(documentId, originalName), original, 92)
        val processed = renderPage(original, quad, filter, rotation, brightness, contrast, snapTo)
        storage.writeJpeg(storage.pageFile(documentId, fileName), processed)
        storage.writeThumb(documentId, thumbName, processed)
        if (processed !== original) processed.recycle()

        val page = PageEntity(
            id = pageId,
            documentId = documentId,
            position = position,
            fileName = fileName,
            thumbName = thumbName,
            originalName = originalName,
            filter = filter.name,
            rotation = rotation,
            quad = quad?.serialize(),
            brightness = brightness,
            contrast = contrast,
            snapFormat = snapTo?.name,
            createdAt = stamp
        )
        pages.insert(page)
        documents.touch(documentId, stamp)
        page
    }

    /**
     * Files a page straight from the shutter (or an outside file) with nothing
     * done to it yet: no edge detection, no crop, no enhancement. [fileName]
     * starts out identical to [PageEntity.originalName] so the page still has
     * something to show on screen, and is overwritten in place once
     * [processPendingPages] sweeps through the document.
     *
     * This is what makes capture fast and keeps the camera up between shots —
     * the OpenCV detection and filter chain only run once, in a batch, after
     * the whole session is done.
     */
    suspend fun addRawPage(documentId: String, original: Bitmap): PageEntity = withContext(Dispatchers.IO) {
        val pageId = UUID.randomUUID().toString()
        val position = pages.nextPosition(documentId)
        val stamp = System.currentTimeMillis()

        val originalName = "orig_$pageId.jpg"
        val fileName = "page_$pageId.jpg"
        val thumbName = "thumb_$pageId.jpg"

        storage.writeJpeg(storage.pageFile(documentId, originalName), original, 92)
        // Same bytes for now under the "processed" name too, so the document
        // screen and the shutter's own thumbnail have something to display
        // before the batch sweep replaces it with the cropped/enhanced version.
        storage.pageFile(documentId, originalName).copyTo(
            storage.pageFile(documentId, fileName), overwrite = true
        )
        storage.writeThumb(documentId, thumbName, original)

        val page = PageEntity(
            id = pageId,
            documentId = documentId,
            position = position,
            fileName = fileName,
            thumbName = thumbName,
            originalName = originalName,
            filter = PageFilter.ORIGINAL.name,
            rotation = 0,
            quad = null,
            brightness = 0,
            contrast = 0,
            snapFormat = null,
            processed = false,
            createdAt = stamp
        )
        pages.insert(page)
        documents.touch(documentId, stamp)
        page
    }

    /**
     * The end-of-session sweep: runs edge detection and the chosen filter over
     * every page in [documentId] still carrying its raw capture, replacing the
     * placeholder file written by [addRawPage] with the real crop and
     * enhancement. Pages already processed (a re-crop, an import through the
     * library, anything from before this flag existed) are left untouched.
     *
     * One page failing to decode does not stop the rest of the batch — it is
     * just left as its raw capture, still usable, just uncropped.
     */
    suspend fun processPendingPages(documentId: String, filter: PageFilter) = withContext(Dispatchers.IO) {
        val pending = pages.unprocessedForDoc(documentId)
        for (page in pending) {
            val originalName = page.originalName ?: page.fileName
            val original = storage.decode(storage.pageFile(documentId, originalName)) ?: continue
            try {
                val quad = EdgeDetector.detect(original)
                val processed = renderPage(
                    original = original,
                    quad = quad,
                    filter = filter,
                    rotation = 0,
                    brightness = 0,
                    contrast = 0,
                    snapTo = null
                )
                storage.writeJpeg(storage.pageFile(documentId, page.fileName), processed)
                storage.writeThumb(documentId, page.thumbName, processed)
                if (processed !== original) processed.recycle()
                pages.update(page.copy(quad = quad?.serialize(), filter = filter.name, processed = true))
            } finally {
                original.recycle()
            }
        }
        if (pending.isNotEmpty()) documents.touch(documentId, System.currentTimeMillis())
    }

    /** A saved page together with the untouched photograph it was made from. */
    data class PageEdit(val page: PageEntity, val original: Bitmap)

    /** Loads a page for re-cropping. */
    suspend fun pageForEdit(pageId: String): PageEdit? = withContext(Dispatchers.IO) {
        val page = pages.byId(pageId) ?: return@withContext null
        val name = page.originalName ?: page.fileName
        val original = storage.decode(storage.pageFile(page.documentId, name))
            ?: return@withContext null
        PageEdit(page, original)
    }

    /** Re-renders an existing page from its untouched original. */
    suspend fun updatePage(
        pageId: String,
        quad: Quad? = null,
        filter: PageFilter? = null,
        rotation: Int? = null,
        brightness: Int? = null,
        contrast: Int? = null,
        snapTo: DocumentFormat? = null
    ): PageEntity? = withContext(Dispatchers.IO) {
        val page = pages.byId(pageId) ?: return@withContext null
        val originalName = page.originalName ?: page.fileName
        val original = storage.decode(storage.pageFile(page.documentId, originalName))
            ?: return@withContext null

        val newQuad = quad ?: Quad.parse(page.quad)
        val newFilter = filter ?: PageFilter.fromName(page.filter)
        val newRotation = rotation ?: page.rotation
        val newBrightness = brightness ?: page.brightness
        val newContrast = contrast ?: page.contrast
        // Null means "leave it alone", so a rotate does not quietly discard a
        // format the user chose by hand.
        val newSnap = snapTo ?: page.snapFormat?.let { DocumentFormat.parse(it) }

        val processed =
            renderPage(original, newQuad, newFilter, newRotation, newBrightness, newContrast, newSnap)
        storage.writeJpeg(storage.pageFile(page.documentId, page.fileName), processed)
        storage.writeThumb(page.documentId, page.thumbName, processed)
        if (processed !== original) processed.recycle()
        original.recycle()

        val updated = page.copy(
            quad = newQuad?.serialize(),
            filter = newFilter.name,
            rotation = newRotation,
            brightness = newBrightness,
            contrast = newContrast,
            snapFormat = newSnap?.name
        )
        pages.update(updated)
        documents.touch(page.documentId, System.currentTimeMillis())
        updated
    }

    private fun renderPage(
        original: Bitmap,
        quad: Quad?,
        filter: PageFilter,
        rotation: Int,
        brightness: Int,
        contrast: Int,
        snapTo: DocumentFormat? = null
    ): Bitmap {
        // A page with no crop would otherwise carry the camera's full frame all
        // the way through the filter chain, where every step holds another copy
        // of it. Warping already bounds its own output.
        val warped = if (quad != null) {
            ImageProcessor.warp(original, quad, snapTo)
        } else {
            ImageProcessor.boundForProcessing(original)
        }
        val rotated = ImageProcessor.rotate(warped, rotation)
        if (rotated !== warped && warped !== original) warped.recycle()
        val filtered = ImageProcessor.applyFilter(rotated, filter, brightness, contrast)
        if (filtered !== rotated && rotated !== original) rotated.recycle()
        return filtered
    }

    suspend fun deletePage(pageId: String) = withContext(Dispatchers.IO) {
        val page = pages.byId(pageId) ?: return@withContext
        pages.delete(page)
        storage.deleteFile(page.documentId, page.fileName)
        storage.deleteFile(page.documentId, page.thumbName)
        page.originalName?.let { storage.deleteFile(page.documentId, it) }
        // Close the gap left in the ordering.
        pages.reorder(pages.forDoc(page.documentId).map { it.id })
        documents.touch(page.documentId, System.currentTimeMillis())
    }

    suspend fun reorderPages(documentId: String, orderedIds: List<String>) {
        pages.reorder(orderedIds)
        documents.touch(documentId, System.currentTimeMillis())
    }

    suspend fun pagesOf(documentId: String): List<PageEntity> = pages.forDoc(documentId)

    fun pageFile(page: PageEntity): File = storage.pageFile(page.documentId, page.fileName)

    /** Decodes a gallery image at a size that is safe to keep in memory. */
    suspend fun decodeImport(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        storage.decodeUri(uri, IMPORT_MAX_EDGE)
    }

    // ---------------------------------------------------------------- fields

    suspend fun saveFieldDef(
        id: String?,
        name: String,
        type: FieldType,
        options: String,
        required: Boolean,
        defaultValue: String,
        folderId: String?
    ): String {
        val fieldId = id ?: UUID.randomUUID().toString()
        val sortOrder = if (id == null) fields.nextSortOrder() else existingSortOrder(id)
        fields.upsertDef(
            FieldDefEntity(
                id = fieldId,
                name = name.trim(),
                type = type.name,
                options = options.split(',').joinToString(",") { it.trim() }.trim(','),
                required = required,
                defaultValue = defaultValue.trim(),
                folderId = folderId,
                sortOrder = sortOrder
            )
        )
        return fieldId
    }

    private suspend fun existingSortOrder(id: String): Int =
        fields.defById(id)?.sortOrder ?: fields.nextSortOrder()

    suspend fun deleteFieldDef(id: String) = fields.deleteDef(id)

    /** Every field definition, for callers that fill fields in without a UI. */
    suspend fun fieldDefsOnce(): List<FieldDefEntity> = fields.allDefsOnce()

    suspend fun setFieldValue(documentId: String, fieldId: String, value: String) {
        if (value.isEmpty()) {
            fields.deleteValue(documentId, fieldId)
        } else {
            fields.upsertValue(FieldValueEntity(documentId, fieldId, value))
        }
        documents.touch(documentId, System.currentTimeMillis())
    }

    suspend fun fieldSuggestions(fieldId: String): List<String> = fields.suggestions(fieldId)

    /** Names of required fields that are still empty, for save-time validation. */
    suspend fun missingRequiredFields(documentId: String): List<String> {
        val doc = documents.byId(documentId) ?: return emptyList()
        val defs = fields.defsForFolder(doc.folderId).filter { it.required }
        if (defs.isEmpty()) return emptyList()
        val values = fields.values(documentId).associate { it.fieldId to it.value }
        return defs.filter { values[it.id].isNullOrBlank() }.map { it.name }
    }

    // ------------------------------------------------------------------- OCR

    suspend fun saveOcrText(documentId: String, text: String) =
        documents.setOcrText(documentId, text, System.currentTimeMillis())

    // -------------------------------------------------------------- exporting

    /** Builds the PDF in the cache and returns it; publishing is a separate step. */
    suspend fun buildPdf(
        documentId: String,
        pageSize: PdfPageSize,
        quality: Int
    ): File = withContext(Dispatchers.IO) {
        val doc = documents.byId(documentId) ?: error("document not found")
        val files = pages.forDoc(documentId).map { storage.pageFile(documentId, it.fileName) }
        require(files.isNotEmpty()) { "document has no pages" }
        val name = StorageManager.sanitizeFileName(doc.title) + ".pdf"
        PdfExporter.export(files, storage.tempFile(name), pageSize, quality)
    }

    suspend fun exportPdf(documentId: String, pageSize: PdfPageSize, quality: Int): String =
        withContext(Dispatchers.IO) {
            val pdf = buildPdf(documentId, pageSize, quality)
            storage.exportToDocuments(pdf, pdf.name, "application/pdf")
        }

    suspend fun exportImages(documentId: String): String = withContext(Dispatchers.IO) {
        val doc = documents.byId(documentId) ?: error("document not found")
        val base = StorageManager.sanitizeFileName(doc.title)
        val pageList = pages.forDoc(documentId)
        require(pageList.isNotEmpty()) { "document has no pages" }
        var last = ""
        pageList.forEachIndexed { index, page ->
            val source = storage.pageFile(documentId, page.fileName)
            val name = if (pageList.size == 1) "$base.jpg" else "${base}_${index + 1}.jpg"
            last = storage.exportToDocuments(source, name, "image/jpeg")
        }
        last
    }

    suspend fun exportText(documentId: String): String = withContext(Dispatchers.IO) {
        val doc = documents.byId(documentId) ?: error("document not found")
        require(doc.ocrText.isNotBlank()) { "no recognized text" }
        val name = StorageManager.sanitizeFileName(doc.title) + ".txt"
        val file = PdfExporter.exportText(buildTextExport(doc), storage.tempFile(name))
        storage.exportToDocuments(file, name, "text/plain")
    }

    /** The text export carries the metadata too — that is the point of the fields. */
    private suspend fun buildTextExport(doc: DocumentEntity): String = buildString {
        appendLine(doc.title)
        appendLine("=".repeat(doc.title.length.coerceAtMost(60)))
        val defs = fields.defsForFolder(doc.folderId)
        val values = fields.values(doc.id).associate { it.fieldId to it.value }
        defs.forEach { def ->
            val raw = values[def.id].orEmpty()
            if (raw.isNotEmpty()) {
                appendLine("${def.name}: ${FieldEntry(def, raw).display()}")
            }
        }
        if (doc.tags.isNotBlank()) appendLine("Tags: ${doc.tags}")
        if (doc.note.isNotBlank()) {
            appendLine()
            appendLine(doc.note)
        }
        appendLine()
        appendLine(doc.ocrText)
    }

    fun storageUsed(): Long = storage.librarySizeBytes()

    private fun defaultTitle(now: Long): String =
        "Scan " + SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(now))

    companion object {
        private const val MAX_FOLDER_DEPTH = 64
        private const val IMPORT_MAX_EDGE = 3000
        private const val ROOT_CRUMB = "/"

        @Volatile
        private var instance: DocumentRepository? = null

        fun get(context: Context): DocumentRepository =
            instance ?: synchronized(this) {
                instance ?: DocumentRepository(
                    context.applicationContext,
                    AppDatabase.get(context),
                    StorageManager(context.applicationContext)
                ).also { instance = it }
            }
    }
}
