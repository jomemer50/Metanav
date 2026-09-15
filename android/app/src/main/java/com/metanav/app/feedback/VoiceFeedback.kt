package com.metanav.app.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.metanav.core.Advisory
import com.metanav.core.Urgency
import java.util.Locale

/**
 * Speaks advisories through the phone's current audio route. When the glasses are connected as
 * a Bluetooth headset that is their open-ear speakers, which is exactly where guidance belongs.
 * Urgent advisories flush anything still queued; nothing is ever queued behind stale guidance.
 */
class VoiceFeedback(context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var ready = false
    private var pending: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            // Deferred so `tts` is assigned even if the engine reports back synchronously.
            mainHandler.post { onInit(status) }
        }
    }

    private fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.getDefault()
            tts.setSpeechRate(1.05f)
            tts.setAudioAttributes(attributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = abandonFocus()
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = abandonFocus()
            })
            pending?.let { speak(it, urgent = false) }
            pending = null
        } else {
            Log.w(TAG, "TextToSpeech unavailable ($status)")
        }
    }

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .build()

    var enabled: Boolean = true

    fun speak(advisory: Advisory) = speak(advisory.text, urgent = advisory.urgency == Urgency.STOP)

    fun speak(text: String, urgent: Boolean) {
        if (!enabled) return
        if (!ready) { pending = text; return }
        audioManager.requestAudioFocus(focusRequest)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, if (urgent) "urgent" else "advisory")
    }

    fun stop() {
        if (ready) tts.stop()
        abandonFocus()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun abandonFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    companion object { private const val TAG = "Metanav:Voice" }
}
