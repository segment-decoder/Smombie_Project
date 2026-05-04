package kr.ac.yu.smombieproject

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.imgproc.Imgproc

class DangerAnalyzer {

    // 🚨 위험 기준치 (화면의 20% 이상을 장애물이 차지하면 코앞에 있다고 판단!)
    // 이 수치는 나중에 직접 점자 블록을 비춰보면서 조절해야 합니다.
    private val DANGER_THRESHOLD_RATIO = 0.20

    fun isDanger(filteredMat: Mat): Boolean {
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()

        // 1. 뭉개진 덩어리(필터링된 이미지)에서 외곽선(Contour)들을 모두 찾습니다.
        Imgproc.findContours(
            filteredMat,
            contours,
            hierarchy,
            Imgproc.RETR_EXTERNAL, // 바깥쪽 껍데기 윤곽선만 찾기
            Imgproc.CHAIN_APPROX_SIMPLE
        )

        var maxArea = 0.0

        // 2. 찾아낸 윤곽선들 중 '가장 거대한 덩어리'의 면적을 구합니다.
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > maxArea) {
                maxArea = area
            }
        }

        // [중요] C++ 메모리 누수 방지
        hierarchy.release()
        for (contour in contours) {
            contour.release()
        }

        // 3. 화면 전체 면적 계산 (가로 x 세로)
        val totalScreenArea = (filteredMat.width() * filteredMat.height()).toDouble()

        // 4. 가장 큰 장애물이 화면에서 차지하는 비율 계산
        val obstacleRatio = maxArea / totalScreenArea

        android.util.Log.d("DangerAnalyzer", "장애물 크기 비율: ${String.format("%.2f", obstacleRatio * 100)}%")

        // 5. 기준치(20%)를 넘으면 "위험해(true)!" 라고 판결을 내립니다.
        return obstacleRatio >= DANGER_THRESHOLD_RATIO
    }
}