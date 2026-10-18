package com.bookmind.core.text

/**
 * Decides the source type from the file's magic bytes first and the extension second, so a renamed file
 * cannot smuggle an unexpected format into the parser.
 */
object FileTypeSniffer {
    const val MAX_BOOK_BYTES = 150L * 1024 * 1024

    fun detect(fileName: String, header: ByteArray): SourceType? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val isPdf = header.size >= 5 && String(header, 0, 5, Charsets.ISO_8859_1) == "%PDF-"
        val isZip = header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte() &&
            header[2] == 0x03.toByte() && header[3] == 0x04.toByte()
        return when {
            isPdf -> SourceType.PDF
            isZip && (ext == "epub" || String(header, Charsets.ISO_8859_1).contains("application/epub+zip")) -> SourceType.EPUB
            isZip -> null
            looksBinary(header) -> null
            ext == "md" || ext == "markdown" -> SourceType.MARKDOWN
            ext == "txt" || ext == "text" || ext.isEmpty() -> SourceType.TXT
            else -> null
        }
    }

    private fun looksBinary(header: ByteArray): Boolean {
        if (header.isEmpty()) return false
        val sample = header.take(1024)
        val zeros = sample.count { it == 0.toByte() }
        return zeros > 0
    }

    /** Decodes text files that are UTF-8 (with or without BOM), UTF-16 with BOM, or legacy Turkish Windows-1254. */
    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
        } catch (_: java.nio.charset.CharacterCodingException) {
            String(bytes, charset("windows-1254"))
        }
    }
}
