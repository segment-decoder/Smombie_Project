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

/**
 * [스몸비 보행 장애물 감지 시스템 - 중앙 컨트롤러]
 * 5개의 독립된 파이프라인(CameraManager -> SignalFilter -> FeatureExtractor -> SpaceAnalyzer/TimeStateBuffer -> AlertManager)을
 * 하나로 통합하고 비동기 데이터 흐름을 제어하는 메인 액티비티입니다.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var viewFinder: PreviewView
    // [비동기 처리] 영상 분석 전용 백그라운드 스레드 풀. UI 프리징(ANR) 방지를 위한 필수 설계.
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var tvStatus: android.widget.TextView
    private lateinit var tvAreaData: android.widget.TextView
    private lateinit var tvBufferData: android.widget.TextView
    private lateinit var statusCard: com.google.android.material.card.MaterialCardView

    // [파이프라인 1] CameraManager: 안드로이드 하드웨어(카메라) 입력 스트림 제어
    private lateinit var cameraManager: CameraManager

    // [파이프라인 2 & 3] Native C++ 엔진 (요소기술 1 & 2-1 담당)
    // JVM(Kotlin)의 연산 한계를 극복하기 위해 메모리 단에서 영상신호처리를 수행하는 JNI 브릿지
    private val nativeProcessor = NativeProcessor()

    // [파이프라인 4] TimeStateBuffer (요소기술 2-2 담당)
    // [Software Tech 3: Time-series Filtering] Circular queue buffer to suppress noise and flickering.
    // [Core Tech 2-2] Implementation of a time-series state machine for reliability.
    // 단일 프레임의 노이즈(오작동)를 수학적으로 억제하는 시계열 상태 머신 (N=8 프레임 중 M=5 프레임 이상 시 위험 확정)
    private val stateBuffer = TimeStateBuffer(capacity = 8, threshold = 5)

    // [파이프라인 5] AlertManager: 하드웨어 인터럽트(진동) 출력 제어
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

        // 🌟 [시스템 초기화 단계] OpenCV 네이티브 라이브러리 적재 확인 (Fail-Fast 설계)
        // C++ 엔진이 메모리에 올라오지 않으면 앱 구동 자체를 차단하여 런타임 크래시를 방지함.
        if (!OpenCVLoader.initDebug()) {
            Log.e("SmombieProject", "OpenCV 초기화 실패! Native 엔진이 작동하지 않습니다.")
        } else {
            Log.d("SmombieProject", "OpenCV 초기화 성공. 영상신호처리 파이프라인 준비 완료.")
        }

        // 레이아웃 뷰 연결 및 단일 스레드풀(SingleThreadExecutor) 초기화 [요소기술 2: 효율적 리소스 관리]
        // 프레임이 순차적으로(FIFO) 처리되도록 보장하여 데이터 레이스(Data Race) 상태를 방지함.
        viewFinder = findViewById(R.id.viewFinder)
        tvStatus = findViewById(R.id.tvStatus)
        tvAreaData = findViewById(R.id.tvAreaData)
        tvBufferData = findViewById(R.id.tvBufferData)
        statusCard = findViewById(R.id.statusCard)
        cameraExecutor = Executors.newSingleThreadExecutor()

        // [파이프라인 5] 진동 하드웨어 서비스 초기화 (API 레벨에 따른 호환성 분기 처리)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        // [파이프라인 1] CameraManager 초기화: 데이터 스트림 연결 및 콜백 등록
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
     * * [핵심 파이프라인 동작부] 매 프레임(초당 약 30회)마다 호출되는 시스템의 심장부입니다.
     * 메인 스레드가 아닌 cameraExecutor(백그라운드 스레드)에서 동작하여 UI가 멈추지 않습니다.
     */
    private fun processImage(image: ImageProxy) {
        // 1. [데이터 변환] ImageProxy -> OpenCV Mat 변환 (Zero-copy 최적화 활용)
        val mat = ImageUtil.getMatFromImageProxy(image)

        // 2. [요소기술 1] JNI 브릿지를 통한 C++ 영상신호처리 엔진 호출
        // 메모리 객체 전체가 아닌 주소값(nativeObjAddr)만 전달하여 JNI 통신 오버헤드를 O(1)로 최소화.
        val maxArea = nativeProcessor.processFrame(mat.nativeObjAddr)

        // 3. [요소기술 2-2] 시계열 상태 머신 알고리즘 적용 (신뢰성 확보)
        val isDangerous = maxArea > 3500.0 // 1차 면적 기반 위험 판별
        val hazardousResult = stateBuffer.isHazardous(isDangerous) // 2차 시계열 버퍼 통계 기반 최종 판별

        // 4. Update UI: 분석 결과를 실시간으로 화면에 렌더링 (UI 스레드로 컨텍스트 스위칭)
        runOnUiThread {
            updateUI(maxArea, hazardousResult)
        }

        // 5. Trigger vibration alarm: 시계열 버퍼에서 '최종 위험'으로 확정되었을 때만 하드웨어 인터럽트 발생
        if (hazardousResult) {
            triggerVibration()
        }

        // 6. 🚨 [메모리 관리 핵심] Resource Release
        // C++ 네이티브 메모리(Mat)와 CameraX 버퍼(ImageProxy)를 즉시 반환하지 않으면
        // 수 초 내에 Out Of Memory(OOM)가 발생하여 앱이 크래시됨.
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
        // [안전 종료] 생명주기 종료 시 스레드풀의 할당된 자원을 강제 반환하여 메모리 누수 방지
        cameraExecutor.shutdown()
    }
}