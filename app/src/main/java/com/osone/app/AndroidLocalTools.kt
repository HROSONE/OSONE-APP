package com.osone.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.roundToInt

/** Catálogo dinâmico de apps e ponte opcional com acessibilidade ativada pelo usuário. */
class AndroidLocalTools(private val context: Context) {
    fun declarations(): JSONArray = JSONArray().apply {
        put(function("list_apps", "Busque apps instalados. Por padrão mostra apps que abrem; incluir_sistema inclui apps sem ícone. Use busca e pagina.", mapOf("busca" to "Parte do nome ou pacote (opcional)", "pagina" to "Página começando em 0", "incluir_sistema" to "true para incluir apps do sistema e sem tela de abertura")))
        put(function("open_app", "Abra um aplicativo instalado pelo nome ou pacote, se solicitado.", mapOf("nome" to "Nome do aplicativo"), listOf("nome")))
        put(function("open_app_settings", "Abra a página de informações/permissões de um app pelo nome ou pacote. O usuário decide as alterações no Android.", mapOf("nome" to "Nome ou pacote do aplicativo"), listOf("nome")))
        put(function("open_settings", "Abra a página das Configurações do Android. Áreas: geral, internet, wifi, bluetooth, som, tela, bateria, aplicativos, notificacoes, acessibilidade, privacidade, seguranca, localizacao, armazenamento, idioma, teclado, data_hora, sobre.", mapOf("area" to "Nome da área das Configurações"), listOf("area")))
        put(function("set_media_volume", "Defina o volume de mídia entre 0 e 100 por cento quando o usuário pedir. Não controla volume de chamada.", mapOf("percentual" to "Número inteiro de 0 a 100"), listOf("percentual"), true))
        put(function("set_brightness", "Defina o brilho manual entre 0 e 100 por cento quando pedido; pode exigir autorização do Android. Se brilho automático estiver ativo, abra a tela para o usuário desligá-lo.", mapOf("percentual" to "Número inteiro de 0 a 100"), listOf("percentual"), true))
        put(function("set_screen_timeout", "Defina o tempo até a tela desligar, quando pedido. Exige autorização para modificar configurações; alguns aparelhos impõem limites próprios.", mapOf("minutos" to "1, 2, 5, 10, 15 ou 30 minutos"), listOf("minutos"), true))
        put(function("set_auto_rotate", "Ligue ou desligue a rotação automática da tela quando pedido. Exige autorização para modificar configurações.", mapOf("ativada" to "true para ligar ou false para desligar"), listOf("ativada")))
        put(function("device_status", "Veja bateria e hora locais.", emptyMap()))
        put(function("inspect_screen", "Leia os controles acessíveis visíveis antes de interagir.", emptyMap()))
        put(function("check_ui", "Depois de agir, confira se um texto/controle aparece na janela atual. Resultado encontrado não garante que uma tarefa externa terminou.", mapOf("texto" to "Texto esperado na tela"), listOf("texto")))
        put(function("interact_ui", "Toque ou segure um controle visível pelo texto. Botões que enviam, pagam, compram, apagam ou confirmam exigem o sim do usuário (veja o erro devolvido).",
            mapOf("texto" to "Texto ou descrição do controle", "acao" to "tocar ou segurar",
                "ordem" to "Qual deles, se houver vários com o mesmo texto (1 = o de cima)",
                "confirmado_pelo_usuario" to "true só depois que o usuário disse sim a uma ação sensível"), listOf("texto", "acao"), true))
        put(function("wait_for_ui", "Espere um texto aparecer na tela (tela carregando, app abrindo) antes do próximo passo.",
            mapOf("texto" to "Texto esperado", "segundos" to "Tempo máximo, 1 a 15 (padrão 8)"), listOf("texto"), true))
        put(function("scroll_to_text", "Role a tela até um texto aparecer (listas longas, configurações) e devolva onde ele está.",
            mapOf("texto" to "Texto a procurar", "direcao" to "baixo (padrão), cima, esquerda ou direita"), listOf("texto")))
        put(function("look_at_screen", "Veja a tela atual como imagem (fotos, jogos, apps sem controles acessíveis). A imagem chega a você logo antes da resposta; descreva o que vê.", emptyMap()))
        put(function("copy_text", "Copie um texto para a área de transferência do celular.", mapOf("texto" to "Texto a copiar"), listOf("texto")))
        put(function("paste_text", "Cole a área de transferência num campo editável.", mapOf("campo" to "Texto do campo; vazio usa o campo em foco")))
        put(function("type_text", "Escreva em um campo editável visível.", mapOf("campo" to "Texto do campo; vazio usa campo em foco", "texto" to "Conteúdo a escrever"), listOf("texto")))
        put(function("scroll_screen", "Role a tela atual; esquerda e direita deslizam carrosséis e abas.", mapOf("direcao" to "cima, baixo, esquerda ou direita"), listOf("direcao")))
        put(function("open_panel", "Abra um painel rápido do Android por cima da tela: internet, wifi, volume ou nfc. O usuário liga ou desliga ali (o Android não deixa apps mudarem sozinhos).",
            mapOf("painel" to "internet, wifi, volume ou nfc"), listOf("painel")))
        put(function("system_navigation", "Volte ou abra início, recentes, notificações ou ajustes rápidos.", mapOf("acao" to "voltar, inicio, recentes, notificacoes ou ajustes_rapidos"), listOf("acao")))
        put(function("screen_gesture", "Gestos na tela: toque_duplo (ex.: ampliar foto), segurar (toque longo num ponto), " +
            "arrastar (segura e move: ícones, itens, controles deslizantes; exige x, y, fim_x e fim_y), ampliar e reduzir " +
            "(pinça com dois dedos: zoom em fotos, mapas e páginas). Use coordenadas de inspect_screen; sem x e y, usa o centro da tela.",
            mapOf("tipo" to "toque_duplo, segurar, arrastar, ampliar ou reduzir", "x" to "Posição X (opcional)",
                "y" to "Posição Y (opcional)", "fim_x" to "X final, para arrastar", "fim_y" to "Y final, para arrastar",
                "quantidade" to "Pinça: quanto os dedos andam, 10 a 90 (% da tela); padrão 40"), listOf("tipo"), true))
        put(function("touch_screen", "Toque rápido em coordenadas da tela, ou deslize rápido (swipe) se informar fim_x e fim_y.", mapOf("x" to "Posição X", "y" to "Posição Y", "fim_x" to "X final opcional", "fim_y" to "Y final opcional",
            "confirmado_pelo_usuario" to "true só depois que o usuário disse sim a uma ação sensível"), listOf("x", "y"), true))
    }

    private fun function(name: String, description: String, fields: Map<String, String>,
        required: List<String> = emptyList(), numbers: Boolean = false): JSONObject =
        JSONObject().put("name", name).put("description", description).apply {
            if (fields.isNotEmpty()) put("parameters", JSONObject().put("type", "OBJECT")
                .put("properties", JSONObject().apply { fields.forEach { (key, value) ->
                put(key, JSONObject().put("type", if (key in listOf("incluir_sistema", "ativada", "confirmado_pelo_usuario")) "BOOLEAN"
                    else if (numbers && key in listOf("x", "y", "fim_x", "fim_y", "percentual", "minutos", "quantidade", "ordem", "segundos")) "INTEGER" else "STRING").put("description", value))
                } }).put("required", JSONArray(required)))
        }

    fun execute(name: String, args: JSONObject): JSONObject = try {
        when (name) {
            "list_apps" -> {
                val search = normalized(args.optString("busca"))
                val all = apps(args.optBoolean("incluir_sistema", false)).filter {
                    search.isEmpty() || normalized(it.first).contains(search) || it.second.contains(search, true) }
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
            "open_app_settings" -> {
                val match = findApp(args.optString("nome"), includeSystem = true)
                if (match is JSONObject) match else {
                    val app = match as Pair<*, *>
                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${app.second}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        "Informações de ${app.first} abertas. Verifique a tela antes de mudar permissões.")
                }
            }
            "open_settings" -> {
                val area = normalized(args.optString("area"))
                val action = when (area) {
                    "geral", "configuracoes", "configuracao" -> Settings.ACTION_SETTINGS
                    "internet", "rede" -> Settings.ACTION_WIRELESS_SETTINGS
                    "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
                    "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
                    "som", "audio", "volume" -> Settings.ACTION_SOUND_SETTINGS
                    "tela", "display", "brilho" -> Settings.ACTION_DISPLAY_SETTINGS
                    "bateria", "economia de bateria" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                    "aplicativos", "apps" -> Settings.ACTION_APPLICATION_SETTINGS
                    "notificacoes" -> if (Build.VERSION.SDK_INT >= 33)
                        Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS else Settings.ACTION_APPLICATION_SETTINGS
                    "acessibilidade" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                    "privacidade", "seguranca" -> Settings.ACTION_SECURITY_SETTINGS
                    "localizacao", "local" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                    "armazenamento", "espaco" -> Settings.ACTION_INTERNAL_STORAGE_SETTINGS
                    "idioma", "lingua" -> Settings.ACTION_LOCALE_SETTINGS
                    "teclado" -> Settings.ACTION_INPUT_METHOD_SETTINGS
                    "data_hora", "data e hora" -> Settings.ACTION_DATE_SETTINGS
                    "sobre", "informacoes do telefone" -> Settings.ACTION_DEVICE_INFO_SETTINGS
                    else -> return JSONObject().put("erro", "Área desconhecida. Use uma das áreas descritas em open_settings.")
                }
                open(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    "Configurações de $area abertas. Inspecione os controles antes de alterar algo.")
            }
            "set_media_volume" -> {
                val value = percentage(args)
                val audio = context.getSystemService(AudioManager::class.java)
                val maximum = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                    (maximum * value / 100f).roundToInt(), AudioManager.FLAG_SHOW_UI)
                JSONObject().put("volume_midia_percentual_aproximado",
                    if (maximum > 0) audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / maximum else 0)
            }
            "set_brightness" -> {
                val value = percentage(args)
                if (!Settings.System.canWrite(context)) {
                    open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        "Autorize OSTIE a modificar configurações do sistema e repita o pedido de brilho.")
                } else if (Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC) {
                    open(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        "Brilho automático está ativo. Desative-o em Tela se quiser definir um valor fixo.")
                } else {
                    val level = (255 * value / 100f).roundToInt().coerceIn(1, 255)
                    check(Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)) {
                        "O Android não permitiu alterar o brilho." }
                    JSONObject().put("brilho_percentual_aproximado", value)
                }
            }
            "set_screen_timeout" -> {
                val minutes = args.optInt("minutos", -1)
                require(minutes in listOf(1, 2, 5, 10, 15, 30)) { "Escolha 1, 2, 5, 10, 15 ou 30 minutos." }
                if (!Settings.System.canWrite(context)) requestWriteSettings() else {
                    check(Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT,
                        minutes * 60_000)) { "O Android não permitiu mudar o tempo da tela." }
                    JSONObject().put("tempo_solicitado_minutos", minutes)
                        .put("tempo_atual_minutos", Settings.System.getInt(context.contentResolver,
                            Settings.System.SCREEN_OFF_TIMEOUT, 0) / 60_000)
                }
            }
            "set_auto_rotate" -> {
                require(args.opt("ativada") is Boolean) { "Informe ativada como true ou false." }
                val enabled = args.getBoolean("ativada")
                if (!Settings.System.canWrite(context)) requestWriteSettings() else {
                    check(Settings.System.putInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION,
                        if (enabled) 1 else 0)) { "O Android não permitiu alterar a rotação." }
                    JSONObject().put("rotacao_automatica", Settings.System.getInt(context.contentResolver,
                        Settings.System.ACCELEROMETER_ROTATION, 0) == 1)
                }
            }
            "device_status" -> {
                val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
                val audio = context.getSystemService(AudioManager::class.java)
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                JSONObject().put("bateria", if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "indisponível")
                    .put("horario", java.text.SimpleDateFormat("HH:mm", java.util.Locale("pt", "BR")).format(System.currentTimeMillis()))
                    .put("volume_midia_percentual_aproximado", if (max > 0)
                        audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max else 0)
                    .put("brilho_automatico", Settings.System.getInt(context.contentResolver,
                        Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            }
            "copy_text" -> {
                val text = args.optString("texto").take(20_000)
                require(text.isNotBlank()) { "Informe o texto a copiar." }
                context.getSystemService(android.content.ClipboardManager::class.java)
                    .setPrimaryClip(android.content.ClipData.newPlainText("OSTIE", text))
                JSONObject().put("resultado", "Texto copiado (${text.length} caracteres).").also { log(name, text.take(40), it) }
            }
            "open_panel" -> {
                val panel = normalized(args.optString("painel"))
                val action = if (Build.VERSION.SDK_INT >= 29) when (panel) {
                    "internet", "dados", "rede" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
                    "wifi", "wi-fi" -> Settings.Panel.ACTION_WIFI
                    "volume", "som" -> Settings.Panel.ACTION_VOLUME
                    "nfc" -> Settings.Panel.ACTION_NFC
                    else -> return JSONObject().put("erro", "Painel desconhecido: use internet, wifi, volume ou nfc.")
                } else when (panel) {
                    "wifi", "wi-fi" -> Settings.ACTION_WIFI_SETTINGS
                    "volume", "som" -> Settings.ACTION_SOUND_SETTINGS
                    "nfc" -> Settings.ACTION_NFC_SETTINGS
                    else -> Settings.ACTION_WIRELESS_SETTINGS
                }
                open(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), "Painel de $panel aberto; o usuário escolhe ali.")
                    .also { log(name, panel, it) }
            }
            "inspect_screen", "check_ui", "interact_ui", "type_text", "scroll_screen", "system_navigation", "touch_screen",
            "screen_gesture", "paste_text" -> {
                val service = OsoneAccessibilityService.active
                    ?: return JSONObject().put("erro", "Ative OSTIE em Ajustes > Acessibilidade para controlar outros apps.")
                val confirmed = args.optBoolean("confirmado_pelo_usuario", false)
                when (name) {
                    "inspect_screen" -> service.inspect()
                    "check_ui" -> service.checkUi(args.optString("texto"))
                    "paste_text" -> service.paste(args.optString("campo"))
                    "interact_ui" -> {
                        val label = args.optString("texto")
                        // A trava vale para tocar; segurar só abre menus.
                        val blocked = if (args.optString("acao") == "tocar") guard.check(label, confirmed) else null
                        blocked?.let { JSONObject().put("erro", it).put("aguardando_confirmacao", true) }
                            ?: service.interact(label, args.optString("acao"), args.optInt("ordem", 1).coerceAtLeast(1))
                    }
                    "type_text" -> service.type(args.optString("campo"), args.optString("texto"))
                    "scroll_screen" -> service.scroll(args.optString("direcao"))
                    "system_navigation" -> service.navigate(args.optString("acao"))
                    "screen_gesture" -> {
                        fun number(key: String) = if (args.has(key) && !args.isNull(key)) args.optInt(key, -1) else null
                        service.multiGesture(args.optString("tipo"), number("x"), number("y"), number("fim_x"),
                            number("fim_y"), number("quantidade"))
                    }
                    else -> {
                        val x = args.optInt("x", -1); val y = args.optInt("y", -1)
                        val swipe = args.has("fim_x") && args.has("fim_y")
                        val blocked = if (swipe) null else guard.check(service.labelAt(x, y), confirmed)
                        blocked?.let { JSONObject().put("erro", it).put("aguardando_confirmacao", true) }
                            ?: service.gesture(x, y, args.optInt("fim_x", -1).takeIf { args.has("fim_x") },
                                args.optInt("fim_y", -1).takeIf { args.has("fim_y") })
                    }
                }.also { if (name !in setOf("inspect_screen", "check_ui")) log(name, describe(name, args), it) }
            }
            else -> JSONObject().put("erro", "Ação local não autorizada neste app.")
        }
    } catch (failure: Exception) {
        AppDiagnostics.get(context).record("Agente local", "Falha na ação $name (${failure.javaClass.simpleName}).")
        JSONObject().put("erro", "Não consegui executar esta ação neste aparelho (${failure.javaClass.simpleName}).")
    }

    /** Ferramentas que respondem depois (esperar, rolar até achar, olhar a tela): o Live usa [executeAsync]. */
    fun runsAsync(name: String) = name in ASYNC

    /** Chame na thread principal; [respond] também é chamado nela. */
    fun executeAsync(name: String, args: JSONObject, respond: (JSONObject) -> Unit) {
        val service = OsoneAccessibilityService.active
            ?: return respond(JSONObject().put("erro", "Ative OSTIE em Ajustes > Acessibilidade para controlar outros apps."))
        val done: (JSONObject) -> Unit = { answer ->
            log(name, if (name == "look_at_screen") "print da tela" else describe(name, args), answer)
            respond(answer)
        }
        when (name) {
            "wait_for_ui" -> service.waitFor(args.optString("texto"), args.optInt("segundos", 8), done)
            "scroll_to_text" -> service.scrollToText(args.optString("texto"),
                args.optString("direcao").ifBlank { "baixo" }, done)
            "look_at_screen" -> service.screenshot(done)
            else -> respond(JSONObject().put("erro", "Ação local não autorizada neste app."))
        }
    }

    private fun describe(name: String, args: JSONObject): String = when {
        args.has("texto") -> "\"${args.optString("texto").take(40)}\""
        args.has("x") -> "(${args.optInt("x")}, ${args.optInt("y")})" +
            (if (args.has("fim_x")) " → (${args.optInt("fim_x")}, ${args.optInt("fim_y")})" else "")
        args.has("direcao") -> args.optString("direcao")
        args.has("acao") -> args.optString("acao")
        args.has("tipo") -> args.optString("tipo")
        else -> ""
    } + (if (name == "screen_gesture" && args.has("tipo") && args.has("x")) " ${args.optString("tipo")}" else "")

    private fun log(name: String, detail: String, answer: JSONObject) =
        AgentLog.record(LABELS[name] ?: name, detail, !answer.has("erro"))

    companion object {
        /** Uma trava para o app inteiro: a confirmação do usuário vale entre chamadas. */
        private val guard = AgentGuard()
        private val ASYNC = setOf("wait_for_ui", "scroll_to_text", "look_at_screen")
        private val LABELS = mapOf("interact_ui" to "Tocar", "type_text" to "Digitar", "scroll_screen" to "Rolar",
            "system_navigation" to "Navegar", "touch_screen" to "Toque na tela", "screen_gesture" to "Gesto",
            "paste_text" to "Colar", "copy_text" to "Copiar", "open_panel" to "Painel", "wait_for_ui" to "Esperar",
            "scroll_to_text" to "Rolar até achar", "look_at_screen" to "Olhar a tela")
    }

    private fun normalized(value: String): String = Normalizer.normalize(value.lowercase(java.util.Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim()

    private fun percentage(args: JSONObject): Int {
        val value = args.optInt("percentual", -1)
        require(args.has("percentual") && value in 0..100) { "Use um percentual inteiro entre 0 e 100." }
        return value
    }

    private fun requestWriteSettings(): JSONObject = open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        "Autorize OSTIE a modificar configurações do sistema e repita o pedido.")

    private fun open(intent: Intent, message: String): JSONObject = try {
        context.startActivity(intent)
        JSONObject().put("resultado", message)
    } catch (_: android.content.ActivityNotFoundException) {
        JSONObject().put("erro", "Esta tela de Configurações não está disponível neste aparelho.")
    }

    private fun findApp(wanted: String, includeSystem: Boolean): Any {
        require(wanted.trim().length in 2..160) { "Informe o nome ou pacote do aplicativo." }
        val all = apps(includeSystem)
        val matches = all.filter { normalized(it.first) == normalized(wanted) || it.second.equals(wanted, true) }
            .ifEmpty { all.filter { normalized(it.first).contains(normalized(wanted)) } }
        return when {
            matches.isEmpty() -> JSONObject().put("erro", "App não encontrado. Tente list_apps com incluir_sistema.")
            matches.size > 1 -> JSONObject().put("erro", "Nome ambíguo; escolha um pacote.")
                .put("opcoes", JSONArray(matches.take(12).map { "${it.first} (${it.second})" }))
            else -> matches[0]
        }
    }

    @Suppress("DEPRECATION")
    private fun apps(includeSystem: Boolean = false): List<Pair<String, String>> {
        if (includeSystem) return context.packageManager.getInstalledApplications(0)
            .map { it.loadLabel(context.packageManager).toString() to it.packageName }
            .distinctBy { it.second }.sortedBy { normalized(it.first) }
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = context.packageManager.queryIntentActivities(query, 0)
        return found.map { it.loadLabel(context.packageManager).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.sortedBy { normalized(it.first) }
    }
}
