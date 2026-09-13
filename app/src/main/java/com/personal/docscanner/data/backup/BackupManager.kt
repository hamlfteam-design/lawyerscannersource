package com.personal.docscanner.data.backup

import android.content.Context
import android.net.Uri
import com.personal.docscanner.data.db.AppDatabase
import com.personal.docscanner.data.db.DocumentEntity
import com.personal.docscanner.data.db.FieldDefEntity
import com.personal.docscanner.data.db.FieldValueEntity
import com.personal.docscanner.data.db.FolderEntity
import com.personal.docscanner.data.db.PageEntity
import com.personal.docscanner.data.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup and restore of the whole library into a single file the user places
 * wherever they like — Drive, a memory card, another phone.
 *
 * The file is a ZIP: `data.json` holds every database row, `files/` holds every
 * page image. A plain ZIP was chosen over a database copy because it survives a
 * schema change — restore reads named fields and fills in defaults for anything
 * a newer version added.
 *
 * When a password is given the whole ZIP is encrypted (see [BackupCrypto]) and
 * the result is not a readable archive at all, which is the point: a backup of
 * case files sitting in someone's cloud drive should be meaningless without the
 * password.
 */
class BackupManager(
    private val context: Context,
    private val db: AppDatabase,
    private val storage: StorageManager
) {
    data class Progress(val done: Int, val total: Int, val label: String)

    data class Summary(
        val folders: Int,
        val documents: Int,
        val pages: Int,
        val fields: Int,
        val bytes: Long,
        val encrypted: Boolean
    )

    class RestoreFailed(message: String, cause: Throwable? = null) : Exception(message, cause)

    // ----------------------------------------------------------------- write

    /**
     * Writes a backup to [target].
     *
     * @param password when non-null the archive is encrypted with it. There is
     *   no recovery path if it is forgotten — that is what encryption means.
     */
    suspend fun backupTo(
        target: Uri,
        password: CharArray? = null,
        onProgress: (Progress) -> Unit = {}
    ): Summary = withContext(Dispatchers.IO) {
        val folders = db.folderDao().childrenOnceAll()
        val documents = db.documentDao().allOnce()
        val fieldDefs = db.fieldDao().allDefsOnce()

        val pages = ArrayList<PageEntity>()
        val values = ArrayList<FieldValueEntity>()
        documents.forEach { doc ->
            pages += db.pageDao().forDoc(doc.id)
            values += db.fieldDao().values(doc.id)
        }

        val json = encodeLibrary(folders, documents, pages, fieldDefs, values)

        // Encrypted backups are staged to a temp file first: the cipher needs to
        // wrap the finished archive, and a ZIP cannot be encrypted as it streams.
        val staging = if (password == null) null else File(context.cacheDir, "backup.tmp")

        try {
            val rawOut: OutputStream = staging?.outputStream()
                ?: openTruncating(target)

            var written = 0
            rawOut.use { out ->
                ZipOutputStream(out.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(ENTRY_MANIFEST))
                    zip.write(manifest(documents.size, encrypted = password != null).toByteArray())
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry(ENTRY_DATA))
                    zip.write(json.toByteArray())
                    zip.closeEntry()

                    val total = pages.size
                    pages.forEach { page ->
                        onProgress(Progress(written, total, page.documentId))
                        // Originals are included so a restored library can still
                        // be re-cropped; thumbnails are not, they are regenerated.
                        listOfNotNull(page.fileName, page.originalName).forEach { name ->
                            val file = storage.pageFile(page.documentId, name)
                            if (file.exists()) {
                                zip.putNextEntry(ZipEntry("$DIR_FILES/${page.documentId}/$name"))
                                file.inputStream().use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                        }
                        written++
                    }
                    onProgress(Progress(total, total, ""))
                }
            }

            if (staging != null) {
                openTruncating(target).use { out ->
                    BackupCrypto.encrypt(staging.inputStream(), out, password!!)
                }
            }

            val size = sizeOf(target)
            Summary(
                folders = folders.size,
                documents = documents.size,
                pages = pages.size,
                fields = fieldDefs.size,
                bytes = size,
                encrypted = password != null
            )
        } finally {
            staging?.delete()
            password?.fill('\u0000')
        }
    }

    // ------------------------------------------------------------------ read

    /**
     * Reads a backup's header without restoring, so the UI can say what is in
     * the file — and whether a password will be needed — before touching
     * anything.
     */
    suspend fun inspect(source: Uri): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                val head = ByteArray(BackupCrypto.MAGIC.size)
                val read = input.read(head)
                if (read == head.size && head.contentEquals(BackupCrypto.MAGIC)) {
                    return@use JSONObject().put(KEY_ENCRYPTED, true)
                }
                null
            }
        }.getOrNull() ?: readManifest(source)
    }

    private fun readManifest(source: Uri): JSONObject? = runCatching {
        context.contentResolver.openInputStream(source)?.use { input ->
            ZipInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == ENTRY_MANIFEST) {
                        return@use JSONObject(zip.readBytes().decodeToString())
                            .put(KEY_ENCRYPTED, false)
                    }
                    entry = zip.nextEntry
                }
                null
            }
        }
    }.getOrNull()

    /**
     * Replaces the current library with the contents of [source].
     *
     * Replace, not merge: merging would need a rule for every conflict — same
     * document edited on both sides, a folder deleted here but not there — and
     * guessing wrong silently corrupts a case file. The caller warns first.
     */
    suspend fun restoreFrom(
        source: Uri,
        password: CharArray? = null,
        onProgress: (Progress) -> Unit = {}
    ): Summary = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore.tmp")
        val stagedFiles = File(context.cacheDir, "restore_files")
        try {
            stagedFiles.deleteRecursively()
            context.contentResolver.openInputStream(source).use { input ->
                requireNotNull(input) { "cannot open the backup file" }
                if (password != null) {
                    staging.outputStream().use { out ->
                        BackupCrypto.decrypt(input, out, password)
                    }
                } else {
                    staging.outputStream().use { out -> input.copyTo(out) }
                }
            }

            var data: JSONObject? = null

            // Unpacked to disk rather than held in memory. A real library is
            // hundreds of megabytes of page images; keeping them all as byte
            // arrays would run the app out of memory before it wrote a single
            // one, which would make a backup that can never be restored.
            ZipInputStream(staging.inputStream().buffered()).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    when {
                        name == ENTRY_DATA -> data = JSONObject(zip.readBytes().decodeToString())
                        name.startsWith("$DIR_FILES/") -> {
                            // files/<docId>/<fileName>
                            val parts = name.removePrefix("$DIR_FILES/").split('/', limit = 2)
                            // A path that climbs out of the staging directory is
                            // a malformed archive, not a page.
                            if (parts.size == 2 && parts.none { it.contains("..") }) {
                                val target = File(File(stagedFiles, parts[0]), parts[1])
                                target.parentFile?.mkdirs()
                                target.outputStream().buffered().use { out -> zip.copyTo(out) }
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val library = data ?: throw RestoreFailed("the file does not contain a library")

            // Everything is read before anything is deleted, so a corrupt backup
            // cannot leave the app with neither the old nor the new library.
            clearLibrary()

            val summary = decodeLibrary(library, stagedFiles, onProgress)
            summary.copy(bytes = staging.length(), encrypted = password != null)
        } catch (t: RestoreFailed) {
            throw t
        } catch (t: Throwable) {
            throw RestoreFailed(t.message ?: "the backup could not be read", t)
        } finally {
            staging.delete()
            stagedFiles.deleteRecursively()
            password?.fill('\u0000')
        }
    }

    private suspend fun clearLibrary() {
        db.documentDao().allOnce().forEach { doc ->
            db.documentDao().deleteById(doc.id)
            storage.deleteDoc(doc.id)
        }
        db.folderDao().childrenOnceAll().forEach { db.folderDao().deleteById(it.id) }
        db.fieldDao().allDefsOnce().forEach { db.fieldDao().deleteDef(it.id) }
    }

    // -------------------------------------------------------------- encoding

    private fun encodeLibrary(
        folders: List<FolderEntity>,
        documents: List<DocumentEntity>,
        pages: List<PageEntity>,
        fieldDefs: List<FieldDefEntity>,
        values: List<FieldValueEntity>
    ): String {
        val root = JSONObject()
        root.put("folders", JSONArray().apply {
            folders.forEach {
                put(JSONObject()
                    .put("id", it.id).put("name", it.name)
                    .put("parentId", it.parentId ?: JSONObject.NULL)
                    .put("colorArgb", it.colorArgb).put("sortOrder", it.sortOrder)
                    .put("locked", it.locked).put("createdAt", it.createdAt))
            }
        })
        root.put("documents", JSONArray().apply {
            documents.forEach {
                put(JSONObject()
                    .put("id", it.id).put("title", it.title)
                    .put("folderId", it.folderId ?: JSONObject.NULL)
                    .put("note", it.note).put("tags", it.tags)
                    .put("ocrText", it.ocrText).put("favorite", it.favorite)
                    .put("createdAt", it.createdAt).put("updatedAt", it.updatedAt))
            }
        })
        root.put("pages", JSONArray().apply {
            pages.forEach {
                put(JSONObject()
                    .put("id", it.id).put("documentId", it.documentId)
                    .put("position", it.position).put("fileName", it.fileName)
                    .put("thumbName", it.thumbName)
                    .put("originalName", it.originalName ?: JSONObject.NULL)
                    .put("filter", it.filter).put("rotation", it.rotation)
                    .put("quad", it.quad ?: JSONObject.NULL)
                    .put("brightness", it.brightness).put("contrast", it.contrast)
                    .put("snapFormat", it.snapFormat ?: JSONObject.NULL)
                    .put("createdAt", it.createdAt))
            }
        })
        root.put("fieldDefs", JSONArray().apply {
            fieldDefs.forEach {
                put(JSONObject()
                    .put("id", it.id).put("name", it.name).put("type", it.type)
                    .put("options", it.options).put("required", it.required)
                    .put("defaultValue", it.defaultValue)
                    .put("folderId", it.folderId ?: JSONObject.NULL)
                    .put("sortOrder", it.sortOrder))
            }
        })
        root.put("fieldValues", JSONArray().apply {
            values.forEach {
                put(JSONObject()
                    .put("documentId", it.documentId)
                    .put("fieldId", it.fieldId)
                    .put("value", it.value))
            }
        })
        return root.toString()
    }

    private suspend fun decodeLibrary(
        root: JSONObject,
        stagedFiles: File,
        onProgress: (Progress) -> Unit
    ): Summary {
        fun JSONObject.nullableString(key: String): String? =
            if (isNull(key)) null else optString(key)

        val folders = root.optJSONArray("folders") ?: JSONArray()
        for (i in 0 until folders.length()) {
            val o = folders.getJSONObject(i)
            db.folderDao().upsert(
                FolderEntity(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    parentId = o.nullableString("parentId"),
                    colorArgb = o.optInt("colorArgb"),
                    sortOrder = o.optInt("sortOrder"),
                    locked = o.optBoolean("locked"),
                    createdAt = o.optLong("createdAt")
                )
            )
        }

        val documents = root.optJSONArray("documents") ?: JSONArray()
        for (i in 0 until documents.length()) {
            val o = documents.getJSONObject(i)
            db.documentDao().upsert(
                DocumentEntity(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    folderId = o.nullableString("folderId"),
                    note = o.optString("note"),
                    tags = o.optString("tags"),
                    ocrText = o.optString("ocrText"),
                    favorite = o.optBoolean("favorite"),
                    createdAt = o.optLong("createdAt"),
                    updatedAt = o.optLong("updatedAt")
                )
            )
        }

        val fieldDefs = root.optJSONArray("fieldDefs") ?: JSONArray()
        for (i in 0 until fieldDefs.length()) {
            val o = fieldDefs.getJSONObject(i)
            db.fieldDao().upsertDef(
                FieldDefEntity(
                    id = o.getString("id"),
                    name = o.optString("name"),
                    type = o.optString("type"),
                    options = o.optString("options"),
                    required = o.optBoolean("required"),
                    defaultValue = o.optString("defaultValue"),
                    folderId = o.nullableString("folderId"),
                    sortOrder = o.optInt("sortOrder")
                )
            )
        }

        // Files land before the page rows that reference them, so the library is
        // never briefly showing pages whose images are not on disk yet.
        stagedFiles.listFiles()?.forEach { docDir ->
            if (!docDir.isDirectory) return@forEach
            docDir.listFiles()?.forEach { file ->
                val target = storage.pageFile(docDir.name, file.name)
                target.parentFile?.mkdirs()
                // Moved rather than copied: the staging copy is about to be
                // deleted anyway, and a rename does not need a second copy of
                // the library's worth of images on a phone that may be short of
                // space. Falls back to copying across filesystem boundaries.
                if (!file.renameTo(target)) file.copyTo(target, overwrite = true)
            }
        }

        val pages = root.optJSONArray("pages") ?: JSONArray()
        var restored = 0
        for (i in 0 until pages.length()) {
            val o = pages.getJSONObject(i)
            onProgress(Progress(i, pages.length(), o.optString("documentId")))
            val page = PageEntity(
                id = o.getString("id"),
                documentId = o.getString("documentId"),
                position = o.optInt("position"),
                fileName = o.optString("fileName"),
                thumbName = o.optString("thumbName"),
                originalName = o.nullableString("originalName"),
                filter = o.optString("filter"),
                rotation = o.optInt("rotation"),
                quad = o.nullableString("quad"),
                brightness = o.optInt("brightness"),
                contrast = o.optInt("contrast"),
                snapFormat = o.nullableString("snapFormat"),
                createdAt = o.optLong("createdAt")
            )
            db.pageDao().insert(page)
            // Thumbnails are not in the archive; regenerate from the page image.
            regenerateThumb(page)
            restored++
        }

        val values = root.optJSONArray("fieldValues") ?: JSONArray()
        val restoredValues = ArrayList<FieldValueEntity>(values.length())
        for (i in 0 until values.length()) {
            val o = values.getJSONObject(i)
            restoredValues += FieldValueEntity(
                documentId = o.getString("documentId"),
                fieldId = o.getString("fieldId"),
                value = o.optString("value")
            )
        }
        if (restoredValues.isNotEmpty()) db.fieldDao().upsertValues(restoredValues)

        onProgress(Progress(pages.length(), pages.length(), ""))
        return Summary(
            folders = folders.length(),
            documents = documents.length(),
            pages = restored,
            fields = fieldDefs.length(),
            bytes = 0,
            encrypted = false
        )
    }

    private fun regenerateThumb(page: PageEntity) {
        runCatching {
            val source = storage.decode(storage.pageFile(page.documentId, page.fileName))
                ?: return
            storage.writeThumb(page.documentId, page.thumbName, source)
            source.recycle()
        }
    }

    // ------------------------------------------------------------- utilities

    private fun manifest(documentCount: Int, encrypted: Boolean): String =
        JSONObject()
            .put("format", FORMAT_VERSION)
            .put("app", context.packageName)
            .put("documents", documentCount)
            .put("encrypted", encrypted)
            .toString()

    private fun sizeOf(uri: Uri): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
    }.getOrNull() ?: 0L

    /**
     * Opens [target] for writing, truncating whatever is there.
     *
     * "wt" rather than "w": overwriting a long backup with a shorter one under
     * plain "w" leaves the tail of the old file behind, which silently produces
     * a corrupt archive. Not every provider implements "wt", so a provider that
     * rejects it falls back — those providers truncate on "w" anyway.
     */
    private fun openTruncating(target: Uri): OutputStream {
        val resolver = context.contentResolver
        return runCatching { resolver.openOutputStream(target, "wt") }.getOrNull()
            ?: resolver.openOutputStream(target)
            ?: throw RestoreFailed("cannot open the destination for writing")
    }

    /**
     * `scanner-office-2026-08-07-1430.scanbak`
     *
     * Dated so successive backups sort chronologically, and labelled so backups
     * from two devices do not look interchangeable in the same cloud folder.
     */
    fun suggestedFileName(dateStamp: String, encrypted: Boolean, label: String = ""): String {
        val slug = StorageManager.sanitizeFileName(label, fallback = "").replace(" ", "-")
        val middle = if (slug.isEmpty()) "" else "$slug-"
        return "scanner-$middle$dateStamp" + if (encrypted) EXT_ENCRYPTED else EXT_PLAIN
    }

    /**
     * True when [uri] can still be written to — the file may have been deleted,
     * moved, or had its permission revoked since the last backup.
     *
     * Deliberately does NOT open the file for writing to find out: some
     * providers truncate on open, so the check itself would destroy the very
     * backup it is verifying. Instead it confirms the grant is still held and
     * that the document still answers a query.
     */
    fun canStillWrite(uri: Uri): Boolean {
        val resolver = context.contentResolver
        val granted = resolver.persistedUriPermissions.any {
            it.uri == uri && it.isWritePermission
        }
        if (!granted) return false
        return runCatching {
            resolver.query(uri, null, null, null, null)?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val ENTRY_MANIFEST = "manifest.json"
        const val ENTRY_DATA = "data.json"
        const val DIR_FILES = "files"
        const val KEY_ENCRYPTED = "encrypted"

        /** Distinct extensions so the picker and the user can tell them apart. */
        const val EXT_PLAIN = ".zip"
        const val EXT_ENCRYPTED = ".scanbak"

        val MIME_TYPES = arrayOf("application/zip", "application/octet-stream", "*/*")
    }
}
