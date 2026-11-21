package com.lingualive.app.core

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Traduction sur l'appareil avec Google ML Kit.
 * Chaque participant traduit lui-même vers SA langue : le serveur ne relaie que le texte d'origine.
 * Le modèle de langue (~30 Mo) est téléchargé une seule fois, ensuite tout fonctionne hors-ligne.
 */
class TranslationManager {
    private val translators = mutableMapOf<String, Translator>()
    private val ready = mutableSetOf<String>()
    private val mutex = Mutex()

    suspend fun translate(text: String, from: String, to: String): String {
        if (text.isBlank() || from == to) return text
        val src = TranslateLanguage.fromLanguageTag(from) ?: return text
        val tgt = TranslateLanguage.fromLanguageTag(to) ?: return text
        val key = "$src>$tgt"
        val translator = mutex.withLock {
            val t = translators.getOrPut(key) {
                Translation.getClient(
                    TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build()
                )
            }
            if (key !in ready) {
                t.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                ready += key
            }
            t
        }
        return translator.translate(text).await()
    }

    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
        ready.clear()
    }
}
