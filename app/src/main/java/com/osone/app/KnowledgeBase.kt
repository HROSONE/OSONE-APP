package com.osone.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * Base de conhecimento: textos, links e arquivos (PDF, MD, TXT, DOCX) que transformam o OSTIE num
 * assistente de atendimento sobre aquele assunto. Tudo vira texto no aparelho (PDF é lido pelo Gemini uma
 * vez) e fica salvo no app e em Documentos/OSTIE/conhecimento.json, que sobrevive a reinstalações.
 */
class KnowledgeBase private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: KnowledgeBase? = null
        fun get(context: Context): KnowledgeBase = instance ?: synchronized(this) {
            instance ?: KnowledgeBase(context.applicationContext).also { instance = it }
        }
        const val TOOL = "knowledge_search"
        private const val FILE = "conhecimento.json"
        private const val MAX_SOURCE = 300_000
        private const val MAX_TOTAL = 1_000_000
        private const val MAX_SOURCES = 40
    }

    private val internal = File(context.filesDir, FILE)
    private val memory = MemoryStore.get(context)
    private val main = Handler(Looper.getMainLooper())

    var enabled by mutableStateOf(true)
        private set
    var strict by mutableStateOf(false)
        private set
    var instructions by mutableStateOf("")
        private set
    var sources by mutableStateOf<List<KnowledgeSource>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var status by mutableStateOf<String?>(null)
        private set

    init { load() }

    /** Ativa quando ligada e com algo configurado. */
    val active get() = enabled && (sources.isNotEmpty() || instructions.isNotBlank())

    fun promptBlock(): String = if (enabled) KnowledgeIndex.promptBlock(sources, instructions, strict) else ""

    /** Base grande: trechos ligados à pergunta, para modelos sem a ferramenta de busca (ex.: Groq, OpenRouter). */
    fun relevant(query: String): String {
        if (!enabled || sources.sumOf { it.text.length } <= KnowledgeIndex.INLINE_LIMIT) return ""
        val hits = KnowledgeIndex.search(sources, query, 4)
        if (hits.isEmpty()) return ""
        return "\n\nTrechos da base de conhecimento ligados à última mensagem:" +
            hits.joinToString("") { "\n\n[${it.title}]\n${it.text}" }
    }

    fun toolDeclaration(): JSONObject = JSONObject()
        .put("name", TOOL)
        .put("description", "Procure na base de conhecimento carregada neste aparelho (empresa, produto, pessoa ou assunto configurado). " +
            "Use antes de responder perguntas sobre esses assuntos. Retorna os trechos mais relevantes com o título da fonte.")
        .put("parameters", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject().put("consulta", JSONObject().put("type", "STRING")
                .put("description", "A pergunta ou palavras-chave, com os termos que a pessoa usou")))
            .put("required", JSONArray().put("consulta")))

    fun search(args: JSONObject): JSONObject {
        val hits = KnowledgeIndex.search(sources, args.optString("consulta"))
        return if (hits.isEmpty()) JSONObject().put("resultado", "Nada na base de conhecimento sobre isso.")
        else JSONObject().put("trechos", JSONArray(hits.map { JSONObject().put("fonte", it.title).put("texto", it.text) }))
    }

    fun updateEnabled(value: Boolean) { enabled = value; save() }
    fun updateStrict(value: Boolean) { strict = value; save() }
    fun updateInstructions(value: String) { instructions = value.take(2_000); save() }

    fun remove(id: String) {
        sources = sources.filterNot { it.id == id }
        save()
    }

    fun addText(title: String, text: String) = run("Salvando…") {
        add(title.ifBlank { text.lineSequence().firstOrNull { it.isNotBlank() }?.take(60) ?: "Texto" }, "texto", text)
    }

    fun addLink(address: String) = run("Lendo a página…") {
        val url = address.trim().let { if (it.startsWith("http", true)) it else "https://$it" }
        val connection = UpdateFeed.openHttps(url)
        val (bytes, type) = try {
            readLimited(connection.inputStream, 8_000_000) to (connection.contentType ?: "").lowercase()
        } finally { connection.disconnect() }
        val host = Uri.parse(url).host ?: url
        when {
            type.contains("pdf") || url.lowercase().endsWith(".pdf") ->
                add(Uri.parse(url).lastPathSegment ?: host, "link", readPdf(JSONObject().put("inlineData",
                    JSONObject().put("mimeType", "application/pdf").put("data", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))))
            type.contains("html") || type.isBlank() -> {
                val html = String(bytes, Charsets.UTF_8)
                val title = Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                    ?.let { android.text.Html.fromHtml(it, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim() }
                    ?.takeIf { it.isNotBlank() } ?: host
                val body = html.replace(Regex("<(script|style|noscript|svg|nav|footer)[\\s\\S]*?</\\1>", RegexOption.IGNORE_CASE), " ")
                    .replace(Regex("</(p|div|li|h[1-6]|tr|br|section|article)>", RegexOption.IGNORE_CASE), "$0\n")
                val text = android.text.Html.fromHtml(body, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
                    .replace('￼', ' ').replace(Regex("[ \\t]+"), " ").replace(Regex("\\n\\s*\\n+"), "\n\n").trim()
                add("$title ($host)", "link", text)
            }
            type.startsWith("text/") || type.contains("json") || type.contains("markdown") -> add(host, "link", String(bytes, Charsets.UTF_8))
            else -> error("Esse link não é uma página, PDF nem texto ($type).")
        }
    }

    /** PDF, MD, TXT, DOCX e outros textos escolhidos no aparelho. */
    fun addFile(uri: Uri) = run("Lendo o arquivo…") {
        val client = AttachmentClient(context)
        val ref = client.describe(uri)
        val key = SecureKeyStore(context).read()
        val payload = client.prepare(ref, key ?: "")
        try {
            val part = payload.part
            val text = if (part.has("text")) part.getString("text").substringAfter(":\n", part.getString("text"))
                else {
                    if (key == null) error("Para ler PDF, salve a chave Gemini em Ajustes.")
                    readPdf(part)
                }
            add(ref.name.substringBeforeLast('.').ifBlank { ref.name }, ref.name.substringAfterLast('.', "arquivo").lowercase(), text)
        } finally {
            payload.remoteName?.let { name -> key?.let { try { client.delete(name, it) } catch (_: Exception) { } } }
        }
    }

    /** O Gemini transcreve o PDF uma vez; depois a base usa só o texto. */
    private fun readPdf(part: JSONObject): String {
        val key = SecureKeyStore(context).read() ?: error("Para ler PDF, salve a chave Gemini em Ajustes.")
        val prompt = "Transcreva todo o texto deste documento, na ordem, em Markdown simples. Não resuma, não comente e " +
            "não invente. Converta tabelas em listas legíveis. Se houver imagens com texto, transcreva o texto."
        var last: Exception? = null
        for (model in listOf(ChatModel.GEMINI_25) + ChatModel.candidates(ChatModel.GEMINI_38, true)) {
            try {
                return GeminiClient().streamAnswer(key, model, listOf(ChatMessage("user", prompt)), ThinkingMode.FAST,
                    part, "Você transcreve documentos com fidelidade.", 240_000) { }
            } catch (failure: GeminiHttpException) {
                last = failure
                if (!failure.allowsFallback && failure.status != 400) throw failure
            }
        }
        throw last ?: IllegalStateException("Não consegui ler o PDF.")
    }

    private fun add(title: String, kind: String, raw: String) {
        val text = raw.replace("\r\n", "\n").trim()
        require(text.length >= 20) { "Não encontrei texto suficiente nessa fonte." }
        require(PlanStore.allows(PlanFeature.KNOWLEDGE)) { PlanRules.blocked(PlanFeature.KNOWLEDGE) }
        require(sources.size < MAX_SOURCES) { "Limite de $MAX_SOURCES fontes; apague alguma antes." }
        val source = KnowledgeSource(UUID.randomUUID().toString().take(8), title.trim().take(80), kind, text.take(MAX_SOURCE), System.currentTimeMillis())
        // Base cheia: recusa a fonte nova com aviso; nunca apaga uma fonte antiga sozinho.
        KnowledgeIndex.fullMessage(sources.sumOf { it.text.length }, source.text.length, MAX_TOTAL)?.let { error(it) }
        main.post {
            KnowledgeIndex.fullMessage(sources.sumOf { it.text.length }, source.text.length, MAX_TOTAL)?.let {
                status = "Não consegui adicionar: $it"
                return@post
            }
            sources = sources + source
            save()
            status = "\"${source.title}\" adicionada (${"%,d".format(source.text.length)} caracteres)." +
                if (text.length > MAX_SOURCE) " O texto foi cortado no limite de ${"%,d".format(MAX_SOURCE)} caracteres." else ""
        }
    }

    private fun run(message: String, work: () -> Unit) {
        if (busy) return
        busy = true
        status = message
        Thread({
            val error = try { work(); null } catch (failure: Exception) {
                AppDiagnostics.get(context).record("Base de conhecimento", failure.message?.take(140) ?: failure.javaClass.simpleName)
                failure.message ?: "Falha ao ler a fonte."
            }
            main.post { busy = false; if (error != null) status = "Não consegui adicionar: $error" }
        }, "ostie-knowledge").start()
    }

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray = input.use {
        val buffer = ByteArrayOutputStream()
        val block = ByteArray(16_384)
        while (true) {
            val count = it.read(block)
            if (count < 0) break
            require(buffer.size() + count <= limit) { "Conteúdo acima de ${limit / 1_000_000} MB." }
            buffer.write(block, 0, count)
        }
        buffer.toByteArray()
    }

    private fun save() {
        val json = JSONObject().put("ativa", enabled).put("estrita", strict).put("instrucoes", instructions)
            .put("fontes", JSONArray(sources.map {
                JSONObject().put("id", it.id).put("titulo", it.title).put("tipo", it.kind).put("texto", it.text).put("em", it.addedAt)
            })).toString()
        Thread({
            try { internal.writeText(json) } catch (_: Exception) { }
            memory.writeShared(FILE, json)
        }, "ostie-knowledge-save").start()
    }

    /** Lê a cópia do app; sem ela (ex.: reinstalação), a da pasta Documentos/OSTIE. */
    private fun load() {
        val raw = (try { internal.takeIf { it.isFile }?.readText() } catch (_: Exception) { null }) ?: memory.readShared(FILE) ?: return
        try {
            val json = JSONObject(raw)
            enabled = json.optBoolean("ativa", true)
            strict = json.optBoolean("estrita", false)
            instructions = json.optString("instrucoes")
            val list = json.optJSONArray("fontes") ?: JSONArray()
            sources = (0 until list.length()).mapNotNull { index ->
                list.optJSONObject(index)?.let {
                    KnowledgeSource(it.optString("id"), it.optString("titulo"), it.optString("tipo"), it.optString("texto"), it.optLong("em"))
                }
            }
        } catch (_: Exception) { }
    }
}
