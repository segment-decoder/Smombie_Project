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

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private lateinit var viewFinder: PreviewView

    // ⚙️ 우리가 만든 핵심 3대장 모듈!
    private lateinit var alertManager: AlertManager
    private val signalFilter = SignalFilter()
    private val dangerAnalyzer = DangerAnalyzer() // 🌟 판사 등판!

    // 진동 쿨타임 관리용 변수 (너무 자주 울리지 않도록)
    private var lastVibrateTime = 0L

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

        alertManager = AlertManager(this)
        viewFinder = findViewById(R.id.viewFinder)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(viewFinder.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            // 🔥 [대망의 완성된 파이프라인] 🔥
            imageAnalysis.setAnalyzer(cameraExecutor, SmombieAnalyzer { mat ->

                // 1단계: 팀원 C (점자 블록 노이즈 뭉개기)
                val filteredMat = signalFilter.applyClosingFilter(mat)

                // 2단계: 팀원 B (면적 계산 및 위험 판단)
                val isDangerNow = dangerAnalyzer.isDanger(filteredMat)

                // 3단계: 팀원 A (위험하면 진동 울리기!)
                if (isDangerNow) {
                    val currentTime = System.currentTimeMillis()
                    // 마지막으로 울린 지 2초(2000ms)가 지났을 때만 다시 울림
                    if (currentTime - lastVibrateTime > 2000) {
                        Log.w("SmombieWarning", "🚨 위험 감지! 진동 발생 🚨")
                        alertManager.playDangerVibration()
                        lastVibrateTime = currentTime
                    }
                }

                // 다 쓴 메모리는 반드시 반납!
                filteredMat.release()
                mat.release()
            })

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

    // 터치 테스트 코드는 이제 지우셔도 되지만, 폰 확인용으로 남겨둡니다.
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            alertManager.playDangerVibration()
        }
        return super.onTouchEvent(event)
    }
}