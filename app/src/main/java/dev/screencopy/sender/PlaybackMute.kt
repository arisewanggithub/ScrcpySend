package dev.screencopy.sender

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import java.io.Closeable
import java.io.IOException

/** One projection owns the mute, including size-switch restarts. Never rewrites volume indexes. */
internal class PlaybackMute(
    private val audio: AudioManager,
    private val preferences: SharedPreferences
) : Closeable {
    private var engaged = false
    private var closed = false

    @Synchronized fun mute() {
        if (closed) throw IOException("AUDIO_FAILED: local audio ownership has ended")
        if (engaged) return
        restore()
        if (audio.isStreamMute(AudioManager.STREAM_MUSIC) || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
            engaged = true
            return
        }
        if (audio.isVolumeFixed) throw IOException("AUDIO_FAILED: Android does not allow this app to mute local media on this device.")
        if (!preferences.edit().putBoolean(RESTORE, true).commit())
            throw IOException("AUDIO_FAILED: cannot record local audio restoration state; phone volume was not changed")
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            if (!audio.isStreamMute(AudioManager.STREAM_MUSIC))
                throw IOException("AUDIO_FAILED: Android did not mute local media. Lower the phone's media volume manually.")
            engaged = true
        } catch (e: SecurityException) {
            throw IOException("AUDIO_FAILED: Android denied local media mute", e)
        }
    }

    /** Also used on the next launch if the process died without running service cleanup. */
    @Synchronized fun restore() {
        if (!preferences.getBoolean(RESTORE, false)) return
        try {
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                if (audio.isStreamMute(AudioManager.STREAM_MUSIC))
                    throw IOException("Cannot restore local media audio. Unmute the phone manually and reopen the app.")
            }
        } catch (e: SecurityException) { throw IOException("Android denied local media audio restoration", e) }
        if (!preferences.edit().remove(RESTORE).commit())
            throw IOException("Cannot clear local audio restoration state")
    }

    @Synchronized override fun close() {
        if (closed) return
        restore()
        closed = true
    }

    companion object {
        private const val RESTORE = "restore-media-mute"
        fun create(context: Context) = PlaybackMute(context.getSystemService(AudioManager::class.java),
            context.getSharedPreferences("playback-mute", Context.MODE_PRIVATE))
        fun applies(settings: Settings) = settings.audio && settings.audioSource in setOf("playback", "output")
    }
}
