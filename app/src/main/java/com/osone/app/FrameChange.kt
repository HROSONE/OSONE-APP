package com.osone.app

import kotlin.math.abs

/**
 * Compartilhar a tela sem repetir quadros: cada quadro vira uma miniatura em cinza ([SIDE] x [SIDE]) e só é enviado
 * se alguma região mudar. Tela parada manda um quadro a cada [HEARTBEAT_MS] para o modelo não perder o contexto.
 * Lógica pura, testável na JVM.
 */
object FrameChange {
    const val SIDE = 40
    const val HEARTBEAT_MS = 6_000L
    /** Diferença de cinza (0 a 255) numa região que já conta como mudança (cursor, texto novo, jogo). */
    const val CELL_DELTA = 6

    /** Miniatura em cinza a partir de pixels ARGB já reduzidos para [SIDE] x [SIDE]. */
    fun signature(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val p = argb[i]
        (((p shr 16) and 0xFF) * 30 + ((p shr 8) and 0xFF) * 59 + (p and 0xFF) * 11) / 100
    }

    fun changed(previous: IntArray?, current: IntArray): Boolean =
        previous == null || previous.size != current.size || current.indices.any { abs(current[it] - previous[it]) > CELL_DELTA }

    fun shouldSend(previous: IntArray?, current: IntArray, lastSentAt: Long, now: Long): Boolean =
        changed(previous, current) || now - lastSentAt >= HEARTBEAT_MS
}
