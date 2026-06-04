package kr.ac.yu.smombieproject

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService

/**
 * [Pipeline 1] CameraManager
 * [Software Tech 5: Async Pipeline]
 * Controls the CameraX API to receive real-time video streams.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val executor: ExecutorService,
    private val onAnalyze: (ImageProxy) -> Unit
) {
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraProvider: ProcessCameraProvider? = null

    /**
     * Initializes and starts the camera bound to the LifecycleOwner (e.g., LifecycleService).
     */
    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 1. Preview use case (Always build it so it can be attached/detached)
            preview = Preview.Builder().build()

            // 2. Image Analysis use case (Core Engine)
            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executor) { imageProxy ->
                        onAnalyze(imageProxy)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                // Binding to the service's lifecycle ensures camera stays active in background
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e("CameraManager", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * Dynamically updates the preview surface
     */
    fun setPreviewView(previewView: PreviewView?) {
        if (previewView != null) {
            preview?.setSurfaceProvider(previewView.surfaceProvider)
        } else {
            preview?.setSurfaceProvider(null)
        }
    }
}
