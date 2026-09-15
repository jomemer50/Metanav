package com.metanav.app.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.metanav.core.Urgency

/** A short tap for caution, a firm double buzz for stop. Phone only; the glasses have no motor. */
class HapticFeedback(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    var enabled: Boolean = true

    fun buzz(urgency: Urgency) {
        if (!enabled) return
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = when (urgency) {
            Urgency.STOP -> VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 160), -1)
            Urgency.CAUTION -> VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
            Urgency.NONE -> VibrationEffect.createOneShot(25, 80)
        }
        v.vibrate(effect)
    }
}
