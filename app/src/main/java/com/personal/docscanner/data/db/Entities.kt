package com.personal.docscanner.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A folder in the user's own hierarchy. [parentId] is null for a top-level folder,
 * so folders nest to any depth — the structure is whatever the user builds by hand.
 */
@Entity(
    tableName = "folders",
    indices = [Index("parentId")]
)
data class FolderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val parentId: String?,
    val colorArgb: Int,
    val sortOrder: Int,
    val locked: Boolean,
    val createdAt: Long
)

@Entity(
    tableName = "documents",
    indices = [Index("folderId"), Index("updatedAt")]
)
data class DocumentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val folderId: String?,
    val note: String,
    /** Comma-separated, normalized lowercase for search. */
    val tags: String,
    /** Concatenated OCR result across pages; empty until OCR runs. */
    val ocrText: String,
    val favorite: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * One scanned page. Files are stored on internal storage under
 * `filesDir/docs/<documentId>/`; only relative names are kept in the DB so the
 * library survives an app data path change.
 */
@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("documentId")]
)
data class PageEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val position: Int,
    /** Processed page image (what the user sees and what gets exported). */
    val fileName: String,
    val thumbName: String,
    /** Untouched capture, kept so cropping can be redone without quality loss. */
    val originalName: String?,
    val filter: String,
    val rotation: Int,
    /** Crop quad in original-image pixel coordinates, "x1,y1;x2,y2;x3,y3;x4,y4". */
    val quad: String?,
    val brightness: Int,
    val contrast: Int,
    /**
     * The paper format this page was snapped to, as a [DocumentFormat] name, or
     * null to re-detect from the crop.
     *
     * Stored because a choice the app cannot make for itself must survive being
     * re-rendered. A4 and a passport page differ by 0.44% in shape, which is
     * finer than a photograph can be measured, so only the user knows which one
     * a page is — and without this the answer would be thrown away and
     * re-guessed the next time the page was rotated or re-cropped.
     */
    val snapFormat: String? = null,
    /**
     * False for a page saved straight from the shutter or from an outside
     * file, before edge detection and auto-enhancement have run on it.
     *
     * Capture is deliberately dumb and fast — it just files the raw shot —
     * and a whole session's pages are swept through detection and enhancement
     * together once the user taps "تم". Until that sweep, [fileName] holds the
     * same untouched image as [originalName], so a page never sits half-done
     * on screen. True for anything already processed: a re-crop, an edit, or
     * a page from an app version before this flag existed.
     */
    val processed: Boolean = true,
    val createdAt: Long
)

/**
 * A user-defined save field. This is the piece that goes beyond a fixed schema:
 * the user declares which fields exist, their type, and whether they apply
 * everywhere or only inside one folder.
 */
@Entity(
    tableName = "field_defs",
    indices = [Index("folderId")]
)
data class FieldDefEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** One of [com.personal.docscanner.data.model.FieldType] names. */
    val type: String,
    /** Comma-separated choices; only meaningful for SELECT. */
    val options: String,
    val required: Boolean,
    val defaultValue: String,
    /** null = applies to every folder. */
    val folderId: String?,
    val sortOrder: Int
)

@Entity(
    tableName = "field_values",
    primaryKeys = ["documentId", "fieldId"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = FieldDefEntity::class,
            parentColumns = ["id"],
            childColumns = ["fieldId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("fieldId"), Index("value")]
)
data class FieldValueEntity(
    val documentId: String,
    val fieldId: String,
    /**
     * Everything is stored as text. Numbers use plain decimal, dates use epoch
     * millis, booleans use "1"/"0". Keeping one column makes user-defined fields
     * possible without schema migrations every time a field is added.
     */
    val value: String
)
