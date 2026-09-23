package com.osone.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Process
import android.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/** PCM16 mono: 16 kHz de entrada e 24 kHz de saída, sem reconhecimento de fala ou TTS. */
class LiveAudioEngine(
    private val context: Context,
    private val send: (String) -> Unit,
    private val inputLevel: (Float) -> Unit,
    private val outputLevel: (Float) -> Unit,
    private val onError: () -> Unit,
    private val onDiagnostic: (String) -> Unit
) {
    private data class AudioChunk(val generation: Int, val bytes: ByteArray)
    private val queue = ArrayBlockingQueue<AudioChunk>(96)
    private val queuedBytes = AtomicInteger(0)
    private val generation = AtomicInteger(0)
    private val outputLock = Any()
    @Volatile private var running = false
    @Volatile private var turnEnded = false
    @Volatile var muted = false
    @Volatile var outputGain = 1f
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var leftoverByte: Byte? = null
    private var lastOverflowWarning = 0L
    fun start() {
        // Reprodução usa a rota/volume de mídia do aparelho. A entrada continua com
        // VOICE_COMMUNICATION e AEC quando o dispositivo fornecer cancelamento de eco.
        val inputBuffer = maxOf(4096, AudioRecord.getMinBufferSize(16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val outputBuffer = maxOf(32768, AudioTrack.getMinBufferSize(24000,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val input = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, inputBuffer)
        if (input.state != AudioRecord.STATE_INITIALIZED) {
            input.release(); throw IllegalStateException("Microfone indisponível")
        }
        echoCanceler = try {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(input.audioSessionId)?.also { it.enabled = true }
            else null
        } catch (_: Exception) { null }
        if (echoCanceler?.enabled != true)
            onDiagnostic("Cancelamento de eco do Android indisponível nesta rota de áudio; um fone pode evitar interrupções.")
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(outputBuffer).setTransferMode(AudioTrack.MODE_STREAM).build()
        if (output.state != AudioTrack.STATE_INITIALIZED) {
            echoCanceler?.release(); echoCanceler = null
            input.release(); output.release(); throw IllegalStateException("Áudio indisponível")
        }
        // O pré-buffer é controlado pela fila acima; respostas curtas também precisam tocar.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) output.setStartThresholdInFrames(1)
        try { input.startRecording() } catch (error: Exception) {
            echoCanceler?.release(); echoCanceler = null
            input.release(); output.release(); throw error
        }
        recorder = input; player = output; running = true
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            val frame = ByteArray(1280) // 40 ms at 16 kHz, 16-bit mono.
            while (running) {
                val count = try { input.read(frame, 0, frame.size) } catch (_: Exception) { break }
                if (count <= 0) { if (running) onError(); break }
                if (!muted) {
                    inputLevel(amplitude(frame, count))
                    send(Base64.encodeToString(frame, 0, count, Base64.NO_WRAP))
                } else inputLevel(0f)
            }
        }, "osone-microphone").start()
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var buffering = true
            var targetBytes = 8640 // 180 ms de pré-buffer para a saída PCM do Android.
            var lastUnderruns = output.underrunCount
            var lastWarning = 0L
            var observedGeneration = generation.get()
            var writtenFrames = 0L
            while (running) {
                if (observedGeneration != generation.get()) {
                    observedGeneration = generation.get()
                    writtenFrames = 0L
                    buffering = true
                }
                // turnComplete indica fim da entrada, não que o alto-falante terminou.
                if (turnEnded && queuedBytes.get() == 0 && queue.isEmpty()) {
                    val remaining = writtenFrames - output.playbackHeadPosition.toLong()
                    if (buffering || remaining <= 240L) {
                        synchronized(outputLock) { if (running) { output.pause(); output.flush() } }
                        writtenFrames = 0L
                        turnEnded = false
                        buffering = true
                    } else {
                        try { Thread.sleep(15) } catch (_: InterruptedException) { break }
                    }
                    if (turnEnded || buffering) continue
                }
                if (buffering && queuedBytes.get() < targetBytes && !(turnEnded && queuedBytes.get() > 0)) {
                    try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                    continue
                }
                if (buffering) {
                    try { if (output.playState != AudioTrack.PLAYSTATE_PLAYING) output.play() }
                    catch (_: Exception) { if (running) onError(); break }
                    buffering = false
                    lastUnderruns = output.underrunCount
                }
                val next = try { queue.poll(25, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
                if (next == null) {
                    if (!turnEnded && output.underrunCount > lastUnderruns) {
                        lastUnderruns = output.underrunCount
                        targetBytes = minOf(targetBytes + 2880, 24000) // Máximo de 500 ms.
                        buffering = true
                        val now = System.currentTimeMillis()
                        if (now - lastWarning > 3000) {
                            lastWarning = now
                            onDiagnostic("Faltou áudio para reprodução; buffer ampliado para ${targetBytes / 48} ms.")
                        }
                    }
                    outputLevel(0f)
                    continue
                }
                queuedBytes.updateAndGet { maxOf(0, it - next.bytes.size) }
                if (next.generation != generation.get()) continue
                val playback = amplify(next.bytes, outputGain)
                var offset = 0
                while (running && next.generation == generation.get() && offset < playback.size) {
                    val count = try { synchronized(outputLock) {
                        if (running && next.generation == generation.get())
                            output.write(playback, offset, minOf(2048, playback.size - offset)) else -1
                    } } catch (_: Exception) { -1 }
                    if (count <= 0) { if (running && next.generation == generation.get()) onDiagnostic("Falha ao escrever no alto-falante."); break }
                    offset += count
                    writtenFrames += count / 2
                    outputLevel(amplitude(playback, count, offset - count))
                }
            }
        }, "osone-speaker").start()
    }

    @Synchronized fun receive(encoded: String) {
        if (!running) return
        val decoded = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: IllegalArgumentException) {
            onDiagnostic("Bloco de áudio inválido recebido do serviço."); return
        }
        if (decoded.isEmpty()) return
        val data = if (leftoverByte != null) byteArrayOf(leftoverByte!!) + decoded else decoded
        leftoverByte = if (data.size % 2 != 0) data.last() else null
        val bytes = if (leftoverByte != null) data.copyOf(data.size - 1) else data
        if (bytes.isEmpty()) return
        queuedBytes.addAndGet(bytes.size)
        if (!queue.offer(AudioChunk(generation.get(), bytes))) {
            queuedBytes.addAndGet(-bytes.size)
            val now = System.currentTimeMillis()
            if (now - lastOverflowWarning > 3000) {
                lastOverflowWarning = now
                onDiagnostic("Fila de reprodução cheia; áudio do serviço chegou mais rápido que o aparelho reproduziu.")
            }
        }
    }

    fun finishTurn() { turnEnded = true }

    @Synchronized fun interrupt() {
        generation.incrementAndGet()
        queue.clear()
        queuedBytes.set(0)
        leftoverByte = null
        turnEnded = false
        synchronized(outputLock) {
            player?.let { if (running) { it.pause(); it.flush() } }
        }
        outputLevel(0f)
    }

    fun stop() {
        running = false
        generation.incrementAndGet()
        queue.clear()
        queuedBytes.set(0)
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        echoCanceler?.release(); echoCanceler = null
        synchronized(outputLock) {
            try { player?.pause(); player?.flush(); player?.stop() } catch (_: Exception) {}
            player?.release(); player = null
        }
        inputLevel(0f); outputLevel(0f)
    }

    /** Ganho digital com saturação: evita overflow ao aumentar uma resposta PCM baixa. */
    private fun amplify(input: ByteArray, gain: Float): ByteArray {
        if (gain == 1f) return input
        val result = input.copyOf()
        for (i in 0 until result.size - 1 step 2) {
            val value = (((result[i + 1].toInt() and 255) shl 8) or
                (result[i].toInt() and 255)).toShort().toInt()
            val scaled = (value * gain).toInt().coerceIn(-32768, 32767)
            result[i] = scaled.toByte()
            result[i + 1] = (scaled shr 8).toByte()
        }
        return result
    }

    private fun amplitude(bytes: ByteArray, count: Int, start: Int = 0): Float {
        var sum = 0.0
        val samples = count / 2
        if (samples == 0) return 0f
        for (i in 0 until samples) {
            val at = start + i * 2
            val sample = ((bytes[at + 1].toInt() shl 8) or (bytes[at].toInt() and 255)).toShort().toInt()
            sum += sample.toDouble() * sample
        }
        return (sqrt(sum / samples) / 10000.0).toFloat().coerceIn(0f, 1f)
    }
}
