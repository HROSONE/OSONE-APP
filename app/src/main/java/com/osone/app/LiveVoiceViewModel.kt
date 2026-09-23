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
import okio.ByteString
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
    var voice by mutableStateOf(LiveVoices.fromName(preferences.getString("live_voice", null)))
        private set
    var gain by mutableStateOf(preferences.getFloat("live_gain", 1.4f).coerceIn(0.5f, 2f))
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
    var attempts by mutableStateOf<List<String>>(emptyList())
        private set

    private var candidates = emptyList<LiveModel>()
    private var candidateIndex = 0
    private var key: String? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    private var audio: LiveAudioEngine? = null
    private var reconnectOnce = false
    private var running = false

    fun select(model: LiveModel) {
        selected = model
        preferences.edit().putString("live_model", model.id).apply()
    }

    fun updateFallback(enabled: Boolean) {
        fallback = enabled
        preferences.edit().putBoolean("live_fallback", enabled).apply()
    }

    fun selectVoice(name: String) {
        voice = LiveVoices.fromName(name)
        preferences.edit().putString("live_voice", voice).apply()
        if (running) start() // A voz pertence à configuração inicial de cada sessão.
    }

    fun setGain(value: Float) {
        gain = value.coerceIn(0.5f, 2f)
        preferences.edit().putFloat("live_gain", gain).apply()
        audio?.outputGain = gain
    }

    fun start() {
        stop()
        val saved = secrets.read()
        if (saved == null) { status = "Salve sua chave Gemini em Ajustes antes de iniciar."; return }
        key = saved
        running = true
        candidates = LiveModel.candidates(selected, fallback)
        candidateIndex = 0
        reconnectOnce = false
        attempts = emptyList()
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
        inputLevel = 0f; outputLevel = 0f
        status = "Conversa encerrada"
    }

    private fun connect() {
        val apiKey = key ?: return
        val model = candidates.getOrNull(candidateIndex) ?: return
        ready = false
        connected = false
        active = model
        status = if (reconnectOnce) "Reconectando ${model.label}…" else "Conectando ${model.label}…"
        // Query encodificada; chave apenas em memória, sem logs ou mensagens de erro de rede.
        val url = endpoint.toHttpUrl().newBuilder().scheme("https").addQueryParameter("key", apiKey).build()
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (!running || socket !== webSocket) return@post
                    // Começa com o setup mínimo da documentação, comum aos modelos Live.
                    val generation = JSONObject()
                        .put("responseModalities", JSONArray().put("AUDIO"))
                        .put("speechConfig", JSONObject().put("voiceConfig", JSONObject()
                            .put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))))
                    val setup = JSONObject().put("setup", JSONObject()
                        .put("model", "models/${model.id}")
                        .put("generationConfig", generation)
                        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject()
                            .put("text", "Você é OSONE APP, assistente de Henrique. Converse naturalmente em português brasileiro. Não afirme ter executado ações externas que não realizou.")))))
                    if (!webSocket.send(setup.toString())) fail(webSocket, "envio da configuração falhou")
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                dispatchMessage(webSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // O Gemini pode enviar setupComplete e áudio em quadros binários JSON.
                dispatchMessage(webSocket, bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                main.post {
                    val code = response?.code
                    val cause = when {
                        code != null -> "HTTP $code"
                        t is java.net.UnknownHostException -> "DNS ou internet indisponível"
                        t is javax.net.ssl.SSLException -> "falha TLS"
                        t is java.net.SocketTimeoutException -> "tempo de conexão esgotado"
                        else -> "falha de rede (${t.javaClass.simpleName.take(40)})"
                    }
                    fail(webSocket, cause, code == 401 || code == 403,
                        code == null || code == 401 || code == 403)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                main.post {
                    // Nunca mostra o texto bruto do servidor: pode conter dados da requisição.
                    val invalidKey = reason.contains("api key", true) || reason.contains("unauth", true) ||
                        reason.contains("permission denied", true)
                    fail(webSocket, "WebSocket $code" + when {
                        invalidKey -> " (chave recusada)"
                        code == 1007 -> " (configuração ou modelo recusado)"
                        code == 1011 -> " (falha temporária do serviço)"
                        else -> ""
                    }, invalidKey, code == 1006)
                }
            }
        }
        val newSocket = try { client.newWebSocket(request, listener) } catch (_: Exception) {
            stop(); status = "Não foi possível abrir a conexão Live."; return
        }
        socket = newSocket
        main.postDelayed({ if (running && socket === newSocket && !ready)
            fail(newSocket, "servidor não confirmou a sessão em 15 s") }, 15_000)
    }

    private fun dispatchMessage(webSocket: WebSocket, data: String) {
        main.post {
            if (!running || socket !== webSocket) return@post
            try { handleMessage(webSocket, JSONObject(data)) } catch (_: Exception) {
                fail(webSocket, "resposta Live inválida", terminal = true)
            }
        }
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
                    ).also { it.muted = muted; it.outputGain = gain; it.start() }
                } catch (_: Exception) { stop(); status = "Não foi possível iniciar o microfone ou o alto-falante." }
            }
        }
        if (message.has("goAway")) { fail(ws, "servidor pediu reconexão"); return }
        if (message.has("error")) { fail(ws, "erro comunicado pelo serviço"); return }
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

    private fun fail(ws: WebSocket, cause: String, unauthorized: Boolean = false, terminal: Boolean = false) {
        if (!running || socket !== ws) return
        val wasReady = ready
        attempts = attempts + "${active?.label.orEmpty()}: $cause"
        ready = false
        connected = false
        socket = null
        ws.cancel()
        audio?.stop(); audio = null
        if (unauthorized) { stop(); status = "Chave Gemini recusada. Confira em Ajustes."; return }
        if (terminal) { stop(); status = "Live indisponível: $cause."; return }
        if (wasReady && !reconnectOnce) {
            reconnectOnce = true
            connect()
            return
        }
        reconnectOnce = false
        candidateIndex++
        if (candidateIndex < candidates.size) {
            connect()
        } else {
            stop()
            status = "Nenhum modelo Live conectou. Veja o diagnóstico abaixo."
        }
    }

    override fun onCleared() { stop(); client.dispatcher.executorService.shutdown(); super.onCleared() }
}
