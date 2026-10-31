package dev.screencopy.sender

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class ReceiverOrientationSettingsTest {
    @Test fun defaultsAndAbsentFieldUseAuto() {
        val settings = Settings()
        assertEquals("auto", settings.receiverOrientation)
        assertEquals("auto", settings.request().getString("receiverOrientation"))
        val legacy = settings.request().apply { remove("receiverOrientation") }
        assertEquals("auto", Settings.from(legacy).receiverOrientation)
    }

    @Test fun everySupportedValueRoundTripsAtTopLevelWithoutChangingMediaFields() {
        val baseline = populatedSettings().request()
        for (value in listOf("auto", "landscape", "portrait")) {
            val settings = populatedSettings().apply { receiverOrientation = value }
            settings.validate()
            val request = settings.request()
            assertEquals(3, request.length())
            assertEquals(value, request.getString("receiverOrientation"))
            assertJsonEquals(baseline.getJSONObject("video"), request.getJSONObject("video"))
            assertJsonEquals(baseline.getJSONObject("audio"), request.getJSONObject("audio"))
            val restored = Settings.from(JSONObject(request.toString()))
            assertEquals(value, restored.receiverOrientation)
            assertJsonEquals(request, restored.request())
        }
    }

    @Test fun presentNonStringValuesIncludingNullAreRejected() {
        for (value in listOf(JSONObject.NULL, true, false, 0, 90, 1.5, JSONObject(), JSONArray())) {
            val request = Settings().request().put("receiverOrientation", value)
            assertThrows(JSONException::class.java) { Settings.from(request) }
        }
    }

    @Test fun unknownAndMalformedStringsAreRejectedByParsingAndValidation() {
        for (value in listOf("", "AUTO", "Landscape", "90", "auto ", " portrait", "unknown")) {
            assertThrows(IllegalArgumentException::class.java) {
                Settings.from(Settings().request().put("receiverOrientation", value))
            }
            assertThrows(IllegalArgumentException::class.java) {
                Settings().apply { receiverOrientation = value }.validate()
            }
        }
    }

    @Test fun persistedSettingsRoundTripPreservesAllRequestFields() {
        for (value in listOf("auto", "landscape", "portrait")) {
            val settings = populatedSettings().apply { receiverOrientation = value }
            val prefs = Mockito.mock(SharedPreferences::class.java)
            val editor = Mockito.mock(SharedPreferences.Editor::class.java, Mockito.RETURNS_SELF)
            Mockito.`when`(prefs.edit()).thenReturn(editor)
            Mockito.`when`(editor.commit()).thenReturn(true)
            val store = CaptureSettingsStore(prefs)
            store.write(settings)
            val serialized = settings.request().toString()
            Mockito.verify(editor).putString("capture", serialized)
            Mockito.verify(editor).commit()
            Mockito.`when`(prefs.getString("capture", null)).thenReturn(serialized)
            val restored = store.read()
            assertEquals(value, restored.receiverOrientation)
            assertJsonEquals(settings.request(), restored.request())
        }
    }

    private fun populatedSettings() = Settings().apply {
        videoCodec = "h265"; videoEncoder = "c2.test.hevc"; videoBitrate = 9_000_000
        maxSize = 1200; maxFps = 30; displayId = 2; orientation = "@270"
        cropWidth = 640; cropHeight = 480; cropX = 12; cropY = 14; videoOptions = "profile=1"
        audio = false; audioCodec = "aac"; audioEncoder = "c2.test.aac"
        audioBitrate = 256_000; audioSource = "mic"; audioOptions = "aac-profile=2"
    }
}
