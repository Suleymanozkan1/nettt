package com.bookmind.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Streams a model file into [part], resuming from the bytes already there with an HTTP range request, and checks
 * size and SHA-256 before the caller moves it into place. Network problems surface as [IOException] so the
 * caller can retry later without losing what was downloaded.
 */
class ModelDownloader(private val client: OkHttpClient) {

    suspend fun download(url: String, expectedBytes: Long, part: File, onProgress: suspend (Long) -> Unit) = withContext(Dispatchers.IO) {
        var have = part.length().takeIf { it <= expectedBytes } ?: 0L.also { part.delete() }
        if (have == expectedBytes) return@withContext
        val request = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                206 -> Unit
                200 -> { have = 0; part.delete() } // the server ignored the range: start over
                416 -> { part.delete(); throw IOException("range not satisfiable") }
                else -> throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("empty body")
            FileOutputStream(part, have > 0).use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        if (have > expectedBytes) throw IOException("larger than expected")
                        onProgress(have)
                    }
                }
            }
        }
        if (have != expectedBytes) throw IOException("incomplete: $have of $expectedBytes")
    }

    suspend fun verify(file: File, expectedBytes: Long, sha256: String): Boolean = withContext(Dispatchers.IO) {
        if (file.length() != expectedBytes) return@withContext false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                ensureActive()
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }.equals(sha256, ignoreCase = true)
    }
}
