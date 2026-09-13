package com.personal.docscanner.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    @Query("SELECT * FROM folders ORDER BY sortOrder, name")
    fun observeAll(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE parentId IS :parentId ORDER BY sortOrder, name")
    fun observeChildren(parentId: String?): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id")
    suspend fun byId(id: String): FolderEntity?

    /** One-shot read, for traversals that must not observe mid-walk changes. */
    @Query("SELECT * FROM folders WHERE parentId IS :parentId ORDER BY sortOrder, name")
    suspend fun childrenOnce(parentId: String?): List<FolderEntity>

    /** Every folder, one-shot. Used by backup. */
    @Query("SELECT * FROM folders ORDER BY sortOrder, name")
    suspend fun childrenOnceAll(): List<FolderEntity>

    @Query("SELECT COUNT(*) FROM documents WHERE folderId = :folderId")
    fun observeDocCount(folderId: String): Flow<Int>

    @Upsert
    suspend fun upsert(folder: FolderEntity)

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("UPDATE folders SET locked = :locked WHERE id = :id")
    suspend fun setLocked(id: String, locked: Boolean)

    /**
     * Deleting a folder must not orphan its contents: children and documents are
     * lifted to the deleted folder's parent, then the row goes away.
     */
    @Transaction
    suspend fun deleteAndReparent(id: String) {
        val parent = byId(id)?.parentId
        reparentFolders(id, parent)
        reparentDocuments(id, parent)
        deleteById(id)
    }

    @Query("UPDATE folders SET parentId = :newParent WHERE parentId = :oldParent")
    suspend fun reparentFolders(oldParent: String, newParent: String?)

    @Query("UPDATE documents SET folderId = :newParent WHERE folderId = :oldParent")
    suspend fun reparentDocuments(oldParent: String, newParent: String?)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents WHERE folderId IS :folderId ORDER BY updatedAt DESC")
    fun observeInFolder(folderId: String?): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id")
    fun observe(id: String): Flow<DocumentEntity?>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun byId(id: String): DocumentEntity?

    /** One-shot read, used by folder-level export. */
    @Query("SELECT * FROM documents WHERE folderId IS :folderId ORDER BY createdAt")
    suspend fun inFolderOnce(folderId: String?): List<DocumentEntity>

    /** Every document, one-shot. Used by backup. */
    @Query("SELECT * FROM documents ORDER BY createdAt")
    suspend fun allOnce(): List<DocumentEntity>

    /**
     * Free-text search across the built-in columns *and* every custom field value,
     * so a document is findable by anything the user typed into it.
     */
    @Query(
        """
        SELECT DISTINCT d.* FROM documents d
        LEFT JOIN field_values fv ON fv.documentId = d.id
        WHERE d.title LIKE '%' || :q || '%'
           OR d.note LIKE '%' || :q || '%'
           OR d.tags LIKE '%' || :q || '%'
           OR d.ocrText LIKE '%' || :q || '%'
           OR fv.value LIKE '%' || :q || '%'
        ORDER BY d.updatedAt DESC
        """
    )
    fun search(q: String): Flow<List<DocumentEntity>>

    @Upsert
    suspend fun upsert(doc: DocumentEntity)

    @Update
    suspend fun update(doc: DocumentEntity)

    @Query("UPDATE documents SET folderId = :folderId, updatedAt = :now WHERE id = :id")
    suspend fun move(id: String, folderId: String?, now: Long)

    @Query("UPDATE documents SET favorite = :favorite, updatedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean, now: Long)

    @Query("UPDATE documents SET ocrText = :text, updatedAt = :now WHERE id = :id")
    suspend fun setOcrText(id: String, text: String, now: Long)

    @Query("UPDATE documents SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface PageDao {

    @Query("SELECT * FROM pages WHERE documentId = :docId ORDER BY position")
    fun observeForDoc(docId: String): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE documentId = :docId ORDER BY position")
    suspend fun forDoc(docId: String): List<PageEntity>

    @Query("SELECT * FROM pages WHERE documentId = :docId ORDER BY position LIMIT 1")
    suspend fun firstPage(docId: String): PageEntity?

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun byId(id: String): PageEntity?

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM pages WHERE documentId = :docId")
    suspend fun nextPosition(docId: String): Int

    /** Pages still carrying their raw capture, waiting for the end-of-session sweep. */
    @Query("SELECT * FROM pages WHERE documentId = :docId AND processed = 0 ORDER BY position")
    suspend fun unprocessedForDoc(docId: String): List<PageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(page: PageEntity)

    @Update
    suspend fun update(page: PageEntity)

    @Query("UPDATE pages SET position = :position WHERE id = :id")
    suspend fun setPosition(id: String, position: Int)

    @Transaction
    suspend fun reorder(orderedIds: List<String>) {
        orderedIds.forEachIndexed { index, id -> setPosition(id, index) }
    }

    @Delete
    suspend fun delete(page: PageEntity)

    @Query("DELETE FROM pages WHERE documentId = :docId")
    suspend fun deleteForDoc(docId: String)
}

@Dao
interface FieldDao {

    @Query("SELECT * FROM field_defs ORDER BY sortOrder, name")
    fun observeAllDefs(): Flow<List<FieldDefEntity>>

    /** Global fields plus the ones scoped to this folder. */
    @Query(
        """
        SELECT * FROM field_defs
        WHERE folderId IS NULL OR folderId = :folderId
        ORDER BY sortOrder, name
        """
    )
    fun observeDefsForFolder(folderId: String?): Flow<List<FieldDefEntity>>

    @Query(
        """
        SELECT * FROM field_defs
        WHERE folderId IS NULL OR folderId = :folderId
        ORDER BY sortOrder, name
        """
    )
    suspend fun defsForFolder(folderId: String?): List<FieldDefEntity>

    @Query("SELECT * FROM field_defs WHERE id = :id")
    suspend fun defById(id: String): FieldDefEntity?

    @Query("SELECT COUNT(*) FROM field_defs")
    suspend fun defCount(): Int

    /** Every field definition, one-shot. Used by backup. */
    @Query("SELECT * FROM field_defs ORDER BY sortOrder, name")
    suspend fun allDefsOnce(): List<FieldDefEntity>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM field_defs")
    suspend fun nextSortOrder(): Int

    @Upsert
    suspend fun upsertDef(def: FieldDefEntity)

    @Query("DELETE FROM field_defs WHERE id = :id")
    suspend fun deleteDef(id: String)

    @Query("SELECT * FROM field_values WHERE documentId = :docId")
    fun observeValues(docId: String): Flow<List<FieldValueEntity>>

    @Query("SELECT * FROM field_values WHERE documentId = :docId")
    suspend fun values(docId: String): List<FieldValueEntity>

    @Upsert
    suspend fun upsertValue(value: FieldValueEntity)

    @Upsert
    suspend fun upsertValues(values: List<FieldValueEntity>)

    @Query("DELETE FROM field_values WHERE documentId = :docId AND fieldId = :fieldId")
    suspend fun deleteValue(docId: String, fieldId: String)

    /** Distinct previously-entered values for a field, to power autocomplete. */
    @Query(
        """
        SELECT DISTINCT value FROM field_values
        WHERE fieldId = :fieldId AND value != ''
        ORDER BY value LIMIT 50
        """
    )
    suspend fun suggestions(fieldId: String): List<String>
}
