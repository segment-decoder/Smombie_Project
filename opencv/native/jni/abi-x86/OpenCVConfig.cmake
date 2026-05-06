cmake_minimum_required(VERSION 3.22.1)

project("smombie_native")

# 1. OpenCV 경로 정의 (프로젝트 구조에 맞춰 정확히 설정)
# app/src/main/cpp 기준이므로 4단계 위가 루트입니다.
set(OPENCV_LIB_PATH ${CMAKE_SOURCE_DIR}/../../../../opencv/native/libs/${ANDROID_ABI}/libopencv_java4.so)
set(OPENCV_INCLUDE_PATH ${CMAKE_SOURCE_DIR}/../../../../opencv/native/jni/include)

# 2. OpenCV 라이브러리 수동 등록 (find_package 절대 사용 금지)
add_library(lib_opencv SHARED IMPORTED)
set_target_properties(lib_opencv PROPERTIES IMPORTED_LOCATION ${OPENCV_LIB_PATH})

# 3. [요소기술 1, 2-1] 구현용 네이티브 소스 등록
add_library(smombie_native SHARED native-lib.cpp)

# 4. 헤더 경로 포함
include_directories(${OPENCV_INCLUDE_PATH})

# 5. 최종 링크 (log와 android 라이브러리는 하드웨어 제어 및 디버깅용)
target_link_libraries(smombie_native
        lib_opencv
        android
        log)