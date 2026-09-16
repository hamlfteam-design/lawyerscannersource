package com.personal.docscanner.data.model

import androidx.annotation.StringRes
import com.personal.docscanner.R
import com.personal.docscanner.data.db.DocumentEntity
import com.personal.docscanner.data.db.FieldDefEntity
import com.personal.docscanner.data.db.FolderEntity
import com.personal.docscanner.data.db.PageEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The kinds of save field the user can define. */
enum class FieldType(@StringRes val labelRes: Int) {
    TEXT(R.string.type_text),
    NUMBER(R.string.type_number),
    DATE(R.string.type_date),
    SELECT(R.string.type_select),
    BOOL(R.string.type_bool);

    companion object {
        fun fromName(name: String): FieldType =
            entries.firstOrNull { it.name == name } ?: TEXT
    }
}

/** Image processing presets, mirroring the ones a scanner app is expected to have. */
enum class PageFilter(@StringRes val labelRes: Int) {
    /** One-tap correction: shadow removal, white balance, contrast, sharpen. */
    AUTO(R.string.filter_auto),
    ORIGINAL(R.string.filter_original),
    MAGIC_COLOR(R.string.filter_magic),
    GRAYSCALE(R.string.filter_gray),
    BLACK_WHITE(R.string.filter_bw),
    LIGHTEN(R.string.filter_lighten),
    /** Grayscale plus a smaller, harder-compressed file — a bundle of pages to send over WhatsApp. */
    ECONOMY(R.string.filter_economy);

    companion object {
        fun fromName(name: String): PageFilter =
            entries.firstOrNull { it.name == name } ?: AUTO
    }
}

enum class PdfPageSize(@StringRes val labelRes: Int, val widthPt: Int, val heightPt: Int) {
    FIT(R.string.pdf_size_fit, 0, 0),
    A4(R.string.pdf_size_a4, 595, 842),
    LETTER(R.string.pdf_size_letter, 612, 792)
}

enum class SortMode(@StringRes val labelRes: Int) {
    DATE_DESC(R.string.sort_date_desc),
    DATE_ASC(R.string.sort_date_asc),
    NAME(R.string.sort_name)
}

/** A field definition paired with the value stored for one document. */
data class FieldEntry(
    val def: FieldDefEntity,
    val value: String
) {
    val type: FieldType get() = FieldType.fromName(def.type)

    val options: List<String>
        get() = def.options.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    val boolValue: Boolean get() = value == "1"

    val dateValue: Long? get() = value.toLongOrNull()

    /** How the value should read in a list or on an export. */
    fun display(): String = when (type) {
        FieldType.BOOL -> if (boolValue) "✓" else "—"
        FieldType.DATE -> dateValue?.let { dateFormat.format(Date(it)) } ?: ""
        else -> value
    }

    companion object {
        val dateFormat: SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }
}

/** A document plus everything needed to render a row without extra queries. */
data class DocumentSummary(
    val doc: DocumentEntity,
    val pageCount: Int,
    val thumbPath: String?,
    val fields: List<FieldEntry>
) {
    val tagList: List<String>
        get() = doc.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}

/** A folder with the counts shown on its card. */
data class FolderSummary(
    val folder: FolderEntity,
    val docCount: Int,
    val subfolderCount: Int
)

data class DocumentDetail(
    val doc: DocumentEntity,
    val pages: List<PageEntity>,
    val fields: List<FieldEntry>,
    val folderPath: List<FolderEntity>
)

/** One breadcrumb step, used by the folder header. */
data class Crumb(val id: String?, val name: String)
