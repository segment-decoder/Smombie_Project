#include <jni.h>
#include <string>
#include <android/log.h>

// OpenCV 헤더 - CMake에서 include_directories가 성공하면 빨간 줄이 사라집니다.
#include <opencv2/opencv.hpp>

#define LOG_TAG "SmombieArchitect"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern "C" JNIEXPORT jstring JNICALL
Java_kr_ac_yu_smombieproject_MainActivity_checkOpenCV(
        JNIEnv* env,
        jobject /* this */) {

    // OpenCV 네임스페이스 명시적 사용
    std::string info = "OpenCV v";
    info += CV_VERSION;

    // Mat 객체 생성 테스트
    cv::Mat testMat = cv::Mat::zeros(100, 100, CV_8UC1);

    if (!testMat.empty()) {
        return env->NewStringUTF(info.append(" | Engine Ready").c_str());
    } else {
        return env->NewStringUTF("Engine Error");
    }
}