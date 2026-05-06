cmake_minimum_required(VERSION 3.22.1)

# [요소기술 1, 2 구현] 프로젝트 이름 설정
project("smombie_native")

# 1. OpenCV 경로 설정 (스크린샷 기반)
set(OPENCV_LIB_DIR ${CMAKE_SOURCE_DIR}/../../../../opencv/native/jni)
set(OPENCV_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/../../../../opencv/native/jni/include)

# 2. libcpufeatures 참조 오류 우회 (에러의 핵심 해결책)
# OpenCVConfig.cmake를 무시하고 필요한 라이브러리만 직접 추가함
add_library(opencv_java4 SHARED IMPORTED)
set_target_properties(opencv_java4 PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../../../../opencv/native/libs/${ANDROID_ABI}/libopencv_java4.so)

# 3. 네이티브 소스 파일 등록
# [요소기술 1]의 Gaussian Blur, Morphology Closing 로직이 담긴 파일
add_library(smombie_native SHARED native-lib.cpp)

# 4. 헤더 파일 경로 포함
include_directories(${OPENCV_INCLUDE_DIR})

# 5. 라이브러리 링크
# [요소기술 2-1] ROI 마스킹 연산을 위해 OpenCV와 Android 로그 라이브러리를 결합
target_link_libraries(smombie_native
        opencv_java4
        android
        log)