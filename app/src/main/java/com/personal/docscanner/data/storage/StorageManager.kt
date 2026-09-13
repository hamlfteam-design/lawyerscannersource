package com.personal.docscanner.data.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale

/**
 * Owns every byte the app writes.
 *
 * The document library lives on **internal storage** (`filesDir/docs/`), which is
 * private to the app and never uploaded anywhere. Exports are the only thing that
 * leaves that sandbox, and only when the user asks: they go to the shared
 * `Documents/` folder so a file manager can see them.
 */
class StorageManager(private val context: Context) {

    private val libraryRoot: File
        get() = File(context.filesDir, DIR_LIBRARY).apply { mkdirs() }

    private val shareCache: File
        get() = File(context.cacheDir, DIR_SHARE).apply { mkdirs() }

    fun docDir(documentId: String): File =
        File(libraryRoot, documentId).apply { mkdirs() }

    fun pageFile(documentId: String, fileName: String): File =
        File(docDir(documentId), fileName)

    fun pagePath(documentId: String, fileName: String): String =
        pageFile(documentId, fileName).absolutePath

    // ---------------------------------------------------------------- writing

    fun writeJpeg(target: File, bitmap: Bitmap, quality: Int = JPEG_QUALITY): File {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            out.flush()
        }
        return target
    }

    fun writeThumb(documentId: String, thumbName: String, source: Bitmap): File {
        val scale = THUMB_MAX_EDGE.toFloat() / maxOf(source.width, source.height).toFloat()
        val thumb = if (scale >= 1f) {
            source
        } else {
            Bitmap.createScaledBitmap(
                source,
                (source.width * scale).toInt().coerceAtLeast(1),
                (source.height * scale).toInt().coerceAtLeast(1),
                true
            )
        }
        val file = writeJpeg(pageFile(documentId, thumbName), thumb, THUMB_QUALITY)
        if (thumb !== source) thumb.recycle()
        return file
    }

    fun copyFromUri(uri: Uri, target: File): File {
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open $uri" }
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        return target
    }

    // ---------------------------------------------------------------- reading

    fun decode(file: File, maxEdge: Int = 0): Bitmap? {
        if (!file.exists()) return null
        if (maxEdge <= 0) return BitmapFactory.decodeFile(file.absolutePath)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    fun decodeUri(uri: Uri, maxEdge: Int): Bitmap? {
        fun open(): InputStream? = context.contentResolver.openInputStream(uri)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return open().use { BitmapFactory.decodeStream(it, null, opts) }
    }

    // --------------------------------------------------------------- deleting

    fun deleteDoc(documentId: String) {
        docDir(documentId).deleteRecursively()
    }

    fun deleteFile(documentId: String, fileName: String) {
        pageFile(documentId, fileName).delete()
    }

    fun librarySizeBytes(): Long =
        libraryRoot.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    // --------------------------------------------------------------- exporting

    /**
     * Publishes [source] into the device's shared `Documents/Scanner/` folder,
     * optionally inside [subPath] (e.g. `"Ahmed Ali/Case 1204"`), which is how a
     * whole client tree gets mirrored onto storage a file manager can reach.
     *
     * On API 29+ this goes through MediaStore — the only way to create nested
     * public directories under scoped storage. Older devices write files directly.
     *
     * @return a human-readable destination, for the confirmation message.
     */
    fun exportToDocuments(
        source: File,
        displayName: String,
        mimeType: String,
        subPath: String = ""
    ): String {
        val relative = buildString {
            append(EXPORT_DIR)
            val cleaned = subPath.trim('/')
            if (cleaned.isNotEmpty()) {
                append('/')
                append(cleaned)
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOCUMENTS}/$relative"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
                ?: error("MediaStore rejected the insert")
            try {
                resolver.openOutputStream(uri).use { out ->
                    requireNotNull(out) { "cannot open $uri for writing" }
                    source.inputStream().use { it.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (t: Throwable) {
                // A pending row that never got its bytes would be invisible clutter.
                runCatching { resolver.delete(uri, null, null) }
                throw t
            }
            "Documents/$relative/$displayName"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                relative
            ).apply { mkdirs() }
            val target = File(dir, displayName)
            source.copyTo(target, overwrite = true)
            "Documents/$relative/$displayName"
        }
    }

    /**
     * MediaStore will not overwrite: inserting a duplicate name yields `name (1)`.
     * Callers that want deterministic names ask for a free one first.
     */
    fun existsInExport(subPath: String, displayName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                if (subPath.isEmpty()) EXPORT_DIR else "$EXPORT_DIR/${subPath.trim('/')}"
            )
            return File(dir, displayName).exists()
        }
        val relative = if (subPath.isEmpty()) EXPORT_DIR else "$EXPORT_DIR/${subPath.trim('/')}"
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection =
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        val args = arrayOf("${Environment.DIRECTORY_DOCUMENTS}/$relative%", displayName)
        return context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            projection, selection, args, null
        )?.use { it.count > 0 } ?: false
    }

    /** A copy in the cache that a FileProvider can hand to another app. */
    fun shareableUri(source: File, displayName: String): Uri {
        val target = File(shareCache, displayName)
        if (source.absolutePath != target.absolutePath) source.copyTo(target, overwrite = true)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }

    fun tempFile(name: String): File = File(shareCache, name)

    fun clearShareCache() {
        shareCache.listFiles()?.forEach { it.delete() }
    }

    companion object {
        private const val DIR_LIBRARY = "docs"
        private const val DIR_SHARE = "share"
        /**
         * The exported tree lands under `Documents/<EXPORT_DIR>/<client>/<case>/…`
         * so a case file always reads as "قضايا العملاء ← اسم العميل ← رقم/اسم
         * القضية" from a plain file manager — no app needed to make sense of it.
         */
        const val EXPORT_DIR = "قضايا العملاء"
        const val JPEG_QUALITY = 88
        private const val THUMB_QUALITY = 80
        private const val THUMB_MAX_EDGE = 480

        fun humanSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB")
            var value = bytes / 1024.0
            var unit = 0
            while (value >= 1024 && unit < units.lastIndex) {
                value /= 1024.0
                unit++
            }
            return String.format(Locale.US, "%.1f %s", value, units[unit])
        }

        /** Strips characters that are illegal in a file name on any Android volume. */
        fun sanitizeFileName(raw: String, fallback: String = "document"): String {
            val cleaned = raw.trim()
                .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim('.', ' ')
                .take(80)
            return cleaned.ifEmpty { fallback }
        }
    }
}
