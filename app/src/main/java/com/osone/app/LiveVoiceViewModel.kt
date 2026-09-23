package com.osone.app

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Controla uma única chamada Live; nunca grava áudio ou transcrições em disco. */
class LiveVoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("osone_config", 0)
    private val secrets = SecureKeyStore(application)
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val endpoint = "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    var selected by mutableStateOf(LiveModel.fromId(preferences.getString("live_model", null)))
        private set
    var fallback by mutableStateOf(preferences.getBoolean("live_fallback", true))
        private set
    var active by mutableStateOf<LiveModel?>(null)
        private set
    var status by mutableStateOf("Pronto para conversar")
        private set
    var connected by mutableStateOf(false)
        private set
    var muted by mutableStateOf(false)
        private set
    var inputLevel by mutableStateOf(0f)
        private set
    var outputLevel by mutableStateOf(0f)
        private set

    private var candidates = emptyList<LiveModel>()
    private var candidateIndex = 0
    private var key: String? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    private var audio: LiveAudioEngine? = null
    private var resumptionHandle: String? = null
    private var resumedOnce = false
    private var running = false

    fun select(model: LiveModel) {
        selected = model
        preferences.edit().putString("live_model", model.id).apply()
    }

    fun updateFallback(enabled: Boolean) {
        fallback = enabled
        preferences.edit().putBoolean("live_fallback", enabled).apply()
    }

    fun start() {
        stop()
        val saved = secrets.read()
        if (saved == null) { status = "Salve sua chave Gemini em Ajustes antes de iniciar."; return }
        key = saved
        running = true
        candidates = LiveModel.candidates(selected, fallback)
        candidateIndex = 0
        connect()
    }

    fun toggleMute() {
        muted = !muted
        audio?.muted = muted
        if (muted) inputLevel = 0f
    }

    fun stop() {
        running = false
        ready = false
        connected = false
        active = null
        socket?.close(1000, "Conversa encerrada")
        socket = null
        audio?.stop(); audio = null
        key = null
        resumptionHandle = null
        inputLevel = 0f; outputLevel = 0f
        status = "Conversa encerrada"
    }

    private fun connect() {
        val apiKey = key ?: return
        val model = candidates.getOrNull(candidateIndex) ?: return
        val currentHandle = resumptionHandle
        ready = false
        connected = false
        active = model
        status = if (currentHandle != null) "Reconectando ${model.label}…" else "Conectando ${model.label}…"
        // Query encodificada; chave apenas em memória, sem logs ou mensagens de erro de rede.
        val url = endpoint.toHttpUrl().newBuilder().scheme("https").addQueryParameter("key", apiKey).build()
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (!running || socket !== webSocket) return@post
                    val setup = JSONObject().put("setup", JSONObject()
                        .put("model", "models/${model.id}")
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject()
                            .put("text", "Você é OSONE APP, assistente de Henrique. Converse naturalmente em português brasileiro. Não afirme ter executado ações externas que não realizou."))))
                        .put("sessionResumption", if (currentHandle == null) JSONObject() else JSONObject().put("handle", currentHandle))
                        .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject())))
                    if (!webSocket.send(setup.toString())) fail(webSocket, false)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                main.post {
                    if (!running || socket !== webSocket) return@post
                    try { handleMessage(webSocket, JSONObject(text)) } catch (_: Exception) {
                        // Um evento desconhecido não derruba o fluxo de áudio.
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post { fail(webSocket, response?.code == 401 || response?.code == 403) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { fail(webSocket, code == 1008 && reason.contains("auth", ignoreCase = true)) }
            }
        }
        val newSocket = client.newWebSocket(request, listener)
        socket = newSocket
        main.postDelayed({ if (running && socket === newSocket && !ready) fail(newSocket, false) }, 15_000)
    }

    private fun handleMessage(ws: WebSocket, message: JSONObject) {
        if (message.has("setupComplete")) {
            ready = true
            connected = true
            status = "Ouvindo · ${active?.label.orEmpty()}"
            if (audio == null) {
                try {
                    audio = LiveAudioEngine(
                        send = { data ->
                            val current = socket
                            if (ready && current != null && current.queueSize() < 512_000L) {
                                current.send(JSONObject().put("realtimeInput", JSONObject().put("audio", JSONObject()
                                    .put("data", data).put("mimeType", "audio/pcm;rate=16000"))).toString())
                            }
                        },
                        inputLevel = { value -> main.post { if (running) inputLevel = value } },
                        outputLevel = { value -> main.post { if (running) outputLevel = value } },
                        onError = { main.post { if (running) { stop(); status = "Microfone indisponível. Tente novamente." } } }
                    ).also { it.muted = muted; it.start() }
                } catch (_: Exception) { stop(); status = "Não foi possível iniciar o microfone ou o alto-falante." }
            }
        }
        message.optJSONObject("sessionResumptionUpdate")?.let { update ->
            if (update.optBoolean("resumable")) resumptionHandle = update.optString("newHandle").takeIf { it.isNotBlank() }
        }
        if (message.has("goAway")) { fail(ws, false); return }
        val content = message.optJSONObject("serverContent") ?: return
        if (content.optBoolean("interrupted")) audio?.interrupt()
        val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts") ?: return
        for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            if (inline.optString("mimeType").startsWith("audio/pcm")) {
                val data = inline.optString("data")
                if (data.isNotBlank()) audio?.receive(data)
            }
        }
    }

    private fun fail(ws: WebSocket, unauthorized: Boolean) {
        if (!running || socket !== ws) return
        ready = false
        connected = false
        socket = null
        ws.cancel()
        audio?.stop(); audio = null
        if (unauthorized) { stop(); status = "Chave Gemini recusada. Confira em Ajustes."; return }
        if (resumptionHandle != null && !resumedOnce) {
            resumedOnce = true
            connect()
            return
        }
        resumptionHandle = null
        resumedOnce = false
        candidateIndex++
        if (candidateIndex < candidates.size) {
            status = "Tentando outro modelo…"
            connect()
        } else {
            stop()
            status = "Não foi possível conectar aos modelos Live. Confira a internet, a chave e a cota."
        }
    }

    override fun onCleared() { stop(); client.dispatcher.executorService.shutdown(); super.onCleared() }
}
