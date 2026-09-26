package com.osone.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plano deste aparelho e o contador diário do agente. Enquanto a assinatura (login + Stripe) não está no ar,
 * [ENFORCED] fica desligado e todos usam tudo, como antes: ninguém que ganhou o OSTIE perde recurso.
 */
object PlanStore {
    /** Liga os limites quando a assinatura estiver funcionando. */
    const val ENFORCED = false
    private const val PREFS = "osone_plan"
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Plano assinado (vem do servidor no lote de assinatura). */
    var subscribed by mutableStateOf(Plan.GRATIS)
        private set

    fun load(context: Context) {
        subscribed = Plan.fromId(context.getSharedPreferences(PREFS, 0).getString("plan", null))
    }

    /** Plano que vale agora: sem os limites ligados, tudo liberado. */
    val current: Plan get() = if (ENFORCED) subscribed else Plan.EMPRESA

    fun allows(feature: PlanFeature) = PlanRules.allows(current, feature)

    private fun today() = synchronized(day) { day.format(Date()) }

    /** Confere e conta uma ação do agente na tela; null = pode agir. */
    @Synchronized fun takeAgentAction(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val used = PlanRules.countFor(prefs.getString("agent_day", "").orEmpty(), prefs.getInt("agent_count", 0), today())
        PlanRules.agentAction(current, used)?.let { return it }
        prefs.edit().putString("agent_day", today()).putInt("agent_count", used + 1).apply()
        return null
    }

    /** Ações do agente usadas hoje (para a tela Seu plano). */
    fun agentUsedToday(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, 0)
        return PlanRules.countFor(prefs.getString("agent_day", "").orEmpty(), prefs.getInt("agent_count", 0), today())
    }
}
