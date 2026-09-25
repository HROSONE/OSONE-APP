package com.osone.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Últimas ações do agente no celular (só na memória), mostradas no painel do Live. */
object AgentLog {
    class Entry(val at: Long, val action: String, val detail: String, val ok: Boolean)

    private const val LIMIT = 30
    private val main = Handler(Looper.getMainLooper())
    var entries by mutableStateOf<List<Entry>>(emptyList())
        private set

    fun record(action: String, detail: String, ok: Boolean) {
        val entry = Entry(System.currentTimeMillis(), action, detail.take(120), ok)
        main.post { entries = (listOf(entry) + entries).take(LIMIT) }
    }

    fun clear() { main.post { entries = emptyList() } }
}
