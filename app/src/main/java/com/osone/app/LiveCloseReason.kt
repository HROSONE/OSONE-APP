package com.osone.app

/** Interpreta o fechamento do WebSocket Live; sem dependências Android para ser testável. */
object LiveCloseReason {
    private val apiKey = Regex("AIza[0-9A-Za-z_\\-]{10,}")
    private val keyParam = Regex("(?i)key=[^&\\s]+")
    private val rejectionCode = Regex("WebSocket (1007|1008|1011)\\b")

    /** Trecho curto do motivo enviado pelo servidor, sem chaves, para o diagnóstico. */
    fun excerpt(reason: String): String {
        val clean = reason.replace(apiKey, "***").replace(keyParam, "key=***")
            .replace(Regex("\\s+"), " ").trim().take(90)
        return if (clean.isEmpty()) "" else " · $clean"
    }

    /**
     * Fechou antes de o modelo responder qualquer coisa (ou ainda no setup) com código de recusa:
     * provavelmente o modelo não aceitou parte da configuração. Cota e chave não se resolvem assim.
     */
    fun isSetupRejection(cause: String, wasReady: Boolean, heardFromModel: Boolean, msSinceReady: Long): Boolean {
        if (cause.contains("(cota") || cause.contains("(chave")) return false
        val early = !wasReady || (!heardFromModel && msSinceReady < 20_000)
        return early && (cause == "HTTP 400" || rejectionCode.containsMatchIn(cause))
    }
}
