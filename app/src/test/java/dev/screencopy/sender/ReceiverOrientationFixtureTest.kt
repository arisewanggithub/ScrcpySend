package dev.screencopy.sender

import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class ReceiverOrientationFixtureTest {
    @Test fun sharedPresentationFixturesUseStrictOptionalTopLevelSchemaWithoutMediaChanges() {
        val fixture = Wire.parseJson(Files.readAllBytes(Path.of(System.getProperty("fixtures"), "receiver-orientation.json")))
        val baseline = fixture.getJSONObject("baseRequest")
        val cases = fixture.getJSONArray("cases")
        assertEquals(13, cases.length())
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val request = JSONObject(baseline.toString())
            if (case.getBoolean("present")) request.put("receiverOrientation", case.get("value"))
            if (case.getBoolean("valid")) {
                val settings = Settings.from(request)
                assertEquals(case.getString("name"), case.getString("expected"), settings.receiverOrientation)
                val serialized = settings.request()
                assertJsonEquals(baseline.getJSONObject("video"), serialized.getJSONObject("video"))
                assertJsonEquals(baseline.getJSONObject("audio"), serialized.getJSONObject("audio"))
                assertEquals(case.getString("expected"), serialized.getString("receiverOrientation"))
            } else if (case.get("value") is String) {
                assertThrows(case.getString("name"), IllegalArgumentException::class.java) { Settings.from(request) }
            } else {
                assertThrows(case.getString("name"), JSONException::class.java) { Settings.from(request) }
            }
        }
    }
}
