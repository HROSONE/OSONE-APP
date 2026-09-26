package com.osone.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * OSTIE como "Assistente digital padrão" do Android: segurar o botão lateral (ou o início) ou deslizar do canto
 * abre o Live, sem microfone ligado em segundo plano. O Android exige três peças: o serviço, a sessão e um
 * reconhecedor de voz. Nosso reconhecedor repassa para o do aparelho (Google), para o ditado dos outros apps
 * continuar funcionando mesmo com o OSTIE como assistente.
 */
class OstieVoiceInteractionService : VoiceInteractionService()

class OstieVoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = OstieVoiceSession(this)
}

class OstieVoiceSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val open = Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_LIVE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try { startAssistantActivity(open) } catch (_: Exception) {
            try { context.startActivity(open) } catch (failure: Exception) {
                AppDiagnostics.get(context).record("Assistente", "Não abriu o Live (${failure.javaClass.simpleName}).")
            }
        }
        hide()
    }
}

/** Repassa o reconhecimento de voz para o reconhecedor do aparelho (o primeiro que não é o OSTIE). */
class OstieRecognitionService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null

    private fun target(): ComponentName? = packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
        .filter { it.packageName != packageName }
        // Google primeiro: é o que o celular usava antes.
        .sortedByDescending { it.packageName.startsWith("com.google") }
        .firstOrNull()

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        delegate?.destroy()
        val component = target()
        if (component == null) { try { listener.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) { }; return }
        delegate = SpeechRecognizer.createSpeechRecognizer(this, component).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = safe { listener.readyForSpeech(params ?: Bundle()) }
                override fun onBeginningOfSpeech() = safe { listener.beginningOfSpeech() }
                override fun onRmsChanged(rmsdB: Float) = safe { listener.rmsChanged(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) = safe { buffer?.let(listener::bufferReceived) }
                override fun onEndOfSpeech() = safe { listener.endOfSpeech() }
                override fun onError(error: Int) = safe { listener.error(error) }
                override fun onResults(results: Bundle?) = safe { listener.results(results ?: Bundle()) }
                override fun onPartialResults(partialResults: Bundle?) = safe { listener.partialResults(partialResults ?: Bundle()) }
                override fun onEvent(eventType: Int, params: Bundle?) { }
            })
            startListening(recognizerIntent)
        }
    }

    override fun onStopListening(listener: Callback) { delegate?.stopListening() }

    override fun onCancel(listener: Callback) { delegate?.cancel() }

    override fun onDestroy() { delegate?.destroy(); delegate = null; super.onDestroy() }

    /** O app que pediu o ditado pode ter fechado: ignorar a falha ao avisá-lo. */
    private fun safe(block: () -> Unit) { try { block() } catch (_: Exception) { } }
}
