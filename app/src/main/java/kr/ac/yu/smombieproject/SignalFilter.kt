package kr.ac.yu.smombieproject

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

class SignalFilter {

    // 팀원 A(관우님)가 넘겨준 흑백(Grayscale) 이미지를 받아서 필터링합니다.
    fun applyClosingFilter(inputGrayMat: Mat): Mat {
        val resultMat = Mat()

        // 1. 노이즈 제거 (가우시안 블러)
        // 5x5 크기의 필터로 자잘한 픽셀 노이즈를 1차적으로 부드럽게 깎아냅니다.
        Imgproc.GaussianBlur(inputGrayMat, resultMat, Size(5.0, 5.0), 0.0)

        // 2. 모폴로지 커널(마스크) 생성
        // 15x15 크기로 뭉갭니다. (나중에 현장 테스트할 때 이 숫자를 홀수로 조절하며 최적화해야 합니다!)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(15.0, 15.0))

        // 3. [핵심] 모폴로지 닫기(Closing) 연산
        // 팽창(Dilation) 후 침식(Erosion)을 거쳐 쪼개진 점자 블록 패턴들의 구멍을 메워 하나의 객체로 만듭니다.
        Imgproc.morphologyEx(resultMat, resultMat, Imgproc.MORPH_CLOSE, kernel)

        // 메모리 누수 방지 (다 쓴 커널은 버려줍니다)
        kernel.release()

        return resultMat
    }
}