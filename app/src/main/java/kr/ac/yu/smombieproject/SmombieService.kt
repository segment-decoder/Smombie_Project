package kr.ac.yu.smombieproject

import android.app.*
import android.content.Intent
import android.os.*
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.Executors

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
    private val stateBuffer = TimeStateBuffer(capacity = 8, threshold = 5)

    // Bridge for UI communication
    interface ServiceCallback {
        fun onDataUpdated(area: Double, isHazardous: Boolean, type: Int)
    }
    
    private var callback: ServiceCallback? = null

    companion object {
        private var instance: SmombieService? = null
        
        fun setPreview(view: PreviewView?) {
            instance?.cameraManager?.setPreviewView(view)
        }
        
        fun setListener(cb: ServiceCallback?) {
            instance?.callback = cb
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

        createNotificationChannel()
        startForeground(notificationId, buildNotification("Radar is scanning for hazards..."))
        
        cameraManager.startCamera()
    }

    private fun processImage(image: ImageProxy) {
        val mat = ImageUtil.getMatFromImageProxy(image)
        val result = nativeProcessor.processFrame(mat.nativeObjAddr)
        
        val objectType = (result / 1000000).toInt()
        val area = result % 1000000

        val isDangerous = area > 3500.0
        val hazardousResult = stateBuffer.isHazardous(isDangerous)

        // Notify Activity if attached
        callback?.onDataUpdated(area, hazardousResult, objectType)

        if (hazardousResult) {
            triggerAlert(objectType)
        }

        mat.release()
        image.close()
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
}
