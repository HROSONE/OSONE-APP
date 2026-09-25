package com.osone.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/** [days] usa Calendar.DAY_OF_WEEK (1 = domingo); vazio = todos os dias. */
/** [onNotification] preenchido = a rotina dispara com notificações (termos separados por vírgula), não por horário. */
data class Routine(val id: String, val title: String, val instruction: String, val hour: Int, val minute: Int,
    val days: Set<Int>, val reminder: Boolean, val enabled: Boolean = true, val onNotification: String = "") {
    val timeLabel get() = "%02d:%02d".format(hour, minute)
    val daysLabel get() = RoutineSchedule.daysLabel(days)
    val byEvent get() = onNotification.isNotBlank()
    /** "08:00, dias úteis" ou "ao chegar notificação de WhatsApp, Ana". */
    val whenLabel get() = if (byEvent) "ao chegar notificação de $onNotification" else "$timeLabel, $daysLabel"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("titulo", title).put("instrucao", instruction)
        .put("hora", hour).put("minuto", minute).put("dias", JSONArray(days.sorted()))
        .put("lembrete", reminder).put("ativa", enabled)
        .apply { if (byEvent) put("notificacao", onNotification) }

    companion object {
        fun fromJson(json: JSONObject) = Routine(json.optString("id"), json.optString("titulo"),
            json.optString("instrucao"), json.optInt("hora"), json.optInt("minuto"),
            json.optJSONArray("dias")?.let { list -> (0 until list.length()).map { list.optInt(it) }.toSet() } ?: emptySet(),
            json.optBoolean("lembrete"), json.optBoolean("ativa", true), json.optString("notificacao").trim())
    }
}

/** Cálculos puros de agenda, testáveis sem Android. */
object RoutineSchedule {
    private val names = mapOf("dom" to 1, "seg" to 2, "ter" to 3, "qua" to 4, "qui" to 5, "sex" to 6, "sab" to 7)

    /** "todos", "uteis", "fim_de_semana" ou lista como "seg,qua,sex". */
    fun parseDays(value: String): Set<Int> {
        val text = value.lowercase(Locale.ROOT).replace("á", "a").replace("ú", "u").trim()
        return when {
            text.isEmpty() || text.startsWith("todo") || text == "diario" -> emptySet()
            text.startsWith("uteis") || text.contains("semana") && text.contains("dia") -> setOf(2, 3, 4, 5, 6)
            text.contains("fim") -> setOf(1, 7)
            else -> names.filterKeys { text.contains(it) }.values.toSet()
        }
    }

    fun daysLabel(days: Set<Int>): String = when (days) {
        emptySet<Int>(), (1..7).toSet() -> "todos os dias"
        setOf(2, 3, 4, 5, 6) -> "dias úteis"
        setOf(1, 7) -> "fins de semana"
        else -> days.sorted().joinToString(", ") { day -> names.entries.first { it.value == day }.key }
    }

    private val shortDays = listOf("dom", "seg", "ter", "qua", "qui", "sex", "sáb")

    /** Quando a rotina roda de novo, em linguagem simples: "hoje 08:00", "amanhã 08:00", "sex 08:00" ou "12/10 08:00". */
    fun whenLabel(now: Long, at: Long): String {
        fun startOfDay(time: Long) = Calendar.getInstance().apply {
            timeInMillis = time
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val target = Calendar.getInstance().apply { timeInMillis = at }
        val time = "%02d:%02d".format(target.get(Calendar.HOUR_OF_DAY), target.get(Calendar.MINUTE))
        // Arredonda: dias com horário de verão têm 23 ou 25 horas.
        val days = Math.round((startOfDay(at) - startOfDay(now)) / 86_400_000.0)
        return when (days) {
            0L -> "hoje $time"
            1L -> "amanhã $time"
            in 2L..6L -> "${shortDays[target.get(Calendar.DAY_OF_WEEK) - 1]} $time"
            else -> "%02d/%02d %s".format(target.get(Calendar.DAY_OF_MONTH), target.get(Calendar.MONTH) + 1, time)
        }
    }

    fun next(now: Long, hour: Int, minute: Int, days: Set<Int>): Long {
        val calendar = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        repeat(8) {
            if (calendar.timeInMillis > now && (days.isEmpty() || calendar.get(Calendar.DAY_OF_WEEK) in days))
                return calendar.timeInMillis
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        return calendar.timeInMillis
    }
}

/**
 * Rotinas do OSTIE: lembretes simples ou tarefas que o modelo de texto executa no horário
 * (resumo, notícias, clima com Pesquisa Google, memória). Também salvas em Documentos/OSTIE/rotinas.json.
 */
class RoutineStore private constructor(private val context: Context) {
    companion object {
        @Volatile private var instance: RoutineStore? = null
        fun get(context: Context): RoutineStore = instance ?: synchronized(this) {
            instance ?: RoutineStore(context.applicationContext).also { instance = it }
        }
        private const val FILE = "rotinas.json"
        const val CHANNEL = "ostie_routines"
    }

    private val preferences = context.getSharedPreferences("ostie_routines", 0)
    private val memory = MemoryStore.get(context)
    var routines by mutableStateOf(load())
        private set
    /** Últimos resultados (mais recente primeiro), exibidos na aba Rotinas. */
    var history by mutableStateOf(loadHistory())
        private set

    private fun loadHistory(): List<Triple<String, String, Long>> = try {
        JSONArray(preferences.getString("history", "[]")).let { list -> (0 until list.length()).map {
            list.getJSONObject(it).let { item -> Triple(item.optString("titulo"), item.optString("texto"), item.optLong("em")) } } }
    } catch (_: Exception) { emptyList() }

    private fun load(): List<Routine> {
        val raw = preferences.getString("list", null) ?: memory.readShared(FILE)
        return try { JSONArray(raw ?: "[]").let { list -> (0 until list.length()).map { Routine.fromJson(list.getJSONObject(it)) } } }
            catch (_: Exception) { emptyList() }
    }

    /** Após reinstalar, recupera as rotinas da pasta quando a permissão volta. */
    fun restoreFromFolder() {
        if (routines.isNotEmpty()) return
        val restored = load()
        if (restored.isNotEmpty()) { routines = restored; persist(); RoutineScheduler.scheduleAll(context) }
    }

    private fun persist() {
        val json = JSONArray(routines.map { it.toJson() }).toString()
        preferences.edit().putString("list", json).apply()
        memory.writeShared(FILE, JSONArray(routines.map { it.toJson() }).toString(2))
        OstieWidget.refresh(context)
    }

    fun find(id: String) = routines.firstOrNull { it.id == id }

    fun add(title: String, instruction: String, hour: Int, minute: Int, days: Set<Int>, reminder: Boolean,
        onNotification: String = ""): Routine {
        require(hour in 0..23 && minute in 0..59) { "Horário inválido." }
        require(title.isNotBlank()) { "Dê um nome à rotina." }
        require(instruction.isNotBlank()) { "Descreva o que a rotina faz." }
        require(routines.size < 30) { "Limite de 30 rotinas." }
        val routine = Routine(UUID.randomUUID().toString().take(8), title.trim().take(60), instruction.trim().take(1500),
            hour, minute, days, reminder, onNotification = onNotification.trim().take(120))
        routines = routines + routine
        persist()
        RoutineScheduler.schedule(context, routine)
        return routine
    }

    /** Troca nome, instrução, horário, dias ou tipo; a rotina mantém o id e o estado (ativa ou pausada). */
    fun update(id: String, title: String, instruction: String, hour: Int, minute: Int, days: Set<Int>, reminder: Boolean): Routine {
        require(hour in 0..23 && minute in 0..59) { "Horário inválido." }
        require(title.isNotBlank()) { "Dê um nome à rotina." }
        require(instruction.isNotBlank()) { "Descreva o que a rotina faz." }
        val current = find(id) ?: throw IllegalArgumentException("Rotina não encontrada.")
        val updated = current.copy(title = title.trim().take(60), instruction = instruction.trim().take(1500),
            hour = hour, minute = minute, days = days, reminder = reminder)
        routines = routines.map { if (it.id == id) updated else it }
        persist()
        RoutineScheduler.cancel(context, current)
        RoutineScheduler.schedule(context, updated)
        return updated
    }

    fun setEnabled(id: String, enabled: Boolean) {
        routines = routines.map { if (it.id == id) it.copy(enabled = enabled) else it }
        persist()
        find(id)?.let { if (enabled) RoutineScheduler.schedule(context, it) else RoutineScheduler.cancel(context, it) }
    }

    fun remove(id: String): Boolean {
        val routine = find(id) ?: return false
        RoutineScheduler.cancel(context, routine)
        routines = routines.filterNot { it.id == id }
        persist()
        return true
    }

    fun matching(query: String): List<Routine> = routines.filter {
        it.id == query.trim() || it.title.contains(query.trim(), ignoreCase = true)
    }

    /** Resultados que o chat mostra na próxima abertura. */
    fun pushInbox(title: String, text: String) {
        val list = JSONArray(preferences.getString("inbox", "[]"))
        list.put(JSONObject().put("titulo", title).put("texto", text))
        while (list.length() > 20) list.remove(0)
        val updated = (listOf(Triple(title, text, System.currentTimeMillis())) + loadHistory()).take(15)
        preferences.edit().putString("inbox", list.toString()).putString("history", JSONArray(updated.map {
            JSONObject().put("titulo", it.first).put("texto", it.second).put("em", it.third) }).toString()).apply()
        // Chamado pelo WorkManager fora da thread principal; o estado Compose é atualizado na principal.
        android.os.Handler(android.os.Looper.getMainLooper()).post { history = updated }
    }

    fun drainInbox(): List<Pair<String, String>> {
        val list = JSONArray(preferences.getString("inbox", "[]"))
        preferences.edit().remove("inbox").apply()
        return (0 until list.length()).map { list.getJSONObject(it).let { item -> item.optString("titulo") to item.optString("texto") } }
    }
}

object RoutineScheduler {
    private fun pending(context: Context, routine: Routine, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(context, routine.id.hashCode(),
            Intent(context, RoutineAlarmReceiver::class.java).setAction("com.osone.app.ROUTINE").putExtra("id", routine.id),
            flags or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(context: Context, routine: Routine) {
        if (!routine.enabled || routine.byEvent) return
        val alarms = context.getSystemService(AlarmManager::class.java)
        val at = RoutineSchedule.next(System.currentTimeMillis(), routine.hour, routine.minute, routine.days)
        val intent = pending(context, routine, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        // Horário exato quando o Android permite; senão, janela curta que respeita a economia de bateria.
        if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
        else alarms.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, intent)
    }

    fun cancel(context: Context, routine: Routine) {
        pending(context, routine, PendingIntent.FLAG_NO_CREATE)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it)
        }
    }

    fun scheduleAll(context: Context) = RoutineStore.get(context).routines.forEach { schedule(context, it) }

    private val lastEvent = HashMap<String, Long>()

    /** Disparo por notificação, no máximo uma vez a cada [RoutineTrigger.COOLDOWN_MS] por rotina. */
    fun runOnEvent(context: Context, routine: Routine, event: String) {
        val now = System.currentTimeMillis()
        synchronized(lastEvent) {
            if (now - (lastEvent[routine.id] ?: 0L) < RoutineTrigger.COOLDOWN_MS) return
            lastEvent[routine.id] = now
        }
        runNow(context, routine, event)
    }

    fun runNow(context: Context, routine: Routine, event: String? = null) {
        val request = OneTimeWorkRequestBuilder<RoutineWorker>()
            .setInputData(if (event != null) workDataOf("id" to routine.id, "evento" to event.take(600)) else workDataOf("id" to routine.id))
        if (!routine.reminder) request.setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        WorkManager.getInstance(context).enqueue(request.build())
    }
}

/** Dispara no horário (receptor privado), agenda a próxima ocorrência e entrega a execução ao WorkManager. */
class RoutineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val routine = RoutineStore.get(context).find(intent.getStringExtra("id").orEmpty()) ?: return
        if (!routine.enabled) return
        RoutineScheduler.schedule(context, routine)
        RoutineScheduler.runNow(context, routine)
        OstieWidget.refresh(context) // "Próxima" passa para a ocorrência seguinte.
    }
}

/** Alarmes somem ao reiniciar ou atualizar o app: reagenda tudo. Só recebe transmissões do sistema. */
class RoutineBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"))
            RoutineScheduler.scheduleAll(context)
    }
}

class RoutineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val context = applicationContext
        val routine = RoutineStore.get(context).find(inputData.getString("id").orEmpty()) ?: return Result.success()
        val event = inputData.getString("evento")
        if (routine.reminder) {
            notify(context, routine, routine.instruction + (event?.let { "\n\n$it" } ?: ""))
            return Result.success()
        }
        val tools = AgentTools(context, background = true)
        return try {
            val now = SimpleDateFormat("EEEE, dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())
            val memory = MemoryStore.get(context).apply { refresh() }
            val system = GeminiClient.DEFAULT_SYSTEM + FreshInfo.instructions(canSearch = true) +
                UserProfile.get(context).identity(canSave = false) + memory.promptBlock() +
                " Você está executando uma rotina agendada, sem conversa: entregue direto o resultado, curto, " +
                "claro e pronto para ler numa notificação (no máximo 10 linhas), sem perguntas de volta."
            val answer = withContext(Dispatchers.IO) {
                TextModel.ask(context, "Agora é $now. Rotina \"${routine.title}\": ${routine.instruction}" +
                    (event?.let { "\n\nO que disparou a rotina agora: $it" } ?: ""), system + TOOLS_GUIDE,
                    googleSearch = context.getSharedPreferences("osone_config", 0).getBoolean("google_search", true),
                    readTimeoutMs = 90_000, tools = tools)
            }
            notify(context, routine, answer, tools.suggestions)
            RoutineStore.get(context).pushInbox(routine.title, answer)
            Result.success()
        } catch (failure: Exception) {
            // Depois de usar ferramentas (ex.: anotar na memória), repetir faria tudo de novo.
            if (runAttemptCount < 2 && tools.used == 0) return Result.retry()
            AppDiagnostics.get(context).record("Rotina", "${routine.title}: ${failure.javaClass.simpleName}.")
            notify(context, routine, "Não consegui executar agora (${failure.message?.take(80) ?: "falha de rede"}).")
            Result.success()
        }
    }

    private fun notify(context: Context, routine: Routine, text: String, actions: List<Pair<String, Intent>> = emptyList()) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(RoutineStore.CHANNEL, "Rotinas do OSTIE",
            NotificationManager.IMPORTANCE_HIGH).apply { description = "Resultados e lembretes das rotinas agendadas" })
        val open = PendingIntent.getActivity(context, 12, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(routine.id.hashCode(), Notification.Builder(context, RoutineStore.CHANNEL)
            .setSmallIcon(R.drawable.ic_ostie_notification)
            .setColor(0xFF2F6BFF.toInt())
            .setContentTitle(routine.title)
            .setContentText(text.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: text.take(120))
            .setStyle(Notification.BigTextStyle().bigText(text.take(3_000)))
            .setContentIntent(open).setAutoCancel(true)
            .apply {
                // Botões preparados pela rotina: abrem a tela pronta; enviar ou ligar fica com o usuário.
                actions.forEachIndexed { index, (label, intent) ->
                    val pending = PendingIntent.getActivity(context, routine.id.hashCode() * 4 + index + 1,
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, label, pending).build())
                }
            }.build())
    }

    private companion object {
        const val TOOLS_GUIDE = " Use as ferramentas para buscar o que a rotina precisa: agenda (read_calendar), notificações, " +
            "bateria, memória e pesquisa. Se a rotina pedir para avisar alguém, ligar, ir a algum lugar ou despertar, use " +
            "suggest_action para deixar um botão pronto na notificação e diga no texto que ele está lá. Nada é enviado sem o " +
            "toque do usuário. Se uma ferramenta devolver erro de permissão, diga em uma frase como liberar."
    }
}
