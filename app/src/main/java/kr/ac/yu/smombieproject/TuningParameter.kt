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

    const val CONTOUR_FILTER_STRENGTH = 201
    const val FOOT_FILTER_STRENGTH = 202
}

enum class TuningScope {
    NATIVE,
    SERVICE,
    COMPOSITE
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
        TuningParameter(TuningParameterIds.CONTOUR_FILTER_STRENGTH, "Contour Filter", 0.50, 0.0, 1.0, false, TuningScope.COMPOSITE),
        TuningParameter(TuningParameterIds.FOOT_FILTER_STRENGTH, "Foot Filter", 0.50, 0.0, 1.0, false, TuningScope.COMPOSITE),
        TuningParameter(TuningParameterIds.DANGER_AREA_THRESHOLD, "Danger Area", 3500.0, 0.0, 50000.0, false, TuningScope.SERVICE),
        TuningParameter(TuningParameterIds.BUFFER_THRESHOLD, "Buffer Threshold", 5.0, 1.0, 8.0, true, TuningScope.SERVICE)
    )

    private val hiddenDefaults = mapOf(
        TuningParameterIds.MIN_CONTOUR_AREA_PX to 200.0,
        TuningParameterIds.MIN_CONTOUR_AREA_RATIO_TO_ROI to 0.0015,
        TuningParameterIds.MAX_CONTOUR_AREA_RATIO_TO_ROI to 0.55,
        TuningParameterIds.MAX_THIN_CONTOUR_ASPECT_RATIO to 8.0,
        TuningParameterIds.MIN_CONTOUR_EXTENT to 0.14,
        TuningParameterIds.MIN_CONTOUR_CENTER_Y_RATIO to 0.48,
        TuningParameterIds.FOOT_BOTTOM_TOUCH_RATIO to 0.94,
        TuningParameterIds.FOOT_CENTER_Y_RATIO to 0.72,
        TuningParameterIds.FOOT_MAX_HEIGHT_RATIO to 0.32,
        TuningParameterIds.BUFFER_CAPACITY to 8.0
    )

    fun key(parameter: TuningParameter): String = "tuning_${parameter.id}"

    fun find(parameterId: Int): TuningParameter? = all.firstOrNull { it.id == parameterId }

    fun defaultValue(parameterId: Int): Double {
        return find(parameterId)?.defaultValue ?: hiddenDefaults[parameterId] ?: 0.0
    }

    fun sanitize(parameter: TuningParameter, value: Double): Double {
        val clamped = value.coerceIn(parameter.minValue, parameter.maxValue)
        return if (parameter.integerOnly) clamped.roundToInt().toDouble() else clamped
    }
}
