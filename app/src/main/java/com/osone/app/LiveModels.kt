package com.osone.app

enum class LiveModel(val id: String, val label: String) {
    GEMINI_38("gemini-3.8-live", "Gemini 3.8 Live"),
    GEMINI_31("gemini-3.1-flash-live-preview", "Gemini 3.1 Flash Live"),
    GEMINI_25("gemini-2.5-flash-native-audio-preview-12-2025", "Gemini 2.5 Flash Live");

    companion object {
        fun fromId(value: String?) = entries.firstOrNull { it.id == value } ?: GEMINI_38
        fun candidates(selected: LiveModel, fallback: Boolean): List<LiveModel> =
            if (fallback) listOf(selected) + entries.filterNot { it == selected } else listOf(selected)
    }
}
