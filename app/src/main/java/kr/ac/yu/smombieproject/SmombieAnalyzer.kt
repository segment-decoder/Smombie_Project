package kr.ac.yu.smombieproject

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer

// 카메라 프레임을 분석하고 Mat 객체로 변환하는 클래스
class SmombieAnalyzer(private val listener: (Mat) -> Unit) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        // 1. YUV 데이터 중 Y(밝기, 흑백) 평면만 추출 (초고속 흑백 변환의 핵심)
        val yBuffer: ByteBuffer = image.planes[0].buffer
        val ySize = yBuffer.remaining()
        val yBytes = ByteArray(ySize)
        yBuffer.get(yBytes)

        // 2. OpenCV용 Mat 객체 생성 (흑백, 1채널)
        val grayMat = Mat(image.height, image.width, CvType.CV_8UC1)
        grayMat.put(0, 0, yBytes)

        // 3. 스마트폰 카메라 센서 방향에 맞게 Mat 행렬 회전
        // (일반적으로 스마트폰을 세로로 들면 90도 회전된 상태로 프레임이 들어옵니다)
        val rotatedMat = Mat()
        when (image.imageInfo.rotationDegrees) {
            90 -> Core.rotate(grayMat, rotatedMat, Core.ROTATE_90_CLOCKWISE)
            270 -> Core.rotate(grayMat, rotatedMat, Core.ROTATE_90_COUNTERCLOCKWISE)
            180 -> Core.rotate(grayMat, rotatedMat, Core.ROTATE_180)
            else -> grayMat.copyTo(rotatedMat)
        }

        // 4. 변환 완료된 흑백 Mat 객체를 메인으로 전달
        listener(rotatedMat)

        // 5. [매우 중요] 메모리 누수 방지
        // C++ 기반인 OpenCV는 가비지 컬렉터가 알아서 안 치워주므로 직접 버려야 합니다.
        grayMat.release()
        image.close() // 카메라 프레임을 닫아줘야 다음 프레임이 들어옵니다.
    }
}