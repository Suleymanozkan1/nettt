package com.bookmind.app.processing

import com.bookmind.app.data.settings.AppLanguage
import android.content.Context
import androidx.core.graphics.createBitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.bookmind.app.data.files.ImportException
import com.bookmind.core.text.EpubParser
import com.bookmind.core.text.ExtractedBook
import com.bookmind.core.text.FileTypeSniffer
import com.bookmind.core.text.MarkdownParser
import com.bookmind.core.text.PdfStructureBuilder
import com.bookmind.core.text.PlainTextParser
import com.bookmind.core.text.SourceType
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Turns a stored source file into structured text. Runs on Dispatchers.IO, never on the UI thread. */
@Singleton
class TextExtractor @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun extract(file: File, type: SourceType, onProgress: (Int) -> Unit): ExtractedBook = withContext(Dispatchers.IO) {
        when (type) {
            SourceType.PDF -> extractPdf(file, onProgress)
            SourceType.EPUB -> try { EpubParser.parse(file, AppLanguage.tag) } catch (e: EpubParser.EpubException) {
                if (e.message?.contains("DRM") == true) throw ImportException(ImportException.Reason.DRM, e.message) else throw e
            }
            SourceType.MARKDOWN -> MarkdownParser.parse(FileTypeSniffer.decodeText(file.readBytes()), AppLanguage.tag)
            SourceType.TXT, SourceType.MANUAL -> PlainTextParser.parse(FileTypeSniffer.decodeText(file.readBytes()), AppLanguage.tag)
        }
    }

    private suspend fun extractPdf(file: File, onProgress: (Int) -> Unit): ExtractedBook {
        PDFBoxResourceLoader.init(context)
        val doc = try {
            // Temp-file backed buffers keep memory flat for large PDFs.
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
        } catch (e: InvalidPasswordException) {
            throw ImportException(ImportException.Reason.DRM, "password protected")
        }
        doc.use {
            if (doc.isEncrypted && !doc.currentAccessPermission.canExtractContent()) throw ImportException(ImportException.Reason.DRM, "extraction not permitted")
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val pageCount = doc.numberOfPages
            val pages = ArrayList<String>(pageCount)
            for (i in 1..pageCount) {
                coroutineContext.ensureActive()
                stripper.startPage = i
                stripper.endPage = i
                pages += runCatching { stripper.getText(doc) }.getOrDefault("")
                if (i % 10 == 0) onProgress(i * 100 / pageCount)
            }
            val outline = mutableListOf<PdfStructureBuilder.OutlineEntry>()
            fun walk(node: PDOutlineNode, depth: Int) {
                for (item in node.children()) {
                    val page = runCatching { item.findDestinationPage(doc) }.getOrNull()
                    val index = page?.let { doc.pages.indexOf(it) } ?: -1
                    // Top-level entries are chapters; go one level deeper only when there is a single top entry (book title).
                    if (index >= 0 && depth == 0) outline += PdfStructureBuilder.OutlineEntry(item.title.orEmpty(), index)
                    if (depth == 0 && node.children().count() == 1) walk(item, depth)
                }
            }
            doc.documentCatalog.documentOutline?.let { walk(it, 0) }
            val info = doc.documentInformation
            return PdfStructureBuilder.build(pages, outline, lang = AppLanguage.tag).copy(
                title = info?.title?.takeIf { it.isNotBlank() },
                author = info?.author?.takeIf { it.isNotBlank() },
            )
        }
    }

    /**
     * OCR for scanned PDFs using on-device ML Kit text recognition (Latin script, covers Turkish letters).
     * Quality depends on the scan; the UI tells the user that OCR text may contain errors.
     */
    suspend fun ocrPdf(file: File, maxPages: Int = 400, onProgress: (Int) -> Unit): ExtractedBook = withContext(Dispatchers.Default) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    val count = minOf(renderer.pageCount, maxPages)
                    val pages = ArrayList<String>(count)
                    for (i in 0 until count) {
                        coroutineContext.ensureActive()
                        renderer.openPage(i).use { page ->
                            val scale = 1600f / page.width
                            val bmp = createBitmap((page.width * scale).toInt(), (page.height * scale).toInt())
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            pages += suspendCancellableCoroutine<String> { cont ->
                                recognizer.process(InputImage.fromBitmap(bmp, 0))
                                    .addOnSuccessListener { cont.resume(it.text) }
                                    .addOnFailureListener { cont.resumeWithException(it) }
                            }
                            bmp.recycle()
                        }
                        onProgress((i + 1) * 100 / count)
                    }
                    val book = PdfStructureBuilder.build(pages, emptyList(), lang = AppLanguage.tag)
                    val warnings = book.warnings + listOf(AppLanguage.text.ocrWarning) +
                        (if (renderer.pageCount > maxPages) listOf(AppLanguage.text.onlyFirstPages(maxPages)) else emptyList())
                    if (book.needsOcr || book.chapters.isEmpty()) throw ImportException(ImportException.Reason.NO_TEXT, "OCR found no text")
                    book.copy(warnings = warnings)
                }
            }
        } finally {
            recognizer.close()
        }
    }
}
