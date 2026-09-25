package com.osone.app

import java.text.Normalizer

/**
 * Trava do agente: toques que enviam, pagam, compram, apagam ou confirmam algo em outro app só acontecem
 * depois do "sim" do usuário. A primeira tentativa é recusada; a mesma ação só passa com
 * confirmado_pelo_usuario=true numa nova chamada, dentro de [WINDOW_MS]. Lógica pura, testável na JVM.
 */
class AgentGuard(private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val WINDOW_MS = 120_000L
        private val risky = Regex("\\b(enviar|envia|send|pagar|pague|pagamento|pay|comprar|compre|buy|finalizar|checkout|" +
            "excluir|exclua|apagar|apague|delete|remover|remova|desinstalar|uninstall|confirmar|confirme|confirm|" +
            "transferir|transferencia|pix|assinar|assine|subscribe|publicar|postar|post|compartilhar publicamente|" +
            "sair da conta|logout|redefinir|resetar|formatar)\\b")

        private fun normalized(value: String) = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "").trim()

        /** O controle parece fazer algo difícil de desfazer. */
        fun isRisky(label: String): Boolean = label.isNotBlank() && risky.containsMatchIn(normalized(label))
    }

    private val asked = HashMap<String, Long>()

    /**
     * null = pode tocar; senão, a mensagem de recusa que volta ao modelo.
     * [confirmed] só vale se a mesma ação já foi recusada antes (o modelo precisou perguntar ao usuário).
     */
    @Synchronized fun check(label: String, confirmed: Boolean): String? {
        if (!isRisky(label)) return null
        val key = normalized(label)
        val now = clock()
        asked.entries.removeAll { now - it.value > WINDOW_MS }
        if (confirmed && asked.containsKey(key)) { asked.remove(key); return null }
        asked[key] = now
        return "Ação sensível (\"${label.take(60)}\"): pergunte ao usuário, em voz, se pode tocar. Só depois do sim dele, " +
            "repita esta mesma chamada com confirmado_pelo_usuario=true. Se ele disser não, não toque."
    }
}
