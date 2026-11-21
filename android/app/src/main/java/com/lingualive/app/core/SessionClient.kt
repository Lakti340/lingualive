package com.lingualive.app.core

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Connexion WebSocket au serveur de sessions.
 * Le présentateur crée une session (code à 6 caractères) et envoie ses segments ;
 * les participants rejoignent avec le code, depuis la salle ou à distance (hybride / virtuel).
 */
class SessionClient(
    private val onMessage: (JSONObject) -> Unit,
    private val onState: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private var ws: WebSocket? = null
    private val main = Handler(Looper.getMainLooper())

    val isOpen: Boolean get() = ws != null

    fun connect(url: String, onOpen: () -> Unit) {
        if (ws != null) { onOpen(); return }
        onState("Connexion au serveur…")
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            onState("Adresse de serveur invalide : $url")
            return
        }
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post { onState("Connecté"); onOpen() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
                main.post { onMessage(obj) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post {
                    ws = null
                    onState("Serveur injoignable (${t.message ?: "erreur"}) — mode local uniquement")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { ws = null; onState("Déconnecté") }
            }
        })
    }

    fun send(obj: JSONObject): Boolean = ws?.send(obj.toString()) ?: false

    fun close() {
        ws?.close(1000, null)
        ws = null
    }

    companion object {
        fun segment(id: Long, text: String, final: Boolean, lang: String): JSONObject =
            JSONObject()
                .put("type", "segment")
                .put("id", id)
                .put("text", text)
                .put("final", final)
                .put("lang", lang)
    }
}
