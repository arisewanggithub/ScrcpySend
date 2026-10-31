package dev.screencopy.sender

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Process
import android.util.Xml
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.platform.app.InstrumentationRegistry
import java.io.IOException
import java.io.File
import java.util.UUID
import org.xmlpull.v1.XmlPullParser

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SenderDiagnosticPrecisionTest {
    private fun persistedValues(file: File): Map<String, String> = file.inputStream().use { input ->
        val parser = Xml.newPullParser().apply { setInput(input, "UTF-8") }
        val values = mutableMapOf<String, String>()
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name in setOf("string", "long")) {
                val key = checkNotNull(parser.getAttributeValue(null, "name"))
                values[key] = if (parser.name == "string") parser.nextText()
                    else checkNotNull(parser.getAttributeValue(null, "value"))
            }
            parser.next()
        }
        values
    }

    @Test fun androidDecimalIdsRoundTripExactlyInIsolatedPreferences() {
        // Instrumentation runs under the target UID, not the independently installed test APK UID.
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        assertEquals("Fixture storage must belong to the executing UID", Process.myUid(), context.applicationInfo.uid)
        val name = "diagnostic-precision-test-${UUID.randomUUID()}"
        val file = File(File(context.dataDir, "shared_prefs"), "$name.xml")
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(requested: String, mode: Int): SharedPreferences {
                check(requested == "sender-diagnostics")
                return prefs
            }
        }
        try {
            val trace = SessionStopTrace({ SenderFailureStore.recordDiagnostic(isolated, it) })
            val settings = Settings().apply { mediaTransportMode = "srt" }
            for (id in listOf(764986395015999395L, 764986395015999396L, Long.MAX_VALUE - 1, Long.MAX_VALUE)) {
                val json = JSONObject().put("session", id.toString()).put("timestamp_ms", "1793419722366")
                val committed = prefs.edit().putString("roundTrip", json.toString()).commit()
                assertTrue("Fixture commit failed: context_uid=${context.applicationInfo.uid}" +
                    " process_uid=${Process.myUid()} device_protected=${context.isDeviceProtectedStorage}" +
                    " directory_exists=${file.parentFile!!.exists()} directory_writable=${file.parentFile!!.canWrite()}",
                    committed)
                val decoded = Wire.parseJson(checkNotNull(persistedValues(file)["roundTrip"]).toByteArray())
                assertEquals(id, SenderFailureStore.exactLong(decoded, "session"))
                assertEquals(1793419722366L, SenderFailureStore.exactLong(decoded, "timestamp_ms"))
                trace.sessionStarted(id)
                assertEquals(id, checkNotNull(persistedValues(file)["currentSessionId"]).toLong())
                trace.firstFailure(IOException("SRT send: native failure (code 2001)"),
                    SessionStopTrace.Phase.STREAMING, SessionOutcome.Origin.CAPTURE, id, settings, 1793419722366)
                val stored = persistedValues(file)
                assertEquals(id, checkNotNull(stored["lastSessionFailureId"]).toLong())
                val saved = checkNotNull(stored["lastSessionFailure"])
                val savedFields = Wire.parseJson(saved.toByteArray())
                assertEquals(id, SenderFailureStore.exactLong(savedFields, "session"))
                assertEquals(1793419722366L, SenderFailureStore.exactLong(savedFields, "timestamp_ms"))
                assertTrue(SenderFailureStore.read(isolated)!!.startsWith("Last sender session failure at "))
                trace.firstFailure(IOException("SRT connection closed"), SessionStopTrace.Phase.CLEANUP,
                    SessionOutcome.Origin.CLEANUP, id, settings, 1793419722999)
                assertEquals(saved, persistedValues(file)["lastSessionFailure"])
            }
            trace.firstFailure(IOException("SRT connection closed"), SessionStopTrace.Phase.CLEANUP,
                SessionOutcome.Origin.CLEANUP, 764986395015999395L, settings, 1793419723999)
            assertEquals(Long.MAX_VALUE, checkNotNull(persistedValues(file)["lastSessionFailureId"]).toLong())
            assertThrows(IOException::class.java) { Wire.parseJson("""{"session":9223372036854775807}""".toByteArray()) }
            assertThrows(IOException::class.java) { Wire.parseJson("""{"timestamp_ms":1793419722366}""".toByteArray()) }
        } finally {
            assertTrue(context.deleteSharedPreferences(name))
            assertFalse("Fixture XML must be removed", file.exists())
        }
    }
}
