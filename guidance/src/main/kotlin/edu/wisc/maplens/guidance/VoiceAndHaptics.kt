package edu.wisc.maplens.guidance

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Speaks and vibrates [Announcement]s (#20). TextToSpeech uses the phone's installed voice, which
 * works offline; until it is ready, only the latest line is kept. Vibration works even when
 * voice is off, so cues still reach a user who muted it.
 *
 * Call from the main thread. [shutdown] in the Activity's onDestroy.
 */
class VoiceAndHaptics(context: Context) : TextToSpeech.OnInitListener {
    private val tts = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pending: String? = null

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }

    /** Speech on or off; vibration is unaffected. */
    var voiceOn: Boolean = true
        set(value) {
            field = value
            if (!value) stop()
        }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        tts.setLanguage(Locale.getDefault())
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        ready = true
        pending?.let(::speak)
    }

    fun announce(announcement: Announcement) {
        announcement.haptic?.let(::vibrate)
        if (voiceOn) speak(announcement.text)
    }

    fun vibrate(haptic: Haptic) {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        val pattern = when (haptic) {
            // Two short taps: a maneuver is here.
            Haptic.TURN -> longArrayOf(0, 70, 90, 70)
            // Long, short, long: you have arrived.
            Haptic.ARRIVE -> longArrayOf(0, 300, 120, 120, 120, 300)
        }
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    /** Cuts off speech in progress, e.g. when navigation ends or the screen pauses. */
    fun stop() {
        pending = null
        if (ready) tts.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
    }

    private fun speak(text: String) {
        if (!ready) {
            pending = text
            return
        }
        pending = null
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE)
    }

    private companion object {
        const val UTTERANCE = "maplens-cue"
    }
}
