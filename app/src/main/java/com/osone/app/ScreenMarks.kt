package com.osone.app

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Marcas numeradas e grade do print (look_at_screen): o modelo vê "7" sobre o botão e toca com tap_mark,
 * ou lê na grade a posição em pixels reais da tela (jogos, linha do tempo de editores de vídeo).
 * Lógica pura, testável na JVM; o desenho fica no serviço de acessibilidade.
 */
object ScreenMarks {
    /** Retângulo de um controle, em pixels reais da tela. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int, val label: String,
        val clickable: Boolean = true, val editable: Boolean = false) {
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
        val area get() = (right - left).toLong() * (bottom - top)
    }

    data class Mark(val number: Int, val label: String, val x: Int, val y: Int, val box: Box)

    const val LIMIT = 60

    /**
     * Controles tocáveis visíveis, numerados de cima para baixo e da esquerda para a direita.
     * Ignora os minúsculos, os que cobrem quase a tela toda (fundos) e os repetidos no mesmo lugar.
     */
    fun pick(boxes: List<Box>, width: Int, height: Int, limit: Int = LIMIT): List<Mark> {
        val screen = width.toLong() * height
        val kept = ArrayList<Box>()
        boxes.asSequence()
            .filter { it.clickable || it.editable }
            .map { Box(it.left.coerceIn(0, width), it.top.coerceIn(0, height), it.right.coerceIn(0, width),
                it.bottom.coerceIn(0, height), it.label, it.clickable, it.editable) }
            .filter { it.right - it.left >= 8 && it.bottom - it.top >= 8 && it.area < screen * 0.6 }
            .sortedBy { it.area } // o menor primeiro: entre controles no mesmo lugar, fica o mais preciso
            .forEach { box -> if (kept.none { abs(it.centerX - box.centerX) < 16 && abs(it.centerY - box.centerY) < 16 }) kept += box }
        // Linhas de ~40 px: controles lado a lado ganham números seguidos.
        return kept.sortedWith(compareBy({ it.centerY / 40 }, { it.centerX })).take(limit)
            .mapIndexed { index, box -> Mark(index + 1, box.label.take(40), box.centerX, box.centerY, box) }
    }

    fun find(marks: List<Mark>, number: Int): Mark? = marks.firstOrNull { it.number == number }

    /** Depois disso o print é velho demais para tocar pelo número. */
    const val MAX_AGE_MS = 120_000L

    /**
     * Motivo para não usar a marca (tela mudou), ou null se ainda vale: print velho, outro app na frente, ou o
     * controle naquele ponto agora tem outro texto. Telas que se mexem (vídeo, jogo) continuam valendo.
     */
    fun staleReason(mark: Mark, ageMs: Long, samePackage: Boolean, labelNow: String): String? {
        fun clean(value: String) = value.lowercase().replace(Regex("\\s+"), " ").trim()
        val before = clean(mark.label); val now = clean(labelNow)
        return when {
            ageMs > MAX_AGE_MS -> "O print tem mais de 2 minutos"
            !samePackage -> "Outro app está na frente agora"
            before.isNotEmpty() && now.isNotEmpty() && !before.contains(now) && !now.contains(before) ->
                "No lugar da marca ${mark.number} agora está \"${labelNow.take(40)}\" (antes: \"${mark.label.take(40)}\")"
            else -> null
        }
    }

    /** Espaço entre as linhas da grade, em pixels reais: número redondo, cerca de 8 linhas no lado menor. */
    fun gridStep(width: Int, height: Int): Int {
        val raw = minOf(width, height) / 8.0
        val rounded = listOf(50, 100, 150, 200, 250, 300, 400, 500).minByOrNull { abs(it - raw) } ?: 100
        return rounded
    }

    /** Posições das linhas da grade num eixo (pixels reais), sem a borda 0. */
    fun gridLines(size: Int, step: Int): List<Int> = (1..(size - 1) / step).map { it * step }

    /** Pixel real → pixel do print reduzido. */
    fun toImage(value: Int, scale: Float): Int = (value * scale).roundToInt()
}
