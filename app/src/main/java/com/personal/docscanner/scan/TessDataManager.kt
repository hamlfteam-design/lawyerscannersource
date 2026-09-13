package com.personal.docscanner.scan

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Tesseract needs a `tessdata/<lang>.traineddata` file per language.
 *
 * They are looked for in three places, in order:
 *  1. already installed under `filesDir/tessdata/`
 *  2. bundled in the APK under `assets/tessdata/` (drop files there to make the
 *     app fully offline from first launch — see the project README)
 *  3. downloaded once, on an explicit user tap, from the official tessdata_fast
 *     repository. This is the only network call the app ever makes.
 */
class TessDataManager(private val context: Context) {

    /** Tesseract expects the *parent* of the tessdata directory. */
    val dataPath: File get() = context.filesDir

    private val tessDir: File get() = File(dataPath, DIR).apply { mkdirs() }

    fun fileFor(lang: String): File = File(tessDir, "$lang.traineddata")

    fun isInstalled(lang: String): Boolean =
        fileFor(lang).let { it.exists() && it.length() > MIN_VALID_BYTES }

    /** True when every language in a "ara+eng" style spec is present. */
    fun areInstalled(languageSpec: String): Boolean =
        parseLanguages(languageSpec).all { isInstalled(it) }

    fun missing(languageSpec: String): List<String> =
        parseLanguages(languageSpec).filterNot { isInstalled(it) }

    /**
     * Copies any bundled language files out of assets. Cheap and safe to call on
     * every launch — it skips languages that are already in place.
     */
    suspend fun installFromAssets() = withContext(Dispatchers.IO) {
        val assets = runCatching { context.assets.list(DIR)?.toList() }.getOrNull().orEmpty()
        assets.filter { it.endsWith(EXT) }.forEach { name ->
            val target = File(tessDir, name)
            if (target.exists() && target.length() > MIN_VALID_BYTES) return@forEach
            runCatching {
                context.assets.open("$DIR/$name").use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }.onFailure { target.delete() }
        }
    }

    /**
     * Downloads the missing languages. [onProgress] reports 0f..1f overall.
     * Throws on failure so the caller can surface the reason.
     */
    suspend fun download(
        languageSpec: String,
        onProgress: (Float) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val langs = missing(languageSpec)
        if (langs.isEmpty()) {
            onProgress(1f)
            return@withContext
        }

        langs.forEachIndexed { index, lang ->
            val base = index.toFloat() / langs.size
            val span = 1f / langs.size
            downloadOne(lang) { fraction -> onProgress(base + fraction * span) }
        }
        onProgress(1f)
    }

    private fun downloadOne(lang: String, onProgress: (Float) -> Unit) {
        val target = fileFor(lang)
        // Write to a temp name so an interrupted download never leaves a
        // half-file that later reads as "installed".
        val temp = File(tessDir, "$lang$EXT.part")
        temp.delete()

        val connection = (URL("$BASE_URL$lang$EXT").openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
            var written = 0L

            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                    }
                    output.flush()
                }
            }

            if (temp.length() <= MIN_VALID_BYTES) {
                throw IllegalStateException("file too small (${temp.length()} bytes)")
            }
            target.delete()
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            onProgress(1f)
        } catch (t: Throwable) {
            temp.delete()
            throw t
        } finally {
            connection.disconnect()
        }
    }

    fun installedLanguages(): List<String> =
        tessDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(EXT) && it.length() > MIN_VALID_BYTES }
            ?.map { it.name.removeSuffix(EXT) }
            ?.sorted()
            .orEmpty()

    companion object {
        private const val DIR = "tessdata"
        private const val EXT = ".traineddata"

        /** A GitHub error page would be a few KB; a real model is over a megabyte. */
        private const val MIN_VALID_BYTES = 200_000L

        /**
         * tessdata_fast: the integer-quantised models. Roughly a third the size of
         * the standard ones and several times faster on a phone, at a negligible
         * accuracy cost for printed documents.
         */
        private const val BASE_URL =
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/"

        val SUPPORTED = listOf(
            "ara" to "العربية",
            "eng" to "English",
            "fra" to "Français",
            "deu" to "Deutsch",
            "spa" to "Español",
            "tur" to "Türkçe"
        )

        fun parseLanguages(spec: String): List<String> =
            spec.split('+').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }
}
