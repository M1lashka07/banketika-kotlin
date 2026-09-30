package ru.banketika

import io.ktor.http.*
import io.ktor.server.application.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class Session(
    val csrf: String = randomToken(), var tokens: Tokens? = null,
    var expiresAt: Long = 0, var lastSeen: Long = System.currentTimeMillis(),
    val mutex: Mutex = Mutex()
)
fun randomToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

/** Tokens stay in server memory. Browser receives only an opaque random ID. */
class Sessions(private val secure: Boolean) {
    private val sessions = ConcurrentHashMap<String, Session>()
    private val lifetime = 8 * 60 * 60 * 1000L
    fun get(call: ApplicationCall): Session {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { now - it.value.lastSeen > lifetime }
        val id = call.request.cookies["banketika_session"]
        val existing = id?.let(sessions::get)
        if (existing != null) { existing.lastSeen = now; return existing }
        val session = Session()
        save(call, session)
        return session
    }
    private fun save(call: ApplicationCall, session: Session) {
        val id = randomToken()
        sessions[id] = session
        call.response.cookies.append(Cookie("banketika_session", id, path = "/", httpOnly = true, secure = secure,
            maxAge = (lifetime / 1000).toInt(), extensions = mapOf("SameSite" to "Lax")))
    }
    fun authenticate(call: ApplicationCall, tokens: Tokens) {
        require(tokens.access_token.isNotBlank() && tokens.refresh_token.isNotBlank())
        call.request.cookies["banketika_session"]?.let(sessions::remove)
        save(call, Session(tokens = tokens, expiresAt = System.currentTimeMillis() + tokens.expires_in * 1000))
    }
    fun clear(call: ApplicationCall) {
        call.request.cookies["banketika_session"]?.let(sessions::remove)
        call.response.cookies.append(Cookie("banketika_session", "", path = "/", maxAge = 0, httpOnly = true, secure = secure,
            extensions = mapOf("SameSite" to "Lax")))
    }
    fun csrf(session: Session, supplied: String?): Boolean = supplied != null && MessageDigest.isEqual(
        session.csrf.toByteArray(Charsets.UTF_8), supplied.toByteArray(Charsets.UTF_8))

    suspend fun jwt(session: Session, gateway: Gateway): String? = session.mutex.withLock {
        var tokens = session.tokens ?: return@withLock null
        if (System.currentTimeMillis() >= session.expiresAt - 60_000) {
            tokens = gateway.refresh(tokens.refresh_token)
            session.tokens = tokens
            session.expiresAt = System.currentTimeMillis() + tokens.expires_in * 1000
        }
        tokens.access_token
    }
}
