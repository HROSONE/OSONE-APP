package com.osone.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plano deste aparelho e o contador diário do agente. Os limites só valem quando a versão foi compilada com
 * a conta OSTIE (Firebase) configurada: sem ela não há como assinar, então ninguém perde recurso.
 */
object PlanStore {
    /** Limites ligados: a conta e a assinatura existem nesta versão. */
    val ENFORCED: Boolean get() = OstieAccount.configured()
    private const val PREFS = "osone_plan"
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    /** Plano assinado (vem do servidor no lote de assinatura). */
    var subscribed by mutableStateOf(Plan.GRATIS)
        private set

    private var checkedAt = 0L

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, 0)
        subscribed = Plan.fromId(prefs.getString("plan", null))
        checkedAt = prefs.getLong("checked_at", 0L)
    }

    /** Plano conferido no servidor (login); vale sem internet por [OstieAccount.OFFLINE_GRACE_MS]. */
    fun saveSubscribed(context: Context, plan: Plan) {
        subscribed = plan
        checkedAt = System.currentTimeMillis()
        context.getSharedPreferences(PREFS, 0).edit().putString("plan", plan.name).putLong("checked_at", checkedAt).apply()
    }

    /** Plano que vale agora: sem os limites ligados, tudo liberado; plano pago sem conferir há dias volta ao Grátis. */
    val current: Plan get() = when {
        !ENFORCED -> Plan.EMPRESA
        System.currentTimeMillis() - checkedAt > OstieAccount.OFFLINE_GRACE_MS -> Plan.GRATIS
        else -> subscribed
    }

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
