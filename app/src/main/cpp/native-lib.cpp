#include <jni.h>
#include <opencv2/opencv.hpp>
#include <algorithm>
#include <cmath>
#include <mutex>
#include <vector>

using namespace cv;
using namespace std;

static const int DEBUG_VIEW_NONE = 0;
static const int DEBUG_VIEW_CANNY_INPUT = 1;
static const int DEBUG_VIEW_CANNY = 2;
static const int DEBUG_VIEW_ROI_EDGE = 3;
static const int DEBUG_VIEW_MORPHOLOGY = 4;

static const int TUNING_CANNY_LOW = 1;
static const int TUNING_CANNY_HIGH = 2;
static const int TUNING_MORPH_KERNEL = 3;
static const int TUNING_MIN_CONTOUR_AREA_PX = 4;
static const int TUNING_MIN_CONTOUR_AREA_RATIO_TO_ROI = 5;
static const int TUNING_MAX_CONTOUR_AREA_RATIO_TO_ROI = 6;
static const int TUNING_MAX_THIN_CONTOUR_ASPECT_RATIO = 7;
static const int TUNING_MIN_CONTOUR_EXTENT = 8;
static const int TUNING_MIN_CONTOUR_CENTER_Y_RATIO = 9;
static const int TUNING_FOOT_BOTTOM_TOUCH_RATIO = 10;
static const int TUNING_FOOT_CENTER_Y_RATIO = 11;
static const int TUNING_FOOT_MAX_HEIGHT_RATIO = 12;
static const int TUNING_ILLUMINATION_BLEND = 13;

struct VisionProcessingConfig {
    double cannyLow = 75.0;
    double cannyHigh = 120.0;
    int morphKernelSize = 25;
    double minContourAreaPx = 200.0;
    double minContourAreaRatioToRoi = 0.0015;
    double maxContourAreaRatioToRoi = 0.55;
    double maxThinContourAspectRatio = 8.0;
    double minContourExtent = 0.14;
    double minContourCenterYRatio = 0.48;
    double footBottomTouchRatio = 0.94;
    double footCenterYRatio = 0.72;
    double footMaxHeightRatio = 0.32;
    double illuminationBlend = 0.30;
};

static VisionProcessingConfig g_processingConfig;
static mutex g_processingConfigMutex;

static double clampDouble(double value, double lower, double upper) {
    return max(lower, min(value, upper));
}

static int sanitizeOddKernel(double value) {
    int kernelSize = static_cast<int>(round(value));
    kernelSize = max(1, min(kernelSize, 99));
    if (kernelSize % 2 == 0) kernelSize++;
    return kernelSize;
}

static double getConfigValue(const VisionProcessingConfig& config, int parameterId) {
    switch (parameterId) {
        case TUNING_CANNY_LOW:
            return config.cannyLow;
        case TUNING_CANNY_HIGH:
            return config.cannyHigh;
        case TUNING_MORPH_KERNEL:
            return static_cast<double>(config.morphKernelSize);
        case TUNING_MIN_CONTOUR_AREA_PX:
            return config.minContourAreaPx;
        case TUNING_MIN_CONTOUR_AREA_RATIO_TO_ROI:
            return config.minContourAreaRatioToRoi;
        case TUNING_MAX_CONTOUR_AREA_RATIO_TO_ROI:
            return config.maxContourAreaRatioToRoi;
        case TUNING_MAX_THIN_CONTOUR_ASPECT_RATIO:
            return config.maxThinContourAspectRatio;
        case TUNING_MIN_CONTOUR_EXTENT:
            return config.minContourExtent;
        case TUNING_MIN_CONTOUR_CENTER_Y_RATIO:
            return config.minContourCenterYRatio;
        case TUNING_FOOT_BOTTOM_TOUCH_RATIO:
            return config.footBottomTouchRatio;
        case TUNING_FOOT_CENTER_Y_RATIO:
            return config.footCenterYRatio;
        case TUNING_FOOT_MAX_HEIGHT_RATIO:
            return config.footMaxHeightRatio;
        case TUNING_ILLUMINATION_BLEND:
            return config.illuminationBlend;
        default:
            return 0.0;
    }
}

static VisionProcessingConfig getProcessingConfigSnapshot() {
    lock_guard<mutex> lock(g_processingConfigMutex);
    return g_processingConfig;
}

/**
 * Suppresses broad illumination changes such as shadows before edge extraction.
 * The camera input is a single Y-channel grayscale frame, so this estimates the
 * slow-varying lighting field and keeps local surface structure as reflectance.
 */
static Mat normalizeIllumination(const Mat& gray, double blend) {
    if (gray.empty()) return gray;

    double safeBlend = clampDouble(blend, 0.0, 1.0);
    if (safeBlend <= 0.0) return gray.clone();

    Mat grayFloat;
    gray.convertTo(grayFloat, CV_32F);

    int baseSize = min(gray.cols, gray.rows);
    int kernelSize = min(max(31, baseSize / 12), 121);
    if (kernelSize % 2 == 0) kernelSize++;

    Mat illumination;
    GaussianBlur(grayFloat, illumination, Size(kernelSize, kernelSize), 0);
    illumination += Scalar(1.0f);

    Mat reflectance;
    divide(grayFloat, illumination, reflectance, 128.0);

    Mat corrected;
    reflectance.convertTo(corrected, CV_8UC1);

    if (safeBlend >= 1.0) return corrected;

    Mat blended;
    addWeighted(gray, 1.0 - safeBlend, corrected, safeBlend, 0.0, blended);
    return blended;
}

static bool isLikelyFootContour(
        const Rect& box,
        double centerY,
        int frameHeight,
        const VisionProcessingConfig& config
) {
    double bottom = static_cast<double>(box.y + box.height);
    bool touchesFrameBottom = bottom >= frameHeight * config.footBottomTouchRatio;
    bool isCenteredLow = centerY >= frameHeight * config.footCenterYRatio;
    bool hasLimitedHeight = box.height <= frameHeight * config.footMaxHeightRatio;

    return touchesFrameBottom && isCenteredLow && hasLimitedHeight;
}

static bool isValidHazardContour(
        const vector<Point>& contour,
        double area,
        double roiArea,
        int frameHeight,
        const VisionProcessingConfig& config
) {
    double minArea = max(config.minContourAreaPx, roiArea * config.minContourAreaRatioToRoi);
    if (area < minArea) return false;
    if (area > roiArea * config.maxContourAreaRatioToRoi) return false;

    Rect box = boundingRect(contour);
    if (box.width <= 0 || box.height <= 0) return false;

    double minSide = max(1.0, static_cast<double>(min(box.width, box.height)));
    double maxSide = static_cast<double>(max(box.width, box.height));
    double aspectRatio = maxSide / minSide;

    double boundingArea = max(1.0, static_cast<double>(box.area()));
    double extent = area / boundingArea;

    // 보도블럭 줄눈, 그림자 경계, 도로 차선처럼 길고 얇은 선형 신호는
    // 실제 부피를 가진 위험 후보보다 bounding box 내부가 비어 있는 경우가 많음.
    if (aspectRatio > config.maxThinContourAspectRatio && extent < 0.35) return false;

    // 넓은 사각형 안에 엣지만 듬성듬성 있는 패턴성 바닥 신호를 제거함.
    if (extent < config.minContourExtent) return false;

    Moments m = moments(contour);
    if (fabs(m.m00) < 1e-5) return false;

    double centerY = m.m01 / m.m00;
    if (isLikelyFootContour(box, centerY, frameHeight, config)) return false;

    if (centerY < frameHeight * config.minContourCenterYRatio) return false;

    return true;
}

static double analyzeFrame(Mat& img_input, Mat* debugOutput, int debugMode) {
    if (img_input.empty()) return 0.0;

    VisionProcessingConfig config = getProcessingConfigSnapshot();
    int width = img_input.cols;
    int height = img_input.rows;

    // [요소기술 1] 전처리: 연산량 축소, 조명 보정 및 노이즈 제거
    // RGB 3채널 영상이면 1채널 Grayscale로 변환하고, 단일 Y 채널 입력이면 그대로 사용함.
    Mat img_gray, img_normalized, img_blur, img_edge;
    if (img_input.channels() >= 3) cvtColor(img_input, img_gray, COLOR_RGBA2GRAY);
    else img_gray = img_input.clone();

    // [조명 정규화] 큰 스케일의 밝기 배경을 제거하여 나무 그림자나 햇빛 편차가
    // Canny Edge 단계에서 거대한 가짜 경계로 증폭되는 현상을 완화함.
    img_normalized = normalizeIllumination(img_gray, config.illuminationBlend);

    // Low-pass Filter(가우시안 블러)를 적용하여 일반 아스팔트의 자글자글한 고주파 노이즈를 1차적으로 소거.
    GaussianBlur(img_normalized, img_blur, Size(5, 5), 0);

    // [특징 추출] Canny Edge 문턱값(Threshold) 하드코딩의 공학적 이유:
    // 일반적인 바닥의 그림자나 옅은 얼룩(Low-magnitude)은 무시하고, 점자 블록처럼 명암 대비가 확실한
    // 물리적 요철(장애물)만 추출하기 위해 실측 튜닝값인 (75, 120)을 기본값으로 사용함.
    Canny(img_blur, img_edge, config.cannyLow, config.cannyHigh);

    // [요소기술 2-1: 공간 효율성 최적화] 사다리꼴 ROI(관심 영역) 마스킹
    // 화면 전체(N x M)를 연산하지 않고, 보행자의 실제 발밑 영역(하단 중앙부)만 마스킹함.
    // 하늘이나 먼 배경에서 발생하는 쓰레기 데이터(가짜 엣지)를 O(1) 수준에서 원천 차단하여 시스템 신뢰성과 FPS를 동시에 확보.
    Mat mask = Mat::zeros(img_edge.size(), CV_8UC1);
    vector<Point> roi_points = {
            Point(width * 0.10, height),
            Point(width * 0.90, height),
            Point(width * 0.70, height * 0.50),
            Point(width * 0.30, height * 0.50)
    };
    double roiArea = contourArea(roi_points);
    fillPoly(mask, {roi_points}, Scalar(255));

    // 추출된 전체 엣지 영상에 사다리꼴 마스크를 씌워 불필요한 엣지를 제거
    Mat masked_edge = Mat::zeros(img_edge.size(), CV_8UC1);
    img_edge.copyTo(masked_edge, mask);

    // [요소기술 1] 비선형 형태학적 조작 (Morphological Closing)
    // Canny로 추출된 점자 블록의 엣지는 얇은 선이나 점으로 파편화되어 있음.
    // Size(25, 25)라는 거대한 커널을 사용하여, 끊어진 엣지들을 강제로 팽창(Dilation)시켜 하나로 뭉친 뒤
    // 다시 침식(Erosion)하여 거대한 기하학적 덩어리(Blob) 데이터로 변환함.
    Mat img_morph;
    Mat kernel = getStructuringElement(
            MORPH_RECT,
            Size(config.morphKernelSize, config.morphKernelSize)
    );
    morphologyEx(masked_edge, img_morph, MORPH_CLOSE, kernel);

    if (debugOutput != nullptr) {
        if (debugMode == DEBUG_VIEW_CANNY_INPUT) {
            img_blur.copyTo(*debugOutput);
        } else if (debugMode == DEBUG_VIEW_CANNY) {
            img_edge.copyTo(*debugOutput);
        } else if (debugMode == DEBUG_VIEW_ROI_EDGE) {
            masked_edge.copyTo(*debugOutput);
        } else if (debugMode == DEBUG_VIEW_MORPHOLOGY) {
            img_morph.copyTo(*debugOutput);
        }
    }

    // 기하학적 면적(Area) 산출 로직
    vector<vector<Point>> contours;
    findContours(img_morph, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

    // 화면(사다리꼴 내부)에 존재하는 여러 덩어리 중,
    // 노이즈 필터를 통과한 가장 거대한(위협적인) 덩어리의 면적만 추출.
    double maxArea = 0;
    for (const auto& contour : contours) {
        double area = contourArea(contour);
        if (!isValidHazardContour(contour, area, roiArea, height, config)) continue;
        if (area > maxArea) maxArea = area;
    }

    // 이 maxArea(단일 스칼라 값)는 Kotlin의 [요소기술 2-2: 시계열 버퍼]로 넘어가서 최종 위험 판단의 재료로 사용됨.
    return maxArea;
}

extern "C"
JNIEXPORT jdouble JNICALL
Java_kr_ac_yu_smombieproject_NativeProcessor_processFrame(
        JNIEnv *env, jobject thiz, jlong matAddrInput) {

    // [메모리 접근] JNI 포인터를 통해 Kotlin에서 넘어온 카메라 프레임 메모리에 직접 접근(Zero-copy)
    Mat &img_input = *(Mat *) matAddrInput;
    return (jdouble) analyzeFrame(img_input, nullptr, DEBUG_VIEW_NONE);
}

extern "C"
JNIEXPORT jdouble JNICALL
Java_kr_ac_yu_smombieproject_NativeProcessor_processDebugFrame(
        JNIEnv *env, jobject thiz, jlong matAddrInput, jlong matAddrOutput, jint debugMode) {

    Mat &img_input = *(Mat *) matAddrInput;
    Mat &img_output = *(Mat *) matAddrOutput;

    return (jdouble) analyzeFrame(img_input, &img_output, debugMode);
}

extern "C"
JNIEXPORT jdouble JNICALL
Java_kr_ac_yu_smombieproject_NativeProcessor_setNativeTuningValue(
        JNIEnv *env, jobject thiz, jint parameterId, jdouble value) {

    lock_guard<mutex> lock(g_processingConfigMutex);

    switch (parameterId) {
        case TUNING_CANNY_LOW:
            g_processingConfig.cannyLow = clampDouble(value, 0.0, 499.0);
            break;
        case TUNING_CANNY_HIGH:
            g_processingConfig.cannyHigh = clampDouble(value, 1.0, 500.0);
            break;
        case TUNING_MORPH_KERNEL:
            g_processingConfig.morphKernelSize = sanitizeOddKernel(value);
            break;
        case TUNING_MIN_CONTOUR_AREA_PX:
            g_processingConfig.minContourAreaPx = clampDouble(value, 0.0, 50000.0);
            break;
        case TUNING_MIN_CONTOUR_AREA_RATIO_TO_ROI:
            g_processingConfig.minContourAreaRatioToRoi = clampDouble(value, 0.0, 1.0);
            break;
        case TUNING_MAX_CONTOUR_AREA_RATIO_TO_ROI:
            g_processingConfig.maxContourAreaRatioToRoi = clampDouble(value, 0.0, 1.0);
            break;
        case TUNING_MAX_THIN_CONTOUR_ASPECT_RATIO:
            g_processingConfig.maxThinContourAspectRatio = clampDouble(value, 1.0, 50.0);
            break;
        case TUNING_MIN_CONTOUR_EXTENT:
            g_processingConfig.minContourExtent = clampDouble(value, 0.0, 1.0);
            break;
        case TUNING_MIN_CONTOUR_CENTER_Y_RATIO:
            g_processingConfig.minContourCenterYRatio = clampDouble(value, 0.0, 1.0);
            break;
        case TUNING_FOOT_BOTTOM_TOUCH_RATIO:
            g_processingConfig.footBottomTouchRatio = clampDouble(value, 0.0, 1.0);
            break;
        case TUNING_FOOT_CENTER_Y_RATIO:
            g_processingConfig.footCenterYRatio = clampDouble(value, 0.0, 1.0);
            break;
        case TUNING_FOOT_MAX_HEIGHT_RATIO:
            g_processingConfig.footMaxHeightRatio = clampDouble(value, 0.0, 1.0);
            break;
        case TUNING_ILLUMINATION_BLEND:
            g_processingConfig.illuminationBlend = clampDouble(value, 0.0, 1.0);
            break;
        default:
            return 0.0;
    }

    if (g_processingConfig.cannyHigh <= g_processingConfig.cannyLow) {
        g_processingConfig.cannyHigh = min(500.0, g_processingConfig.cannyLow + 1.0);
    }

    return (jdouble) getConfigValue(g_processingConfig, parameterId);
}

extern "C"
JNIEXPORT jdouble JNICALL
Java_kr_ac_yu_smombieproject_NativeProcessor_getNativeTuningValue(
        JNIEnv *env, jobject thiz, jint parameterId) {

    lock_guard<mutex> lock(g_processingConfigMutex);
    return (jdouble) getConfigValue(g_processingConfig, parameterId);
}
/*
 *저희 엔진은 철저히 모바일 환경의 최적화를 고려해 설계되었습니다.
첫째, 공간 효율성을 위해 사다리꼴 ROI를 적용하여 하늘과 배경 연산을 배제했습니다.
둘째, 단순히 엣지를 찾는 것에 그치지 않고, Size(25, 25)의 대형 커널을 활용한 Morphological Closing 연산을 거쳐
 파편화된 점자 블록 신호를 하나의 거대한 객체(Blob)로 병합했습니다.
셋째, 이 모든 복잡한 행렬 연산의 결과를 안드로이드 메인 메모리로 전부 복사하지 않고,
 오직 최대 면적(maxArea)이라는 단 하나의 Double 스칼라 값만 JNI를 통해 Kotlin으로 전달하여 통신 오버헤드를 제로에 가깝게 만들었습니다." */
