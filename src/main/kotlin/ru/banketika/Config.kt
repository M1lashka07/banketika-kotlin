package ru.banketika

import java.io.File

data class Config(
    val supabaseUrl: String, val publishableKey: String,
    val appUrl: String = "http://localhost:8080", val port: Int = 8080,
    val host: String = "127.0.0.1", val secureCookie: Boolean = false,
    val adminLogin: String = "admin 26", val adminEmail: String = "admin26@banketika.example"
) {
    companion object {
        fun load(): Config {
            val local = File(".env").takeIf(File::exists)?.readLines()?.mapNotNull {
                val line = it.trim()
                if (line.startsWith('#') || '=' !in line) null
                else line.substringBefore('=').trim() to line.substringAfter('=').trim().removeSurrounding("\"")
            }?.toMap().orEmpty()
            fun env(name: String, default: String = "") = System.getenv(name) ?: local[name] ?: default
            val url = env("SUPABASE_URL").trimEnd('/')
            val key = env("SUPABASE_PUBLISHABLE_KEY")
            require(url.startsWith("https://") && key.startsWith("sb_publishable_")) {
                "Заполните SUPABASE_URL и SUPABASE_PUBLISHABLE_KEY в .env (см. .env.example)."
            }
            val appUrl = env("APP_URL", "http://localhost:8080").trimEnd('/')
            val secure = env("COOKIE_SECURE", "false").toBooleanStrict()
            require(!appUrl.startsWith("https://") || secure) { "Для HTTPS установите COOKIE_SECURE=true." }
            return Config(url, key, appUrl, env("PORT", "8080").toInt(), env("HOST", "127.0.0.1"), secure,
                env("ADMIN_LOGIN", "admin 26"), env("ADMIN_EMAIL", "admin26@banketika.example"))
        }
    }
}
