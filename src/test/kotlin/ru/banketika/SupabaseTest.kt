package ru.banketika

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

class SupabaseTest {
    private val config = Config("https://example.supabase.co","sb_publishable_test")
    private fun gateway(handler: MockRequestHandler): Supabase = Supabase(config,HttpClient(MockEngine(handler)) {
        install(ContentNegotiation) { json(Supabase.json) }
    })
    @Test fun `password request is JSON and uses supported Auth endpoint`() = runBlocking {
        val api=gateway { request ->
            assertEquals("/auth/v1/token",request.url.encodedPath)
            assertEquals("password",request.url.parameters["grant_type"])
            val body=Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("ivan@banketika.example",body["email"]!!.jsonPrimitive.content)
            assertEquals("test-password",body["password"]!!.jsonPrimitive.content)
            respond("""{"access_token":"jwt","refresh_token":"refresh","expires_in":3600}""",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }
        assertEquals("jwt",api.login("ivan@banketika.example","test-password").access_token)
        api.client.close()
    }
    @Test fun `banquet request forwards user JWT and never supplies status`() = runBlocking {
        val api=gateway { request ->
            assertEquals("Bearer user-jwt",request.headers[HttpHeaders.Authorization])
            assertEquals("sb_publishable_test",request.headers["apikey"])
            val body=Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("owner",body["owner_id"]!!.jsonPrimitive.content)
            assertFalse(body.containsKey("status"))
            respond("[]",headers=headersOf(HttpHeaders.ContentType,"application/json"))
        }
        api.create("user-jwt",NewBanquet("owner","Вечер","Москва","2099-01-01T15:00:00Z","cash"))
        api.client.close()
    }
    @Test fun `upstream errors have safe structured codes`() = runBlocking {
        val api=gateway { respond("""{"error_code":"invalid_credentials","msg":"private upstream detail"}""",HttpStatusCode.BadRequest,headersOf(HttpHeaders.ContentType,"application/json")) }
        val error=assertFailsWith<ApiProblem> { api.login("ivan@banketika.example","test-password") }
        assertEquals("invalid_credentials",error.code); assertFalse(error.message!!.contains("private upstream detail"))
        api.client.close()
    }
}
