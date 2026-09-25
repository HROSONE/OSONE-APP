package com.osone.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(val role: String, val text: String)

/**
 * Histórico privado ao app; falhas de disco não apagam a conversa em memória. A conversa atual fica em
 * conversa.json; as anteriores (Nova conversa), em conversas/<id>.json.
 */
class ConversationStore(context: Context) {
    private val file = java.io.File(context.filesDir, "conversa.json")
    private val archive = java.io.File(context.filesDir, "conversas")

    /** Conversas anteriores, da mais recente para a mais antiga. */
    fun saved(): List<SavedConversation> = (archive.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { runCatching { ChatHistory.fromJson(it.readText()) }.getOrNull() }
        .sortedByDescending { it.updatedAt }

    /** Guarda a conversa no histórico (Nova conversa); não guarda conversa vazia. */
    fun archive(messages: List<ChatMessage>, id: String = java.util.UUID.randomUUID().toString().take(10)) {
        if (messages.none { it.role == "user" }) return
        archive.mkdirs()
        val target = java.io.File(archive, "$id.json")
        val temp = java.io.File(archive, "$id.tmp")
        temp.writeText(ChatHistory.toJson(SavedConversation(id, ChatHistory.title(messages), System.currentTimeMillis(), messages)))
        if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
    }

    fun delete(id: String) {
        if (id.all { it.isLetterOrDigit() || it == '-' }) java.io.File(archive, "$id.json").delete()
    }

    fun read(): List<ChatMessage> = try {
        val array = JSONArray(file.readText())
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val role = item.optString("role")
            if (role != "user" && role != "model") return@mapNotNull null
            ChatMessage(role, item.optString("text"))
        }.takeLast(LIMIT)
    } catch (_: Exception) { emptyList() }

    fun save(messages: List<ChatMessage>) {
        val array = JSONArray()
        messages.takeLast(LIMIT).forEach { message ->
            array.put(JSONObject().put("role", message.role).put("text", message.text))
        }
        val temp = java.io.File(file.parentFile, "conversa.tmp")
        temp.writeText(array.toString())
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    fun clear() { file.delete() }

    private companion object {
        /** Mensagens guardadas por conversa (antes eram 100 e a mais antiga se perdia). */
        const val LIMIT = 400
    }
}
