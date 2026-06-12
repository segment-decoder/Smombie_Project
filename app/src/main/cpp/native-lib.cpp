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
static const int TUNING_CONTOUR_FILTER_STRENGTH = 14;
static const int TUNING_JOINT_SUPPRESS_STRENGTH = 15;

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
    double contourFilterStrength = 0.50;
    // [보도블럭 줄눈 억제] 통합 강도(0~1). 0이면 완전 비활성(기존 동작과 동일),
    // 1이면 최대 활성. 규칙적 평행 직선(줄눈)에만 보조 점수 가산을 막고 소폭 감점한다.
    // erase는 하지 않으므로 연석·계단·큰 장애물의 엣지는 그대로 모폴로지로 전달된다.
    double jointSuppressStrength = 0.50;
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
        case TUNING_CONTOUR_FILTER_STRENGTH:
            return config.contourFilterStrength;
        case TUNING_JOINT_SUPPRESS_STRENGTH:
            return config.jointSuppressStrength;
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

struct ContourScoreResult {
    double auxiliaryScore = 0.0;
};

static double pointToSegmentDistance(const Point& point, const Point& segmentStart, const Point& segmentEnd) {
    double vx = static_cast<double>(segmentEnd.x - segmentStart.x);
    double vy = static_cast<double>(segmentEnd.y - segmentStart.y);
    double wx = static_cast<double>(point.x - segmentStart.x);
    double wy = static_cast<double>(point.y - segmentStart.y);

    double segmentLengthSquared = vx * vx + vy * vy;
    if (segmentLengthSquared <= 1e-5) {
        double dx = static_cast<double>(point.x - segmentStart.x);
        double dy = static_cast<double>(point.y - segmentStart.y);
        return sqrt(dx * dx + dy * dy);
    }

    double t = clampDouble((wx * vx + wy * vy) / segmentLengthSquared, 0.0, 1.0);
    double projectionX = static_cast<double>(segmentStart.x) + t * vx;
    double projectionY = static_cast<double>(segmentStart.y) + t * vy;
    double dx = static_cast<double>(point.x) - projectionX;
    double dy = static_cast<double>(point.y) - projectionY;
    return sqrt(dx * dx + dy * dy);
}

static bool isNearRoiEntryBoundary(
        const vector<Point>& contour,
        const vector<Point>& roiPoints,
        double boundaryDistancePx
) {
    if (roiPoints.size() < 4) return false;

    const Point& bottomLeft = roiPoints[0];
    const Point& bottomRight = roiPoints[1];
    const Point& topRight = roiPoints[2];
    const Point& topLeft = roiPoints[3];

    for (const Point& point : contour) {
        double topDistance = pointToSegmentDistance(point, topLeft, topRight);
        double leftDistance = pointToSegmentDistance(point, bottomLeft, topLeft);
        double rightDistance = pointToSegmentDistance(point, bottomRight, topRight);
        if (topDistance <= boundaryDistancePx ||
            leftDistance <= boundaryDistancePx ||
            rightDistance <= boundaryDistancePx) {
            return true;
        }
    }

    return false;
}

static ContourScoreResult scoreAndFilterRoiEdgeContours(
        const Mat& roiEdge,
        Mat& filteredRoiEdge,
        const vector<Point>& roiPoints,
        double roiArea,
        int frameWidth,
        int frameHeight,
        const VisionProcessingConfig& config
) {
    roiEdge.copyTo(filteredRoiEdge);

    ContourScoreResult result;
    Mat contourInput = roiEdge.clone();
    vector<vector<Point>> contours;
    findContours(contourInput, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

    if (contours.empty()) return result;

    const double strength = clampDouble(config.contourFilterStrength, 0.0, 1.0);
    const int gridCols = 6;
    const int gridRows = 6;
    const int gridSize = gridCols * gridRows;
    int densityGrid[gridSize] = {0};

    vector<int> contourGridIndex(contours.size(), -1);
    vector<Rect> contourBoxes(contours.size());

    const double smallTextureAreaLimit = max(24.0, roiArea * (0.0014 - 0.0005 * strength));
    const double smallTextureBoxLimit = max(36.0, roiArea * (0.0060 - 0.0020 * strength));
    const double smallTextureSideLimit = min(frameWidth, frameHeight) * (0.18 - 0.04 * strength);

    for (size_t i = 0; i < contours.size(); ++i) {
        const vector<Point>& contour = contours[i];
        if (contour.empty()) continue;

        Rect box = boundingRect(contour);
        if (box.width <= 0 || box.height <= 0) continue;

        double area = fabs(contourArea(contour));
        contourBoxes[i] = box;

        double maxSide = static_cast<double>(max(box.width, box.height));
        bool smallTextureCandidate =
                area <= smallTextureAreaLimit &&
                static_cast<double>(box.area()) <= smallTextureBoxLimit &&
                maxSide <= smallTextureSideLimit;

        if (!smallTextureCandidate) continue;

        int centerX = box.x + box.width / 2;
        int centerY = box.y + box.height / 2;
        int gridX = static_cast<int>(
                clampDouble(static_cast<double>(centerX * gridCols) / max(1, frameWidth), 0.0, gridCols - 1.0)
        );
        int gridY = static_cast<int>(
                clampDouble(static_cast<double>(centerY * gridRows) / max(1, frameHeight), 0.0, gridRows - 1.0)
        );
        int gridIndex = gridY * gridCols + gridX;
        contourGridIndex[i] = gridIndex;
        densityGrid[gridIndex]++;
    }

    const int denseCellLimit = static_cast<int>(round(11.0 - 6.0 * strength));
    const int eraseThickness = max(2, min(9, config.morphKernelSize / 3));
    vector<unsigned char> removedAsTextureNoise(contours.size(), 0);

    for (size_t i = 0; i < contours.size(); ++i) {
        int gridIndex = contourGridIndex[i];
        if (gridIndex < 0 || densityGrid[gridIndex] <= denseCellLimit) continue;

        removedAsTextureNoise[i] = 1;
        drawContours(filteredRoiEdge, contours, static_cast<int>(i), Scalar(0), FILLED);
        drawContours(filteredRoiEdge, contours, static_cast<int>(i), Scalar(0), eraseThickness);
    }

    const double minLineLength = min(frameWidth, frameHeight) * (0.18 - 0.05 * strength);
    const double minLinePerimeter = min(frameWidth, frameHeight) * (0.24 - 0.08 * strength);
    const double lineAspectThreshold = 5.5 - 2.5 * strength;
    const double boundaryDistancePx = max(6.0, frameHeight * (0.010 + 0.012 * strength));
    const double minBoundaryPerimeter = min(frameWidth, frameHeight) * (0.14 - 0.05 * strength);
    const double auxiliaryScoreCap = roiArea * 0.18;

    // ── [보도블럭 줄눈 억제 — 감점 전용] ─────────────────────────
    // 통합 강도(jointSuppressStrength)가 0보다 클 때만 동작한다. "긴 직선"이라는 이유만으로
    // 지우지 않고, 서로 평행하며 등간격으로 촉초히 늘어선 '규칙적 직선 집합(=줄눈 격자)'에
    // 속한 컨투어만 골라 표시한다. 연석(단일 선)·계단(소수·넓은 간격)·큰 장애물(폐곡선)은
    // 이 조건을 만족하지 못해 보호된다. erase는 하지 않으므로 미검출 위험이 없다.
    const double jointStrength = clampDouble(config.jointSuppressStrength, 0.0, 1.0);
    vector<unsigned char> isJointLine(contours.size(), 0);
    if (jointStrength > 0.0) {
        struct LineCand { size_t idx; double angle; double offset; };
        vector<LineCand> cands;
        const double jMinLineLength = min(frameWidth, frameHeight) * 0.16;
        const double jMinAspect = 4.0;
        for (size_t i = 0; i < contours.size(); ++i) {
            if (removedAsTextureNoise[i] || contours[i].size() < 2) continue;
            RotatedRect rr = minAreaRect(contours[i]);
            double longSide = max(rr.size.width, rr.size.height);
            double shortSide = max(1.0f, min(rr.size.width, rr.size.height));
            if (longSide < jMinLineLength || longSide / shortSide < jMinAspect) continue;
            double ang = rr.angle;
            if (rr.size.width < rr.size.height) ang += 90.0;
            ang = fmod(ang + 180.0, 180.0);
            double rad = ang * CV_PI / 180.0;
            // 직선의 법선 방향으로 사영한 수직 오프셋(평행선 간격 계산용)
            double offset = rr.center.x * (-sin(rad)) + rr.center.y * cos(rad);
            cands.push_back({i, ang, offset});
        }

        // 강도가 높을수록 최소 평행선 개수를 약간 완화(단, 안전상 3개 미만으로는 내리지 않음)
        const int minParallelCount = max(3, static_cast<int>(lround(5.0 - 2.0 * jointStrength)));
        const double angleToleranceDeg = 8.0;   // 평행으로 볼 각도 허용오차
        const double spacingCvMax = 0.15;        // 간격 균일성(변동계수) 상한
        const double maxMeanSpacing = frameHeight * 0.18; // 간격이 넓으면 계단으로 보고 제외

        vector<unsigned char> used(cands.size(), 0);
        for (size_t a = 0; a < cands.size(); ++a) {
            if (used[a]) continue;
            vector<size_t> group;
            group.push_back(a);
            for (size_t b = 0; b < cands.size(); ++b) {
                if (b == a || used[b]) continue;
                double d = fabs(cands[a].angle - cands[b].angle);
                d = min(d, 180.0 - d);
                if (d <= angleToleranceDeg) group.push_back(b);
            }
            if (static_cast<int>(group.size()) < minParallelCount) continue;

            vector<double> offs;
            for (size_t k : group) offs.push_back(cands[k].offset);
            sort(offs.begin(), offs.end());
            vector<double> spac;
            for (size_t k = 1; k < offs.size(); ++k) {
                double s = offs[k] - offs[k - 1];
                if (s > 1.0) spac.push_back(s);
            }
            if (static_cast<int>(spac.size()) < minParallelCount - 1) continue;

            double mean = 0.0;
            for (double s : spac) mean += s;
            mean /= spac.size();
            if (mean <= 0.0 || mean > maxMeanSpacing) continue; // 간격 넓음 → 계단 등으로 보고 보호

            double var = 0.0;
            for (double s : spac) var += (s - mean) * (s - mean);
            var /= spac.size();
            if (sqrt(var) / mean > spacingCvMax) continue;       // 불규칙 간격 → 보호

            // 규칙적 평행 직선 집합 확정 → 줄눈으로 표시(감점 대상)
            for (size_t k : group) {
                used[k] = 1;
                isJointLine[cands[k].idx] = 1;
            }
        }
    }
    // ───────────────────────────────────────────────────────

    double auxiliaryScore = 0.0;

    for (size_t i = 0; i < contours.size(); ++i) {
        if (removedAsTextureNoise[i]) continue;

        const vector<Point>& contour = contours[i];
        if (contour.size() < 2) continue;

        Rect box = contourBoxes[i];
        if (box.width <= 0 || box.height <= 0) continue;

        double perimeter = arcLength(contour, false);
        if (perimeter < 1.0) continue;

        RotatedRect minRect = minAreaRect(contour);
        double longSide = max(minRect.size.width, minRect.size.height);
        double shortSide = max(1.0f, min(minRect.size.width, minRect.size.height));
        double aspectRatio = longSide / shortSide;

        // 보도블럭 줄눈으로 확정된 직선: 보조 점수 가산을 막고 소폭 감점(erase 없음).
        // 강도에 비례해 감점하며, 아래에서 최종 auxiliaryScore를 0으로 하한 처리하므로
        // 실제 장애물의 면적 기반 점수(maxArea)는 절대 깍이지 않는다.
        if (isJointLine[i]) {
            auxiliaryScore -= min(longSide * (1.0 + 2.0 * jointStrength), roiArea * 0.04) * jointStrength;
            continue;
        }

        bool longLineContour =
                longSide >= minLineLength &&
                perimeter >= minLinePerimeter &&
                aspectRatio >= lineAspectThreshold;

        bool nearEntryBoundary = isNearRoiEntryBoundary(contour, roiPoints, boundaryDistancePx);
        bool sizableBoundaryContour =
                perimeter >= minBoundaryPerimeter ||
                static_cast<double>(box.area()) >= roiArea * 0.0025;

        if (longLineContour) {
            auxiliaryScore += min(longSide * (4.5 + 3.5 * strength), roiArea * 0.040);
        }

        if (nearEntryBoundary && sizableBoundaryContour) {
            auxiliaryScore += min((perimeter + longSide) * (1.8 + 2.2 * strength), roiArea * 0.035);
        }

        if (longLineContour && nearEntryBoundary) {
            auxiliaryScore += min(longSide * (1.5 + strength), roiArea * 0.020);
        }

        if (auxiliaryScore >= auxiliaryScoreCap) {
            auxiliaryScore = auxiliaryScoreCap;
            break;
        }
    }

    // 감점이 음수로 내려가도 실제 장애물 검출(maxArea)을 깍지 않도록 0으로 하한 처리.
    result.auxiliaryScore = max(0.0, auxiliaryScore);
    return result;
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

    // ROI Edge 원본은 디버그 표시용으로 보존하고, 판단용 복사본에서만 밀집 텍스처 노이즈를 제거함.
    Mat filtered_edge;
    ContourScoreResult contourScore = scoreAndFilterRoiEdgeContours(
            masked_edge,
            filtered_edge,
            roi_points,
            roiArea,
            width,
            height,
            config
    );

    // [요소기술 1] 비선형 형태학적 조작 (Morphological Closing)
    // Canny로 추출된 점자 블록의 엣지는 얇은 선이나 점으로 파편화되어 있음.
    // Size(25, 25)라는 거대한 커널을 사용하여, 끊어진 엣지들을 강제로 팽창(Dilation)시켜 하나로 뭉친 뒤
    // 다시 침식(Erosion)하여 거대한 기하학적 덩어리(Blob) 데이터로 변환함.
    Mat img_morph;
    Mat kernel = getStructuringElement(
            MORPH_RECT,
            Size(config.morphKernelSize, config.morphKernelSize)
    );
    morphologyEx(filtered_edge, img_morph, MORPH_CLOSE, kernel);

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

    double hazardScore = maxArea + contourScore.auxiliaryScore;

    // 이 Hazard Score(단일 스칼라 값)는 Kotlin의 [요소기술 2-2: 시계열 버퍼]로 넘어가서 최종 위험 판단의 재료로 사용됨.
    return min(hazardScore, 999999.0);
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
        case TUNING_CONTOUR_FILTER_STRENGTH:
            g_processingConfig.contourFilterStrength = clampDouble(value, 0.0, 1.0);
            break;
        case TUNING_JOINT_SUPPRESS_STRENGTH:
            g_processingConfig.jointSuppressStrength = clampDouble(value, 0.0, 1.0);
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
