package kr.ac.yu.smombieproject

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tvStatus: android.widget.TextView
    private lateinit var tvAreaData: android.widget.TextView
    private lateinit var tvBufferData: android.widget.TextView
    private lateinit var statusCard: com.google.android.material.card.MaterialCardView

    // [파이프라인 1] 카메라 관리 모듈
    private lateinit var cameraManager: CameraManager

    // [요소기술 1 & 2-1] Native C++ 엔진 연동
    private val nativeProcessor = NativeProcessor()

    // [Core Tech 2-2] Time-series noise suppression algorithm: Circular queue buffer to prevent malfunctions from single-frame errors.
    // capacity: number of recent frames to store, threshold: minimum frames required to confirm a hazard.
    private val stateBuffer = TimeStateBuffer(capacity = 8, threshold = 5)

    private lateinit var vibrator: Vibrator

    // 권한 요청 핸들러 (비동기 처리)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            cameraManager.startCamera()
        } else {
            Toast.makeText(this, "카메라 권한이 거부되어 시스템을 시작할 수 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 🌟 [요소기술 1] 엔진 시동: Native C++ 라이브러리 연동 전 필수 체크
        if (!OpenCVLoader.initDebug()) {
            Log.e("SmombieProject", "OpenCV 초기화 실패! Native 엔진이 작동하지 않습니다.")
        } else {
            Log.d("SmombieProject", "OpenCV 초기화 성공. 영상신호처리 파이프라인 준비 완료.")
        }

        // 레이아웃 뷰 연결 및 스레드풀 초기화 [요소기술 2: 효율적 리소스 관리]
        viewFinder = findViewById(R.id.viewFinder)
        tvStatus = findViewById(R.id.tvStatus)
        tvAreaData = findViewById(R.id.tvAreaData)
        tvBufferData = findViewById(R.id.tvBufferData)
        statusCard = findViewById(R.id.statusCard)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // [파이프라인 5] AlertManager: 진동 하드웨어 서비스 초기화
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // [파이프라인 1] CameraManager 초기화: 데이터 스트림 연결
        cameraManager = CameraManager(this, this, viewFinder, cameraExecutor) { imageProxy ->
            processImage(imageProxy)
        }

        // 시스템 기동: 권한 체크 및 카메라 라이프사이클 바인딩
        if (allPermissionsGranted()) {
            cameraManager.startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /**
     * [Core Analysis Pipeline] Real-time frame processing logic.
     * Passes images from CameraX to the Native engine and reflects results on the UI.
     */
    private fun processImage(image: ImageProxy) {
        // 1. [Data Conversion] Convert ImageProxy to OpenCV Mat object (Memory-efficient management).
        val mat = ImageUtil.getMatFromImageProxy(image)

        // 2. [Core Tech 1 & 2-1] Invoke Native C++ Engine.
        // Calculates the maximum area of obstacles via ROI masking and morphological filtering.
        val maxArea = nativeProcessor.processFrame(mat.nativeObjAddr)

        // 3. [Core Tech 2-2] Final hazard determination via time-series algorithm.
        // Areas above 3500.0 are considered primary hazards and recorded in the time-series buffer.
        val isDangerous = maxArea > 3500.0 
        val hazardousResult = stateBuffer.isHazardous(isDangerous)

        // 4. Update UI: Display analysis results (area, hazard status) on the screen in real-time.
        runOnUiThread {
            updateUI(maxArea, hazardousResult)
        }

        // 5. Trigger vibration alarm when a definitive hazard occurs.
        if (hazardousResult) {
            triggerVibration()
        }

        // 6. Resource Release: Releasing the Mat object is mandatory to prevent Native memory leaks.
        mat.release()
        image.close()
    }

    private fun updateUI(area: Double, isHazardous: Boolean) {
        tvAreaData.text = "Detected Area: ${String.format("%.1f", area)}"
        tvBufferData.text = "Hazard Buffer: ${stateBuffer.getBufferString()}"

        if (isHazardous) {
            tvStatus.text = "DANGER"
            statusCard.setCardBackgroundColor(android.graphics.Color.RED)
        } else if (area > 3000.0) {
            tvStatus.text = "CAUTION"
            statusCard.setCardBackgroundColor(android.graphics.Color.parseColor("#FFA500")) // Orange
        } else {
            tvStatus.text = "SAFE"
            statusCard.setCardBackgroundColor(android.graphics.Color.parseColor("#4CAF50")) // Green
        }
    }

    private fun triggerVibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(200)
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown() // 시스템 종료 시 리소스 반환
    }
}