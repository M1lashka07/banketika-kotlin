package ru.banketika

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*

interface Gateway {
    suspend fun signup(values: Map<String, String>): Tokens
    suspend fun login(email: String, password: String): Tokens
    suspend fun refresh(refreshToken: String): Tokens
    suspend fun verify(hash: String): Tokens
    suspend fun logout(jwt: String)
    suspend fun user(jwt: String): AuthUser
    suspend fun actor(jwt: String): Actor
    suspend fun profiles(jwt: String): List<Profile>
    suspend fun banquets(jwt: String, owner: String? = null): List<Banquet>
    suspend fun create(jwt: String, banquet: NewBanquet)
    suspend fun status(jwt: String, id: String, status: String)
    suspend fun delete(jwt: String, id: String, owner: String? = null)
}

class Supabase(private val config: Config, val client: HttpClient = httpClient()) : Gateway {
    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun httpClient() = HttpClient(CIO) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 5_000 }
        }
    }
    private suspend fun request(method: HttpMethod, path: String, jwt: String? = null, body: Any? = null): HttpResponse {
        val response = client.request(config.supabaseUrl + path) {
            this.method = method
            header("apikey", config.publishableKey)
            if (jwt != null) bearerAuth(jwt)
            contentType(ContentType.Application.Json)
            header("Prefer", "return=representation")
            if (body != null) setBody(body)
        }
        if (response.status.value !in 200..299) {
            val error = runCatching { json.parseToJsonElement(response.bodyAsText()).jsonObject }.getOrNull()
            throw ApiProblem(response.status.value, error?.get("error_code")?.jsonPrimitive?.content
                ?: error?.get("code")?.jsonPrimitive?.content ?: "unavailable")
        }
        return response
    }
    override suspend fun signup(values: Map<String, String>): Tokens {
        val body = buildJsonObject {
            put("email", values.getValue("email")); put("password", values.getValue("password"))
            putJsonObject("data") { put("full_name", values.getValue("full_name")); put("phone", values.getValue("phone")) }
        }
        // With email confirmation enabled, signup returns a user WITHOUT access_token.
        return request(HttpMethod.Post, "/auth/v1/signup?redirect_to=${(config.appUrl + "/login").encodeURLParameter()}", body = body).body()
    }
    override suspend fun login(email: String, password: String): Tokens =
        request(HttpMethod.Post, "/auth/v1/token?grant_type=password", body = buildJsonObject {
            put("email", email); put("password", password)
        }).body()
    override suspend fun refresh(refreshToken: String): Tokens =
        request(HttpMethod.Post, "/auth/v1/token?grant_type=refresh_token", body = buildJsonObject { put("refresh_token", refreshToken) }).body()
    override suspend fun verify(hash: String): Tokens =
        request(HttpMethod.Post, "/auth/v1/verify", body = buildJsonObject { put("token_hash", hash); put("type", "email") }).body()
    override suspend fun logout(jwt: String) { request(HttpMethod.Post, "/auth/v1/logout?scope=local", jwt) }
    override suspend fun user(jwt: String): AuthUser = request(HttpMethod.Get, "/auth/v1/user", jwt).body()
    override suspend fun actor(jwt: String): Actor {
        val user = user(jwt) // Auth validates the JWT; never trust an unverified cookie or metadata role.
        val profile: List<Profile> = request(HttpMethod.Get, "/rest/v1/profiles?id=eq.${user.id}&select=id,full_name,phone,email", jwt).body()
        val admin: Boolean = request(HttpMethod.Post, "/rest/v1/rpc/admin_access", jwt, buildJsonObject {}).body()
        return Actor(user, profile.singleOrNull() ?: throw ApiProblem(403, "profile_missing"), admin)
    }
    override suspend fun profiles(jwt: String): List<Profile> =
        request(HttpMethod.Get, "/rest/v1/profiles?select=id,full_name,phone,email&order=full_name.asc", jwt).body()
    override suspend fun banquets(jwt: String, owner: String?): List<Banquet> =
        request(HttpMethod.Get, "/rest/v1/banquets?select=*&order=created_at.desc" + (owner?.let { "&owner_id=eq.$it" } ?: ""), jwt).body()
    override suspend fun create(jwt: String, banquet: NewBanquet) { request(HttpMethod.Post, "/rest/v1/banquets", jwt, banquet) }
    override suspend fun status(jwt: String, id: String, status: String) {
        val rows: List<Banquet> = request(HttpMethod.Patch, "/rest/v1/banquets?id=eq.$id", jwt, StatusChange(status)).body()
        if (rows.isEmpty()) throw ApiProblem(404, "not_found")
    }
    override suspend fun delete(jwt: String, id: String, owner: String?) {
        val rows: List<Banquet> = request(HttpMethod.Delete, "/rest/v1/banquets?id=eq.$id" + (owner?.let { "&owner_id=eq.$it" } ?: ""), jwt).body()
        if (rows.isEmpty()) throw ApiProblem(404, "not_found")
    }
}
