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
import java.util.concurrent.atomic.AtomicLong

/** Controla uma única chamada Live; nunca grava áudio ou transcrições em disco. */
class LiveVoiceViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("osone_config", 0)
    private val secrets = SecureKeyStore(application)
    private val diagnostics = AppDiagnostics.get(application)
    private val localTools = AndroidLocalTools(application)
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val endpoint = "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    var selected by mutableStateOf(LiveModel.fromId(preferences.getString("live_model", null)))
        private set
    var voice by mutableStateOf(LiveVoices.fromName(preferences.getString("live_voice", null)))
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
    var screenSharing by mutableStateOf(false)
    var screenFramesSent by mutableStateOf(0)
        private set
    var screenFramesSkipped by mutableStateOf(0)
        private set
    var lastScreenFrameAt by mutableStateOf(0L)
        private set
    var allowInterruptions by mutableStateOf(preferences.getBoolean("live_barge_in", false))
        private set
    var localToolsAvailable by mutableStateOf(true)
        private set

    private var candidates = emptyList<LiveModel>()
    private var candidateIndex = 0
    private var key: String? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var ready = false
    @Volatile private var audio: LiveAudioEngine? = null
    private val lastInputUi = AtomicLong(0L)
    private val lastOutputUi = AtomicLong(0L)
    private var interruptedAt = 0L
    private var interruptions = 0
    private var lastBackpressure = 0L
    private var reconnectOnce = false
    private var retriedWithoutTools = false
    @Volatile private var running = false

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

    fun start() {
        stop()
        val saved = secrets.read()
        if (saved == null) { status = "Salve sua chave Gemini em Ajustes antes de iniciar."; return }
        key = saved
        running = true
        candidates = LiveModel.candidates(selected, fallback)
        candidateIndex = 0
        reconnectOnce = false
        retriedWithoutTools = false
        localToolsAvailable = true
        attempts = emptyList()
        screenFramesSent = 0
        screenFramesSkipped = 0
        lastScreenFrameAt = 0L
        connect()
    }

    fun toggleMute() {
        muted = !muted
        audio?.muted = muted
        if (muted) inputLevel = 0f
    }

    fun updateAllowInterruptions(enabled: Boolean) {
        allowInterruptions = enabled
        preferences.edit().putBoolean("live_barge_in", enabled).apply()
        audio?.allowInterruptions = enabled
    }

    /** Um quadro JPEG por segundo, só durante projeção autorizada pelo Android. */
    fun sendScreenFrame(encodedJpeg: String) {
        val current = socket
        if (!ready || !screenSharing || current == null) return
        // Vídeo e microfone compartilham o WebSocket. Descarta a imagem se atrasaria o áudio.
        if (current.queueSize() > 96_000L) {
            main.post { screenFramesSkipped++ }
            if (System.currentTimeMillis() - lastBackpressure > 5000) {
                lastBackpressure = System.currentTimeMillis()
                diagnostics.record("Tela Live", "Quadro descartado: conexão ocupada. O microfone tem prioridade.")
            }
            return
        }
        val sent = current.send(JSONObject().put("realtimeInput", JSONObject().put("video", JSONObject()
            .put("mimeType", "image/jpeg").put("data", encodedJpeg))).toString())
        main.post {
            if (sent) { screenFramesSent++; lastScreenFrameAt = System.currentTimeMillis() }
            else screenFramesSkipped++
        }
    }

    /** Uma pergunta explícita dispara a análise dos quadros recém-enviados. */
    fun describeScreen() {
        val current = socket
        if (!ready || !screenSharing || current == null ||
            System.currentTimeMillis() - lastScreenFrameAt > 2500) {
            status = "Aguardando uma imagem recente da tela para analisar."
            return
        }
        val sent = current.send(JSONObject().put("realtimeInput", JSONObject()
            .put("text", "Observe a tela que estou compartilhando agora. Descreva brevemente o que vê e me ajude com o que estiver acontecendo."))
            .toString())
        if (!sent) diagnostics.record("Tela Live", "Não foi possível pedir análise da tela nesta conexão.")
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
        lastScreenFrameAt = 0L
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
                        .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
                        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject()
                            .put("text", "Você é OSONE APP, assistente de Henrique no Android. Converse naturalmente em português brasileiro. Use ações locais apenas quando Henrique pedir. A tela só é visível quando o compartilhamento estiver ligado e as imagens não são armazenadas. Não afirme ter executado ações externas que não realizou.")))))
                    if (localToolsAvailable) setup.getJSONObject("setup")
                        .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", localTools.declarations())))
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
                        code == 401 || code == 403)
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
        if (!running || socket !== webSocket) return
        val message = try { JSONObject(data) } catch (_: Exception) {
            main.post { fail(webSocket, "resposta Live inválida", terminal = true) }
            return
        }
        val content = message.optJSONObject("serverContent")
        if (content?.optBoolean("interrupted") == true) {
            audio?.interrupt()
            main.post {
                val now = System.currentTimeMillis()
                interruptions = if (now - interruptedAt < 10_000) interruptions + 1 else 1
                interruptedAt = now
                if (interruptions == 3) diagnostics.record("Live", "Três interrupções de voz em 10 s. Possível eco do alto-falante ou fala detectada durante a resposta.")
            }
        }
        val parts = content?.optJSONObject("modelTurn")?.optJSONArray("parts")
        if (parts != null) for (i in 0 until parts.length()) {
            val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            if (inline.optString("mimeType").startsWith("audio/pcm")) {
                val encoded = inline.optString("data")
                if (encoded.isNotBlank()) audio?.receive(encoded)
            }
        }
        if (content?.optBoolean("turnComplete") == true) audio?.finishTurn()
        message.optJSONObject("toolCall")?.let { call ->
            main.post { if (running && socket === webSocket) handleToolCall(webSocket, call) }
        }
        if (message.has("setupComplete") || message.has("goAway") || message.has("error")) {
            main.post { if (running && socket === webSocket) handleControl(webSocket, message) }
        }
    }

    private fun handleToolCall(ws: WebSocket, call: JSONObject) {
        val calls = call.optJSONArray("functionCalls") ?: return
        val responses = JSONArray()
        for (index in 0 until calls.length()) {
            val action = calls.optJSONObject(index) ?: continue
            val name = action.optString("name")
            val answer = localTools.execute(name, action.optJSONObject("args") ?: JSONObject())
            val response = JSONObject().put("name", name).put("response", JSONObject().put("result", answer))
            if (action.has("id")) response.put("id", action.optString("id"))
            responses.put(response)
        }
        if (responses.length() > 0 && socket === ws && running)
            ws.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
    }

    private fun handleControl(ws: WebSocket, message: JSONObject) {
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
                            } else if (ready && System.currentTimeMillis() - lastBackpressure > 5000) {
                                lastBackpressure = System.currentTimeMillis()
                                diagnostics.record("Live", "Envio do microfone atrasou: fila WebSocket cheia.")
                            }
                        },
                        inputLevel = { value ->
                            val now = System.currentTimeMillis()
                            if (now - lastInputUi.get() >= 80) {
                                lastInputUi.set(now)
                                main.post { if (running) inputLevel = value }
                            }
                        },
                        outputLevel = { value ->
                            val now = System.currentTimeMillis()
                            if (now - lastOutputUi.get() >= 70) {
                                lastOutputUi.set(now)
                                main.post { if (running) outputLevel = value }
                            }
                        },
                        onError = { main.post { if (running) {
                            diagnostics.record("Áudio Live", "Microfone ou alto-falante parou durante a chamada.")
                            stop(); status = "Áudio indisponível. Veja o diagnóstico."
                        } } },
                        onDiagnostic = { detail -> diagnostics.record("Áudio Live", detail) }
                    ).also { it.muted = muted; it.allowInterruptions = allowInterruptions; it.start() }
                } catch (failure: Exception) {
                    diagnostics.record("Áudio Live", "Não iniciou microfone/alto-falante (${failure.javaClass.simpleName}).")
                    stop(); status = "Não foi possível iniciar o áudio."
                }
            }
        }
        if (message.has("goAway")) { fail(ws, "servidor pediu reconexão"); return }
        if (message.has("error")) { fail(ws, "erro comunicado pelo serviço"); return }
    }

    private fun fail(ws: WebSocket, cause: String, unauthorized: Boolean = false, terminal: Boolean = false) {
        if (!running || socket !== ws) return
        val wasReady = ready
        attempts = attempts + "${active?.label.orEmpty()}: $cause"
        diagnostics.record("Conexão Live", "${active?.label.orEmpty()}: $cause")
        ready = false
        connected = false
        socket = null
        ws.cancel()
        audio?.stop(); audio = null
        if (unauthorized) { stop(); status = "Chave Gemini recusada. Confira em Ajustes."; return }
        if (terminal) { stop(); status = "Live indisponível: $cause."; return }
        if (!wasReady && !retriedWithoutTools && (cause.contains("1007") || cause == "HTTP 400")) {
            retriedWithoutTools = true
            localToolsAvailable = false
            diagnostics.record("Agente local", "Configuração de ações recusada neste modelo; reconectando somente voz.")
            connect()
            return
        }
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
