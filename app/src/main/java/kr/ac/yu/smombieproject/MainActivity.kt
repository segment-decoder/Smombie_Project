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

    // [파이프라인 1] 카메라 관리 모듈
    private lateinit var cameraManager: CameraManager

    // [요소기술 1 & 2-1] Native C++ 엔진 연동
    private val nativeProcessor = NativeProcessor()

    // [요소기술 2-2] 시계열 노이즈 억제: 원형 큐 기반 알고리즘 설계
    private val stateBuffer = TimeStateBuffer(capacity = 10, threshold = 7)

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
     * [핵심 분석 파이프라인] 실시간 프레임 처리
     */
    private fun processImage(image: ImageProxy) {
        // 1. [데이터 변환] ImageProxy에서 Native 주소 추출 (제로 복사 지향)
        val matAddr = ImageUtil.getNativeAddrFromImageProxy(image)

        // 2. [요소기술 1 & 2-1] Native C++ 호출
        // 하단 사다리꼴 ROI 내에서 Gaussian Blur 및 Morphology Closing 연산 수행
        val maxArea = nativeProcessor.processFrame(matAddr)

        // 3. [요소기술 2-2] 시계열 알고리즘 적용
        // 단일 프레임 오작동(Flickering)을 원형 큐 버퍼로 필터링하여 신뢰성 확보
        val isDangerous = maxArea > 5000.0 // 임계값 기반 1차 판별

        if (stateBuffer.isHazardous(isDangerous)) {
            // 4. [파이프라인 5] 최종 위험 확정 시 진동 출력
            triggerVibration()
        }

        // 프레임 자원 즉시 해제 (Native 메모리 누수 방지 필수)
        image.close()
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