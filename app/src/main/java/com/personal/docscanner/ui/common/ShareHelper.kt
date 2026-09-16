package com.personal.docscanner.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a finished document to another app.
 *
 * Files live in the app's private storage, so nothing can be shared by path —
 * every hand-off goes through a FileProvider content:// URI with a one-shot read
 * grant, which is also what keeps the rest of the library unreachable to the
 * receiving app.
 */
object ShareHelper {

    /** Consumer WhatsApp, then WhatsApp Business. */
    private val WHATSAPP_PACKAGES = listOf("com.whatsapp", "com.whatsapp.w4b")

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun isWhatsAppInstalled(context: Context): Boolean =
        installedWhatsApp(context) != null

    private fun installedWhatsApp(context: Context): String? =
        WHATSAPP_PACKAGES.firstOrNull { pkg ->
            runCatching {
                context.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
            }.isSuccess
        }

    /**
     * Sends [file] straight into WhatsApp's contact picker.
     *
     * @return false if WhatsApp is not installed, so the caller can fall back to
     *         the system chooser rather than showing an error dead end.
     */
    fun sendToWhatsApp(
        context: Context,
        file: File,
        mimeType: String = "application/pdf",
        message: String? = null
    ): Boolean {
        val pkg = installedWhatsApp(context) ?: return false
        val intent = buildSendIntent(context, listOf(file), mimeType, message).apply {
            setPackage(pkg)
        }
        return try {
            context.startActivity(intent.withNewTask())
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    fun sendMultipleToWhatsApp(
        context: Context,
        files: List<File>,
        mimeType: String = "application/pdf",
        message: String? = null
    ): Boolean {
        val pkg = installedWhatsApp(context) ?: return false
        val intent = buildSendIntent(context, files, mimeType, message).apply { setPackage(pkg) }
        return try {
            context.startActivity(intent.withNewTask())
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /**
     * Opens an email compose screen addressed to [recipient] with [file]
     * attached — "بريد إلكتروني إلى نفسي" being the recurring case, though any
     * address works. `message/rfc822` is what routes an ACTION_SEND to mail
     * apps specifically instead of the full share sheet WhatsApp and Drive
     * also answer to.
     *
     * @return false if nothing on the device handles mail composition, so the
     *   caller can fall back to the general chooser instead of a dead tap.
     */
    fun emailTo(
        context: Context,
        recipient: String,
        subject: String,
        file: File
    ): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return try {
            context.startActivity(Intent.createChooser(intent, subject).withNewTask())
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** The system chooser — email, Drive, Bluetooth, anything registered. */
    fun share(
        context: Context,
        files: List<File>,
        mimeType: String = "application/pdf",
        message: String? = null,
        chooserTitle: String = ""
    ) {
        val intent = buildSendIntent(context, files, mimeType, message)
        context.startActivity(Intent.createChooser(intent, chooserTitle).withNewTask())
    }

    private fun buildSendIntent(
        context: Context,
        files: List<File>,
        mimeType: String,
        message: String?
    ): Intent {
        val uris = files.map { uriFor(context, it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        return intent.apply {
            type = mimeType
            if (!message.isNullOrBlank()) putExtra(Intent.EXTRA_TEXT, message)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * A share started from a non-Activity context (a ViewModel's application
     * context) needs its own task, or Android refuses to launch it.
     */
    private fun Intent.withNewTask(): Intent = apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
