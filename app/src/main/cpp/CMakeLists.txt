cmake_minimum_required(VERSION 3.22.1)

# [요소기술 1, 2 구현] 프로젝트 이름 설정
project("smombie_native")

# 1. OpenCV 경로 설정 (스크린샷 기반)
# app/src/main/cpp 기준이므로 4단계 위가 프로젝트 루트입니다.
set(OPENCV_BASE_DIR ${CMAKE_SOURCE_DIR}/../../../../opencv/native)
set(OPENCV_INCLUDE_DIR ${OPENCV_BASE_DIR}/jni/include)
set(OPENCV_LIBS_DIR ${OPENCV_BASE_DIR}/libs/${ANDROID_ABI})

# 2. [에러 원천 차단] libcpufeatures 참조 오류 우회
# find_package를 쓰지 않으므로 libcpufeatures 오류가 발생할 틈이 없음
add_library(lib_opencv SHARED IMPORTED)
set_target_properties(lib_opencv PROPERTIES IMPORTED_LOCATION ${OPENCV_LIBS_DIR}/libopencv_java4.so)

# 3. 네이티브 소스 파일 등록
# [요소기술 1]의 Gaussian Blur, Morphology Closing 로직이 담긴 파일
add_library(smombie_native SHARED native-lib.cpp)

# 4. 헤더 파일 경로 포함
include_directories(${OPENCV_INCLUDE_DIR})

# 5. 라이브러리 링크
# [요소기술 2-1] ROI 마스킹 연산을 위해 OpenCV와 Android 로그 라이브러리를 결합
target_link_libraries(smombie_native
        lib_opencv
        android
        log)