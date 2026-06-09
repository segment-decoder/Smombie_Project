package kr.ac.yu.smombieproject

import kotlin.math.roundToInt

object TuningParameterIds {
    const val CANNY_LOW = 1
    const val CANNY_HIGH = 2
    const val MORPH_KERNEL = 3
    const val MIN_CONTOUR_AREA_PX = 4
    const val MIN_CONTOUR_AREA_RATIO_TO_ROI = 5
    const val MAX_CONTOUR_AREA_RATIO_TO_ROI = 6
    const val MAX_THIN_CONTOUR_ASPECT_RATIO = 7
    const val MIN_CONTOUR_EXTENT = 8
    const val MIN_CONTOUR_CENTER_Y_RATIO = 9
    const val FOOT_BOTTOM_TOUCH_RATIO = 10
    const val FOOT_CENTER_Y_RATIO = 11
    const val FOOT_MAX_HEIGHT_RATIO = 12
    const val ILLUMINATION_BLEND = 13

    const val DANGER_AREA_THRESHOLD = 101
    const val BUFFER_CAPACITY = 102
    const val BUFFER_THRESHOLD = 103
}

enum class TuningScope {
    NATIVE,
    SERVICE
}

data class TuningParameter(
    val id: Int,
    val label: String,
    val defaultValue: Double,
    val minValue: Double,
    val maxValue: Double,
    val integerOnly: Boolean,
    val scope: TuningScope
)

object TuningParameters {
    const val PREFS_NAME = "processing_tuning"

    val all = listOf(
        TuningParameter(TuningParameterIds.CANNY_LOW, "Canny Low", 75.0, 0.0, 499.0, false, TuningScope.NATIVE),
        TuningParameter(TuningParameterIds.CANNY_HIGH, "Canny High", 120.0, 1.0, 500.0, false, TuningScope.NATIVE),
        TuningParameter(TuningParameterIds.ILLUMINATION_BLEND, "Illum Blend", 0.30, 0.0, 1.0, false, TuningScope.NATIVE),
        TuningParameter(TuningParameterIds.MORPH_KERNEL, "Morph Kernel", 25.0, 1.0, 99.0, true, TuningScope.NATIVE),
        TuningParameter(TuningParameterIds.MIN_CONTOUR_AREA_PX, "Min Contour Area", 200.0, 0.0, 50000.0, false, TuningScope.NATIVE),
        TuningParameter(TuningParameterIds.MIN_CONTOUR_AREA_RATIO_TO_ROI, "Min Area Ratio", 0.0015, 0.0, 1.0, false, TuningScope.NATIVE),
        TuningParameter(TuningParameterIds.MAX_CONTOUR_AREA_RATIO_TO_ROI, "Max Area Ratio", 0.55, 0.0, 1.0, false, TuningScope.NATIVE),
        TuningParameter(TuningParameterIds.MAX_THIN_CONTOUR_ASPECT_RATIO, "Max Thin Aspect", 8.0, 1.0, 50.0, false, TuningScope.NATIVE),
        TuningParameter(TuningParameterIds.MIN_CONTOUR_EXTENT, "Min Contour Extent", 0.14, 0.0, 1.0, false, TuningScope.NATIVE),
        TuningParameter(TuningParameterIds.MIN_CONTOUR_CENTER_Y_RATIO, "Min Center Y", 0.48, 0.0, 1.0, false, TuningScope.NATIVE),
        TuningParameter(TuningParameterIds.FOOT_BOTTOM_TOUCH_RATIO, "Foot Bottom Touch", 0.94, 0.0, 1.0, false, TuningScope.NATIVE),
        TuningParameter(TuningParameterIds.FOOT_CENTER_Y_RATIO, "Foot Center Y", 0.72, 0.0, 1.0, false, TuningScope.NATIVE),
        TuningParameter(TuningParameterIds.FOOT_MAX_HEIGHT_RATIO, "Foot Max Height", 0.32, 0.0, 1.0, false, TuningScope.NATIVE),
        TuningParameter(TuningParameterIds.DANGER_AREA_THRESHOLD, "Danger Area", 3500.0, 0.0, 50000.0, false, TuningScope.SERVICE),
        TuningParameter(TuningParameterIds.BUFFER_CAPACITY, "Buffer Capacity", 8.0, 1.0, 30.0, true, TuningScope.SERVICE),
        TuningParameter(TuningParameterIds.BUFFER_THRESHOLD, "Buffer Threshold", 5.0, 1.0, 30.0, true, TuningScope.SERVICE)
    )

    fun key(parameter: TuningParameter): String = "tuning_${parameter.id}"

    fun find(parameterId: Int): TuningParameter? = all.firstOrNull { it.id == parameterId }

    fun sanitize(parameter: TuningParameter, value: Double): Double {
        val clamped = value.coerceIn(parameter.minValue, parameter.maxValue)
        return if (parameter.integerOnly) clamped.roundToInt().toDouble() else clamped
    }
}
