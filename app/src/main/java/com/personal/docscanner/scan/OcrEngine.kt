package com.personal.docscanner.scan

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Offline text recognition.
 *
 * Tesseract is used rather than ML Kit because ML Kit's on-device recognizer has
 * no Arabic model, and Arabic is the primary language here.
 */
class OcrEngine(context: Context) {

    private val appContext = context.applicationContext
    private val tessData = TessDataManager(appContext)

    sealed interface Result {
        data class Success(val text: String) : Result
        data class MissingLanguages(val languages: List<String>) : Result
        data class Failure(val message: String) : Result
    }

    fun missingLanguages(languageSpec: String): List<String> = tessData.missing(languageSpec)

    /**
     * Recognises text across [pageFiles] in order, one page at a time so peak
     * memory stays bounded no matter how long the document is.
     */
    suspend fun recognize(
        pageFiles: List<File>,
        languageSpec: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Result = withContext(Dispatchers.Default) {
        val missing = tessData.missing(languageSpec)
        if (missing.isNotEmpty()) return@withContext Result.MissingLanguages(missing)
        if (pageFiles.isEmpty()) return@withContext Result.Success("")

        val api = TessBaseAPI()
        try {
            if (!api.init(tessData.dataPath.absolutePath, languageSpec)) {
                return@withContext Result.Failure("Tesseract init failed for '$languageSpec'")
            }
            api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO_OSD

            val builder = StringBuilder()
            pageFiles.forEachIndexed { index, file ->
                coroutineContext.ensureActive()
                onProgress(index + 1, pageFiles.size)

                val bitmap = loadForOcr(file) ?: return@forEachIndexed
                try {
                    api.setImage(bitmap)
                    val text = api.getUTF8Text()?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        if (builder.isNotEmpty()) builder.append("\n\n")
                        if (pageFiles.size > 1) builder.append("— ${index + 1} —\n")
                        builder.append(text)
                    }
                } finally {
                    api.clear()
                    bitmap.recycle()
                }
            }
            Result.Success(builder.toString().trim())
        } catch (t: Throwable) {
            Result.Failure(t.message ?: t::class.java.simpleName)
        } finally {
            runCatching { api.recycle() }
        }
    }

    /**
     * Tesseract wants a reasonably large, high-contrast image; feeding it the
     * full 12-megapixel capture is slower without being more accurate.
     */
    private fun loadForOcr(file: File): Bitmap? {
        val decoded = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val limited = ImageProcessor.limitSize(decoded, OCR_MAX_EDGE)
        if (limited !== decoded) decoded.recycle()
        val sharpened = ImageProcessor.sharpenForOcr(limited)
        if (sharpened !== limited) limited.recycle()
        return sharpened
    }

    private companion object {
        const val OCR_MAX_EDGE = 2200
    }
}
