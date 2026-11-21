package com.lingualive.app.core

import android.content.Context

data class Lang(val code: String, val name: String, val speechTag: String)

object Langs {
    val all = listOf(
        Lang("fr", "Français", "fr-FR"),
        Lang("en", "English", "en-US"),
        Lang("ar", "العربية", "ar-TN"),
        Lang("es", "Español", "es-ES"),
        Lang("de", "Deutsch", "de-DE"),
        Lang("it", "Italiano", "it-IT"),
        Lang("pt", "Português", "pt-PT"),
        Lang("tr", "Türkçe", "tr-TR"),
        Lang("nl", "Nederlands", "nl-NL"),
        Lang("ru", "Русский", "ru-RU"),
        Lang("zh", "中文", "zh-CN"),
        Lang("ja", "日本語", "ja-JP"),
        Lang("ko", "한국어", "ko-KR"),
        Lang("hi", "हिन्दी", "hi-IN"),
    )

    fun byCode(code: String): Lang = all.firstOrNull { it.code == code } ?: all[0]
}

/** Un segment de parole reconnu (une phrase / un énoncé). */
data class Segment(
    val id: Long,
    val text: String,
    val lang: String,
    val translated: String? = null,
    val startSec: Double? = null,
    val speaker: String? = null,
)

object Prefs {
    // 10.0.2.2 = la machine hôte vue depuis l'émulateur Android.
    const val DEFAULT_SERVER = "ws://10.0.2.2:8080/ws"
    private const val FILE = "lingualive"

    fun server(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER

    fun setServer(ctx: Context, value: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("server", value.trim()).apply()

    /** Mode hors-ligne : ce téléphone sert de serveur sur le Wi-Fi local / point d'accès. */
    fun offline(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("offline", true)

    fun setOffline(ctx: Context, value: Boolean) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("offline", value).apply()

    fun lastHost(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("lastHost", "") ?: ""

    fun setLastHost(ctx: Context, value: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("lastHost", value).apply()

    /** Adresse à utiliser pour CRÉER une session. */
    fun hostUrl(ctx: Context): String =
        if (offline(ctx)) { LocalHost.ensureStarted(ctx); LocalHost.localUrl } else server(ctx)

    /** Adresse à utiliser pour REJOINDRE une session (hors-ligne : le téléphone hôte). */
    fun joinUrl(ctx: Context, host: String): String =
        if (offline(ctx)) { setLastHost(ctx, host); "ws://${host.trim()}:${LocalHost.PORT}/ws" } else server(ctx)

    fun myLang(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString("myLang", "fr") ?: "fr"

    fun setMyLang(ctx: Context, value: String) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString("myLang", value).apply()
}

class IdCounter {
    private var n = 0L
    fun next(): Long = ++n
}
