package com.osone.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject

/** Ações locais delimitadas; nenhuma permissão de acessibilidade nem cliques automáticos. */
class AndroidLocalTools(private val context: Context) {
    fun declarations(): JSONArray = JSONArray().apply {
        put(function("list_apps", "Liste os aplicativos que o usuário pode abrir no Android.", null))
        put(function("open_app", "Abra um aplicativo instalado, apenas quando o usuário pedir.", "nome"))
        put(function("device_status", "Veja bateria e hora locais do aparelho.", null))
    }

    private fun function(name: String, description: String, param: String?): JSONObject =
        JSONObject().put("name", name).put("description", description).apply {
            if (param != null) put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().put(param, JSONObject().put("type", "STRING")
                    .put("description", "Nome do aplicativo instalado")))
                .put("required", JSONArray().put(param)))
        }

    fun execute(name: String, args: JSONObject): JSONObject = try {
        when (name) {
            "list_apps" -> JSONObject().put("aplicativos", JSONArray(apps().map { it.first }.distinct().take(70)))
            "open_app" -> {
                val wanted = args.optString("nome").trim()
                require(wanted.length in 2..80) { "Informe o nome do aplicativo." }
                val matches = apps().filter { it.first.equals(wanted, true) }
                    .ifEmpty { apps().filter { it.first.contains(wanted, true) } }
                when {
                    matches.isEmpty() -> JSONObject().put("erro", "Aplicativo não encontrado.")
                    matches.size > 1 -> JSONObject().put("erro", "Nome ambíguo; escolha um destes.")
                        .put("opcoes", JSONArray(matches.take(8).map { it.first }))
                    else -> {
                        val launch = context.packageManager.getLaunchIntentForPackage(matches[0].second)
                            ?: throw IllegalStateException("Aplicativo sem tela de abertura.")
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launch)
                        JSONObject().put("resultado", "Aberto: ${matches[0].first}")
                    }
                }
            }
            "device_status" -> {
                val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                JSONObject().put("bateria", if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "indisponível")
                    .put("horario", java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(System.currentTimeMillis()))
            }
            else -> JSONObject().put("erro", "Ação local não autorizada neste app.")
        }
    } catch (failure: Exception) {
        AppDiagnostics.get(context).record("Agente local", "Falha na ação $name (${failure.javaClass.simpleName}).")
        JSONObject().put("erro", "Não consegui executar esta ação neste aparelho (${failure.javaClass.simpleName}).")
    }

    private fun apps(): List<Pair<String, String>> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val found = context.packageManager.queryIntentActivities(query, 0)
        return found.map { it.loadLabel(context.packageManager).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.sortedBy { it.first.lowercase() }
    }
}
