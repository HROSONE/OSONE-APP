package com.osone.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import androidx.annotation.RequiresApi
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** Ponte opcional ativada pelo usuário em Ajustes > Acessibilidade; só executa chamadas explícitas. */
class OsoneAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var active: OsoneAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() { active = this }
    private var lastWindowUpdate = 0L
    private var lastAction = 0L
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            lastWindowUpdate = SystemClock.uptimeMillis()
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() { if (active === this) active = null; super.onDestroy() }

    fun inspect(limit: Int = 120): JSONObject {
        val root = rootInActiveWindow ?: return error("A janela atual não expôs controles acessíveis. Tente mostrar a tela.")
        val nodes = JSONArray()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && nodes.length() < limit) {
            val node = queue.removeFirst()
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            if (!node.isVisibleToUser) continue
            val bounds = Rect().also(node::getBoundsInScreen)
            val label = if (node.isPassword) "[campo protegido]" else
                (node.text?.toString() ?: node.contentDescription?.toString() ?: "").take(100)
            if (label.isBlank() && !node.isClickable && !node.isEditable && !node.isScrollable) continue
            nodes.put(JSONObject().put("texto", label).put("tipo", node.className?.toString()?.substringAfterLast('.'))
                .put("clicavel", node.isClickable).put("editavel", node.isEditable)
                .put("rolavel", node.isScrollable)
                .put("centro", JSONArray().put(bounds.centerX()).put(bounds.centerY()))
                // Estado só quando importa, para a lista continuar curta.
                .apply {
                    if (node.isCheckable) put("ligado", node.isChecked)
                    if (!node.isEnabled) put("desativado", true)
                    if (node.isFocused) put("em_foco", true)
                    if (node.isSelected) put("selecionado", true)
                })
        }
        val metrics = resources.displayMetrics
        return JSONObject().put("app", root.packageName?.toString() ?: "desconhecido")
            .put("tela", JSONArray().put(metrics.widthPixels).put(metrics.heightPixels))
            .put("controles", nodes).put("limite", nodes.length() == limit)
            .put("atualizado_ha_ms", if (lastWindowUpdate > 0) SystemClock.uptimeMillis() - lastWindowUpdate else -1)
    }

    fun checkUi(expected: String): JSONObject {
        if (expected.isBlank()) return error("Informe o texto esperado na tela.")
        val root = rootInActiveWindow ?: return error("Sem janela acessível para verificar.")
        return JSONObject().put("app", root.packageName?.toString() ?: "desconhecido")
            .put("texto", expected.take(120)).put("encontrado", find(expected) != null)
            .put("tela_mudou_apos_acao", lastWindowUpdate > lastAction)
            .put("atualizado_ha_ms", if (lastWindowUpdate > 0) SystemClock.uptimeMillis() - lastWindowUpdate else -1)
    }

    /** [order] escolhe entre controles com o mesmo texto (1 = o primeiro, de cima para baixo). */
    fun interact(label: String, action: String, order: Int = 1): JSONObject {
        val all = findAll(label)
        if (all.isEmpty()) return error("Controle '$label' não encontrado na tela. Inspecione a tela novamente.")
        val node = all.getOrNull(order - 1)
            ?: return error("Só há ${all.size} controle(s) com '$label' na tela; use ordem entre 1 e ${all.size}.")
        val flag = when (action) {
            "tocar" -> AccessibilityNodeInfo.ACTION_CLICK
            "segurar" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
            else -> return error("Ação inválida: use tocar ou segurar.")
        }
        var candidate: AccessibilityNodeInfo? = node
        while (candidate != null && !candidate.isClickable && action == "tocar") candidate = candidate.parent
        val target = candidate ?: node
        lastAction = SystemClock.uptimeMillis()
        return JSONObject().put("aceito_pelo_android", target.performAction(flag))
            .apply { if (all.size > 1) put("aviso", "Havia ${all.size} controles com esse texto; usado o de número $order.") }
    }

    /** Texto do controle que está embaixo de um ponto da tela (para a trava de ações sensíveis). */
    fun labelAt(x: Int, y: Int): String {
        val root = rootInActiveWindow ?: return ""
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val bounds = Rect().also(node::getBoundsInScreen)
            if (!bounds.contains(x, y)) continue
            val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
            val area = bounds.width().toLong() * bounds.height()
            if (text.isNotBlank() && area < bestArea) { best = node; bestArea = area }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return best?.let { it.text?.toString() ?: it.contentDescription?.toString() }.orEmpty()
    }

    private val main = Handler(Looper.getMainLooper())

    /**
     * Espera a tela parar de mudar depois de uma ação (ou no máximo [maxMs]) e devolve a leitura dela:
     * assim o modelo não precisa gastar outra ida e volta com inspect_screen.
     */
    fun settle(maxMs: Long = 1500, minMs: Long = 0, respond: (JSONObject) -> Unit) {
        val start = SystemClock.uptimeMillis()
        fun check() {
            val now = SystemClock.uptimeMillis()
            val quiet = now - maxOf(lastWindowUpdate, lastAction) >= 250
            if ((quiet && now - start >= minMs) || now - start >= maxMs) respond(inspect(60)) else main.postDelayed({ check() }, 80)
        }
        main.postDelayed({ check() }, 150)
    }

    /** Espera um texto aparecer na tela (sem travar o app): responde assim que achar ou ao fim do prazo. */
    fun waitFor(text: String, seconds: Int, respond: (JSONObject) -> Unit) {
        if (text.isBlank()) { respond(error("Informe o texto esperado.")); return }
        val deadline = SystemClock.uptimeMillis() + seconds.coerceIn(1, 15) * 1000L
        fun poll() {
            if (find(text) != null) {
                respond(JSONObject().put("encontrado", true).put("texto", text.take(120)))
            } else if (SystemClock.uptimeMillis() >= deadline) {
                respond(JSONObject().put("encontrado", false).put("texto", text.take(120))
                    .put("dica", "A tela não mostrou esse texto a tempo. Inspecione a tela para ver o que apareceu."))
            } else main.postDelayed({ poll() }, 200)
        }
        poll()
    }

    /** Rola (até 10 vezes) até o texto aparecer. */
    fun scrollToText(text: String, direction: String, respond: (JSONObject) -> Unit) {
        if (text.isBlank()) { respond(error("Informe o texto a procurar.")); return }
        var tries = 0
        fun step() {
            find(text)?.let { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                respond(JSONObject().put("encontrado", true).put("rolagens", tries)
                    .put("centro", JSONArray().put(bounds.centerX()).put(bounds.centerY())))
                return
            }
            if (tries >= 10 || scroll(direction).has("erro")) {
                respond(JSONObject().put("encontrado", false).put("rolagens", tries)
                    .put("dica", "Não achei rolando para $direction. Tente a outra direção ou inspecione a tela."))
                return
            }
            tries++
            main.postDelayed({ step() }, 300)
        }
        step()
    }

    /** Print da tela (Android 11+), em JPEG base64 de até 1024 px, para o Live ver o que está aberto. */
    fun screenshot(respond: (JSONObject) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { respond(error("Olhar a tela exige Android 11 ou mais novo; peça para compartilhar a tela no Live.")); return }
        takeShot(respond)
    }

    @RequiresApi(30)
    private fun takeShot(respond: (JSONObject) -> Unit) {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                Thread({
                    val answer = try {
                        val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            ?: throw IllegalStateException("imagem vazia")
                        val bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false)
                        hardware.recycle(); result.hardwareBuffer.close()
                        val scale = minOf(1f, 1024f / maxOf(bitmap.width, bitmap.height))
                        val small = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(),
                            (bitmap.height * scale).toInt(), true) else bitmap
                        val bytes = java.io.ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray()
                        JSONObject().put("_imagem", Base64.encodeToString(bytes, Base64.NO_WRAP))
                            .put("tamanho", JSONArray().put(small.width).put(small.height))
                    } catch (failure: Exception) { error("Não consegui ler a imagem da tela (${failure.javaClass.simpleName}).") }
                    main.post { respond(answer) }
                }, "ostie-screenshot").start()
            }

            override fun onFailure(errorCode: Int) {
                respond(error(when (errorCode) {
                    ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                        "Sem permissão para ver a tela: desligue e ligue o OSTIE em Ajustes > Acessibilidade."
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "Prints muito seguidos; espere um segundo e tente de novo."
                    ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "Este app protege a tela (bancos, senhas); não é possível ver."
                    else -> "O Android não entregou a imagem da tela (código $errorCode)."
                }))
            }
        })
    }

    /** Cola a área de transferência num campo (pelo texto do campo ou o campo em foco). */
    fun paste(label: String): JSONObject {
        val node = if (label.isBlank()) rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) else find(label)
        if (node?.isEditable != true) return error("Campo editável não encontrado. Toque no campo ou inspecione a tela.")
        lastAction = SystemClock.uptimeMillis()
        return JSONObject().put("aceito_pelo_android", node.performAction(AccessibilityNodeInfo.ACTION_PASTE))
    }

    fun type(label: String, text: String): JSONObject {
        if (text.length > 3000) return error("Texto grande demais para o campo.")
        val node = if (label.isBlank()) rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            else find(label)
        if (node?.isEditable != true) return error("Campo editável não encontrado. Inspecione a tela.")
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        lastAction = SystemClock.uptimeMillis()
        return JSONObject().put("aceito_pelo_android", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
    }

    fun scroll(direction: String): JSONObject {
        if (direction == "esquerda" || direction == "direita") return swipeSideways(direction)
        val root = rootInActiveWindow ?: return error("Sem janela ativa.")
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val action = if (direction == "cima") AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            if (node.isVisibleToUser && node.isScrollable && node.performAction(action)) {
                lastAction = SystemClock.uptimeMillis()
                return JSONObject().put("aceito_pelo_android", true)
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return error("Nenhuma área rolável disponível; tente um gesto na tela.")
    }

    /** Carrosséis e abas: "direita" mostra o que está à direita (o dedo desliza para a esquerda). */
    private fun swipeSideways(direction: String): JSONObject {
        val metrics = resources.displayMetrics
        val y = metrics.heightPixels / 2
        val (from, to) = if (direction == "direita") metrics.widthPixels * 0.85f to metrics.widthPixels * 0.15f
            else metrics.widthPixels * 0.15f to metrics.widthPixels * 0.85f
        val path = Path().apply { moveTo(from, y.toFloat()); lineTo(to, y.toFloat()) }
        lastAction = SystemClock.uptimeMillis()
        val accepted = dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250)).build(), null, null)
        return if (accepted) JSONObject().put("aceito_pelo_android", true) else error("O Android recusou o deslize lateral.")
    }

    fun navigate(action: String): JSONObject {
        val global = when (action) {
            "voltar" -> GLOBAL_ACTION_BACK
            "inicio" -> GLOBAL_ACTION_HOME
            "recentes" -> GLOBAL_ACTION_RECENTS
            "notificacoes" -> GLOBAL_ACTION_NOTIFICATIONS
            "atalhos", "ajustes_rapidos" -> GLOBAL_ACTION_QUICK_SETTINGS
            else -> return error("Navegação desconhecida.")
        }
        lastAction = SystemClock.uptimeMillis()
        return JSONObject().put("aceito_pelo_android", performGlobalAction(global))
    }

    fun gesture(x: Int, y: Int, endX: Int?, endY: Int?): JSONObject {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels; val height = metrics.heightPixels
        if (listOfNotNull(x, y, endX, endY).any { it < 0 } || x >= width || y >= height ||
            (endX != null && endX >= width) || (endY != null && endY >= height))
            return error("Coordenadas fora da tela (${width}x$height).")
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()); if (endX != null && endY != null)
            lineTo(endX.toFloat(), endY.toFloat()) }
        val swipe = endX != null && endY != null
        val stroke = GestureDescription.StrokeDescription(path, 0, if (swipe) 250 else 50)
        lastAction = SystemClock.uptimeMillis()
        val accepted = dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        return JSONObject().put("gesto_aceito", accepted).put("concluido", "Ainda não confirmado; inspecione a tela.")
    }

    /** Gestos com vários dedos ou tempos: toque duplo, segurar, arrastar segurando, pinça (ampliar/reduzir). */
    fun multiGesture(type: String, x: Int?, y: Int?, endX: Int?, endY: Int?, amount: Int?): JSONObject {
        val metrics = resources.displayMetrics
        val plan = try {
            GesturePlan.build(type, x, y, endX, endY, amount, metrics.widthPixels, metrics.heightPixels)
        } catch (invalid: IllegalArgumentException) { return error(invalid.message ?: "Gesto inválido.") }
        // A tela só "assenta" depois que o gesto inteiro terminou.
        lastAction = SystemClock.uptimeMillis() + plan.hold + plan.strokes.maxOf { it.start + it.duration }
        val accepted = if (plan.hold > 0) dragWithHold(plan.hold, plan.strokes.single())
            else dispatchGesture(GestureDescription.Builder().apply { plan.strokes.forEach { addStroke(stroke(it)) } }.build(),
                null, null)
        return JSONObject().put("gesto_aceito", accepted).put("concluido", "Ainda não confirmado; inspecione a tela.")
    }

    private fun stroke(spec: StrokeSpec) = GestureDescription.StrokeDescription(Path().apply {
        moveTo(spec.fromX.toFloat(), spec.fromY.toFloat())
        if (!spec.still) lineTo(spec.toX.toFloat(), spec.toY.toFloat())
    }, spec.start, spec.duration)

    /** Segura parado e só então arrasta: launchers e listas exigem o toque longo antes de mover o item. */
    private fun dragWithHold(hold: Long, move: StrokeSpec): Boolean {
        val press = GestureDescription.StrokeDescription(Path().apply { moveTo(move.fromX.toFloat(), move.fromY.toFloat()) },
            0, hold, true)
        return dispatchGesture(GestureDescription.Builder().addStroke(press).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    val path = Path().apply {
                        moveTo(move.fromX.toFloat(), move.fromY.toFloat()); lineTo(move.toX.toFloat(), move.toY.toFloat())
                    }
                    dispatchGesture(GestureDescription.Builder()
                        .addStroke(press.continueStroke(path, 0, move.duration, false)).build(), null, null)
                }
            }, null)
    }

    private fun find(label: String): AccessibilityNodeInfo? = findAll(label).firstOrNull()

    /** Controles com o texto, de cima para baixo: iguais ao texto primeiro; se não houver, os que o contêm. */
    private fun findAll(label: String): List<AccessibilityNodeInfo> {
        if (label.isBlank()) return emptyList()
        val root = rootInActiveWindow ?: return emptyList()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val exact = ArrayList<AccessibilityNodeInfo>()
        val partial = ArrayList<AccessibilityNodeInfo>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && !node.isPassword) {
                val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
                if (text.equals(label, true) || node.viewIdResourceName?.equals(label, true) == true) exact += node
                else if (text.contains(label, true)) partial += node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        fun top(node: AccessibilityNodeInfo) = Rect().also(node::getBoundsInScreen).let { it.top * 10_000 + it.left }
        return exact.ifEmpty { partial }.sortedBy(::top)
    }

    private fun error(message: String) = JSONObject().put("erro", message)
}
