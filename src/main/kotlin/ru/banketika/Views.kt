package ru.banketika

import kotlinx.html.*
import kotlinx.html.stream.createHTML
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object Views {
    private fun page(title: String, active: String, session: Session, actor: Actor? = null, content: MAIN.() -> Unit): String =
        "<!DOCTYPE html>" + createHTML().html {
            lang = "ru"
            head {
                meta { charset = "utf-8" }; meta { name = "viewport"; this.content = "width=device-width, initial-scale=1" }
                title { +"$title — Банкетика" }
                link { rel = "icon"; href = "/static/logo.svg"; type = "image/svg+xml" }
                link { rel = "stylesheet"; href = "/static/app.css" }
                script { src = "/static/app.js"; defer = true }
            }
            body {
                a("#content", classes = "skip") { +"Перейти к содержимому" }
                header("site-header") {
                    div("header-inner") {
                        a(if (actor == null) "/" else "/cabinet", classes = "brand") {
                            img { src = "/static/logo.svg"; alt = ""; width = "43"; height = "43" }
                            span { +"Банкетика"; small { +"ПОВОД БЫТЬ ВМЕСТЕ" } }
                        }
                        nav { attributes["aria-label"] = "Основная навигация"
                            if (actor == null) {
                                a("/", classes = if (active == "register") "selected" else "") { +"Регистрация" }
                                a("/login", classes = if (active == "login") "selected" else "") { +"Войти" }
                            } else {
                                a("/cabinet", classes = if (active == "cabinet") "selected" else "") { +"Мой кабинет" }
                                a("/banquets/new", classes = if (active == "new") "selected" else "") { +"Создать банкет" }
                                if (actor.admin) a("/admin", classes = if (active == "admin") "selected" else "") { +"Управление" }
                                form("/logout", method = FormMethod.post) { csrf(session); button(classes = "nav-logout") { +"Выйти" } }
                            }
                        }
                    }
                }
                main { id = "content"; content() }
                footer("site-footer") {
                    span { +"Банкетика © 2026" }
                    span { +"Тёплые встречи начинаются здесь" }
                    small { +"Учебный проект" }
                }
            }
        }

    private fun FlowContent.csrf(session: Session) { hiddenInput(name = "csrf") { value = session.csrf } }
    private fun FlowContent.notice(text: String?, error: Boolean = false) {
        if (!text.isNullOrBlank()) div(if (error) "notice error" else "notice") {
            attributes["role"] = if (error) "alert" else "status"; +text
        }
    }
    private fun FlowContent.field(
        name: String, title: String, type: InputType = InputType.text, values: Map<String, String> = emptyMap(),
        errors: Map<String, String> = emptyMap(), placeholder: String = "", autocomplete: String? = null, max: Int? = null,
        min: String? = null, hint: String? = null
    ) {
        div("field") {
            label { htmlFor = name; +title }
            input(type, name = name) {
                id = name; required = true; this.placeholder = placeholder
                if (type != InputType.password) value = values[name].orEmpty()
                if (autocomplete != null) attributes["autocomplete"] = autocomplete
                if (max != null) maxLength = max.toString()
                if (min != null) attributes["min"] = min
                if (errors[name] != null) attributes["aria-invalid"] = "true"
                val descriptions = listOfNotNull(hint?.let { "$name-hint" }, errors[name]?.let { "$name-error" })
                if (descriptions.isNotEmpty()) attributes["aria-describedby"] = descriptions.joinToString(" ")
            }
            if (hint != null) small("field-hint") { id = "$name-hint"; +hint }
            if (errors[name] != null) small("field-error") { id = "$name-error"; +errors.getValue(name) }
        }
    }

    fun auth(register: Boolean, session: Session, values: Map<String, String> = emptyMap(), errors: Map<String, String> = emptyMap(), message: String? = null, problem: String? = null): String =
        page(if (register) "Регистрация" else "Вход", if (register) "register" else "login", session) {
            div("auth-layout") {
                section("welcome") {
                    p("eyebrow") { +"ВАШ ПРАЗДНИК · ВАША ИСТОРИЯ" }
                    h1 { +"Соберите близких."; br(); em { +"Мы соберём" }; br(); +" всё остальное." }
                    p("lead") { +"От семейного вечера до большого торжества. Спланируйте банкет и следите за подготовкой в одном месте." }
                    img("Банкетный стол со свечами и сервировкой", "/static/table.svg", classes = "table-art")
                    div("welcome-bottom") { span { +"01 / ПЛАНИРУЙТЕ" }; span { +"02 / ПРАЗДНУЙТЕ" } }
                }
                section("auth-card") {
                    p("eyebrow") { +if (register) "ДАВАЙТЕ ЗНАКОМИТЬСЯ" else "РАДЫ ВАС ВИДЕТЬ" }
                    h2 { +if (register) "Создать аккаунт" else "С возвращением" }
                    p("muted") { +if (register) "Первый шаг к вашему идеальному вечеру." else "Войдите, чтобы продолжить подготовку." }
                    notice(message); notice(problem, true)
                    form(if (register) "/register" else "/login", method = FormMethod.post) {
                        csrf(session)
                        if (register) {
                            field("full_name", "Фамилия, имя и отчество", values = values, errors = errors, placeholder = "Иванов Иван Иванович", autocomplete = "name", max = 120)
                            field("phone", "Номер телефона", InputType.tel, values, errors, "+7 (999) 123-45-67", "tel", 25)
                        }
                        field("login", "Логин", InputType.text,
                            values, errors, if (register) "Например, ivan_26" else "Ваш логин", "username", 32)
                        field("password", "Пароль", InputType.password, errors = errors, placeholder = if (register) "Не менее 8 символов" else "Ваш пароль",
                            autocomplete = if (register) "new-password" else "current-password", max = 128)
                        button(classes = "button full") { +if (register) "Зарегистрироваться →" else "Войти →" }
                    }
                    p("auth-switch") {
                        +if (register) "Уже есть аккаунт? " else "Пока нет аккаунта? "
                        a(if (register) "/login" else "/") { +if (register) "Войти" else "Зарегистрироваться" }
                    }
                    if (register) p("micro") { +"Логин: 3–32 латинских символа, цифры, точка, дефис или подчёркивание. Контактный телефон понадобится для вашей заявки." }
                }
            }
        }

    fun cabinet(session: Session, actor: Actor, rows: List<Banquet>, message: String? = null): String = page("Личный кабинет", "cabinet", session, actor) {
        div("container") {
            div("page-heading") {
                div { p("eyebrow") { +"ЛИЧНЫЙ КАБИНЕТ" }; h1 { +"Ваши будущие встречи" }; p("muted") { +"Все детали и подготовка — под рукой." } }
                a("/banquets/new", classes = "button") { +"+ Создать банкет" }
            }
            notice(message)
            div("cabinet-grid") {
                aside("profile-card") {
                    div("avatar") { +actor.profile.full_name.take(1).uppercase() }
                    h2 { +actor.profile.full_name }
                    p("role-label") { +if (actor.admin) "Администратор" else "Личный профиль" }
                    dl { dt { +"Логин" }; dd { +actor.profile.login }; dt { +"Телефон" }; dd { +actor.profile.phone } }
                    p("micro") { +"Данные видны вам и администратору, который работает с вашей заявкой." }
                }
                section {
                    div("section-heading") { h2 { +"Мои банкеты" }; span("count") { +rows.size.toString() } }
                    if (rows.isEmpty()) empty("Пока всё впереди", "Создайте первую заявку — и здесь появятся её детали и статус подготовки.", "/banquets/new", "Спланировать банкет")
                    else rows.forEach { banquet(it, session) }
                }
            }
        }
    }

    private fun FlowContent.empty(title: String, text: String, href: String? = null, label: String = "") {
        div("empty") {
            div("empty-symbol") { +"✦" }; h3 { +title }; p { +text }
            if (href != null) a(href, classes = "button secondary") { +label }
        }
    }
    private fun FlowContent.banquet(row: Banquet, session: Session, owner: Profile? = null, admin: Boolean = false) {
        val event = Instant.parse(row.event_at).atZone(MOSCOW)
        article("banquet-card") {
            div("banquet-top") {
                div { p("card-kicker") { +"БАНКЕТ · ${row.id.take(8).uppercase()}" }; h3 { +row.title } }
                span("badge ${row.status}") { +Status.entries.first { it.code == row.status }.label }
            }
            dl("event-details") {
                dt { +"Когда" }; dd { +event.format(DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm")); small { +"по Москве" } }
                dt { +"Где" }; dd { +row.venue }
                dt { +"Оплата" }; dd { +Payment.entries.first { it.code == row.payment_method }.label }
            }
            if (owner != null) div("owner-details") {
                strong { +owner.full_name }; span { +"Логин: ${owner.login}" }; span { +owner.phone }
            }
            div("card-actions") {
                if (admin) form("/admin/banquets/${row.id}/status", method = FormMethod.post, classes = "status-form") {
                    csrf(session)
                    label { htmlFor = "status-${row.id}"; +"Статус" }
                    select { name = "status"; id = "status-${row.id}"; Status.entries.forEach { option { value = it.code; selected = it.code == row.status; +it.label } } }
                    button(classes = "button small secondary") { +"Сохранить" }
                }
                details("delete-confirm") {
                    summary { +if (admin) "Удалить заявку" else "Отказаться от банкета" }
                    p { +"Заявка будет удалена. Подтвердите действие." }
                    form(if (admin) "/admin/banquets/${row.id}/delete" else "/banquets/${row.id}/delete", method = FormMethod.post) {
                        csrf(session); button(classes = "button danger small") { +"Да, удалить заявку" }
                    }
                }
            }
        }
    }

    private fun FlowContent.banquetFields(session: Session, values: Map<String, String>, errors: Map<String, String>, owners: List<Profile>? = null) {
        if (errors.isNotEmpty()) notice("Заявка не отправлена. Исправьте отмеченные поля.", true)
        csrf(session)
        if (owners != null) div("field") {
            label { htmlFor = "owner_id"; +"Пользователь" }
            select { name = "owner_id"; id = "owner_id"; required = true
                option { value = ""; +"Выберите зарегистрированного пользователя" }
                owners.forEach { option { value = it.id; selected = it.id == values["owner_id"]; +"${it.full_name} · ${it.login}" } }
            }
            errors["owner_id"]?.let { small("field-error") { +it } }
        }
        field("title", "Название банкета", values = values, errors = errors, placeholder = "Например, юбилей мамы", max = 120)
        field("venue", "Место проведения", values = values, errors = errors, placeholder = "Название зала и адрес", max = 250)
        div("form-row") {
            field("date", "Дата", InputType.date, values, errors, min = LocalDate.now(MOSCOW).toString(),
                hint = "Выберите сегодняшнюю или будущую дату.")
            field("time", "Время по Москве", InputType.time, values, errors)
        }
        fieldSet("payment") {
            legend { +"Способ оплаты" }
            Payment.entries.forEach {
                label("payment-option") {
                    radioInput(name = "payment") { value = it.code; required = true; checked = values["payment"] == it.code }
                    span { +it.label }
                }
            }
            errors["payment"]?.let { small("field-error") { +it } }
        }
        p("micro") { +"Вы выбираете способ оплаты. Онлайн-платёж не списывается." }
        button(classes = "button full") { +"Отправить заявку →" }
    }
    fun newBanquet(session: Session, actor: Actor, values: Map<String, String> = emptyMap(), errors: Map<String, String> = emptyMap(), problem: String? = null): String = page("Создать банкет", "new", session, actor) {
        div("container narrow") {
            a("/cabinet", classes = "back") { +"← Вернуться в кабинет" }
            p("eyebrow") { +"НАЧАЛО БОЛЬШОГО ВЕЧЕРА" }; h1 { +"Спланируем ваш банкет" }
            p("lead") { +"Расскажите о встрече. После отправки заявка появится в кабинете со статусом «На рассмотрении»." }
            section("form-card") {
                notice(problem, true)
                form("/banquets", method = FormMethod.post) { banquetFields(session, values, errors) }
            }
            p("micro centered") { +"Все поля обязательны · Дата и время указываются по Москве" }
        }
    }
    fun admin(session: Session, actor: Actor, rows: List<Banquet>, profiles: List<Profile>, values: Map<String, String> = emptyMap(), errors: Map<String, String> = emptyMap(), message: String? = null, problem: String? = null): String = page("Админ-панель", "admin", session, actor) {
        div("container") {
            div("page-heading") {
                div { p("eyebrow") { +"УПРАВЛЕНИЕ ЗАЯВКАМИ" }; h1 { +"Каждая встреча важна" }; p("muted") { +"Работайте с заявками и помогайте праздникам состояться." } }
                a("#create-for-user", classes = "button") { +"+ Заявка за пользователя" }
            }
            notice(message); notice(problem, true)
            div("stats") {
                Status.entries.forEach { state -> div { strong { +rows.count { it.status == state.code }.toString() }; span { +state.label } } }
            }
            details("admin-create") {
                id = "create-for-user"; open = errors.isNotEmpty()
                summary { +"Создать заявку за зарегистрированного пользователя" }
                form("/admin/banquets", method = FormMethod.post, classes = "admin-form") { banquetFields(session, values, errors, profiles) }
            }
            div("section-heading") { h2 { +"Все заявки" }; span("count") { +rows.size.toString() } }
            if (rows.isEmpty()) empty("Заявок пока нет", "Когда пользователь отправит заявку, она появится здесь. Также вы можете создать заявку за пользователя.")
            else div("admin-list") { rows.forEach { banquet(it, session, profiles.find { p -> p.id == it.owner_id }, true) } }
        }
    }
    fun error(session: Session, title: String, text: String, actor: Actor? = null): String = page(title, "", session, actor) {
        div("container narrow") { empty(title, text, if (actor == null) "/login" else "/cabinet", if (actor == null) "Ко входу" else "В кабинет") }
    }
}
