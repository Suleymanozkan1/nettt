package com.bookmind.app.data.remote

import com.bookmind.app.BuildConfig
import com.bookmind.app.util.AppClock
import com.bookmind.app.util.AppJson
import com.bookmind.core.sync.SupabaseRest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

data class BackendConfig(val url: String, val anonKey: String, val allowLocalHttp: Boolean = false) {
    /** HTTPS only; plain HTTP is accepted for a local Supabase (emulator host/localhost) in debug builds and tests. */
    val isConfigured: Boolean
        get() = anonKey.isNotBlank() && (url.startsWith("https://") || (allowLocalHttp && Regex("^http://(localhost|127\\.0\\.0\\.1|10\\.0\\.2\\.2)(:\\d+)?(/.*)?$").matches(url)))
    val aiEndpoint: String get() = url.trimEnd('/') + "/functions/v1/ai"

    companion object {
        fun fromBuildConfig() = BackendConfig(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, allowLocalHttp = BuildConfig.DEBUG)
    }
}

/** Optional cloud account. The app is fully usable without signing in (local-only mode). */
@Singleton
class AuthRepository @Inject constructor(
    private val config: BackendConfig,
    private val store: SessionStore,
    private val clock: AppClock,
    http: OkHttpClient,
) {
    @Serializable
    data class StoredSession(val accessToken: String, val refreshToken: String, val expiresAt: Long, val userId: String, val email: String?)

    data class Account(val userId: String, val email: String?)

    private val rest: SupabaseRest? = if (config.isConfigured) SupabaseRest(config.url, config.anonKey, http) else null
    private val mutex = Mutex()
    private var session: StoredSession? = store.read()?.let { runCatching { AppJson.decodeFromString(StoredSession.serializer(), it) }.getOrNull() }
    private val _account = MutableStateFlow(session?.let { Account(it.userId, it.email) })
    val account: StateFlow<Account?> = _account.asStateFlow()

    val isBackendConfigured: Boolean get() = rest != null
    fun client(): SupabaseRest? = rest

    private fun save(s: SupabaseRest.Session?) {
        val stored = s?.let {
            StoredSession(it.accessToken, it.refreshToken, it.expiry(clock.now() / 1000), it.user?.id ?: session?.userId.orEmpty(), it.user?.email ?: session?.email)
        }
        session = stored
        store.write(stored?.let { AppJson.encodeToString(StoredSession.serializer(), it) })
        _account.value = stored?.let { Account(it.userId, it.email) }
    }

    /** @return true when signed in immediately, false when email confirmation is pending. */
    suspend fun signUp(email: String, password: String): Boolean {
        val r = rest ?: error("backend not configured")
        val s = r.signUp(email.trim(), password)
        if (s != null) save(s)
        return s != null
    }

    suspend fun signIn(email: String, password: String) {
        val r = rest ?: error("backend not configured")
        save(r.signIn(email.trim(), password))
    }

    suspend fun signOut() {
        val token = session?.accessToken
        save(null)
        if (token != null) runCatching { rest?.signOut(token) }
    }

    /** Returns a valid access token, refreshing it a minute before expiry. Null when signed out. */
    suspend fun accessToken(): String? = mutex.withLock {
        val s = session ?: return null
        if (s.expiresAt - 60 > clock.now() / 1000) return s.accessToken
        val refreshed = runCatching { rest?.refresh(s.refreshToken) }.getOrElse { e ->
            // Invalid refresh token: sign out locally; network errors keep the session for a later retry.
            if (e is SupabaseRest.HttpException && e.code in 400..499) save(null)
            null
        } ?: return null
        save(refreshed)
        session?.accessToken
    }
}
