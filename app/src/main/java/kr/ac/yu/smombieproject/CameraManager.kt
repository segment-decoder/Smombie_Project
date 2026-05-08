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
 * [핵심 파이프라인 1단계] CameraManager (입력부)
 * 스마트폰 하드웨어 센서(카메라)로부터 실시간 영상 데이터를 수집하는 역할을 담당합니다.
 * * 역할:
 * 1. CameraX API를 활용하여 렌더링용 스트림(Preview)과 연산용 스트림(ImageAnalysis)을 분리.
 * 2. [비동기 파이프라인 구축] 수집된 프레임을 별도의 스레드(Executor)로 넘겨 메인 UI 스레드의 부하를 방지함.
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView,
    private val executor: ExecutorService,
    private val onAnalyze: (ImageProxy) -> Unit
) {
    /**
     * 하드웨어 카메라를 초기화하고 생명주기(Lifecycle)에 바인딩합니다.
     * 메모리 누수(Memory Leak)를 방지하기 위해 액티비티의 생명주기와 동기화하여 작동합니다.
     */
    fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // 1. 프리뷰 뷰(Preview) 설정: 사용자에게 보여지는 화면 렌더링 담당
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // 2. [요소기술 2-1: 알고리즘 시간 효율성] 영상 분석기(ImageAnalysis) 설정
            // C++ 네이티브 엔진의 연산 속도가 카메라 프레임 생성 속도(보통 30FPS)를 따라가지 못할 경우,
            // 큐(Queue)에 프레임이 쌓여 Out Of Memory(OOM)가 발생하거나 심각한 딜레이가 생깁니다.
            // 이를 방지하기 위해 STRATEGY_KEEP_ONLY_LATEST 속성을 적용하여,
            // 처리하지 못한 과거의 프레임은 버리고 항상 '최신 프레임'만 분석하도록 강제(Drop)하여 실시간성(Real-time)을 확보합니다.
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executor) { imageProxy ->
                        onAnalyze(imageProxy) // [파이프라인 2단계] SignalFilter로 데이터 전달
                    }
                }

            // 기본 후면 카메라(Default Back Camera) 선택
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // 중복 바인딩을 방지하기 위해 기존 UseCase 자원 해제 (안정성 확보)
                cameraProvider.unbindAll()
                // 카메라 하드웨어, 프리뷰, 분석기를 생명주기에 묶어 동시에 구동
                cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, preview, imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e("CameraManager", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(context))
    }
}