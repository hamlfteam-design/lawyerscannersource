package com.personal.docscanner.scan

import android.util.Log
import org.opencv.android.OpenCVLoader

/**
 * OpenCV's native library has to be initialised once before any Mat is touched.
 * Every entry point into image processing goes through [ensureLoaded] so a
 * failure degrades to "no auto-detection" instead of crashing the app.
 */
object OpenCvLoader {

    @Volatile
    private var loaded: Boolean? = null

    fun ensureLoaded(): Boolean {
        loaded?.let { return it }
        return synchronized(this) {
            loaded ?: runCatching { OpenCVLoader.initLocal() }
                .onFailure { Log.e(TAG, "OpenCV init threw", it) }
                .getOrDefault(false)
                .also {
                    loaded = it
                    if (!it) Log.e(TAG, "OpenCV failed to initialise; auto-detect disabled")
                }
        }
    }

    private const val TAG = "OpenCvLoader"
}
