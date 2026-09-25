package com.osone.app

/**
 * Quando a escuta "Ei, Ostie" pausa para economizar: bateria abaixo de 15% (sem carregar) ou modo economia.
 * Volta ao carregar, ou com 20% ou mais fora do modo economia (folga evita liga/desliga). Pura, testável.
 */
object BatteryPolicy {
    const val PAUSE_BELOW = 15
    const val RESUME_AT = 20

    fun shouldPause(level: Int, charging: Boolean, powerSave: Boolean, paused: Boolean): Boolean = when {
        charging -> false
        powerSave -> true
        level < 0 -> paused
        paused -> level < RESUME_AT
        else -> level < PAUSE_BELOW
    }
}
