package ru.banketika

import kotlinx.serialization.Serializable
import java.time.*
import java.util.UUID

val MOSCOW: ZoneId = ZoneId.of("Europe/Moscow")
enum class Payment(val code: String, val label: String) {
    CASH("cash", "Наличными"), CARD("card", "Картой")
}
enum class Status(val code: String, val label: String) {
    PENDING("pending", "На рассмотрении"), ACTIVE("active", "В работе"), COMPLETED("completed", "Завершена")
}
@Serializable data class Profile(val id: String, val full_name: String, val phone: String, val login: String)
@Serializable data class Banquet(
    val id: String, val owner_id: String, val title: String, val venue: String,
    val event_at: String, val payment_method: String, val status: String, val created_at: String = ""
)
@Serializable data class NewBanquet(
    val owner_id: String, val title: String, val venue: String, val event_at: String, val payment_method: String
)
@Serializable data class StatusChange(val status: String)
@Serializable data class AuthUser(val id: String, val email: String = "")
@Serializable data class Tokens(
    val access_token: String = "", val refresh_token: String = "", val expires_in: Long = 3600,
    val user: AuthUser? = null
)
data class Actor(val user: AuthUser, val profile: Profile, val admin: Boolean)
class FormProblem(val fields: Map<String, String>) : RuntimeException("Invalid form")
class ApiProblem(val statusCode: Int, val code: String) : RuntimeException("Supabase $statusCode: $code")

object Validation {
    fun registration(values: Map<String, String>) {
        val errors = linkedMapOf<String, String>()
        if (values["full_name"].orEmpty().trim().length !in 3..120)
            errors["full_name"] = "Укажите ФИО: от 3 до 120 символов."
        val phone = values["phone"].orEmpty().trim()
        if (!Regex("^\\+?[0-9 ()-]{10,25}$").matches(phone) || phone.count(Char::isDigit) !in 10..15)
            errors["phone"] = "Укажите телефон из 10–15 цифр, например +7 (999) 123-45-67."
        if (!Regex("^[a-z0-9][a-z0-9._-]{2,31}$").matches(values["login"].orEmpty()))
            errors["login"] = "Логин: 3–32 латинских символа, цифры, точка, дефис или подчёркивание."
        if (values["password"].orEmpty().length !in 8..128)
            errors["password"] = "Пароль должен содержать от 8 до 128 символов."
        if (errors.isNotEmpty()) throw FormProblem(errors)
    }

    fun banquet(values: Map<String, String>, ownerId: String, now: Instant = Instant.now()): NewBanquet {
        val errors = linkedMapOf<String, String>()
        val title = values["title"].orEmpty().trim()
        val venue = values["venue"].orEmpty().trim()
        if (title.length !in 2..120) errors["title"] = "Название: от 2 до 120 символов."
        if (venue.length !in 3..250) errors["venue"] = "Место проведения: от 3 до 250 символов."
        val payment = Payment.entries.find { it.code == values["payment"] }
        if (payment == null) errors["payment"] = "Выберите способ оплаты."
        val date = runCatching { LocalDate.parse(values["date"]) }.getOrNull()
        val time = runCatching { LocalTime.parse(values["time"]) }.getOrNull()
        if (date == null) errors["date"] = "Укажите корректную дату."
        if (time == null) errors["time"] = "Укажите корректное время."
        val instant = if (date != null && time != null) LocalDateTime.of(date, time).atZone(MOSCOW).toInstant() else null
        if (date != null && date.isBefore(now.atZone(MOSCOW).toLocalDate()))
            errors["date"] = "Дата уже прошла. Выберите сегодняшнюю или будущую дату."
        else if (instant != null && !instant.isAfter(now))
            errors["time"] = "Время уже прошло. Выберите будущее время по Москве."
        if (runCatching { UUID.fromString(ownerId) }.isFailure) errors["owner_id"] = "Выберите зарегистрированного пользователя."
        if (errors.isNotEmpty()) throw FormProblem(errors)
        return NewBanquet(ownerId, title, venue, instant.toString(), payment!!.code)
    }
}
