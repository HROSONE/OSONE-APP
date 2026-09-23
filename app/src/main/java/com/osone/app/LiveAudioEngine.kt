package com.osone.app

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Process
import android.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** PCM16 mono: 16 kHz de entrada e 24 kHz de saída, sem reconhecimento de fala ou TTS. */
class LiveAudioEngine(
    private val send: (String) -> Unit,
    private val inputLevel: (Float) -> Unit,
    private val outputLevel: (Float) -> Unit,
    private val onError: () -> Unit
) {
    private val queue = ArrayBlockingQueue<ByteArray>(12)
    private val outputLock = Any()
    @Volatile private var running = false
    @Volatile var muted = false
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null

    fun start() {
        val inputBuffer = maxOf(4096, AudioRecord.getMinBufferSize(16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val outputBuffer = maxOf(8192, AudioTrack.getMinBufferSize(24000,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val input = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, inputBuffer)
        if (input.state != AudioRecord.STATE_INITIALIZED) { input.release(); throw IllegalStateException("Microfone indisponível") }
        val output = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(24000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(outputBuffer).setTransferMode(AudioTrack.MODE_STREAM).build()
        if (output.state != AudioTrack.STATE_INITIALIZED) {
            input.release(); output.release(); throw IllegalStateException("Áudio indisponível")
        }
        try { input.startRecording(); output.play() } catch (error: Exception) {
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
            while (running) {
                val chunk = try { queue.poll(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { break }
                if (chunk == null) { outputLevel(0f); continue }
                var offset = 0
                while (running && offset < chunk.size) {
                    val count = synchronized(outputLock) {
                        if (running) output.write(chunk, offset, minOf(2048, chunk.size - offset)) else -1
                    }
                    if (count <= 0) break
                    offset += count
                    outputLevel(amplitude(chunk, count, offset - count))
                }
            }
        }, "osone-speaker").start()
    }

    fun receive(encoded: String) {
        if (!running) return
        val bytes = try { Base64.decode(encoded, Base64.DEFAULT) } catch (_: IllegalArgumentException) { return }
        if (bytes.isEmpty()) return
        if (!queue.offer(bytes)) { queue.poll(); queue.offer(bytes) }
    }

    fun interrupt() {
        queue.clear()
        synchronized(outputLock) {
            player?.let { if (running) { it.pause(); it.flush(); it.play() } }
        }
        outputLevel(0f)
    }

    fun stop() {
        running = false
        queue.clear()
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release(); recorder = null
        synchronized(outputLock) {
            try { player?.pause(); player?.flush(); player?.stop() } catch (_: Exception) {}
            player?.release(); player = null
        }
        inputLevel(0f); outputLevel(0f)
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
