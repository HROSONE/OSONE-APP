package com.osone.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Um dedo na tela: de (fromX, fromY) a (toX, toY), começando em [start] ms e durando [duration] ms. */
data class StrokeSpec(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int, val start: Long, val duration: Long) {
    val still get() = fromX == toX && fromY == toY
}

/**
 * Gestos do controle do celular (screen_gesture), calculados sem Android para testar na JVM:
 * toque duplo, segurar, arrastar segurando, e pinça para ampliar ou reduzir.
 * [hold] > 0 quer dizer: segure parado esse tempo antes de mover (arrastar ícones e itens).
 */
data class GesturePlan(val strokes: List<StrokeSpec>, val hold: Long = 0) {
    companion object {
        val TYPES = listOf("toque_duplo", "segurar", "arrastar", "ampliar", "reduzir")

        /** [amount] = quanto os dedos percorrem na pinça, em % da menor dimensão da tela (10 a 90). */
        fun build(type: String, x: Int?, y: Int?, endX: Int?, endY: Int?, amount: Int?, width: Int, height: Int): GesturePlan {
            require(width > 0 && height > 0) { "Tamanho da tela desconhecido." }
            val cx = x ?: (width / 2)
            val cy = y ?: (height / 2)
            fun inside(px: Int, py: Int) = px in 0 until width && py in 0 until height
            require(inside(cx, cy)) { "Coordenadas fora da tela (${width}x$height)." }
            return when (type.trim().lowercase()) {
                "toque_duplo", "duplo" -> GesturePlan(listOf(StrokeSpec(cx, cy, cx, cy, 0, 40), StrokeSpec(cx, cy, cx, cy, 110, 40)))
                "segurar" -> GesturePlan(listOf(StrokeSpec(cx, cy, cx, cy, 0, 600)))
                "arrastar" -> {
                    require(x != null && y != null && endX != null && endY != null) { "Para arrastar, informe x, y, fim_x e fim_y." }
                    require(inside(endX, endY)) { "Destino fora da tela (${width}x$height)." }
                    GesturePlan(listOf(StrokeSpec(cx, cy, endX, endY, 0, 450)), hold = 500)
                }
                "ampliar", "reduzir" -> {
                    val side = min(width, height)
                    val near = (side * 0.06).roundToInt().coerceAtLeast(20)
                    val far = near + (side * (amount ?: 40).coerceIn(10, 90) / 100.0).roundToInt()
                    // Dedos na diagonal; perto da borda, o centro da pinça entra na tela para os dois dedos caberem.
                    fun center(value: Int, size: Int) = if (size > 2 * far + 4) value.coerceIn(far + 1, size - far - 2) else size / 2
                    val px = center(cx, width); val py = center(cy, height)
                    fun point(offset: Int, sign: Int) = Pair((px + sign * offset).coerceIn(1, width - 2),
                        (py + sign * offset).coerceIn(1, height - 2))
                    val (inner, outer) = if (type.trim().lowercase() == "ampliar") near to far else far to near
                    val strokes = listOf(-1, 1).map { sign ->
                        val (fx, fy) = point(inner, sign); val (tx, ty) = point(outer, sign)
                        StrokeSpec(fx, fy, tx, ty, 0, 300)
                    }
                    require(strokes.none { it.still }) { "Ponto muito perto da borda para a pinça; use o centro da foto." }
                    GesturePlan(strokes)
                }
                else -> throw IllegalArgumentException("Gesto desconhecido. Use: ${TYPES.joinToString()}.")
            }
        }

        /** Distância entre os dois dedos no início e no fim da pinça (para conferir a direção nos testes). */
        fun spread(plan: GesturePlan): Pair<Int, Int> {
            val (a, b) = plan.strokes
            return max(kotlin.math.abs(a.fromX - b.fromX), kotlin.math.abs(a.fromY - b.fromY)) to
                max(kotlin.math.abs(a.toX - b.toX), kotlin.math.abs(a.toY - b.toY))
        }
    }
}
