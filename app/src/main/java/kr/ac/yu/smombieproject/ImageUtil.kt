package kr.ac.yu.smombieproject

import androidx.camera.core.ImageProxy
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer

object ImageUtil {
    /**
     * [Core Tech 2-1] Near-zero-copy Mat conversion.
     * Directly converts the Y (Grayscale) plane of ImageProxy to Mat for maximum efficiency.
     * The returned Mat MUST be released after use.
     */
    fun getMatFromImageProxy(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val rowStride = plane.rowStride
        val width = image.width
        val height = image.height

        val mat = Mat(height, width, CvType.CV_8UC1)
        
        if (rowStride == width) {
            val data = ByteArray(buffer.remaining())
            buffer.get(data)
            mat.put(0, 0, data)
        } else {
            val rowData = ByteArray(width)
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(rowData)
                mat.put(row, 0, rowData)
            }
        }
        
        return mat
    }
}
