cmake_minimum_required(VERSION 3.22.1)
project("smombieproject")

# [보정] 현재 파일 위치(app/src/main/cpp)에서 프로젝트 루트로 이동 후 opencv 모듈 접근
set(OPENCV_LIB_TYPE SHARED)
set(OPENCV_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../opencv/native)

# 1. 헤더 경로 설정 (opencv2 폴더가 들어있는 include 폴더를 가리켜야 함)
set(OPENCV_INCLUDE_DIR ${OPENCV_DIR}/jni/include)
include_directories(${OPENCV_INCLUDE_DIR})

# 2. 라이브러리 파일(.so) 경로 설정
# ${ANDROID_ABI}는 arm64-v8a, armeabi-v7a 등을 자동으로 매칭합니다.
set(OPENCV_SO_PATH ${OPENCV_DIR}/libs/${ANDROID_ABI}/libopencv_java4.so)

# 3. OpenCV 라이브러리 등록
add_library(lib_opencv SHARED IMPORTED)
set_target_properties(lib_opencv PROPERTIES IMPORTED_LOCATION ${OPENCV_SO_PATH})

# 4. 메인 라이브러리 빌드 (native-lib.cpp)
add_library(${CMAKE_PROJECT_NAME} SHARED native-lib.cpp)

# 5. 링크 (안드로이드 로그 및 OpenCV)
target_link_libraries(${CMAKE_PROJECT_NAME}
        lib_opencv
        android
        log)