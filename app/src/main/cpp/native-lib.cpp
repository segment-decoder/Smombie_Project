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

    // 1. [Core Tech 1] Preprocessing: Gray conversion and Gaussian Blur for primary noise reduction.
    Mat img_gray, img_blur, img_edge;
    if (img_input.channels() >= 3) cvtColor(img_input, img_gray, COLOR_RGBA2GRAY);
    else img_gray = img_input.clone();

    GaussianBlur(img_gray, img_blur, Size(5, 5), 0);

    // Feature Extraction: Detect object contours using the Canny Edge algorithm.
    // High-frequency noise (e.g., floor textures) is filtered using thresholds (70, 200).
    Canny(img_blur, img_edge, 70, 200);

    // 2. [Core Tech 2-1] Spatial Efficiency: ROI (Region of Interest) Masking.
    // Eliminates unnecessary computation areas outside the pedestrian's path (trapezoid at the bottom).
    Mat mask = Mat::zeros(img_edge.size(), CV_8UC1);
    vector<Point> roi_points = {
            Point(width * 0.10, height),
            Point(width * 0.90, height),
            Point(width * 0.70, height * 0.50),
            Point(width * 0.30, height * 0.50)
    };
    fillPoly(mask, {roi_points}, Scalar(255));

    // Apply ROI mask to the detected edge image.
    Mat masked_edge = Mat::zeros(img_edge.size(), CV_8UC1);
    img_edge.copyTo(masked_edge, mask);

    // 3. [Core Tech 1] Nonlinear Morphological Operation: Morphology Closing.
    // Merges fragmented feature points (e.g., braille blocks) into meaningful Blob data.
    // A 25x25 kernel is used to bridge gaps between edges and fill internal holes.
    Mat img_morph;
    Mat kernel = getStructuringElement(MORPH_RECT, Size(25, 25));
    morphologyEx(masked_edge, img_morph, MORPH_CLOSE, kernel);

    // 4. [SpaceAnalyzer] Area Calculation: Computes the maximum area of the final merged object.
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