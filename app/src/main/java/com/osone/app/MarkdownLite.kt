package com.osone.app

/**
 * Markdown simples das respostas do chat (negrito, itálico, código, títulos, listas), sem biblioteca.
 * Tabelas viram listas legíveis no celular. Lógica pura, testável na JVM; o balão monta o texto a partir daqui.
 */
object MarkdownLite {
    enum class Kind { TEXT, HEADING, BULLET, NUMBERED, CODE, RULE, BLANK }
    data class Segment(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)
    data class Line(val kind: Kind, val segments: List<Segment>, val marker: String = "", val level: Int = 0)

    fun parse(text: String): List<Line> {
        val lines = ArrayList<Line>()
        var inCode = false
        for (raw in text.replace("\r\n", "\n").lines()) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) { inCode = !inCode; continue }
            if (inCode) { lines += Line(Kind.CODE, listOf(Segment(raw, code = true))); continue }
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> lines += Line(Kind.BLANK, emptyList())
                Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(trimmed) -> lines += Line(Kind.RULE, emptyList())
                trimmed.startsWith("#") -> {
                    val level = trimmed.takeWhile { it == '#' }.length.coerceAtMost(6)
                    lines += Line(Kind.HEADING, inline(trimmed.drop(level).trim()), level = level)
                }
                trimmed.startsWith("|") -> table(trimmed)?.let { lines += it }
                Regex("^[-*•+]\\s+").containsMatchIn(trimmed) ->
                    lines += Line(Kind.BULLET, inline(trimmed.replaceFirst(Regex("^[-*•+]\\s+"), "")), "•",
                        level = (line.length - line.trimStart().length) / 2)
                Regex("^\\d{1,3}[.)]\\s+").containsMatchIn(trimmed) -> {
                    val number = trimmed.takeWhile { it.isDigit() }
                    lines += Line(Kind.NUMBERED, inline(trimmed.replaceFirst(Regex("^\\d{1,3}[.)]\\s+"), "")), "$number.")
                }
                else -> lines += Line(Kind.TEXT, inline(line))
            }
        }
        return lines
    }

    /** Linha de tabela vira item "célula — célula"; a linha separadora (|---|) some. */
    private fun table(row: String): Line? {
        val cells = row.trim('|').split("|").map { it.trim() }
        if (cells.all { it.isEmpty() || Regex("^:?-{2,}:?$").matches(it) }) return null
        return Line(Kind.BULLET, inline(cells.filter { it.isNotEmpty() }.joinToString(" — ")), "•")
    }

    /** **negrito**, __negrito__, *itálico*, _itálico_ e `código` dentro da linha. */
    fun inline(text: String): List<Segment> {
        val result = ArrayList<Segment>()
        val pattern = Regex("\\*\\*(.+?)\\*\\*|__(.+?)__|`([^`]+)`|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])|(?<![\\w_])_(?!\\s)(.+?)(?<!\\s)_(?![\\w_])")
        var last = 0
        for (match in pattern.findAll(text)) {
            if (match.range.first > last) result += Segment(text.substring(last, match.range.first))
            val groups = match.groupValues
            result += when {
                groups[1].isNotEmpty() -> Segment(groups[1], bold = true)
                groups[2].isNotEmpty() -> Segment(groups[2], bold = true)
                groups[3].isNotEmpty() -> Segment(groups[3], code = true)
                groups[4].isNotEmpty() -> Segment(groups[4], italic = true)
                else -> Segment(groups[5], italic = true)
            }
            last = match.range.last + 1
        }
        if (last < text.length) result += Segment(text.substring(last))
        return result
    }

    /** Texto sem marcações, para copiar e compartilhar. */
    fun plain(text: String): String = parse(text).joinToString("\n") { line ->
        val body = line.segments.joinToString("") { it.text }
        when (line.kind) {
            Kind.BULLET, Kind.NUMBERED -> "  ".repeat(line.level) + "${line.marker} $body"
            Kind.RULE -> "———"
            else -> body
        }
    }.replace(Regex("\n{3,}"), "\n\n").trim()
}
