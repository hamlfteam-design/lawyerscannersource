package com.personal.docscanner.data.repo

import com.personal.docscanner.data.db.AppDatabase
import com.personal.docscanner.data.db.FieldDefEntity
import com.personal.docscanner.data.model.FieldType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Puts a usable set of save fields in place on first launch.
 *
 * These are a starting point for a case-file workflow — client, case number,
 * court, case type — not a fixed schema. Every one of them can be renamed,
 * retyped, scoped to a single folder, or deleted from the Fields screen, and
 * new ones added. Seeding only ever runs when the table is empty, so an edited
 * set is never overwritten.
 */
object Seeder {

    suspend fun seedIfEmpty(db: AppDatabase) = withContext(Dispatchers.IO) {
        val fields = db.fieldDao()
        if (fields.defCount() > 0) return@withContext

        DEFAULTS.forEachIndexed { index, spec ->
            fields.upsertDef(
                FieldDefEntity(
                    id = "seed_${spec.key}",
                    name = spec.name,
                    type = spec.type.name,
                    options = spec.options.joinToString(","),
                    required = spec.required,
                    defaultValue = "",
                    folderId = null,
                    sortOrder = index
                )
            )
        }
    }

    private data class FieldSpec(
        val key: String,
        val name: String,
        val type: FieldType,
        val options: List<String> = emptyList(),
        val required: Boolean = false
    )

    private val DEFAULTS = listOf(
        FieldSpec("client", "اسم العميل", FieldType.TEXT, required = true),
        FieldSpec("case_no", "رقم القضية", FieldType.TEXT),
        FieldSpec("case_year", "السنة", FieldType.TEXT),
        FieldSpec("court", "المحكمة", FieldType.TEXT),
        FieldSpec(
            "case_type", "نوع القضية", FieldType.SELECT,
            options = listOf("مدني", "جنائي", "أحوال شخصية", "تجاري", "إداري", "عمالي", "تنفيذ")
        ),
        FieldSpec(
            "doc_type", "نوع المستند", FieldType.SELECT,
            options = listOf(
                "صحيفة دعوى", "حافظة مستندات", "مذكرة دفاع", "حكم",
                "توكيل", "عقد", "إعلان", "إيصال", "بطاقة", "أخرى"
            )
        )
    )
}
