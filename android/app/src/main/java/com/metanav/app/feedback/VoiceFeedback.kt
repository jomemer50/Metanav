package com.metanav.app.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioTrack
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
                override fun onDone(utteranceId: String?) { if (!running) abandonFocus() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { if (!running) abandonFocus() }
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
    private var keepAlive: AudioTrack? = null
    private var running = false

    /**
     * Call when guidance starts. Holds audio focus and plays inaudible silence for the whole run so
     * the Bluetooth route to the glasses stays open (otherwise the first word of every advisory is
     * swallowed while the link wakes up), and warms up the speech engine.
     */
    fun begin() {
        running = true
        audioManager.requestAudioFocus(focusRequest)
        startKeepAlive()
        if (ready) tts.playSilentUtterance(1, TextToSpeech.QUEUE_ADD, "warmup")
    }

    /** Call when guidance stops: releases focus so other audio gets its volume back. */
    fun end() {
        running = false
        stop()
        stopKeepAlive()
        abandonFocus()
    }

    private fun startKeepAlive() {
        if (keepAlive != null) return
        val sampleRate = 8000
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val silence = ShortArray(maxOf(minBuf / 2, sampleRate))
        keepAlive = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setBufferSizeInBytes(silence.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .apply {
                    write(silence, 0, silence.size)
                    setLoopPoints(0, silence.size, -1)
                    setVolume(0f)
                    play()
                }
        }.onFailure { Log.w(TAG, "keep-alive track failed", it) }.getOrNull()
    }

    private fun stopKeepAlive() {
        keepAlive?.let { runCatching { it.stop(); it.release() } }
        keepAlive = null
    }

    fun speak(advisory: Advisory) = speak(advisory.text, urgent = advisory.urgency == Urgency.STOP)

    fun speak(text: String, urgent: Boolean) {
        if (!enabled) return
        if (!ready) { pending = text; return }
        if (!running) audioManager.requestAudioFocus(focusRequest)
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, if (urgent) "urgent" else "advisory")
    }

    fun stop() {
        if (ready) tts.stop()
        if (!running) abandonFocus()
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
