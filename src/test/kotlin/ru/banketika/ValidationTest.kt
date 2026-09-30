package ru.banketika

import kotlin.test.*
import java.time.Instant

class ValidationTest {
    private val owner = "00000000-0000-0000-0000-000000000001"
    private val now = Instant.parse("2026-09-30T10:00:00Z")
    private val fields = mapOf("title" to "Встреча", "venue" to "Зал, Москва", "date" to "2027-01-01", "time" to "18:30", "payment" to "card")
    @Test fun `Moscow time becomes UTC instant`() {
        assertEquals("2027-01-01T15:30:00Z", Validation.banquet(fields,owner,now).event_at)
    }
    @Test fun `past date and malformed date are rejected`() {
        listOf("2020-01-01", "2027-02-30", "").forEach {
            assertTrue(assertFailsWith<FormProblem> { Validation.banquet(fields + ("date" to it),owner,now) }.fields.containsKey("date"))
        }
    }
    @Test fun `exact current instant is rejected`() {
        assertFailsWith<FormProblem> { Validation.banquet(fields + mapOf("date" to "2026-09-30", "time" to "13:00"),owner,now) }
    }
    @Test fun `unknown payment and invalid owner rejected`() {
        val errors = assertFailsWith<FormProblem> { Validation.banquet(fields + ("payment" to "crypto"),"other",now) }.fields
        assertEquals(setOf("payment","owner_id"),errors.keys)
    }
    @Test fun `registration validates contact data and password`() {
        assertFailsWith<FormProblem> { Validation.registration(mapOf("full_name" to "X", "phone" to "abc", "login" to "invalid space", "password" to "short")) }
        Validation.registration(mapOf("full_name" to "Иванов Иван Иванович", "phone" to "+7 (999) 123-45-67", "login" to "ivan_26", "password" to "longPassword1"))
    }
}

