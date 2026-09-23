package com.osone.app

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
    private val writing = WritingWorkspace.get(application)
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private val endpoint = "https://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

    var selected by mutableStateOf(LiveModel.fromId(preferences.getString("live_model", null)))
        private set
    var voice by mutableStateOf(LiveVoices.fromName(preferences.getString("live_voice", null)))
        private set
    var fallback by mutableStateOf(preferences.getBoolean("live_fallback", true))
        private set
    var echoGuard by mutableStateOf(preferences.getBoolean("live_echo_guard", true))
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
    var screenFramesCaptured by mutableStateOf(0)
        private set
    var screenFramesSkipped by mutableStateOf(0)
        private set
    var lastScreenFrameAt by mutableStateOf(0L)
        private set
    var cameraSharing by mutableStateOf(false)
    var cameraFront by mutableStateOf(false)
    var cameraPreview by mutableStateOf<Bitmap?>(null)
        private set
    var cameraFramesCaptured by mutableStateOf(0)
        private set
    var cameraFramesSent by mutableStateOf(0)
        private set
    var cameraFramesSkipped by mutableStateOf(0)
        private set
    var lastCameraFrameAt by mutableStateOf(0L)
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

    fun updateEchoGuard(enabled: Boolean) {
        echoGuard = enabled
        audio?.echoGuard = enabled
        preferences.edit().putBoolean("live_echo_guard", enabled).apply()
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
        screenFramesCaptured = 0
        screenFramesSkipped = 0
        lastScreenFrameAt = 0L
        interruptions = 0
        connect()
    }

    fun toggleMute() {
        muted = !muted
        audio?.muted = muted
        if (muted) inputLevel = 0f
    }

    fun screenFrameCaptured() { main.post { screenFramesCaptured++ } }

    fun resetCameraCounters() {
        cameraFramesCaptured = 0
        cameraFramesSent = 0
        cameraFramesSkipped = 0
        lastCameraFrameAt = 0L
    }

    fun clearCameraPreview() { cameraPreview = null }

    /** O JPEG mais recente aparece no app; o envio usa a mesma conexão da voz, sem fila de vídeo. */
    fun sendCameraFrame(jpeg: ByteArray, capturedAt: Long) {
        if (!cameraSharing) return
        val image = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        main.post {
            if (cameraSharing) {
                cameraFramesCaptured++
                if (image != null) cameraPreview = image
            }
        }
        val current = socket
        if (!ready || current == null) return
        if (System.currentTimeMillis() - capturedAt > 1500 || current.queueSize() > 32_000L) {
            main.post { cameraFramesSkipped++ }
            if (System.currentTimeMillis() - lastBackpressure > 5000) {
                lastBackpressure = System.currentTimeMillis()
                diagnostics.record("Câmera Live", "Quadro descartado: a conexão está ocupada com áudio.")
            }
            return
        }
        val sent = current.send(JSONObject().put("realtimeInput", JSONObject().put("video", JSONObject()
            .put("mimeType", "image/jpeg").put("data", Base64.encodeToString(jpeg, Base64.NO_WRAP)))).toString())
        main.post {
            if (sent) { cameraFramesSent++; lastCameraFrameAt = System.currentTimeMillis() }
            else cameraFramesSkipped++
        }
    }

    /** Um quadro JPEG por segundo; se a conexão atrasar, nunca enfileira imagens antigas. */
    fun sendScreenFrame(encodedJpeg: String, capturedAt: Long) {
        val current = socket
        if (!ready || !screenSharing || current == null) return
        // Vídeo e microfone compartilham o WebSocket. Descarta a imagem se atrasaria o áudio.
        if (System.currentTimeMillis() - capturedAt > 1500 || current.queueSize() > 32_000L) {
            main.post { screenFramesSkipped++ }
            if (System.currentTimeMillis() - lastBackpressure > 5000) {
                lastBackpressure = System.currentTimeMillis()
                diagnostics.record("Tela Live", "Imagem antiga descartada: conexão ocupada. O microfone tem prioridade.")
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
                            .put("text", "Você é OSTIE, assistente de Henrique no Android. Converse naturalmente em português brasileiro. Quando Henrique pedir para produzir um texto escrito ou código para a Aba de Escrita, escreva o conteúdo integral usando write_document e então diga que está disponível para editar, copiar ou visualizar. Para HTML, envie HTML completo e formato html. Não transcreva toda a conversa por voz; a aba recebe apenas textos ou códigos pedidos. Se ele pedir uma continuação, use operacao adicionar; se pedir alteração, envie o documento completo revisado com operacao substituir. Use ferramentas locais quando Henrique pedir para agir. Para Configurações, use open_settings ou open_app_settings, examine os controles e ajude a ajustar a opção pedida; mude volume de mídia, brilho, tempo de tela ou rotação automática apenas quando solicitado. Não tente alterar Wi-Fi, Bluetooth ou permissões diretamente sem a tela do Android. Descubra apps com busca dinâmica, incluindo apps do sistema se necessário. Após um toque, escrita ou gesto, inspecione novamente ou use check_ui para verificar o resultado antes de dizer que conseguiu. Um gesto aceito não significa que uma tarefa terminou. As imagens da tela e da câmera só chegam quando Henrique liga o compartilhamento correspondente; não são armazenadas. Converse normalmente enquanto analisa a imagem mais recente. Não afirme ter executado ações externas que não realizou.")))))
                    if (localToolsAvailable) setup.getJSONObject("setup")
                        .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", localTools.declarations().put(writeDocumentDeclaration()))))
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
                if (interruptions == 3) diagnostics.record("Live", if (echoGuard)
                    "Três interrupções de voz em 10 s. Ruído alto ou outra pessoa falando perto do microfone."
                    else "Três interrupções de voz em 10 s. Ative a proteção de eco no painel do Live ou use fones.")
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
        if (content?.optBoolean("turnComplete") == true || content?.optBoolean("generationComplete") == true)
            audio?.finishTurn()
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
            val args = action.optJSONObject("args") ?: JSONObject()
            val answer = if (name == "write_document") writing.publish(args) else localTools.execute(name, args)
            val response = JSONObject().put("name", name).put("response", JSONObject().put("result", answer))
            if (action.has("id")) response.put("id", action.optString("id"))
            responses.put(response)
        }
        if (responses.length() > 0 && socket === ws && running)
            ws.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
    }

    private fun writeDocumentDeclaration(): JSONObject = JSONObject()
        .put("name", "write_document")
        .put("description", "Publique o texto ou código completo solicitado pelo usuário na Aba de Escrita editável. Use formato html para páginas HTML que podem ter preview pelo botão Play. Não use para transcrever a conversa.")
        .put("parameters", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject()
                .put("titulo", JSONObject().put("type", "STRING").put("description", "Título curto do documento"))
                .put("conteudo", JSONObject().put("type", "STRING").put("description", "Texto ou código integral, sem omissões nem blocos markdown em torno de HTML"))
                .put("formato", JSONObject().put("type", "STRING").put("description", "html para HTML, text para os demais textos e códigos"))
                .put("operacao", JSONObject().put("type", "STRING").put("description", "substituir para nova versão, adicionar para continuar o documento")))
            .put("required", JSONArray().put("titulo").put("conteudo").put("formato")))

    private fun handleControl(ws: WebSocket, message: JSONObject) {
        if (message.has("setupComplete")) {
            ready = true
            connected = true
            status = "Ouvindo · ${active?.label.orEmpty()}"
            if (audio == null) {
                try {
                    audio = LiveAudioEngine(
                        context = getApplication(),
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
                    ).also { it.muted = muted; it.echoGuard = echoGuard; it.start() }
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
