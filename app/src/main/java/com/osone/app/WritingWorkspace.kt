package com.osone.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** Um documento compartilhado entre a tela de escrita e o serviço Live, salvo apenas no aparelho. */
class WritingWorkspace private constructor(context: Context) {
    companion object {
        @Volatile private var instance: WritingWorkspace? = null
        fun get(context: Context): WritingWorkspace = instance ?: synchronized(this) {
            instance ?: WritingWorkspace(context.applicationContext).also { instance = it }
        }
    }

    private val storage = context.getSharedPreferences("ostie_writing", Context.MODE_PRIVATE)
    var title by mutableStateOf(storage.getString("title", "Novo documento").orEmpty())
        private set
    var content by mutableStateOf(storage.getString("content", "").orEmpty())
        private set
    var format by mutableStateOf(storage.getString("format", "text").orEmpty())
        private set
    /** Aumenta a cada documento recebido do OSTIE; a tela abre o preview de HTML/SVG novo. */
    var revision by mutableIntStateOf(0)
        private set
    /** HTML/SVG recebido que ainda não foi exibido, mesmo se a aba estava fechada. */
    var previewPending by mutableStateOf(false)
        private set

    /** Versão anterior a uma troca feita pelo OSTIE, para desfazer. */
    var canUndo by mutableStateOf(storage.contains("prev_content"))
        private set

    fun consumePreview() { previewPending = false }

    /** Volta ao documento de antes da última troca feita pelo OSTIE (uma vez). */
    fun undo() {
        val previous = storage.getString("prev_content", null) ?: return
        title = storage.getString("prev_title", "Novo documento").orEmpty()
        content = previous
        format = storage.getString("prev_format", "text").orEmpty()
        storage.edit().putString("title", title).putString("content", content).putString("format", format)
            .remove("prev_title").remove("prev_content").remove("prev_format").apply()
        canUndo = false
        previewPending = format == "html"
        revision++
    }

    fun updateContent(value: String) {
        content = value
        storage.edit().putString("content", value).apply()
    }

    fun updateFormat(value: String) {
        format = if (value.equals("html", true)) "html" else "text"
        storage.edit().putString("format", format).apply()
    }

    fun publish(args: JSONObject): JSONObject {
        val value = DocumentPreview.stripFence(args.optString("conteudo"))
        if (value.isBlank()) return JSONObject().put("erro", "Conteúdo vazio; não foi alterado.")
        if (value.length > 160_000) return JSONObject().put("erro", "Texto grande demais para a aba de escrita.")
        val proposedTitle = args.optString("titulo").trim().take(80).ifEmpty { "Novo documento" }
        // Aceita html, svg, xml… e também reconhece marcação quando o modelo informa outro formato.
        val proposedFormat = DocumentPreview.detectFormat(args.optString("formato"), value)
        val append = args.optString("operacao").equals("adicionar", true)
        val newValue = if (append && content.isNotBlank()) "$content\n\n$value" else value
        if (newValue.length > 160_000) return JSONObject().put("erro", "Documento excede o limite local.")
        val editor = storage.edit()
        if (content.isNotBlank()) {
            editor.putString("prev_title", title).putString("prev_content", content).putString("prev_format", format)
            canUndo = true
        }
        title = proposedTitle
        content = newValue
        format = proposedFormat
        editor.putString("title", title).putString("content", content)
            .putString("format", format).apply()
        previewPending = format == "html"
        revision++
        return JSONObject().put("resultado", "Documento salvo na Aba de Escrita")
            .put("titulo", title).put("formato", format).put("caracteres", content.length)
    }

    /** Apaga o documento, mas guarda a versão apagada para "Desfazer". */
    fun clear() {
        val editor = storage.edit().clear()
        if (content.isNotBlank())
            editor.putString("prev_title", title).putString("prev_content", content).putString("prev_format", format)
        canUndo = content.isNotBlank()
        previewPending = false
        title = "Novo documento"
        content = ""
        format = "text"
        editor.apply()
    }
}
