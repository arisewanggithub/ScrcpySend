package dev.screencopy.sender

import android.content.Context
import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Briefly mutes media only; never raises volume, starts playback, or captures screen/audio. */
@RunWith(AndroidJUnit4::class)
class PlaybackMuteDeviceTest {
    @Test fun mediaMuteLeaseRestoresTheActualDeviceStateWithoutChangingVolume() {
        check(!CastService.active) { "Do not interrupt an active cast" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = context.getSystemService(AudioManager::class.java)
        val wasMuted = audio.isStreamMute(AudioManager.STREAM_MUSIC)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val name = "playback-mute-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val lease = PlaybackMute(audio, preferences)
        try {
            lease.mute()
            assertTrue(audio.isStreamMute(AudioManager.STREAM_MUSIC) || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0)
            lease.mute()
            lease.close()
            assertEquals(wasMuted, audio.isStreamMute(AudioManager.STREAM_MUSIC))
            assertEquals(volume, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            assertFalse(preferences.contains("restore-media-mute"))
        } finally {
            lease.close()
            assertTrue(context.deleteSharedPreferences(name))
        }
    }
}
