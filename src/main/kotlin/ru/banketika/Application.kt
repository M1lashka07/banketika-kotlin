package ru.banketika

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.UUID

fun main() {
    val config = Config.load()
    embeddedServer(Netty, host = config.host, port = config.port) { banketika(config, Supabase(config)) }.start(wait = true)
}

private data class SignedIn(val session: Session, val jwt: String, val actor: Actor)
private class Rejected(val status: HttpStatusCode, val explanation: String) : RuntimeException(explanation)

fun Application.banketika(config: Config, gateway: Gateway) {
    val sessions = Sessions(config.secureCookie)
    install(StatusPages) {
        exception<Rejected> { call, e -> call.html(Views.error(sessions.get(call), "Действие недоступно", e.explanation), e.status) }
        exception<ApiProblem> { call, e ->
            val status = if (e.statusCode == 404) HttpStatusCode.NotFound else HttpStatusCode.BadGateway
            call.html(Views.error(sessions.get(call), "Не удалось выполнить действие", friendly(e)), status)
        }
        exception<Throwable> { call, e ->
            // Do not log exception messages: upstream responses can contain personal data.
            call.application.log.error("Request failed: {}", e.javaClass.simpleName)
            call.html(Views.error(sessions.get(call), "Сервис временно недоступен", "Повторите попытку немного позже. Ваши сохранённые заявки останутся в кабинете."), HttpStatusCode.ServiceUnavailable)
        }
        status(HttpStatusCode.NotFound) { call, status -> call.html(Views.error(sessions.get(call), "Страница не найдена", "Проверьте адрес или вернитесь в кабинет."), status) }
    }
    intercept(ApplicationCallPipeline.Call) {
        call.response.header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'")
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header("Cache-Control", "no-store")
    }
    suspend fun ApplicationCall.signedIn(admin: Boolean = false): SignedIn? {
        val session = sessions.get(this)
        val jwt = try { sessions.jwt(session, gateway) } catch (e: ApiProblem) {
            if (e.statusCode !in listOf(400,401,403)) throw e
            sessions.clear(this); null
        }
        if (jwt == null) { redirect("/login?expired=1"); return null }
        val actor = try { gateway.actor(jwt) } catch (e: ApiProblem) {
            if (e.statusCode != 401) throw e
            sessions.clear(this); redirect("/login?expired=1"); return null
        }
        if (admin && !actor.admin) {
            html(Views.error(session, "Недостаточно прав", "Управление заявками доступно только администратору.", actor), HttpStatusCode.Forbidden)
            return null
        }
        return SignedIn(session, jwt, actor)
    }
    suspend fun ApplicationCall.form(): Map<String, String> {
        val origin = request.header("Origin")
        if ((origin != null && origin != config.appUrl) || request.header("Sec-Fetch-Site") == "cross-site")
            throw Rejected(HttpStatusCode.Forbidden, "Запрос пришёл с другого сайта. Откройте форму в Банкетике.")
        if (request.contentType().withoutParameters() != ContentType.Application.FormUrlEncoded)
            throw Rejected(HttpStatusCode.UnsupportedMediaType, "Отправьте заполненную форму.")
        if ((request.header("Content-Length")?.toLongOrNull() ?: 0) > 16_384)
            throw Rejected(HttpStatusCode.PayloadTooLarge, "Форма слишком большая.")
        val parameters = receiveParameters()
        if (!sessions.csrf(sessions.get(this), parameters["csrf"]))
            throw Rejected(HttpStatusCode.Forbidden, "Форма устарела. Обновите страницу и отправьте её ещё раз.")
        return parameters.entries().associate { (key, values) ->
            if (values.size != 1) throw Rejected(HttpStatusCode.BadRequest, "Поле передано несколько раз.")
            key to values.single()
        }
    }
    fun id(call: ApplicationCall): String = call.parameters["id"]?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
        ?: throw Rejected(HttpStatusCode.BadRequest, "Некорректный идентификатор заявки.")

    routing {
        staticResources("/static", "static")
        get("/health") { call.respondText("ok") }
        get("/") {
            val session = sessions.get(call)
            if (session.tokens != null) call.redirect("/cabinet") else call.html(Views.auth(true, session))
        }
        get("/login") {
            val session = sessions.get(call)
            if (session.tokens != null) call.redirect("/cabinet") else call.html(Views.auth(false, session,
                message = if (call.request.queryParameters["expired"] == "1") "Войдите, чтобы продолжить работу." else null))
        }
        post("/register") {
            val values = call.form().mapValues { (k,v) -> if (k == "password") v else v.trim() }.toMutableMap()
            values["login"] = values["login"].orEmpty().lowercase()
            val session = sessions.get(call)
            try {
                Validation.registration(values)
                val tokens = gateway.signup(values)
                if (tokens.access_token.isNotBlank() && tokens.refresh_token.isNotBlank()) {
                    gateway.user(tokens.access_token)
                    sessions.authenticate(call, tokens); call.redirect("/cabinet")
                } else call.html(Views.auth(false, session, mapOf("login" to values["login"].orEmpty()),
                    problem = "Аккаунт пока не активирован. Администратору нужно настроить регистрацию без подтверждения email в Supabase Auth."))
            } catch (e: FormProblem) { call.html(Views.auth(true, session, values, e.fields, problem = "Проверьте выделенные поля."), HttpStatusCode.UnprocessableEntity) }
            catch (e: ApiProblem) { call.html(Views.auth(true, session, values, problem = friendly(e)), HttpStatusCode.BadRequest) }
        }
        post("/login") {
            val values = call.form()
            val session = sessions.get(call)
            val login = values["login"].orEmpty().trim()
            try {
                if (login.isBlank() || values["password"].isNullOrBlank()) throw FormProblem(mapOf("login" to "Введите логин и пароль."))
                if (login != config.adminLogin && !Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{2,31}$").matches(login))
                    throw FormProblem(mapOf("login" to "Проверьте логин: email не используется для входа."))
                val email = if (login == config.adminLogin) config.adminEmail else login.lowercase() + "@banketika.example"
                val tokens = gateway.login(email, values["password"].orEmpty())
                val actor = gateway.actor(tokens.access_token)
                sessions.authenticate(call, tokens)
                call.redirect(if (actor.admin) "/admin" else "/cabinet")
            } catch (e: FormProblem) { call.html(Views.auth(false, session, values, e.fields), HttpStatusCode.UnprocessableEntity) }
            catch (e: ApiProblem) { call.html(Views.auth(false, session, values, problem = friendly(e)), HttpStatusCode.BadRequest) }
        }
        post("/logout") {
            call.form()
            val jwt = sessions.jwt(sessions.get(call), gateway)
            try { if (jwt != null) gateway.logout(jwt) } finally { sessions.clear(call) }
            call.redirect("/login")
        }
        get("/cabinet") {
            val signed = call.signedIn() ?: return@get
            call.html(Views.cabinet(signed.session, signed.actor, gateway.banquets(signed.jwt, signed.actor.user.id),
                message(call.request.queryParameters["done"])))
        }
        get("/banquets/new") {
            val signed = call.signedIn() ?: return@get
            call.html(Views.newBanquet(signed.session, signed.actor))
        }
        post("/banquets") {
            val values = call.form()
            val signed = call.signedIn() ?: return@post
            try {
                // Owner and initial status are never read from browser-supplied fields.
                gateway.create(signed.jwt, Validation.banquet(values, signed.actor.user.id))
                call.redirect("/cabinet?done=created")
            } catch (e: FormProblem) { call.html(Views.newBanquet(signed.session, signed.actor, values, e.fields), HttpStatusCode.UnprocessableEntity) }
            catch (e: ApiProblem) { call.html(Views.newBanquet(signed.session, signed.actor, values, problem = friendly(e)), HttpStatusCode.BadRequest) }
        }
        post("/banquets/{id}/delete") {
            call.form()
            val signed = call.signedIn() ?: return@post
            gateway.delete(signed.jwt, id(call), signed.actor.user.id)
            call.redirect("/cabinet?done=deleted")
        }
        get("/admin") {
            val signed = call.signedIn(true) ?: return@get
            call.html(Views.admin(signed.session, signed.actor, gateway.banquets(signed.jwt), gateway.profiles(signed.jwt), message = message(call.request.queryParameters["done"])))
        }
        post("/admin/banquets") {
            val values = call.form()
            val signed = call.signedIn(true) ?: return@post
            val profiles = gateway.profiles(signed.jwt)
            try {
                val owner = values["owner_id"].orEmpty()
                if (profiles.none { it.id == owner }) throw FormProblem(mapOf("owner_id" to "Выберите зарегистрированного пользователя."))
                gateway.create(signed.jwt, Validation.banquet(values, owner))
                call.redirect("/admin?done=created")
            } catch (e: FormProblem) { call.html(Views.admin(signed.session, signed.actor, gateway.banquets(signed.jwt), profiles, values, e.fields, problem = "Проверьте выделенные поля."), HttpStatusCode.UnprocessableEntity) }
            catch (e: ApiProblem) { call.html(Views.admin(signed.session, signed.actor, gateway.banquets(signed.jwt), profiles, values, problem = friendly(e)), HttpStatusCode.BadRequest) }
        }
        post("/admin/banquets/{id}/status") {
            val values = call.form()
            val signed = call.signedIn(true) ?: return@post
            val status = values["status"].orEmpty()
            if (Status.entries.none { it.code == status }) throw Rejected(HttpStatusCode.UnprocessableEntity, "Выберите один из трёх предусмотренных статусов.")
            gateway.status(signed.jwt, id(call), status); call.redirect("/admin?done=status")
        }
        post("/admin/banquets/{id}/delete") {
            call.form()
            val signed = call.signedIn(true) ?: return@post
            gateway.delete(signed.jwt, id(call)); call.redirect("/admin?done=deleted")
        }
    }
}
private suspend fun ApplicationCall.html(content: String, status: HttpStatusCode = HttpStatusCode.OK) = respondText(content, ContentType.Text.Html, status)
private suspend fun ApplicationCall.redirect(url: String) { response.header(HttpHeaders.Location, url); respond(HttpStatusCode.SeeOther) }
private fun message(code: String?): String? = when (code) {
    "created" -> "Заявка отправлена и принята на рассмотрение."
    "deleted" -> "Заявка удалена."
    "status" -> "Статус заявки сохранён."
    else -> null
}
private fun friendly(e: ApiProblem): String = when (e.code) {
    "invalid_credentials" -> "Неверный логин или пароль."
    "23505" -> "Этот логин уже занят. Выберите другой или перейдите ко входу."
    "email_not_confirmed" -> "Сначала подтвердите email по ссылке из письма."
    "user_already_exists" -> "Аккаунт с этим email уже существует. Перейдите ко входу."
    "over_email_send_rate_limit", "over_request_rate_limit" -> "Слишком много попыток. Подождите немного и повторите."
    "weak_password" -> "Пароль слишком простой. Выберите другой пароль."
    "email_provider_disabled" -> "Вход пока недоступен. Администратору нужно включить провайдер пароля в Supabase Auth."
    "otp_expired" -> "Ссылка подтверждения устарела или уже использована."
    "not_found" -> "Заявка не найдена или у вас нет доступа к ней."
    "23514" -> "Проверьте поля заявки и убедитесь, что дата и время находятся в будущем."
    else -> "Не удалось выполнить действие. Проверьте данные и повторите попытку."
}
