package dev.screencopy.sender

import android.content.SharedPreferences
import android.media.AudioManager
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class PlaybackMuteTest {
    private class Device(var muted: Boolean = false, var volume: Int = 7, fixed: Boolean = false) {
        val audio = Mockito.mock(AudioManager::class.java)
        val preferences = Mockito.mock(SharedPreferences::class.java)
        val editor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        var saved = false
        private var pending = false
        var writesSucceed = true
        val changes = mutableListOf<Int>()
        init {
            Mockito.`when`(audio.isStreamMute(AudioManager.STREAM_MUSIC)).thenAnswer { muted }
            Mockito.`when`(audio.getStreamVolume(AudioManager.STREAM_MUSIC)).thenAnswer { if (muted) 0 else volume }
            Mockito.`when`(audio.isVolumeFixed).thenReturn(fixed)
            Mockito.doAnswer {
                val direction = it.getArgument<Int>(1)
                changes += direction
                muted = direction == AudioManager.ADJUST_MUTE
                null
            }.`when`(audio).adjustStreamVolume(Mockito.eq(AudioManager.STREAM_MUSIC), Mockito.anyInt(), Mockito.eq(0))
            Mockito.`when`(preferences.getBoolean("restore-media-mute", false)).thenAnswer { saved }
            Mockito.`when`(preferences.edit()).thenAnswer { pending = saved; editor }
            Mockito.`when`(editor.putBoolean("restore-media-mute", true)).thenAnswer { pending = true; editor }
            Mockito.`when`(editor.remove("restore-media-mute")).thenAnswer { pending = false; editor }
            Mockito.`when`(editor.commit()).thenAnswer {
                if (writesSucceed) { saved = pending; true } else false
            }
        }
        fun lease() = PlaybackMute(audio, preferences)
    }

    @Test fun onlyPlaybackCaptureMutesNotMicrophoneOrDisabledAudio() {
        for (source in listOf("playback", "output", "mic")) for (enabled in listOf(false, true)) {
            assertEquals(enabled && source != "mic", PlaybackMute.applies(Settings().apply { audio = enabled; audioSource = source }))
        }
    }
    @Test fun muteIsOwnedOnceAcrossSizeSwitchesAndRestoresWithoutChangingVolumeIndex() {
        val device = Device()
        val lease = device.lease()
        lease.mute()
        assertTrue(device.muted)
        assertTrue(device.saved)
        repeat(3) { lease.mute() }
        assertEquals(listOf(AudioManager.ADJUST_MUTE), device.changes)
        lease.close(); lease.close()
        assertFalse(device.muted)
        assertFalse(device.saved)
        assertEquals(7, device.volume)
        assertEquals(listOf(AudioManager.ADJUST_MUTE, AudioManager.ADJUST_UNMUTE), device.changes)
        Mockito.verify(device.audio, Mockito.never()).setStreamVolume(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt())
        assertThrows(IOException::class.java) { lease.mute() }
    }
    @Test fun alreadyMutedAndZeroVolumeRemainUntouchedIncludingAfterUserVolumeChanges() {
        for (device in listOf(Device(muted = true), Device(volume = 0))) {
            val lease = device.lease()
            lease.mute()
            assertFalse(device.saved)
            assertTrue(device.changes.isEmpty())
            lease.close()
            assertTrue(device.changes.isEmpty())
        }
        val device = Device()
        val lease = device.lease()
        lease.mute()
        device.muted = false; device.volume = 11
        lease.close()
        assertEquals(11, device.volume)
        assertEquals(listOf(AudioManager.ADJUST_MUTE), device.changes)
        assertFalse(device.saved)
    }
    @Test fun processDeathRecoveryRestoresOnlyAnAppOwnedMuteAndThenClearsItsRecord() {
        val device = Device()
        device.lease().mute()
        assertTrue(device.saved)
        device.lease().restore()
        assertFalse(device.muted)
        assertFalse(device.saved)
        device.lease().restore()
        assertEquals(listOf(AudioManager.ADJUST_MUTE, AudioManager.ADJUST_UNMUTE), device.changes)
        val alreadyMuted = Device(muted = true)
        alreadyMuted.lease().restore()
        assertTrue(alreadyMuted.muted)
        assertTrue(alreadyMuted.changes.isEmpty())
    }
    @Test fun failuresBeforeMutationAndDuringRestorationAreExplicitAndRetryable() {
        val fixed = Device(fixed = true)
        assertThrows(IOException::class.java) { fixed.lease().mute() }
        assertFalse(fixed.saved)
        assertTrue(fixed.changes.isEmpty())
        val device = Device()
        device.writesSucceed = false
        val lease = device.lease()
        assertThrows(IOException::class.java) { lease.mute() }
        assertFalse(device.muted)
        assertTrue(device.changes.isEmpty())
        device.writesSucceed = true
        lease.mute()
        device.writesSucceed = false
        assertThrows(IOException::class.java) { lease.close() }
        assertFalse(device.muted)
        assertTrue(device.saved)
        device.writesSucceed = true
        lease.close()
        assertFalse(device.saved)
    }
    @Test fun rejectedMuteAndRestoreDoNotLoseRestorationEvidenceOrPretendSuccess() {
        val device = Device()
        val lease = device.lease()
        Mockito.doThrow(SecurityException("denied")).`when`(device.audio)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        assertThrows(IOException::class.java) { lease.mute() }
        assertTrue(device.saved)
        lease.close()
        assertFalse(device.saved)
        val restore = Device()
        val pending = restore.lease()
        pending.mute()
        Mockito.doThrow(SecurityException("denied")).`when`(restore.audio)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        assertThrows(IOException::class.java) { pending.close() }
        assertTrue(restore.muted)
        assertTrue(restore.saved)
    }
}
