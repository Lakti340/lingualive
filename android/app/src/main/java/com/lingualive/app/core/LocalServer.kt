package com.lingualive.app.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Serveur de sessions intégré à l'appli : le téléphone de l'hôte fait office de serveur
 * sur le Wi-Fi local ou sur son propre point d'accès. Aucun internet nécessaire.
 * Même protocole que server/server.js (modes présentation et réunion).
 */
class LocalServer(port: Int) : WebSocketServer(InetSocketAddress(port)) {
    private class Client(val id: Int) {
        var role = ""
        var session: Sess? = null
        var name = "Invité"
    }

    private class Sess(val code: String, val mode: String, val lang: String) {
        var presenter: WebSocket? = null
        val members = LinkedHashSet<WebSocket>()
        val history = ArrayList<JSONObject>()
        var ended = false
        var seq = 0L
        val createdAt = System.currentTimeMillis()
    }

    private val sessions = LinkedHashMap<String, Sess>()
    private val lock = Any()
    private var nextId = 1
    val started = CountDownLatch(1)

    init {
        isReuseAddr = true
        connectionLostTimeout = 30
    }

    override fun onStart() { started.countDown() }
    override fun onError(conn: WebSocket?, ex: Exception) { if (conn == null) started.countDown() }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        synchronized(lock) { conn.setAttachment(Client(nextId++)) }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val m = runCatching { JSONObject(message) }.getOrNull() ?: return
        synchronized(lock) { handle(conn, m) }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        synchronized(lock) {
            val c = conn.getAttachment<Client>() ?: return
            val s = c.session ?: return
            when (c.role) {
                "member" -> {
                    s.members.remove(conn)
                    broadcast(s, JSONObject().put("type", "left").put("from", c.id).put("name", c.name))
                    presence(s)
                    if (s.members.isEmpty()) endSession(s)
                }
                "attendee" -> { s.members.remove(conn); stats(s) }
                "presenter" -> if (s.presenter == conn) endSession(s)
            }
        }
    }

    private fun send(ws: WebSocket?, o: JSONObject) {
        if (ws != null && ws.isOpen) runCatching { ws.send(o.toString()) }
    }

    private fun broadcast(s: Sess, o: JSONObject, except: WebSocket? = null) {
        val data = o.toString()
        s.members.forEach { if (it != except && it.isOpen) runCatching { it.send(data) } }
    }

    private fun stats(s: Sess) = send(s.presenter, JSONObject().put("type", "stats").put("listeners", s.members.size))

    private fun presence(s: Sess) = broadcast(
        s, JSONObject().put("type", "presence").put("count", s.members.size)
            .put("names", JSONArray(s.members.map { it.getAttachment<Client>()?.name ?: "" }))
    )

    private fun endSession(s: Sess) {
        if (s.ended) return
        s.ended = true
        broadcast(s, JSONObject().put("type", "ended"))
    }

    private fun newCode(): String {
        val a = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        var c: String
        do { c = (1..6).map { a.random() }.joinToString("") } while (sessions.containsKey(c))
        return c
    }

    private fun joined(s: Sess, you: Int?) = JSONObject()
        .put("type", "joined").put("mode", s.mode).put("code", s.code)
        .put("lang", s.lang).put("ended", s.ended).put("history", JSONArray(s.history))
        .apply { if (you != null) put("you", you) }

    private fun joinMeeting(conn: WebSocket, c: Client, s: Sess, m: JSONObject) {
        c.role = "member"; c.session = s
        c.name = m.optString("name", "Invité").ifBlank { "Invité" }.take(40)
        s.members.add(conn)
        send(conn, joined(s, c.id))
        presence(s)
    }

    private fun handle(conn: WebSocket, m: JSONObject) {
        val c = conn.getAttachment<Client>() ?: return
        when (m.optString("type")) {
            "create" -> {
                // Un hôte hors-ligne n'a qu'une session active à la fois : on nettoie les anciennes.
                sessions.values.removeAll { it.ended }
                val mode = if (m.optString("mode") == "meeting") "meeting" else "presentation"
                val s = Sess(newCode(), mode, m.optString("lang", "fr"))
                sessions[s.code] = s
                if (mode == "meeting") joinMeeting(conn, c, s, m)
                else {
                    s.presenter = conn; c.role = "presenter"; c.session = s
                    send(conn, JSONObject().put("type", "created").put("code", s.code))
                }
            }
            "join" -> {
                val code = m.optString("code").trim().uppercase()
                // Hors-ligne, le code est facultatif : on rejoint la session en cours sur ce téléphone.
                val s = if (code.isBlank()) sessions.values.filter { !it.ended }.maxByOrNull { it.createdAt }
                        else sessions[code]
                if (s == null) { send(conn, JSONObject().put("type", "error").put("message", "Aucune session trouvée sur ce téléphone")); return }
                if (s.mode == "meeting") { joinMeeting(conn, c, s, m); return }
                c.role = "attendee"; c.session = s
                s.members.add(conn)
                send(conn, joined(s, null))
                stats(s)
            }
            "segment" -> {
                val s = c.session ?: return
                if (s.ended) return
                val text = m.optString("text").take(2000)
                if (text.isBlank()) return
                val lang = m.optString("lang", s.lang)
                val final = m.optBoolean("final")
                if (c.role == "member") {
                    if (final) {
                        val seg = JSONObject().put("type", "segment").put("id", ++s.seq).put("from", c.id)
                            .put("name", c.name).put("text", text).put("final", true).put("lang", lang)
                        s.history.add(seg); if (s.history.size > 5000) s.history.removeAt(0)
                        broadcast(s, seg)
                    } else {
                        broadcast(s, JSONObject().put("type", "segment").put("from", c.id).put("name", c.name)
                            .put("text", text).put("final", false).put("lang", lang), except = conn)
                    }
                } else if (c.role == "presenter") {
                    val seg = JSONObject().put("type", "segment").put("id", m.optLong("id"))
                        .put("text", text).put("final", final).put("lang", lang)
                    if (final) { s.history.add(seg); if (s.history.size > 5000) s.history.removeAt(0) }
                    broadcast(s, seg)
                }
            }
            "end" -> c.session?.let { if (c.role == "presenter") endSession(it) }
        }
    }
}

/** Démarre/arrête le serveur intégré et l'annonce sur le réseau local (découverte automatique). */
object LocalHost {
    const val PORT = 8765
    const val SERVICE_TYPE = "_lingualive._tcp."
    val localUrl = "ws://127.0.0.1:$PORT/ws"

    private var server: LocalServer? = null
    private var nsd: NsdManager? = null
    private var registration: NsdManager.RegistrationListener? = null

    @Synchronized
    fun ensureStarted(ctx: Context) {
        if (server != null) return
        val s = LocalServer(PORT)
        s.start()
        s.started.await(2, TimeUnit.SECONDS)
        server = s
        advertise(ctx.applicationContext)
    }

    @Synchronized
    fun stop() {
        runCatching { registration?.let { nsd?.unregisterService(it) } }
        runCatching { server?.stop(500) }
        server = null; registration = null
    }

    /** Adresses IPv4 de ce téléphone (Wi-Fi ou point d'accès en premier). */
    fun ipAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it.hostAddress.orEmpty() } }
            .sortedBy { (n, _) -> if (n.startsWith("wlan") || n.startsWith("swlan") || n.startsWith("ap")) 0 else 1 }
            .map { it.second }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    private fun advertise(ctx: Context) {
        val manager = ctx.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = "LinguaLive ${Build.MODEL}"
            serviceType = SERVICE_TYPE
            port = PORT
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
        nsd = manager; registration = listener
    }
}
