package com.osone.app

import org.json.JSONArray
import org.json.JSONObject

/** Conversa guardada no histórico (Conversas anteriores). */
data class SavedConversation(val id: String, val title: String, val updatedAt: Long, val messages: List<ChatMessage>)

/** Título, busca e formato das conversas guardadas. Lógica pura, testável na JVM. */
object ChatHistory {
    /** Título automático: a primeira pergunta do usuário, curta. */
    fun title(messages: List<ChatMessage>): String {
        val first = messages.firstOrNull { it.role == "user" }?.text.orEmpty()
            .substringBefore("\n📎 ").removePrefix("Por voz: ").replace(Regex("\\s+"), " ").trim()
        return when {
            first.isEmpty() -> "Conversa"
            first.length <= 48 -> first
            else -> first.take(48).substringBeforeLast(' ').ifBlank { first.take(48) } + "…"
        }
    }

    class Match(val conversation: SavedConversation, val snippet: String)

    /** Conversas que contêm todas as palavras da busca (sem acento), com um trecho em volta da primeira ocorrência. */
    fun search(conversations: List<SavedConversation>, query: String): List<Match> {
        val words = normalized(query).split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return conversations.map { Match(it, "") }
        return conversations.mapNotNull { conversation ->
            val all = normalized(conversation.title + "\n" + conversation.messages.joinToString("\n") { it.text })
            if (!words.all { all.contains(it) }) return@mapNotNull null
            val hit = conversation.messages.firstOrNull { normalized(it.text).contains(words.first()) }?.text
                ?: return@mapNotNull Match(conversation, "")
            val at = normalized(hit).indexOf(words.first()).coerceAtLeast(0)
            val start = (at - 40).coerceAtLeast(0)
            Match(conversation, (if (start > 0) "…" else "") + hit.substring(start, minOf(hit.length, at + 80)).replace('\n', ' ').trim() +
                (if (at + 80 < hit.length) "…" else ""))
        }
    }

    private fun normalized(text: String) = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ")

    fun toJson(conversation: SavedConversation): String = JSONObject().put("id", conversation.id)
        .put("titulo", conversation.title).put("atualizada", conversation.updatedAt)
        .put("mensagens", JSONArray().apply {
            conversation.messages.forEach { put(JSONObject().put("role", it.role).put("text", it.text)) }
        }).toString()

    fun fromJson(text: String): SavedConversation? = try {
        val json = JSONObject(text)
        val list = json.optJSONArray("mensagens") ?: JSONArray()
        val messages = (0 until list.length()).mapNotNull { i ->
            list.optJSONObject(i)?.let { item ->
                item.optString("role").takeIf { it == "user" || it == "model" }?.let { ChatMessage(it, item.optString("text")) }
            }
        }
        SavedConversation(json.optString("id"), json.optString("titulo").ifBlank { title(messages) },
            json.optLong("atualizada"), messages).takeIf { it.id.isNotBlank() }
    } catch (_: Exception) { null }
}
