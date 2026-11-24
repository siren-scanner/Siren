package app.sirenscout

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Siren's own alert sounds, generated on the fly. They play out of the phone's own speaker (or
 * through the car, if the user prefers), briefly lowering music like turn-by-turn directions -
 * on the alarm volume when the user wants them heard even on silent.
 */
object AlertSound {
    enum class Kind(val label: String) {
        POLICE("Police equipment nearby or following you"),
        RADIO("Public-safety radio transmitting nearby"),
        TRACKER("Unknown tracker traveling with you"),
        CAMERA("Plate reader ahead"),
        SPEED_CAMERA("Speed camera, red-light camera or traffic-stop hotspot ahead"),
        AIR("Drone or police aircraft nearby"),
        OTHER("Other matches (fleet, fire / EMS, surveillance)"),
    }

    private const val RATE = 22_050
    /** The same kind of sound won't repeat faster than this, so a busy area isn't a din. */
    private const val MIN_GAP_MS = 8_000L
    private val lastPlayed = HashMap<Kind, Long>()

    /** (frequency Hz, milliseconds); 0 Hz is a pause. */
    private fun pattern(kind: Kind): List<Pair<Double, Int>> = when (kind) {
        // A short police-style "yelp": high-low, four times.
        Kind.POLICE -> List(4) { listOf(960.0 to 170, 700.0 to 170) }.flatten()
        Kind.RADIO -> listOf(600.0 to 150, 0.0 to 90, 600.0 to 150)
        Kind.TRACKER -> listOf(1250.0 to 100, 0.0 to 70, 1250.0 to 100, 0.0 to 70, 1250.0 to 100)
        Kind.CAMERA -> listOf(880.0 to 200, 1320.0 to 260)
        // A "slow down" chime: three bell-like notes stepping down (G, E, C).
        Kind.SPEED_CAMERA -> listOf(1568.0 to 140, 0.0 to 50, 1319.0 to 140, 0.0 to 50, 1047.0 to 320)
        // A falling "whoosh", like something passing overhead.
        Kind.AIR -> listOf(1100.0 to 120, 900.0 to 120, 700.0 to 160, 0.0 to 120, 1100.0 to 120, 900.0 to 120, 700.0 to 160)
        Kind.OTHER -> listOf(800.0 to 220)
    }

    fun enabled(prefs: Prefs, kind: Kind) = when (kind) {
        Kind.POLICE -> prefs.soundPolice
        Kind.RADIO -> prefs.soundRadio
        Kind.TRACKER -> prefs.soundTracker
        Kind.CAMERA -> prefs.soundCamera
        Kind.SPEED_CAMERA -> prefs.soundSpeedCamera
        Kind.AIR -> prefs.soundAir
        Kind.OTHER -> prefs.soundOther
    }

    /** Plays [kind] if the user has it turned on (or [force], for the Test buttons). */
    fun play(context: Context, kind: Kind, force: Boolean = false) {
        val prefs = Prefs(context)
        if (!force && !enabled(prefs, kind)) return
        val now = System.currentTimeMillis()
        synchronized(lastPlayed) {
            if (!force && now - (lastPlayed[kind] ?: 0L) < MIN_GAP_MS) return
            lastPlayed[kind] = now
        }
        runCatching { start(context, kind, prefs.soundWhenSilent, prefs.soundThroughCar) }
            .onFailure { AppLog.w("Alert sound failed", it) }
    }

    private fun start(context: Context, kind: Kind, whenSilent: Boolean, throughCar: Boolean) {
        val pcm = synth(pattern(kind))
        val attributes = AudioAttributes.Builder()
            .setUsage(if (whenSilent) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val audio = context.getSystemService(AudioManager::class.java)
        // Lower other audio (music, podcasts) briefly, like navigation directions do.
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        audio?.requestAudioFocus(focus)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        if (!throughCar) {
            audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { track.setPreferredDevice(it) }
        }
        track.write(pcm, 0, pcm.size)
        track.setNotificationMarkerPosition(pcm.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) {
                t.release()
                audio?.abandonAudioFocusRequest(focus)
            }

            override fun onPeriodicNotification(t: AudioTrack) {}
        })
        track.play()
    }

    /** Sine tones with short fades so they don't click. */
    fun synth(pattern: List<Pair<Double, Int>>): ShortArray {
        val total = pattern.sumOf { RATE * it.second / 1000 }
        val out = ShortArray(total)
        var i = 0
        for ((freq, ms) in pattern) {
            val n = RATE * ms / 1000
            val fade = min(n / 4, RATE / 100) // 10 ms
            for (k in 0 until n) {
                val envelope = when {
                    freq == 0.0 -> 0.0
                    k < fade -> k / fade.toDouble()
                    k > n - fade -> (n - k) / fade.toDouble()
                    else -> 1.0
                }
                out[i++] = (sin(2 * PI * freq * k / RATE) * envelope * 0.8 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }
}
