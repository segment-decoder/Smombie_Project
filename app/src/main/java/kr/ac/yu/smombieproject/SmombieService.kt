package kr.ac.yu.smombieproject

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.*
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * [Software Tech: Background Intelligence Core]
 * Inherits from LifecycleService to maintain camera analysis even in background.
 */
class SmombieService : LifecycleService() {

    private val channelId = "SmombieRadarChannel"
    private val notificationId = 1
    private val ACTION_STOP_SERVICE = "kr.ac.yu.smombieproject.STOP_SERVICE"
    private lateinit var vibrator: Vibrator
    
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private lateinit var cameraManager: CameraManager
    private val nativeProcessor = NativeProcessor()
    private val tuningLock = Any()
    private var bufferCapacity = defaultTuningValue(TuningParameterIds.BUFFER_CAPACITY).roundToInt()
    private var bufferThreshold = defaultTuningValue(TuningParameterIds.BUFFER_THRESHOLD).roundToInt()
    private var stateBuffer = TimeStateBuffer(capacity = bufferCapacity, threshold = bufferThreshold)
    @Volatile
    private var dangerScoreThreshold = defaultTuningValue(TuningParameterIds.DANGER_AREA_THRESHOLD)
    @Volatile
    private var debugMode: Int = DEBUG_VIEW_CAMERA

    // Bridge for UI communication
    interface ServiceCallback {
        fun onDataUpdated(score: Double, isHazardous: Boolean, type: Int)
        fun onDebugFrameUpdated(bitmap: Bitmap?) {}
        fun onBufferUpdated(bufferState: String) {}
    }
    
    private var callback: ServiceCallback? = null

    companion object {
        const val DEBUG_VIEW_CAMERA = 0
        const val DEBUG_VIEW_CANNY_INPUT = 1
        const val DEBUG_VIEW_CANNY = 2
        const val DEBUG_VIEW_ROI_EDGE = 3
        const val DEBUG_VIEW_MORPHOLOGY = 4
        private const val RESULT_SCORE_MODULUS = 1000000.0

        private var instance: SmombieService? = null
        
        fun setPreview(view: PreviewView?) {
            instance?.cameraManager?.setPreviewView(view)
        }
        
        fun setListener(cb: ServiceCallback?) {
            instance?.callback = cb
        }

        fun setDebugMode(mode: Int) {
            instance?.debugMode = mode
        }

        fun applyTuningValue(parameterId: Int, value: Double): Double? {
            return instance?.applyTuningValue(parameterId, value)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vManager = getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }

        // Initialize Camera in Service
        cameraManager = CameraManager(this, this, cameraExecutor) { imageProxy ->
            processImage(imageProxy)
        }

        applySavedTuningValues()
        createNotificationChannel()
        startForeground(notificationId, buildNotification("Radar is scanning for hazards..."))
        
        cameraManager.startCamera()
    }

    private fun processImage(image: ImageProxy) {
        val rawMat = ImageUtil.getMatFromImageProxy(image)
        val analysisMat = rotateMatIfNeeded(rawMat, image.imageInfo.rotationDegrees)

        try {
            val activeDebugMode = debugMode
            val activeCallback = callback
            val result = if (activeDebugMode == DEBUG_VIEW_CAMERA || activeCallback == null) {
                nativeProcessor.processFrame(analysisMat.nativeObjAddr)
            } else {
                val debugMat = Mat()
                val debugResult = nativeProcessor.processDebugFrame(
                    analysisMat.nativeObjAddr,
                    debugMat.nativeObjAddr,
                    activeDebugMode
                )
                if (!debugMat.empty()) {
                    val bitmap = Bitmap.createBitmap(debugMat.cols(), debugMat.rows(), Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(debugMat, bitmap)
                    activeCallback.onDebugFrameUpdated(bitmap)
                }
                debugMat.release()
                debugResult
            }

            val objectType = (result / RESULT_SCORE_MODULUS).toInt()
            val hazardScore = result % RESULT_SCORE_MODULUS

            val isCautionFrame = hazardScore > dangerScoreThreshold
            val bufferResult = synchronized(tuningLock) {
                val hazardous = stateBuffer.isHazardous(isCautionFrame)
                hazardous to stateBuffer.getBufferString()
            }
            val hazardousResult = bufferResult.first
            val bufferState = bufferResult.second

            // Notify Activity if attached
            callback?.onDataUpdated(hazardScore, hazardousResult, objectType)
            callback?.onBufferUpdated(bufferState)

            if (hazardousResult) {
                triggerAlert(objectType)
            }
        } finally {
            if (analysisMat !== rawMat) {
                analysisMat.release()
            }
            rawMat.release()
            image.close()
        }
    }

    private fun rotateMatIfNeeded(mat: Mat, rotationDegrees: Int): Mat {
        val normalizedDegrees = ((rotationDegrees % 360) + 360) % 360
        if (normalizedDegrees == 0) return mat

        val rotated = Mat()
        when (normalizedDegrees) {
            90 -> Core.rotate(mat, rotated, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(mat, rotated, Core.ROTATE_180)
            270 -> Core.rotate(mat, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> return mat
        }

        return rotated
    }

    private fun applySavedTuningValues() {
        val prefs = getSharedPreferences(TuningParameters.PREFS_NAME, Context.MODE_PRIVATE)
        for (parameter in TuningParameters.all) {
            val savedValue = prefs.getString(TuningParameters.key(parameter), null)
                ?.toDoubleOrNull()
                ?: parameter.defaultValue
            applyTuningValue(parameter.id, savedValue)
        }
    }

    private fun applyTuningValue(parameterId: Int, value: Double): Double {
        val parameter = TuningParameters.find(parameterId) ?: return 0.0
        val sanitizedValue = TuningParameters.sanitize(parameter, value)

        return when (parameter.scope) {
            TuningScope.NATIVE -> nativeProcessor.setNativeTuningValue(parameterId, sanitizedValue)
            TuningScope.SERVICE -> applyServiceTuningValue(parameterId, sanitizedValue)
            TuningScope.COMPOSITE -> applyCompositeTuningValue(parameterId, sanitizedValue)
        }
    }

    private fun applyCompositeTuningValue(parameterId: Int, value: Double): Double {
        val strength = value.coerceIn(0.0, 1.0)

        return when (parameterId) {
            TuningParameterIds.CONTOUR_FILTER_STRENGTH -> {
                applyContourFilterStrength(strength)
                strength
            }
            TuningParameterIds.FOOT_FILTER_STRENGTH -> {
                applyFootFilterStrength(strength)
                strength
            }
            else -> strength
        }
    }

    private fun applyContourFilterStrength(strength: Double) {
        nativeProcessor.setNativeTuningValue(TuningParameterIds.NATIVE_CONTOUR_FILTER_STRENGTH, strength)
        nativeProcessor.setNativeTuningValue(TuningParameterIds.MIN_CONTOUR_AREA_PX, 200.0)
        nativeProcessor.setNativeTuningValue(
            TuningParameterIds.MIN_CONTOUR_AREA_RATIO_TO_ROI,
            0.0005 + (0.0020 * strength)
        )
        nativeProcessor.setNativeTuningValue(
            TuningParameterIds.MAX_CONTOUR_AREA_RATIO_TO_ROI,
            0.75 - (0.40 * strength)
        )
        nativeProcessor.setNativeTuningValue(
            TuningParameterIds.MAX_THIN_CONTOUR_ASPECT_RATIO,
            12.0 - (8.0 * strength)
        )
        nativeProcessor.setNativeTuningValue(
            TuningParameterIds.MIN_CONTOUR_EXTENT,
            0.08 + (0.12 * strength)
        )
        nativeProcessor.setNativeTuningValue(TuningParameterIds.MIN_CONTOUR_CENTER_Y_RATIO, 0.48)
    }

    private fun applyFootFilterStrength(strength: Double) {
        nativeProcessor.setNativeTuningValue(
            TuningParameterIds.FOOT_BOTTOM_TOUCH_RATIO,
            0.98 - (0.08 * strength)
        )
        nativeProcessor.setNativeTuningValue(
            TuningParameterIds.FOOT_CENTER_Y_RATIO,
            0.82 - (0.20 * strength)
        )
        nativeProcessor.setNativeTuningValue(
            TuningParameterIds.FOOT_MAX_HEIGHT_RATIO,
            0.20 + (0.24 * strength)
        )
    }

    private fun applyServiceTuningValue(parameterId: Int, value: Double): Double {
        return when (parameterId) {
            TuningParameterIds.DANGER_AREA_THRESHOLD -> {
                dangerScoreThreshold = value.coerceAtLeast(0.0)
                dangerScoreThreshold
            }
            TuningParameterIds.BUFFER_CAPACITY -> synchronized(tuningLock) {
                bufferCapacity = value.roundToInt().coerceIn(1, 30)
                if (bufferThreshold > bufferCapacity) bufferThreshold = bufferCapacity
                resetStateBuffer()
                bufferCapacity.toDouble()
            }
            TuningParameterIds.BUFFER_THRESHOLD -> synchronized(tuningLock) {
                bufferThreshold = value.roundToInt().coerceIn(1, bufferCapacity)
                resetStateBuffer()
                bufferThreshold.toDouble()
            }
            else -> value
        }
    }

    private fun resetStateBuffer() {
        stateBuffer = TimeStateBuffer(capacity = bufferCapacity, threshold = bufferThreshold)
    }

    private fun triggerAlert(type: Int) {
        val typeStr = when (type) {
            1 -> "BRAILLE BLOCK"
            2 -> "CROSSWALK"
            3 -> "OBSTACLE"
            else -> "HAZARD"
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(300)
        }

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, buildNotification("DANGER: $typeStr DETECTED!"))
    }

    private fun buildNotification(content: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, 
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Add a "Stop" button to the notification
        val stopIntent = Intent(this, SmombieService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Smombie Safety Radar")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Monitoring", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                channelId,
                "Smombie Radar Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            stopSelf()
            return START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        cameraExecutor.shutdown()
    }

    private fun defaultTuningValue(parameterId: Int): Double {
        return TuningParameters.defaultValue(parameterId)
    }
}
