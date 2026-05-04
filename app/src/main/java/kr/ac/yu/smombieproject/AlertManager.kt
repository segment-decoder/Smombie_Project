package kr.ac.yu.smombieproject

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

class AlertManager(context: Context) {

    // 안드로이드 버전에 따라 알맞은 진동 모터(Vibrator) 객체를 가져옵니다.
    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    // 위험 감지 시 호출할 "징- 징-" 진동 함수
    fun playDangerVibration() {
        // 진동 타이밍 배열: [대기시간, 진동시간, 대기시간, 진동시간] (밀리초 단위)
        // 즉, 0초 대기 -> 0.3초 진동 -> 0.2초 멈춤 -> 0.3초 진동
        val timings = longArrayOf(0, 300, 200, 300)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 안드로이드 8.0(Oreo) 이상 최신 버전용 정밀 진동 제어
            val amplitudes = intArrayOf(0, VibrationEffect.DEFAULT_AMPLITUDE, 0, VibrationEffect.DEFAULT_AMPLITUDE)
            val effect = VibrationEffect.createWaveform(timings, amplitudes, -1) // -1은 반복 안 함(한 번만 울림)
            vibrator.vibrate(effect)
        } else {
            // 옛날 안드로이드 버전용 호환 코드
            @Suppress("DEPRECATION")
            vibrator.vibrate(timings, -1)
        }
    }
}