package com.osone.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/** Captura JPEG da câmera a até 1 fps, sem gravação local. O serviço controla a permissão e o ciclo de vida. */
class LiveCameraController(
    private val context: Context,
    private val front: Boolean,
    private val onFrame: (ByteArray, Long) -> Unit,
    private val onError: (String) -> Unit
) {
    private val thread = HandlerThread("ostie-camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val closed = AtomicBoolean(false)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var request: CaptureRequest? = null

    fun start() {
        try {
        require(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "Permissão da câmera não concedida."
        }
        val manager = context.getSystemService(CameraManager::class.java)
        val wanted = if (front) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val id = manager.cameraIdList.firstOrNull {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == wanted
        } ?: error(if (front) "Câmera frontal indisponível." else "Câmera traseira indisponível.")
        val characteristics = manager.getCameraCharacteristics(id)
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)?.toList().orEmpty()
        val size = sizes.filter { it.width <= 1280 && it.height <= 720 }
            .maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: error("Câmera sem saída JPEG disponível.")
        val output = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        reader = output
        output.setOnImageAvailableListener({ source ->
            val image = try { source.acquireLatestImage() } catch (_: Exception) { null }
                ?: return@setOnImageAvailableListener
            try {
                if (!closed.get()) {
                    val data = image.planes[0].buffer
                    val bytes = ByteArray(data.remaining())
                    data.get(bytes)
                    if (bytes.isNotEmpty()) onFrame(bytes, System.currentTimeMillis())
                }
            } catch (failure: Exception) { fail("Falha no quadro da câmera (${failure.javaClass.simpleName}).") }
            finally { image.close() }
        }, handler)
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (closed.get()) { camera.close(); return }
                    device = camera
                    try {
                        val photo = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                        photo.addTarget(output.surface)
                        photo.set(CaptureRequest.JPEG_QUALITY, 65.toByte())
                        photo.set(CaptureRequest.JPEG_ORIENTATION,
                            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                        request = photo.build()
                        @Suppress("DEPRECATION")
                        camera.createCaptureSession(listOf(output.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(active: CameraCaptureSession) {
                                if (closed.get()) { active.close(); return }
                                session = active
                                takeFrame()
                            }
                            override fun onConfigureFailed(active: CameraCaptureSession) {
                                active.close(); fail("Não foi possível iniciar a câmera neste aparelho.")
                            }
                        }, handler)
                    } catch (failure: Exception) { fail("Câmera indisponível (${failure.javaClass.simpleName}).") }
                }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); fail("A câmera foi desconectada.") }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close(); fail("A câmera retornou erro $error.")
                }
            }, handler)
        } catch (failure: Exception) {
            stop()
            throw failure
        }
    }

    private fun takeFrame() {
        if (closed.get()) return
        val active = session ?: return
        val shot = request ?: return
        try {
            active.capture(shot, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest,
                    result: TotalCaptureResult) { scheduleNext() }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest,
                    failure: CaptureFailure) { scheduleNext() }
            }, handler)
        } catch (failure: Exception) { fail("Não foi possível capturar a imagem (${failure.javaClass.simpleName}).") }
    }

    private fun scheduleNext() { if (!closed.get()) handler.postDelayed({ takeFrame() }, 1000) }

    private fun fail(message: String) {
        if (!closed.get()) onError(message)
    }

    fun stop() {
        if (!closed.compareAndSet(false, true)) return
        handler.post {
            try { session?.stopRepeating() } catch (_: Exception) {}
            session?.close(); session = null
            device?.close(); device = null
            reader?.close(); reader = null
            thread.quitSafely()
        }
    }
}
