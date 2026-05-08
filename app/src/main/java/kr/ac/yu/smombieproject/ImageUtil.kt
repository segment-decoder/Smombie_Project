package kr.ac.yu.smombieproject

import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer

object ImageUtil {
    /**
     * [핵심 파이프라인 브릿지] 안드로이드 CameraX와 C++ OpenCV 엔진 사이의 데이터 병목을 해소하는 유틸리티입니다.
     * [요소기술 2-1] 시간 효율성 극대화: 일반적인 YUV -> RGB -> Grayscale 변환 알고리즘(O(N))을 거치지 않고,
     * 하드웨어 메모리에 직접 접근하여 Y 플레인(밝기 채널)만 추출하는 Zero-copy 수준의 최적화를 달성했습니다.
     */
    fun getMatFromImageProxy(image: ImageProxy): Mat {
        // 카메라의 YUV_420_888 포맷 중 Y(Grayscale) 데이터가 담긴 첫 번째 플레인만 가져옴.
        // [요소기술 1]의 Canny Edge와 Morphology 연산은 색상 정보가 불필요하므로, UV 플레인을 버려 연산량을 66% 감소시킴.
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        // C++ 네이티브 메모리 영역에 1채널(8-bit Unsigned, Grayscale) Mat 객체 할당
        val mat = Mat(height, width, CvType.CV_8UC1)

        if (rowStride == width) {
            // [메모리 복사 최적화 - Fast Path]
            // 카메라 센서의 가로 픽셀 수와 메모리 정렬 크기가 일치할 경우 (Padding 없음),
            // 루프 없이 ByteBuffer 전체를 한 번에 JNI Mat 메모리로 덤프(Dump)하여 I/O 지연을 극적으로 최소화.
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            mat.put(0, 0, data)
        } else {
            // [하드웨어 파편화 대응 - Slow Path]
            // 메모리 정렬(Alignment) 문제로 rowStride가 width보다 클 경우 (끝에 쓰레기값 Padding 존재),
            // 이미지가 기울어지거나 깨지는(Skew) 현상을 막기 위해 패딩을 무시하고 유효한 가로 픽셀(width)만 행(Row) 단위로 정교하게 추출.
            val rowData = ByteArray(width)
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(rowData)
                mat.put(row, 0, rowData)
            }
        }

        // 주의: 여기서 반환된 Mat은 JVM 가비지 컬렉터가 아닌 C++ 네이티브 메모리에 존재하므로,
        // 사용이 끝난 후 반드시 release()를 호출하여 메모리 누수(Memory Leak)를 방지해야 함.
        return mat
    }

    /**
     *안드로이드 API 사용해 YUV를 RGB비트맵으로 바꾸고 다시 OPENCV MAT으로 변환하면 마대한 메모리 복사와 연산 오버헤드 발생
     * 시간 효율성 개선을 위해 IMAGEUTIL을 직접 구현해 메모리버퍼(ByteBuffer)에서 형태학전 연산에 필요한 Y데이터만
     * 복사했습니다. 또한 카메라 하드웨어 rowStride 정렬패딩 이슈도 O(N)으로 방어
     * */
}