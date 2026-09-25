package com.osone.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pesquisa na web (ferramenta web_search) para o Live, o chat e as rotinas. Usa a API de busca do Google
 * (chave do Cloud Console + ID do mecanismo) quando configurada em Ajustes; senão, ou se ela falhar, o
 * modelo de texto Gemini pesquisa com a Pesquisa Google embutida e devolve um resumo com fontes.
 */
object WebSearch {
    const val NAME = "web_search"
    /** Chave da API de busca (cofre), ID do mecanismo e preferência pela API em vez da Pesquisa Google do Gemini. */
    const val KEY_SLOT = "key_google_search"
    const val CX = "google_search_cx"
    const val PREFER_API = "google_search_api"
    private const val SYSTEM = "Você pesquisa na web para um assistente de voz. Responda em português do Brasil, " +
        "em no máximo 6 frases objetivas, com números, datas e nomes exatos encontrados. Se não encontrar, diga isso."

    private fun preferences(context: Context) = context.getSharedPreferences("osone_config", 0)

    /** Chave e cx salvos da API de busca do Google. */
    fun configured(context: Context): Boolean =
        preferences(context).getString(CX, null).orEmpty().isNotBlank() && SecureKeyStore(context, KEY_SLOT).read() != null

    /** A API de busca substitui a Pesquisa Google embutida do Gemini (Live e chat usam web_search). */
    fun preferApi(context: Context): Boolean = configured(context) && preferences(context).getBoolean(PREFER_API, true)

    /** Há como pesquisar: API de busca ou chave Gemini. */
    fun available(context: Context): Boolean = configured(context) || SecureKeyStore(context).read() != null

    fun declaration(): JSONObject = JSONObject()
        .put("name", NAME)
        .put("description", "Pesquise na web (Google) informações atuais ou que você não sabe com certeza: notícias, " +
            "preços, placares, clima, horários, lançamentos, fatos recentes. Retorna resultados ou um resumo com as fontes.")
        .put("parameters", JSONObject().put("type", "OBJECT")
            .put("properties", JSONObject().put("consulta", JSONObject().put("type", "STRING")
                .put("description", "O que pesquisar, com contexto (lugar, data, nomes)")))
            .put("required", JSONArray().put("consulta")))

    /** Roda fora da thread principal; [respond] recebe o resultado da ferramenta. */
    fun run(context: Context, args: JSONObject, respond: (JSONObject) -> Unit) {
        val query = args.optString("consulta").trim().take(500)
        if (query.isEmpty()) { respond(JSONObject().put("erro", "Informe o que pesquisar.")); return }
        Thread({
            val diagnostics = AppDiagnostics.get(context)
            var result: JSONObject? = null
            val reasons = ArrayList<String>()
            if (configured(context)) {
                try {
                    result = GoogleSearchApi.search(SecureKeyStore(context, KEY_SLOT).read().orEmpty(),
                        preferences(context).getString(CX, null).orEmpty(), query)
                } catch (failure: Exception) {
                    val reason = if (failure is GoogleSearchException) failure.message.orEmpty()
                        else "sem conexão com a busca Google (${failure.javaClass.simpleName})"
                    reasons += reason
                    diagnostics.record("Busca Google", reason)
                }
            }
            respond(result ?: try {
                JSONObject().put("resultado", gemini(context, query).take(3_000))
            } catch (failure: Exception) {
                val reason = when {
                    failure is GeminiHttpException && failure.status == 429 -> "a cota de pesquisa do Gemini acabou por hoje"
                    failure is GeminiHttpException -> "o Gemini recusou a pesquisa (HTTP ${failure.status})"
                    SecureKeyStore(context).read() == null -> "não há chave Gemini salva"
                    else -> "o Gemini não respondeu (${failure.javaClass.simpleName})"
                }
                reasons += reason
                diagnostics.record("Pesquisa", reason)
                // O motivo vai junto: o modelo explica ao usuário em vez de dizer só "não consegui".
                JSONObject().put("erro", "Pesquisa indisponível agora: ${reasons.joinToString("; ")}.")
                    .put("motivo", reasons.joinToString("; "))
            })
        }, "ostie-web-search").start()
    }

    private fun gemini(context: Context, query: String): String {
        val key = SecureKeyStore(context).read() ?: error("Chave Gemini não configurada.")
        val choices = ChatModel.candidates(ChatModel.fromId(preferences(context).getString("model", null)), true)
        var lastError: Exception? = null
        for (choice in choices) {
            try {
                return GeminiClient().streamAnswer(key, choice, listOf(ChatMessage("user", "Pesquise na web: $query")),
                    ThinkingMode.FAST, null, SYSTEM, 60_000, googleSearch = true) { }
            } catch (failure: GeminiHttpException) {
                // 400 = modelo sem pesquisa; 404/429/5xx = indisponível ou sem cota: tenta o próximo.
                lastError = failure
                if (!failure.allowsFallback && failure.status != 400) throw failure
            }
        }
        throw lastError ?: IllegalStateException("sem resposta")
    }
}

/**
 * Conversa de voz transcrita que vai para o histórico do chat escrito.
 * O Live grava aqui; o chat consome quando a revisão muda (tela aberta) ou ao voltar ao app.
 */
object LiveTranscriptInbox {
    private val main = Handler(Looper.getMainLooper())
    var revision by mutableIntStateOf(0)
        private set

    fun push(context: Context, user: String, model: String) {
        if (user.isBlank() && model.isBlank()) return
        val preferences = context.getSharedPreferences("osone_live_transcript", 0)
        synchronized(this) {
            val list = try { JSONArray(preferences.getString("pending", "[]")) } catch (_: Exception) { JSONArray() }
            list.put(JSONObject().put("user", user.trim().take(4_000)).put("model", model.trim().take(8_000)))
            while (list.length() > 60) list.remove(0)
            preferences.edit().putString("pending", list.toString()).apply()
        }
        main.post { revision++ }
    }

    fun drain(context: Context): List<Pair<String, String>> = synchronized(this) {
        val preferences = context.getSharedPreferences("osone_live_transcript", 0)
        val list = try { JSONArray(preferences.getString("pending", "[]")) } catch (_: Exception) { JSONArray() }
        preferences.edit().remove("pending").apply()
        (0 until list.length()).map { list.getJSONObject(it).let { item -> item.optString("user") to item.optString("model") } }
    }
}
