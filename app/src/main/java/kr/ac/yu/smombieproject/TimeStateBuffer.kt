package kr.ac.yu.smombieproject

/**
 * [핵심 요소기술 2-2] 시계열 상태 머신 (Time-Series State Machine)
 * 단일 프레임의 연산 결과에 의존하지 않고, 최근 N개 프레임의 상태를 추적하여 판단의 신뢰성을 확보하는 모듈입니다.
 * * Data Structure: Circular Queue (원형 큐)
 * Maintains the status of the last N frames to ensure determination reliability.
 * 알고리즘 효율성: 배열 요소를 이동(Shift)시키지 않고 인덱스(head)만 회전시키므로,
 * 삽입/삭제의 시간복잡도가 O(1)로 고정되어 실시간 영상 처리에 병목을 일으키지 않습니다.
 */
class TimeStateBuffer(private val capacity: Int, private val threshold: Int) {
    // N개의 프레임 상태를 저장하는 고정 크기 버퍼 (메모리 재할당 방지)
    private val buffer = BooleanArray(capacity) { false }
    // 원형 큐의 현재 삽입 위치를 가리키는 포인터
    private var head = 0
    // 매번 배열 전체를 순회하지 않기 위해 유지하는 위험 프레임(True) 카운터
    private var hazardCount = 0

    /**
     * 새로운 프레임의 위험 여부를 입력받아 시계열 통계 기반의 최종 위험 여부를 반환합니다.
     * 순간적인 카메라 흔들림이나 빛 반사로 인한 가짜 신호(Flickering Noise)를 억제합니다.
     */
    fun isHazardous(currentFrameStatus: Boolean): Boolean {
        // Handle outgoing value
        // 1. Dequeue: 포인터가 가리키는 가장 오래된 데이터가 True였다면 카운터 감소
        if (buffer[head]) hazardCount--

        // Handle incoming value
        // 2. Enqueue: 현재 프레임의 판별 상태(True/False)를 버퍼에 덮어쓰고 카운터 갱신
        buffer[head] = currentFrameStatus
        if (currentFrameStatus) hazardCount++

        // Circular index increment
        // 3. Modulo(%) 연산을 활용한 포인터 회전 (배열의 끝에 도달하면 처음으로 돌아감)
        head = (head + 1) % capacity

        // Hazard confirmed only when exceeding threshold (M frames)
        // 4. 최종 판단: 최근 N(capacity)개의 프레임 중 M(threshold)개 이상이 위험일 때만 True 반환
        return hazardCount >= threshold
    }

    /**
     * [디버깅 및 시각화용]
     * 현재 버퍼의 상태를 시각적인 문자열로 반환하여 UI에 출력하기 위한 함수입니다.
     * 노이즈가 필터링되고 진짜 위험 상태가 채워지는 과정을 증명합니다. (예: [░░█████░░░])
     */
    fun getBufferString(): String {
        val sb = StringBuilder("[")
        for (i in 0 until capacity) {
            sb.append(if (buffer[i]) "█" else "░")
        }
        sb.append("]")
        return sb.toString()
    }
}

//왜 리스트 안쓰고 원형큐로 했냐면 초당 30프레임 실시간 영상 처리 환경에서 일반 리스트나 시프트연산을 사용하면
//O(n)의 시간 복잡도가 발생해 프레임 드랍의 원인이라 포인터 연산과 누적카운터를 활용해 모든 판단 로직을 O(1)로 최적화