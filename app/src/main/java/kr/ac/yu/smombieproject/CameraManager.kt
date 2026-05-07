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
 * Controls the CameraX API to receive real-time video streams and connects them to the analysis pipeline.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val executor: ExecutorService,
    private val onAnalyze: (ImageProxy) -> Unit
) {
    /**
     * Initializes and starts the camera.
     * Binds preview and image analysis use cases to the lifecycle.
     */
    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 1. Preview configuration (For display output)
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // 2. [Core Tech 2: Efficiency] Image Analysis configuration
            // Uses STRATEGY_KEEP_ONLY_LATEST to prevent computation lag if analysis is slower than frame generation.
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executor) { imageProxy ->
                        onAnalyze(imageProxy)
                    }
                }

            // Select back camera as default
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind existing use cases before rebinding
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e("CameraManager", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}
