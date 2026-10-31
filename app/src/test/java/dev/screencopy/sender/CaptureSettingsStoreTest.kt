package dev.screencopy.sender

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.IOException

class CaptureSettingsStoreTest {
    private fun preferences(value: String? = null, commit: Boolean = true): Pair<SharedPreferences, SharedPreferences.Editor> {
        val prefs = Mockito.mock(SharedPreferences::class.java)
        val editor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
        Mockito.`when`(prefs.getString("capture", null)).thenReturn(value)
        Mockito.`when`(prefs.edit()).thenReturn(editor)
        Mockito.`when`(editor.commit()).thenReturn(commit)
        return prefs to editor
    }
    @Test fun defaultsAndEveryVideoAudioCropEncoderFieldStayAuthoritative() {
        val settings = Settings().apply {
            videoCodec = "h265"; videoEncoder = "c2.test.hevc"; maxSize = 1200; maxFps = 30
            videoBitrate = 9_000_000; orientation = "@270"; displayId = 2
            cropX = 12; cropY = 14; cropWidth = 640; cropHeight = 480
            videoOptions = "profile=1"; audio = true; audioCodec = "aac"
            audioEncoder = "c2.test.aac"; audioBitrate = 256000; audioSource = "mic"; audioOptions = "aac-profile=2"
        }
        val (prefs, editor) = preferences(settings.request().toString())
        val store = CaptureSettingsStore(prefs)
        assertEquals(settings.request().toString(), store.read().request().toString())
        store.write(settings)
        Mockito.verify(editor).putString("capture", settings.request().toString())
        Mockito.verify(editor).commit()
        val defaults = CaptureSettingsStore(preferences().first).read()
        assertTrue(defaults.audio); assertEquals("opus", defaults.audioCodec); assertEquals(192000, defaults.audioBitrate)
    }
    @Test fun corruptStoredSettingsNeverSilentlyBecomeDefaults() {
        val store = CaptureSettingsStore(preferences("{malformed").first)
        assertThrows(Exception::class.java) { store.read() }
    }
    @Test fun writeFailureIsExplicitAndInvalidOptionsNeverSaved() {
        val (prefs, editor) = preferences(commit = false)
        assertThrows(IOException::class.java) { CaptureSettingsStore(prefs).write(Settings()) }
        Mockito.clearInvocations(editor)
        assertThrows(IllegalArgumentException::class.java) {
            CaptureSettingsStore(prefs).write(Settings().apply { videoOptions = "max-bframes=1" })
        }
        Mockito.verifyNoInteractions(editor)
    }
}
