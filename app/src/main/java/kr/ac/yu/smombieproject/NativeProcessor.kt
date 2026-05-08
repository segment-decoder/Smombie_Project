package kr.ac.yu.smombieproject

/**
 * [핵심 요소기술 1 & 2-1] 네이티브 신호처리 인터페이스 클래스
 * 안드로이드 시스템과 고성능 C++ 엔진(OpenCV) 사이를 연결하는 JNI(Java Native Interface) 브릿지입니다.
 * * 역할:
 * 1. [요소기술 1] 영상신호처리를 위한 네이티브 라이브러리 로드.
 * 2. [요소기술 2-1] 고해상도 행렬 연산의 시간복잡도를 줄이기 위한 네이티브 함수 호출.
 */
class NativeProcessor {
    companion object {
        init {
            // [엔진 로드] CMakeLists.txt에 정의된 'smombie_native' 라이브러리를 메모리에 적재합니다.
            // Kotlin(JVM)의 한계를 넘어 CPU 레벨의 직접 연산을 가능하게 함으로써 실시간성을 확보합니다.
            System.loadLibrary("smombie_native")
        }
    }

    /**
     * [신호처리 파이프라인 호출] C++로 작성된 영상 분석 로직을 실행합니다.
     * * @param matAddr CameraX로부터 수신한 OpenCV Mat 객체의 메모리 주소(Pointer Address).
     * 객체 전체를 전달하지 않고 주소값만 전달하여 메모리 복사 비용을 최적화함.
     * * @return [요소기술 1]의 형태학적 필터링(Morphology)을 거쳐 최종 산출된
     * 장애물 특징점의 최대 픽셀 면적(Max Area).
     * 이 결과는 이후 [요소기술 2-2] 시계열 알고리즘의 입력 데이터로 활용됨.
     */
    external fun processFrame(matAddr: Long): Double
}

/**
 * 메모리 주소값 전달 matAddr은 이미지 데이터를 통째로 넘기는 대신 JNI 포인터 주소를 전달함으로써 모바일 환경에서
 * 자원을 많이 먹는 메모리 복사 오버헤드를 O(1)로 제어함
 * 네이티브 언어 선정ㅇ 이유는 보행자 안전을 위해 실시간 fps를 유지하며 장애물 검출을 위해
 * 반환값의 의미는 비선형 필터링을 통해 정제된 장애물의 기하학적 특징량
 */