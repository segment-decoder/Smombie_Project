package kr.ac.yu.smombieproject

/**
 * [Core Tech 1 & 2-1] Native Interface for C++ Engine
 * Bridges the Kotlin application with the high-performance C++ signal processing library.
 */
class NativeProcessor {
    companion object {
        init {
            // Must match the library name defined in CMakeLists.txt
            System.loadLibrary("smombie_native")
        }
    }

    /**
     * Invokes the C++ processing logic.
     * @param matAddr Memory address of the OpenCV Mat object.
     * @return The maximum area of detected hazardous objects.
     */
    external fun processFrame(matAddr: Long): Double
}
