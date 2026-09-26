package com.osone.app

/** Planos do OSTIE. Os preços aparecem na tela Planos; a cobrança é pela Stripe (lote de assinatura). */
enum class Plan(val label: String, val price: String) {
    GRATIS("Grátis", "R$ 0"),
    PRO("Pro", "R$ 29,90 por mês ou R$ 239 por ano"),
    EMPRESA("Empresa", "R$ 99,90 por mês por aparelho");

    companion object {
        fun fromId(value: String?): Plan = entries.firstOrNull { it.name == value } ?: GRATIS
    }
}

/** O que cada plano libera. */
enum class PlanFeature(val label: String, val minimum: Plan) {
    GAMES_EDITORS("jogos, editores de vídeo e gestos", Plan.PRO),
    UNLIMITED_AGENT("agente na tela sem limite diário", Plan.PRO),
    UNLIMITED_ROUTINES("rotinas sem limite", Plan.PRO),
    NOTIFICATION_ROUTINES("rotinas que reagem a notificações", Plan.PRO),
    IMAGES("criar imagens", Plan.PRO),
    WAKE_WORD("\"Ei, Ostie\"", Plan.PRO),
    JEV("Jev", Plan.PRO),
    KNOWLEDGE("base de conhecimento para atendimento", Plan.EMPRESA),
}

/**
 * Regras dos planos, sem Android (testável na JVM). Grátis: conversar, abrir apps e ações diretas; agente na tela
 * com [FREE_AGENT_ACTIONS] ações por dia e até [FREE_ROUTINES] rotinas. Pro: tudo. Empresa: tudo + base de conhecimento.
 */
object PlanRules {
    const val FREE_AGENT_ACTIONS = 10
    const val FREE_ROUTINES = 2

    fun allows(plan: Plan, feature: PlanFeature): Boolean = plan.ordinal >= feature.minimum.ordinal

    /** Mensagem de recusa (para o modelo repassar ao usuário e para a tela). */
    fun blocked(feature: PlanFeature): String =
        "Recurso do plano ${feature.minimum.label}: ${feature.label}. Veja em Ajustes > Seu plano."

    /** null = pode agir; senão, o motivo. [usedToday] = ações do agente já feitas hoje. */
    fun agentAction(plan: Plan, usedToday: Int): String? =
        if (allows(plan, PlanFeature.UNLIMITED_AGENT) || usedToday < FREE_AGENT_ACTIONS) null
        else "O plano Grátis permite $FREE_AGENT_ACTIONS ações do agente na tela por dia, e elas acabaram hoje. " +
            "Com o Pro não tem limite (Ajustes > Seu plano). Conversar e abrir apps continuam liberados."

    /** null = pode criar; senão, o motivo. */
    fun newRoutine(plan: Plan, existing: Int, byNotification: Boolean): String? = when {
        byNotification && !allows(plan, PlanFeature.NOTIFICATION_ROUTINES) -> blocked(PlanFeature.NOTIFICATION_ROUTINES)
        !allows(plan, PlanFeature.UNLIMITED_ROUTINES) && existing >= FREE_ROUTINES ->
            "O plano Grátis permite até $FREE_ROUTINES rotinas. Apague uma ou veja o Pro em Ajustes > Seu plano."
        else -> null
    }

    /** Contador diário do agente: novo dia zera. */
    fun countFor(storedDay: String, storedCount: Int, today: String): Int = if (storedDay == today) storedCount else 0
}
