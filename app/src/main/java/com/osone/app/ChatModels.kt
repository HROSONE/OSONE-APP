package com.osone.app

/** A seleção controla somente o chat escrito; o modo Live tem modelos independentes. */
enum class ChatModel(val id: String, val label: String) {
    GEMINI_38("gemini-3.8-flash", "Gemini 3.8 Flash"),
    GEMINI_37("gemini-3.7-flash", "Gemini 3.7 Flash"),
    GEMINI_36("gemini-3.6-flash", "Gemini 3.6 Flash"),
    GEMINI_35("gemini-3.5-flash", "Gemini 3.5 Flash"),
    GEMINI_25("gemini-2.5-flash", "Gemini 2.5 Flash");

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: GEMINI_38

        /** Nunca volta para um modelo acima do que o usuário escolheu. */
        fun candidates(selected: ChatModel, fallback: Boolean) =
            if (fallback) entries.drop(entries.indexOf(selected)) else listOf(selected)
    }
}

enum class ThinkingMode(val value: String, val label: String) {
    FAST("low", "Rápido"), BALANCED("medium", "Equilibrado"), DEEP("high", "Profundo");
    companion object {
        fun fromValue(value: String?) = entries.firstOrNull { it.value == value } ?: FAST
    }
}
