package kr.ac.yu.smombieproject

/**
 * Data Structure: Circular Queue
 * Maintains the status of the last N frames to ensure determination reliability.
 */
class TimeStateBuffer(private val capacity: Int, private val threshold: Int) {
    private val buffer = BooleanArray(capacity) { false }
    private var head = 0
    private var hazardCount = 0

    fun isHazardous(currentFrameStatus: Boolean): Boolean {
        // Handle outgoing value
        if (buffer[head]) hazardCount--

        // Handle incoming value
        buffer[head] = currentFrameStatus
        if (currentFrameStatus) hazardCount++

        // Circular index increment
        head = (head + 1) % capacity

        // Hazard confirmed only when exceeding threshold (M frames)
        return hazardCount >= threshold
    }

    fun getBufferString(): String {
        val sb = StringBuilder("[")
        for (i in 0 until capacity) {
            sb.append(if (buffer[i]) "█" else "░")
        }
        sb.append("]")
        return sb.toString()
    }
}