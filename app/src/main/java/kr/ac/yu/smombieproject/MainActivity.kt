package kr.ac.yu.smombieproject

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    // 전역 변수 선언
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var viewFinder: PreviewView
    private lateinit var alertManager: AlertManager

    // 🌟 [추가 1] 팀원 C의 필터 모듈 장착 완료!
    private val signalFilter = SignalFilter()

    // 권한 요청 런처
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 알람 매니저 객체 생성 및 뷰파인더 연결
        alertManager = AlertManager(this)
        viewFinder = findViewById(R.id.viewFinder)

        // 권한 확인 및 요청
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 화면에 띄우는 프리뷰 설정
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            // OpenCV로 넘겨줄 실시간 프레임 분석기 설정
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            // 🌟 [추가 2] 파이프라인 연동 부분 (완벽하게 잘하셨습니다!)
            imageAnalysis.setAnalyzer(cameraExecutor, SmombieAnalyzer { mat ->

                // 1. 카메라에서 들어온 원본 흑백 Mat을 필터 기계에 넣습니다.
                val filteredMat = signalFilter.applyClosingFilter(mat)

                // 2. 로그로 잘 변환되었는지 확인합니다.
                Log.d("CameraManager", "원본: ${mat.width()}x${mat.height()} -> 필터링 완료!")

                // 3. [매우 중요] 다 쓴 Mat 객체는 무조건 메모리에서 해제해야 앱이 안 튕깁니다.
                filteredMat.release() // 필터링된 이미지 버리기
                mat.release()         // 원본 이미지 버리기
            })

            // 후면 카메라 기본 설정
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
            } catch (exc: Exception) {
                Log.e("CameraManager", "카메라 바인딩 실패", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    // 진동 테스트용 터치 이벤트
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            Log.d("AlertManager", "화면 터치 감지! 진동 테스트 시작")
            alertManager.playDangerVibration()
        }
        return super.onTouchEvent(event)
    }
}