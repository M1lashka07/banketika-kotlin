package ru.banketika

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

private const val USER = "00000000-0000-0000-0000-000000000001"
private const val OTHER = "00000000-0000-0000-0000-000000000002"
private const val ID = "00000000-0000-0000-0000-000000000003"

private class FakeGateway : Gateway {
    var admin = false
    var signupSession = false
    var created: NewBanquet? = null
    var changed: String? = null
    var deleteOwner: String? = null
    var usedEmail: String? = null
    var readsOwner: String? = null
    val profile = Profile(USER,"Иванов Иван","+79991234567","user@example.com")
    private fun tokens() = Tokens("access-token-secret", "refresh-token-secret", 3600, AuthUser(USER,profile.login + "@banketika.example"))
    override suspend fun signup(values: Map<String,String>) = if (signupSession) tokens() else Tokens()
    override suspend fun login(email: String,password: String): Tokens { usedEmail = email; return tokens() }
    override suspend fun refresh(refreshToken: String) = tokens()
    override suspend fun logout(jwt: String) {}
    override suspend fun user(jwt: String) = AuthUser(USER,profile.login + "@banketika.example")
    override suspend fun actor(jwt: String) = Actor(user(jwt),profile,admin)
    override suspend fun profiles(jwt: String) = listOf(profile,profile.copy(id=OTHER))
    override suspend fun banquets(jwt: String,owner: String?): List<Banquet> { readsOwner=owner; return emptyList() }
    override suspend fun create(jwt: String,banquet: NewBanquet) { created=banquet }
    override suspend fun status(jwt: String,id: String,status: String) { changed=status }
    override suspend fun delete(jwt: String,id: String,owner: String?) { deleteOwner=owner }
}
private fun config() = Config("https://example.supabase.co","sb_publishable_test")
private data class Browser(val cookie: String,val csrf: String)
private fun browser(response: HttpResponse,html: String): Browser = Browser(
    response.headers.getAll(HttpHeaders.SetCookie)!!.first().substringBefore(';'),
    Regex("name=\"csrf\"[^>]*value=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
        ?: Regex("value=\"([^\"]+)\"[^>]*name=\"csrf\"").find(html)!!.groupValues[1])
private suspend fun HttpClient.form(path: String,b: Browser,values: Map<String,String> = emptyMap()) = post(path) {
    header(HttpHeaders.Cookie,b.cookie); contentType(ContentType.Application.FormUrlEncoded)
    setBody(Parameters.build { append("csrf",b.csrf); values.forEach { (k,v)->append(k,v) } }.formUrlEncode())
}
private suspend fun HttpClient.login(): Browser {
    val page=get("/login"); val b=browser(page,page.bodyAsText())
    val logged=form("/login",b,mapOf("login" to "user_26","password" to "password123"))
    val cookie=logged.headers.getAll(HttpHeaders.SetCookie)!!.first().substringBefore(';')
    val cabinet=get("/cabinet") { header(HttpHeaders.Cookie,cookie) }
    return Browser(cookie,Regex("name=\"csrf\"[^>]*value=\"([^\"]+)\"").find(cabinet.bodyAsText())!!.groupValues[1])
}

class ApplicationTest {
    @Test fun `root registration and anonymous cabinet redirects`() = testApplication {
        application { banketika(config(),FakeGateway()) }
        val client=createClient { followRedirects=false }
        assertContains(client.get("/").bodyAsText(),"Создать аккаунт")
        assertEquals("/login?expired=1",client.get("/cabinet").headers[HttpHeaders.Location])
    }
    @Test fun `CSRF and cross origin mutation are forbidden`() = testApplication {
        application { banketika(config(),FakeGateway()) }
        val client=createClient { followRedirects=false }; val page=client.get("/"); val b=browser(page,page.bodyAsText())
        assertEquals(HttpStatusCode.Forbidden,client.form("/register",b.copy(csrf="bad")).status)
        val cross=client.post("/register") { header(HttpHeaders.Origin,"https://evil.example"); header(HttpHeaders.Cookie,b.cookie); contentType(ContentType.Application.FormUrlEncoded); setBody("csrf=${b.csrf}") }
        assertEquals(HttpStatusCode.Forbidden,cross.status)
    }
    @Test fun `sandboxed same origin still requires CSRF and rejects cross site`() = testApplication {
        application { banketika(config(),FakeGateway()) }; val client=createClient { followRedirects=false }
        val page=client.get("/login"); val b=browser(page,page.bodyAsText())
        suspend fun submit(site: String,csrf: String) = client.post("/login") {
            header(HttpHeaders.Origin,"null"); header("Sec-Fetch-Site",site); header(HttpHeaders.Cookie,b.cookie)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(Parameters.build { append("csrf",csrf); append("login","ivan_26"); append("password","test-password") }.formUrlEncode())
        }
        assertEquals(HttpStatusCode.Forbidden,submit("cross-site",b.csrf).status)
        assertEquals(HttpStatusCode.Forbidden,submit("same-origin","bad").status)
        assertEquals(HttpStatusCode.SeeOther,submit("same-origin",b.csrf).status)
    }
    @Test fun `signup without tokens does not authenticate`() = testApplication {
        application { banketika(config(),FakeGateway()) }; val client=createClient { followRedirects=false }
        val page=client.get("/"); val b=browser(page,page.bodyAsText())
        val r=client.form("/register",b,mapOf("full_name" to "Иванов Иван","phone" to "+79991234567","login" to "ivan_26","password" to "password123"))
        assertContains(r.bodyAsText(),"Аккаунт пока не активирован")
        assertEquals(HttpStatusCode.SeeOther,client.get("/cabinet") { header(HttpHeaders.Cookie,b.cookie) }.status)
    }
    @Test fun `login rotates HttpOnly cookie and hides tokens`() = testApplication {
        application { banketika(config(),FakeGateway()) }; val client=createClient { followRedirects=false }
        val page=client.get("/login"); val b=browser(page,page.bodyAsText()); val r=client.form("/login",b,mapOf("login" to "ivan_26","password" to "password123"))
        val cookie=r.headers.getAll(HttpHeaders.SetCookie)!!.first()
        assertContains(cookie,"HttpOnly"); assertContains(cookie,"SameSite=Lax"); assertFalse(cookie.contains(b.cookie)); assertFalse(cookie.contains("access-token"))
    }
    @Test fun `ordinary user cannot use admin routes even with valid CSRF`() = testApplication {
        val gateway=FakeGateway(); application { banketika(config(),gateway) }; val client=createClient { followRedirects=false }; val b=client.login()
        assertEquals(HttpStatusCode.Forbidden,client.get("/admin") { header(HttpHeaders.Cookie,b.cookie) }.status)
        assertEquals(HttpStatusCode.Forbidden,client.form("/admin/banquets/$ID/status",b,mapOf("status" to "completed")).status)
        assertNull(gateway.changed)
        assertEquals(HttpStatusCode.Forbidden,client.form("/admin/banquets/$ID/delete",b).status)
    }
    @Test fun `creation ignores attacker supplied owner and status`() = testApplication {
        val gateway=FakeGateway(); application { banketika(config(),gateway) }; val client=createClient { followRedirects=false }; val b=client.login()
        val r=client.form("/banquets",b,mapOf("title" to "Вечер","venue" to "Москва","date" to "2099-01-01","time" to "18:00","payment" to "cash","owner_id" to OTHER,"status" to "completed"))
        assertEquals(HttpStatusCode.SeeOther,r.status); assertEquals(USER,gateway.created!!.owner_id)
        client.form("/banquets/$ID/delete",b); assertEquals(USER,gateway.deleteOwner)
    }
    @Test fun `administrator alias uses Auth and protected role`() = testApplication {
        val gateway=FakeGateway().apply { admin=true }; application { banketika(config(),gateway) }; val client=createClient { followRedirects=false }
        val page=client.get("/login"); val b=browser(page,page.bodyAsText())
        val r=client.form("/login",b,mapOf("login" to "admin 26","password" to "test-password-only"))
        assertEquals("admin26@banketika.example",gateway.usedEmail); assertEquals("/admin",r.headers[HttpHeaders.Location])
    }
    @Test fun `administrator cannot use internal username instead of configured login`() = testApplication {
        val gateway=FakeGateway().apply { admin=true }; application { banketika(config(),gateway) }
        val client=createClient { followRedirects=false }; val page=client.get("/login"); val b=browser(page,page.bodyAsText())
        val r=client.form("/login",b,mapOf("login" to "admin26","password" to "test-password-only"))
        assertEquals(HttpStatusCode.BadRequest,r.status)
        assertContains(r.bodyAsText(),"Неверный логин или пароль")
        assertEquals(HttpStatusCode.SeeOther,client.get("/admin") { header(HttpHeaders.Cookie,b.cookie) }.status)
    }
    @Test fun `HTML escapes user data`() {
        val actor=Actor(AuthUser(USER),Profile(USER,"<script>alert(1)</script>","+79991234567","a@example.com"),false)
        val html=Views.cabinet(Session(),actor,emptyList())
        assertContains(html,"&lt;script&gt;"); assertFalse(html.contains("<script>alert(1)</script>"))
    }
}

