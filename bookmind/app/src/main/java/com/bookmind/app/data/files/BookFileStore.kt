package com.bookmind.app.data.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.bookmind.core.text.FileTypeSniffer
import com.bookmind.core.text.SourceType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

class ImportException(val reason: Reason, message: String? = null) : IOException(message ?: reason.name) {
    enum class Reason { TOO_LARGE, EMPTY, UNSUPPORTED, UNREADABLE, DUPLICATE, DRM, NO_TEXT }
}

/**
 * App-private storage for imported books. Files are streamed (never fully loaded into memory), size-limited,
 * checksummed while copying and type-checked by magic bytes. Everything lives under filesDir/books/<bookId>.
 */
@Singleton
class BookFileStore @Inject constructor(@ApplicationContext private val context: Context) {
    data class Imported(val relativePath: String, val checksum: String, val size: Long, val type: SourceType, val originalName: String?)

    private val root get() = File(context.filesDir, "books")

    fun bookDir(bookId: String): File = File(root, bookId).apply { mkdirs() }
    fun resolve(relative: String): File = File(context.filesDir, relative)

    fun describe(uri: Uri): Pair<String?, Long?> =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else {
                val name = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
                val size = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
                name to size
            }
        } ?: (uri.lastPathSegment to null)

    /** Copies [uri] into the book directory. Throws [ImportException] for oversize, empty or unsupported files. */
    fun import(uri: Uri, bookId: String): Imported {
        val (name, declaredSize) = describe(uri)
        if (declaredSize != null && declaredSize > FileTypeSniffer.MAX_BOOK_BYTES) throw ImportException(ImportException.Reason.TOO_LARGE)
        val header = context.contentResolver.openInputStream(uri)?.use { input ->
            val buf = ByteArray(1024); val n = input.read(buf); if (n <= 0) ByteArray(0) else buf.copyOf(n)
        } ?: throw ImportException(ImportException.Reason.UNREADABLE)
        if (header.isEmpty()) throw ImportException(ImportException.Reason.EMPTY)
        val type = FileTypeSniffer.detect(name ?: "book", header) ?: throw ImportException(ImportException.Reason.UNSUPPORTED)

        val ext = when (type) { SourceType.PDF -> "pdf"; SourceType.EPUB -> "epub"; SourceType.MARKDOWN -> "md"; else -> "txt" }
        val target = File(bookDir(bookId), "source.$ext")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                DigestInputStream(raw, digest).use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > FileTypeSniffer.MAX_BOOK_BYTES) throw ImportException(ImportException.Reason.TOO_LARGE)
                            out.write(buf, 0, n)
                        }
                    }
                }
            } ?: throw ImportException(ImportException.Reason.UNREADABLE)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        val checksum = digest.digest().joinToString("") { "%02x".format(it) }
        return Imported(target.relativeTo(context.filesDir).path, checksum, total, type, name)
    }

    /** Stores pasted/manual text as a book source. */
    fun importText(text: String, bookId: String, markdown: Boolean): Imported {
        val bytes = text.toByteArray()
        if (bytes.isEmpty()) throw ImportException(ImportException.Reason.EMPTY)
        if (bytes.size > FileTypeSniffer.MAX_BOOK_BYTES) throw ImportException(ImportException.Reason.TOO_LARGE)
        val target = File(bookDir(bookId), if (markdown) "source.md" else "source.txt")
        target.writeBytes(bytes)
        val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return Imported(target.relativeTo(context.filesDir).path, checksum, bytes.size.toLong(), if (markdown) SourceType.MARKDOWN else SourceType.TXT, null)
    }

    fun writeChapterText(bookId: String, index: Int, text: String): String {
        val dir = File(bookDir(bookId), "chapters").apply { mkdirs() }
        val f = File(dir, "$index.txt")
        // Write to a temp file and rename so an interrupted write never leaves a half chapter behind.
        val tmp = File(dir, "$index.txt.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        return f.relativeTo(context.filesDir).path
    }

    fun readChapterText(relative: String): String = resolve(relative).takeIf { it.exists() }?.readText().orEmpty()

    fun deleteBook(bookId: String) { File(root, bookId).deleteRecursively() }

    fun deleteAll() { root.deleteRecursively() }

    fun totalBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
