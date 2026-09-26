package com.osone.app

import kotlin.math.sqrt

/**
 * Porta de voz do "Ei, Ostie": em silêncio o reconhecedor nem trabalha (menos bateria). Mede a energia de cada
 * bloco de áudio; quando passa do ruído de fundo, entrega também os blocos anteriores ([PRE_ROLL]) para o "Ei"
 * não se perder, e continua entregando até [HANGOVER] blocos de silêncio. Lógica pura, testável na JVM.
 */
class VoiceGate {
    companion object {
        const val PRE_ROLL = 4 // 400 ms antes da voz
        const val HANGOVER = 15 // 1,5 s depois da voz
        const val MIN_LEVEL = 250.0
        const val FACTOR = 3.0

        /** Energia (RMS) de áudio PCM 16 bits little-endian. */
        fun level(pcm: ByteArray, count: Int): Double {
            val samples = count / 2
            if (samples == 0) return 0.0
            var sum = 0.0
            for (i in 0 until samples) {
                val value = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toDouble()
                sum += value * value
            }
            return sqrt(sum / samples)
        }
    }

    private val history = ArrayDeque<ByteArray>()
    private var noise = MIN_LEVEL
    private var quietLeft = 0

    /** true enquanto está deixando o áudio passar. */
    val open get() = quietLeft > 0

    /** Blocos a entregar ao reconhecedor (vazio em silêncio). */
    fun offer(pcm: ByteArray, count: Int): List<ByteArray> {
        val frame = pcm.copyOf(count)
        val energy = level(frame, count)
        val voice = energy > maxOf(MIN_LEVEL, noise * FACTOR)
        // O ruído de fundo acompanha o ambiente devagar, e só com silêncio.
        if (!voice) noise = (noise * 0.95 + energy * 0.05).coerceAtLeast(MIN_LEVEL / FACTOR)
        return if (voice) {
            val out = history.toList() + frame
            history.clear()
            quietLeft = HANGOVER
            out
        } else if (quietLeft > 0) {
            quietLeft--
            listOf(frame)
        } else {
            history.addLast(frame)
            while (history.size > PRE_ROLL) history.removeFirst()
            emptyList()
        }
    }

    fun reset() { history.clear(); quietLeft = 0 }
}

/** O "Ei, Ostie" sai da frente: ligação ou outro app gravando (áudio, vídeo, câmera) solta o microfone. */
object WakeYield {
    /** [audioMode] de AudioManager: 2 = em ligação, 3 = em comunicação (WhatsApp, Meet). */
    fun shouldYield(otherRecordings: Int, audioMode: Int): Boolean = otherRecordings > 0 || audioMode == 2 || audioMode == 3
}
