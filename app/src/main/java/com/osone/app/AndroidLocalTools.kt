package com.osone.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** Catálogo dinâmico de apps e ponte opcional com acessibilidade ativada pelo usuário. */
class AndroidLocalTools(private val context: Context) {
    fun declarations(): JSONArray = JSONArray().apply {
        put(function("list_apps", "Busque apps instalados; use busca e pagina para navegar todos, sem lista fixa.", mapOf("busca" to "Parte do nome (opcional)", "pagina" to "Página começando em 0")))
        put(function("open_app", "Abra um aplicativo instalado pelo nome ou pacote, se solicitado.", mapOf("nome" to "Nome do aplicativo"), listOf("nome")))
        put(function("device_status", "Veja bateria e hora locais.", emptyMap()))
        put(function("inspect_screen", "Leia os controles acessíveis visíveis antes de interagir.", emptyMap()))
        put(function("interact_ui", "Toque ou segure um controle visível pelo texto.", mapOf("texto" to "Texto ou descrição do controle", "acao" to "tocar ou segurar"), listOf("texto", "acao")))
        put(function("type_text", "Escreva em um campo editável visível.", mapOf("campo" to "Texto do campo; vazio usa campo em foco", "texto" to "Conteúdo a escrever"), listOf("texto")))
        put(function("scroll_screen", "Role a tela atual.", mapOf("direcao" to "cima ou baixo"), listOf("direcao")))
        put(function("system_navigation", "Volte ou abra início, recentes ou notificações.", mapOf("acao" to "voltar, inicio, recentes ou notificacoes"), listOf("acao")))
        put(function("touch_screen", "Toque em coordenadas da tela, ou arraste se informar fim_x e fim_y.", mapOf("x" to "Posição X", "y" to "Posição Y", "fim_x" to "X final opcional", "fim_y" to "Y final opcional"), listOf("x", "y"), true))
    }

    private fun function(name: String, description: String, fields: Map<String, String>,
        required: List<String> = emptyList(), numbers: Boolean = false): JSONObject =
        JSONObject().put("name", name).put("description", description).apply {
            if (fields.isNotEmpty()) put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().apply { fields.forEach { (key, value) ->
                    put(key, JSONObject().put("type", if (numbers && key in listOf("x", "y", "fim_x", "fim_y")) "INTEGER" else "STRING").put("description", value))
                } }).put("required", JSONArray(required)))
        }

    fun execute(name: String, args: JSONObject): JSONObject = try {
        when (name) {
            "list_apps" -> {
                val search = normalized(args.optString("busca"))
                val all = apps().filter { search.isEmpty() || normalized(it.first).contains(search) || it.second.contains(search, true) }
                val page = args.optInt("pagina", 0).coerceIn(0, 100)
                JSONObject().put("total", all.size).put("pagina", page)
                    .put("aplicativos", JSONArray(all.drop(page * 50).take(50).map { it.first }))
                    .put("proxima_pagina", if ((page + 1) * 50 < all.size) page + 1 else JSONObject.NULL)
            }
            "open_app" -> {
                val wanted = args.optString("nome").trim()
                require(wanted.length in 2..120) { "Informe o nome do aplicativo." }
                val all = apps()
                val matches = all.filter { normalized(it.first) == normalized(wanted) || it.second.equals(wanted, true) }
                    .ifEmpty { all.filter { normalized(it.first).contains(normalized(wanted)) } }
                when {
                    matches.isEmpty() -> JSONObject().put("erro", "Aplicativo não encontrado. Busque por parte do nome com list_apps.")
                    matches.size > 1 -> JSONObject().put("erro", "Nome ambíguo; escolha um destes.")
                        .put("opcoes", JSONArray(matches.take(12).map { "${it.first} (${it.second})" }))
                    else -> {
                        val launch = context.packageManager.getLaunchIntentForPackage(matches[0].second)
                            ?: throw IllegalStateException("Aplicativo sem tela de abertura.")
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(launch)
                        JSONObject().put("resultado", "Abertura solicitada: ${matches[0].first}")
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
            "inspect_screen", "interact_ui", "type_text", "scroll_screen", "system_navigation", "touch_screen" -> {
                val service = OsoneAccessibilityService.active
                    ?: return JSONObject().put("erro", "Ative OSTIE em Ajustes > Acessibilidade para controlar outros apps.")
                when (name) {
                    "inspect_screen" -> service.inspect()
                    "interact_ui" -> service.interact(args.optString("texto"), args.optString("acao"))
                    "type_text" -> service.type(args.optString("campo"), args.optString("texto"))
                    "scroll_screen" -> service.scroll(args.optString("direcao"))
                    "system_navigation" -> service.navigate(args.optString("acao"))
                    else -> service.gesture(args.optInt("x", -1), args.optInt("y", -1),
                        args.optInt("fim_x", -1).takeIf { args.has("fim_x") },
                        args.optInt("fim_y", -1).takeIf { args.has("fim_y") })
                }
            }
            else -> JSONObject().put("erro", "Ação local não autorizada neste app.")
        }
    } catch (failure: Exception) {
        AppDiagnostics.get(context).record("Agente local", "Falha na ação $name (${failure.javaClass.simpleName}).")
        JSONObject().put("erro", "Não consegui executar esta ação neste aparelho (${failure.javaClass.simpleName}).")
    }

    private fun normalized(value: String): String = Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim()

    private fun apps(): List<Pair<String, String>> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val found = context.packageManager.queryIntentActivities(query, 0)
        return found.map { it.loadLabel(context.packageManager).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.sortedBy { normalized(it.first) }
    }
}
