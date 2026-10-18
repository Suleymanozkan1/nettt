package com.bookmind.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Minimal Supabase client over plain HTTP: GoTrue auth, PostgREST rows and Storage objects. Row level security
 * on the server is what actually protects data; this client only ever sends the user's own JWT.
 */
class SupabaseRest(
    baseUrl: String,
    private val anonKey: String,
    client: OkHttpClient = OkHttpClient(),
) {
    private val base = baseUrl.trimEnd('/')
    private val http = client.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonType = "application/json".toMediaType()

    class HttpException(val code: Int, message: String) : IOException("HTTP $code: $message")

    @Serializable
    data class Session(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresIn: Long = 3600,
        @SerialName("expires_at") val expiresAt: Long? = null,
        val user: User? = null,
    ) {
        /** Epoch seconds when the access token expires (computed if the server did not send it). */
        fun expiry(nowSeconds: Long): Long = expiresAt ?: (nowSeconds + expiresIn)
    }

    @Serializable
    data class User(val id: String, val email: String? = null)

    @Serializable
    private data class Credentials(val email: String, val password: String)

    @Serializable
    private data class Refresh(@SerialName("refresh_token") val refreshToken: String)

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpException(r.code, body.take(300))
            body
        }
    }

    private fun builder(path: String, token: String? = null) = Request.Builder()
        .url("$base$path")
        .header("apikey", anonKey)
        .header("Authorization", "Bearer ${token ?: anonKey}")

    suspend fun signUp(email: String, password: String): Session? {
        val body = execute(builder("/auth/v1/signup").post(json.encodeToString(Credentials.serializer(), Credentials(email, password)).toRequestBody(jsonType)).build())
        // With email confirmation enabled the server returns the user but no session yet.
        return runCatching { json.decodeFromString(Session.serializer(), body) }.getOrNull()
    }

    suspend fun signIn(email: String, password: String): Session =
        json.decodeFromString(
            Session.serializer(),
            execute(builder("/auth/v1/token?grant_type=password").post(json.encodeToString(Credentials.serializer(), Credentials(email, password)).toRequestBody(jsonType)).build()),
        )

    suspend fun refresh(refreshToken: String): Session =
        json.decodeFromString(
            Session.serializer(),
            execute(builder("/auth/v1/token?grant_type=refresh_token").post(json.encodeToString(Refresh.serializer(), Refresh(refreshToken)).toRequestBody(jsonType)).build()),
        )

    suspend fun signOut(token: String) {
        execute(builder("/auth/v1/logout", token).post(ByteArray(0).toRequestBody(null)).build())
    }

    /** Upserts rows; the server fills user_id from the JWT and RLS rejects anything else. */
    suspend fun upsert(table: String, rows: List<JsonObject>, token: String) {
        if (rows.isEmpty()) return
        execute(
            builder("/rest/v1/$table", token)
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(JsonArray(rows).toString().toRequestBody(jsonType))
                .build(),
        )
    }

    suspend fun delete(table: String, id: String, token: String) {
        val url = "$base/rest/v1/$table".toHttpUrl().newBuilder().addQueryParameter("id", "eq.$id").build()
        execute(Request.Builder().url(url).header("apikey", anonKey).header("Authorization", "Bearer $token").delete().build())
    }

    /** Rows changed after [sinceIso] (ISO-8601), ordered for deterministic paging. */
    suspend fun changedSince(table: String, column: String, sinceIso: String, token: String, limit: Int = 500, offset: Int = 0): List<JsonObject> {
        val url = "$base/rest/v1/$table".toHttpUrl().newBuilder()
            .addQueryParameter("select", "*")
            .addQueryParameter(column, "gt.$sinceIso")
            .addQueryParameter("order", "$column.asc")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .build()
        val body = execute(Request.Builder().url(url).header("apikey", anonKey).header("Authorization", "Bearer $token").get().build())
        return json.parseToJsonElement(body).let { (it as? JsonArray)?.mapNotNull { e -> e as? JsonObject } }.orEmpty()
    }

    suspend fun uploadFile(bucket: String, path: String, file: File, token: String, contentType: String) {
        val body: RequestBody = file.asRequestBody(contentType.toMediaType())
        execute(builder("/storage/v1/object/$bucket/$path", token).header("x-upsert", "true").post(body).build())
    }

    suspend fun downloadFile(bucket: String, path: String, target: File, token: String) = withContext(Dispatchers.IO) {
        http.newCall(builder("/storage/v1/object/$bucket/$path", token).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw HttpException(r.code, "download failed")
            val tmp = File(target.parentFile, target.name + ".part")
            r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(target)) { target.delete(); tmp.renameTo(target) }
        }
    }

    /** Calls an Edge Function with the user's token. */
    suspend fun invoke(function: String, token: String, body: String = "{}"): String =
        execute(builder("/functions/v1/$function", token).post(body.toRequestBody(jsonType)).build())
}
