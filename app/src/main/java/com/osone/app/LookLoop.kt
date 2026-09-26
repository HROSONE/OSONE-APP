package com.osone.app

/**
 * Freio para o agente que só olha a tela e não age (print atrás de print). Conta olhadas seguidas sem ação:
 * na terceira avisa, da quarta em diante recusa e manda agir ou falar com o usuário. Qualquer ação zera,
 * e um minuto parado também. Lógica pura, testável na JVM.
 */
class LookLoop(private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val WARN_AT = 3
        const val IDLE_RESET_MS = 60_000L
        val LOOKS = setOf("look_at_screen", "inspect_screen", "check_ui")
        const val MESSAGE = "Você já olhou a tela várias vezes seguidas e ela não muda sozinha. Aja agora com o que já viu: " +
            "tap_mark com o número amarelo do controle, ou interact_ui com o texto do botão (ex.: interact_ui texto=\"Pagamentos e assinaturas\"). " +
            "Se não der para agir, diga ao usuário o que está impedindo."
    }

    enum class Verdict { OK, WARN, REFUSE }

    private var count = 0
    private var last = 0L

    @Synchronized fun look(): Verdict {
        val now = clock()
        if (now - last > IDLE_RESET_MS) count = 0
        last = now
        count++
        return when {
            count < WARN_AT -> Verdict.OK
            count == WARN_AT -> Verdict.WARN
            else -> Verdict.REFUSE
        }
    }

    @Synchronized fun acted() { count = 0; last = clock() }
}
