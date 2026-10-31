package dev.screencopy.sender

import android.content.Intent
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Activity observer/window fixture with service-state adoption, never real capture. */
@RunWith(AndroidJUnit4::class)
class ReceiverRowsDeviceTest {
    @Test fun freshDecodedAdvertisementRendersIdleHomeRowWithoutStartingCapture() {
        assumeTrue("Designated idle test app only", !CastService.active && !ShizukuSetup.shared.snapshot().ready)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model = ReceiverCasting.shared
        assumeTrue(model.snapshot().active == null)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                val bytes = """{"protocol":"screencopy","version":3,"name":"UI fixture","port":27184,"state":"available"}""".toByteArray()
                val receiver = Discovery.decode(bytes, "192.0.2.1", System.nanoTime())
                model.updateDiscovery(listOf(receiver))
                val rows = activity.findViewById<LinearLayout>(R.id.receiver_rows)
                assertEquals("A packet fresh in Discovery must also be fresh in the shared home model", 1, rows.childCount)
                val line = rows.getChildAt(0) as LinearLayout
                assertEquals("Play", (line.getChildAt(1) as Button).text.toString())
                model.setNextSizeOverride(true)
                assertEquals(1, rows.childCount)
                assertNull(model.snapshot().active)
                assertFalse(CastService.active)
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.finish(); model.updateDiscovery(emptyList()); model.setNextSizeOverride(false)
            }
        }
    }

    @Test fun activeServiceRecreationRendersStopDisablesSettingsAndScopesKeepScreenOnToVisibleWindow() {
        assumeTrue("Designated idle test app only", !CastService.active && !ShizukuSetup.shared.snapshot().ready)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model = ReceiverCasting.shared
        val state = CastingServiceState(model)
        model.updateDiscovery(listOf(Discovery.Receiver("UI fixture", "192.0.2.1", 27184, "available", System.nanoTime())))
        val saved = Settings().apply {
            maxFps = 24; cropWidth = 640; cropHeight = 480
        }
        model.setNextSizeOverride(true)
        val request = model.reserve(ReceiverKey("192.0.2.1", 27184), saved, applyRuntimeOverride = true)
        val owner = checkNotNull(state.adopt(request.token))
        var activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            instrumentation.runOnMainSync {
                assertActive(activity, "Connecting... (Stop)")
                state.progress(owner, "Streaming")
                assertActive(activity, "Stop")
                model.updateDiscovery(emptyList())
                assertActive(activity, "Stop")
            }
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            try {
                instrumentation.runOnMainSync { activity.recreate() }
                val recreated = monitor.waitForActivityWithTimeout(5000)
                assertNotNull(recreated)
                activity = recreated as MainActivity
            } finally { instrumentation.removeMonitor(monitor) }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertActive(activity, "Stop")
                assertTrue(state.owns(owner))
                instrumentation.callActivityOnPause(activity)
                instrumentation.callActivityOnStop(activity)
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                instrumentation.callActivityOnStart(activity)
                instrumentation.callActivityOnResume(activity)
                assertActive(activity, "Stop")
                state.progress(owner, "Stopping")
                assertActive(activity, "Stopping...")
                state.finish(owner)
                assertTrue(activity.findViewById<CheckBox>(R.id.home_ignore_size_crop).isEnabled)
                assertTrue(activity.findViewById<TextView>(R.id.home_applied_settings).text.toString()
                    .startsWith("Applied settings — next cast"))
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.finish(); state.finish(owner); model.updateDiscovery(emptyList()); model.setNextSizeOverride(false)
            }
        }
    }
    private fun assertActive(activity: MainActivity, label: String) {
        val rows = activity.findViewById<LinearLayout>(R.id.receiver_rows)
        val line = rows.getChildAt(0) as LinearLayout
        assertEquals(label, (line.getChildAt(1) as Button).text.toString())
        assertFalse(activity.findViewById<Button>(R.id.capture_settings).isEnabled)
        val fullScreen = activity.findViewById<CheckBox>(R.id.home_ignore_size_crop)
        assertTrue("Recreated home reads the shared runtime override", fullScreen.isChecked)
        assertEquals("Only a playing safe owned cast can request a switch", label == "Stop", fullScreen.isEnabled)
        val summary = activity.findViewById<TextView>(R.id.home_applied_settings).text.toString()
        assertTrue(summary.startsWith("Active cast request"))
        assertTrue(summary.contains("Maximum FPS: 24 FPS"))
        assertTrue(summary.contains("Full screen: size/crop ignored"))
        assertFalse(summary.contains("Crop:"))
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }
}
