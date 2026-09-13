package com.personal.docscanner.data.importer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.personal.docscanner.data.model.PageFilter
import com.personal.docscanner.data.repo.DocumentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Brings PDFs exported from another scanner app into the library.
 *
 * Two shapes, matching how the material comes out of an export:
 *  - [importPdfs] for a hand-picked set of files, all landing in one folder
 *  - [importTree] for a whole exported directory, whose folder structure is
 *    recreated here — which is the point when the structure *is* the filing
 *    system, as it is for client → case
 */
class LibraryImporter(
    context: Context,
    private val repo: DocumentRepository
) {
    private val appContext = context.applicationContext
    private val pdf = PdfImporter(appContext)

    data class Progress(
        val documentsDone: Int,
        val documentsTotal: Int,
        val currentName: String,
        val pageDone: Int = 0,
        val pageTotal: Int = 0
    )

    data class Result(
        val documents: Int,
        val pages: Int,
        val folders: Int,
        val skipped: List<String>,
        /** Papers moved into a client's folder because they name that client. */
        val linkedToClients: Int = 0
    )

    /** A document imported into a folder that is not a client's. */
    private data class Loose(val documentId: String, val title: String)

    // ------------------------------------------------------------ flat files

    suspend fun importPdfs(
        uris: List<Uri>,
        folderId: String?,
        onProgress: (Progress) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val skipped = ArrayList<String>()
        var documents = 0
        var pages = 0

        val indexer = FilingIndexer.create(repo)
        // Hand-picked files carry no tree of their own, so the index comes from
        // where they are being put — which is the same client/case structure.
        val chain = repo.breadcrumbs(folderId).map { it.name }.filter { it != "/" }
        val filing = FilingIndexer.filingFor(chain)

        uris.forEachIndexed { index, uri ->
            coroutineContext.ensureActive()
            val name = pdf.displayName(uri)
            onProgress(Progress(index, uris.size, name))
            runCatching {
                importOne(uri, folderId, name, uris.size, index, onProgress, indexer, filing)
            }
                .onSuccess { documents++; pages += it.pages }
                .onFailure { skipped += "$name — ${it.message ?: "failed"}" }
        }

        onProgress(Progress(uris.size, uris.size, ""))
        Result(documents, pages, folders = 0, skipped = skipped)
    }

    // ------------------------------------------------------------------ tree

    /**
     * Walks [treeUri] and mirrors it: every directory becomes a folder here,
     * every PDF becomes a document inside the folder that matches its place in
     * the source tree.
     *
     * Empty directories are still created. An exported case folder that happens
     * to hold nothing yet is information — it says the case exists.
     */
    suspend fun importTree(
        treeUri: Uri,
        parentFolderId: String?,
        onProgress: (Progress) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(appContext, treeUri)
            ?: throw IllegalArgumentException("could not open that folder")

        // Counted up front so progress is against a real total rather than a
        // guess that keeps moving as the walk discovers more files.
        val total = countPdfs(root)
        val skipped = ArrayList<String>()
        var documents = 0
        var pages = 0
        var folders = 0

        val indexer = FilingIndexer.create(repo)

        // Folders holding clients, and the documents that landed outside them.
        // Both are collected during the walk and used afterwards, because a
        // power of attorney can be read before the client folder it belongs to
        // has been created.
        val clientFolders = LinkedHashMap<String, String>()
        val loose = ArrayList<Loose>()

        suspend fun walk(dir: DocumentFile, targetFolderId: String?, chain: List<String>) {
            coroutineContext.ensureActive()

            dir.listFiles().forEach { entry ->
                coroutineContext.ensureActive()
                when {
                    entry.isDirectory -> {
                        val folderName = entry.name.orEmpty().ifEmpty { "مجلد" }
                        val childId = repo.createFolder(
                            name = folderName,
                            parentId = targetFolderId,
                            colorArgb = FOLDER_COLOR
                        )
                        folders++
                        // A folder sitting directly inside "قضايا العملاء" is a
                        // client. Anything else — توكيلات, شهادات, فواتير — is a
                        // pile sorted by kind of paper, not by whose it is.
                        if (isClientsRoot(chain.lastOrNull())) {
                            clientFolders[folderName] = childId
                        }
                        walk(entry, childId, chain + folderName)
                    }

                    entry.isFile && entry.isPdf() -> {
                        val name = entry.name.orEmpty().removeSuffix(".pdf").ifEmpty { "مستند" }
                        onProgress(Progress(documents, total, name))
                        runCatching {
                            importOne(
                                uri = entry.uri,
                                folderId = targetFolderId,
                                title = name,
                                total = total,
                                done = documents,
                                onProgress = onProgress,
                                indexer = indexer,
                                filing = FilingIndexer.filingFor(chain)
                            )
                        }.onSuccess { result ->
                            documents++
                            pages += result.pages
                            // Loose means "not already somewhere under the
                            // clients folder". Testing only the immediate parent
                            // would call a document inside a *case* folder loose
                            // and move it up out of its own case.
                            if (chain.none { isClientsRoot(it) }) {
                                loose += Loose(result.documentId, name)
                            }
                        }.onFailure { skipped += "$name — ${it.message ?: "failed"}" }
                    }
                }
            }
        }

        // The chosen folder itself becomes a folder here, so importing
        // "أحمد محمود" does not scatter its cases across the library root.
        val rootName = root.name.orEmpty().ifEmpty { "استيراد" }
        val rootId = repo.createFolder(
            name = rootName,
            parentId = parentFolderId,
            colorArgb = FOLDER_COLOR
        )
        folders++
        // The chain starts at the picked folder, and also carries whatever this
        // import is being dropped into, so an import inside an existing client
        // folder still reads that client's name off the tree.
        val outerChain = repo.breadcrumbs(parentFolderId).map { it.name }.filter { it != "/" }
        walk(root, rootId, outerChain + rootName)

        val linked = linkLooseToClients(loose, clientFolders, indexer)

        onProgress(Progress(total, total, ""))
        Result(documents, pages, folders, skipped, linked)
    }

    /**
     * Moves papers that name a client into that client's folder.
     *
     * A power of attorney or a copy of an identity card belongs to the person,
     * not to any one case — it is used across all of their matters — so a
     * توكيلات folder full of them is really a pile waiting to be distributed.
     * Where the file names the client, this does the distributing.
     *
     * Where it does not, or names two clients equally well, the document stays
     * exactly where it was. A power of attorney filed under the wrong client is
     * worse than one left in a folder called توكيلات, which is at least where it
     * was expected to be.
     */
    private suspend fun linkLooseToClients(
        loose: List<Loose>,
        clientFolders: Map<String, String>,
        indexer: FilingIndexer
    ): Int {
        if (loose.isEmpty() || clientFolders.isEmpty()) return 0

        var linked = 0
        loose.forEach { item ->
            coroutineContext.ensureActive()
            val client = ArabicNames.bestMatch(item.title, clientFolders.keys) ?: return@forEach
            val folderId = clientFolders[client] ?: return@forEach
            runCatching {
                repo.moveDocument(item.documentId, folderId)
                indexer.apply(
                    item.documentId,
                    FilingIndexer.Filing(client = client, caseNumber = "", year = "")
                )
            }.onSuccess { linked++ }
        }
        return linked
    }

    /** True when [folderName] is the folder clients are kept in. */
    private fun isClientsRoot(folderName: String?): Boolean {
        val name = folderName ?: return false
        return CLIENT_ROOT_WORDS.any { name.contains(it) }
    }

    // ----------------------------------------------------------------- shared

    private data class Imported(val documentId: String, val pages: Int)

    private suspend fun importOne(
        uri: Uri,
        folderId: String?,
        title: String,
        total: Int,
        done: Int,
        onProgress: (Progress) -> Unit,
        indexer: FilingIndexer,
        filing: FilingIndexer.Filing
    ): Imported {
        val docId = repo.createDocument(folderId, title)
        var count = 0
        pdf.renderPages(
            uri = uri,
            onProgress = { page, pageTotal ->
                onProgress(Progress(done, total, title, page, pageTotal))
            }
        ) { page ->
            // ORIGINAL and no crop quad: the source is already a finished scan.
            repo.addPage(
                documentId = docId,
                original = page.bitmap,
                quad = null,
                filter = PageFilter.ORIGINAL
            )
            page.bitmap.recycle()
            count++
        }

        // A PDF that turned out to have no renderable pages would otherwise
        // leave an empty document behind.
        if (count == 0) {
            repo.deleteDocument(docId)
            throw PdfImporter.NotReadable("no pages could be read")
        }

        // Indexed only after the pages are in, so a file that turned out to be
        // unreadable does not leave a client and case number behind.
        indexer.apply(docId, filing)
        return Imported(docId, count)
    }

    private fun countPdfs(dir: DocumentFile): Int {
        var total = 0
        val queue = ArrayDeque<DocumentFile>()
        queue.add(dir)
        var guard = 0
        while (queue.isNotEmpty() && guard++ < MAX_ENTRIES) {
            val current = queue.removeFirst()
            current.listFiles().forEach { entry ->
                if (entry.isDirectory) queue.add(entry)
                else if (entry.isPdf()) total++
            }
        }
        return total
    }

    private fun DocumentFile.isPdf(): Boolean =
        type == "application/pdf" || name.orEmpty().endsWith(".pdf", ignoreCase = true)

    private companion object {
        /** Neutral slate; imported folders are recoloured by hand if wanted. */
        const val FOLDER_COLOR = 0xFF5A6E7F.toInt()

        /** Backstop against a pathological or cyclic provider tree. */
        const val MAX_ENTRIES = 20_000

        /**
         * Names for the folder that holds clients. Matched by substring so
         * "قضايا العملاء", "العملاء" and "ملفات العميل" all count.
         */
        val CLIENT_ROOT_WORDS = listOf("عملاء", "العميل", "عميل", "clients", "client")
    }
}
