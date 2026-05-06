#include <jni.h>
#include <opencv2/opencv.hpp>
#include <vector>

using namespace cv;
using namespace std;

extern "C"
JNIEXPORT jdouble JNICALL
Java_kr_ac_yu_smombieproject_NativeProcessor_processFrame(
        JNIEnv *env, jobject thiz, jlong matAddrInput) {

    Mat &img_input = *(Mat *) matAddrInput;
    if (img_input.empty()) return 0.0;

    int width = img_input.cols;
    int height = img_input.rows;

    // 1. [요소기술 1] 전처리 및 특징점 추출 (마스킹보다 먼저 수행!!)
    Mat img_gray, img_blur, img_edge;
    if (img_input.channels() >= 3) cvtColor(img_input, img_gray, COLOR_RGBA2GRAY);
    else img_gray = img_input.clone();

    // 노이즈 제거
    GaussianBlur(img_gray, img_blur, Size(5, 5), 0);
    // 특징점 추출 (문턱값 정상화)
    Canny(img_blur, img_edge, 50, 150);

    // 2. [요소기술 2-1] 공간 효율성: Canny 결과물에 ROI 마스크 씌우기
    // 이렇게 해야 사다리꼴 테두리가 장애물로 인식되는 치명적 버그를 막을 수 있음
    Mat mask = Mat::zeros(img_edge.size(), CV_8UC1);
    vector<Point> roi_points = {
            Point(width * 0.10, height),
            Point(width * 0.90, height),
            Point(width * 0.70, height * 0.50),
            Point(width * 0.30, height * 0.50)
    };
    fillPoly(mask, {roi_points}, Scalar(255));

    // 엣지 이미지에 마스크 적용 (사다리꼴 내부의 엣지만 살아남음)
    Mat masked_edge = Mat::zeros(img_edge.size(), CV_8UC1);
    img_edge.copyTo(masked_edge, mask);

    // 3. [요소기술 1] 비선형 닫기 필터 (점자 블록 덩어리화)
    Mat img_morph;
    Mat kernel = getStructuringElement(MORPH_RECT, Size(15, 15));
    morphologyEx(masked_edge, img_morph, MORPH_CLOSE, kernel);

    // 4. [SpaceAnalyzer] 면적 산출
    vector<vector<Point>> contours;
    findContours(img_morph, contours, RETR_EXTERNAL, CHAIN_APPROX_SIMPLE);

    double maxArea = 0;
    for (const auto& contour : contours) {
        double area = contourArea(contour);
        if (area > maxArea) maxArea = area;
    }

    // 이 maxArea는 Kotlin의 시계열 버퍼로 넘어가서 최종 판단됨
    return (jdouble)maxArea;
}