package dev.screencopy.sender

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import android.text.TextUtils
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import kotlin.math.abs

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
                assertTrue(activity.findViewById<TextView>(R.id.home_applied_settings_heading).text.toString()
                    .startsWith("Applied settings — next cast"))
                assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.finish(); state.finish(owner); model.updateDiscovery(emptyList()); model.setNextSizeOverride(false)
            }
        }
    }
    @Test fun homeSeparatesHeadingsFromDescriptionsAndEmphasizesReceiverNames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val model = ReceiverCasting.shared
        assumeTrue("Do not interfere with an active session", !CastService.active && model.snapshot().active == null)
        val preferences = instrumentation.targetContext.getSharedPreferences("settings", 0)
        val saved = preferences.getString("capture", null)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.waitForIdleSync()
        try {
            instrumentation.runOnMainSync {
                val heading = activity.findViewById<TextView>(R.id.home_applied_settings_heading)
                val summary = activity.findViewById<TextView>(R.id.home_applied_settings)
                assertTrue(heading.isAccessibilityHeading)
                assertTrue(heading.typeface.isBold)
                assertTrue(heading.textSize > summary.textSize)
                assertTrue(heading.text.toString().startsWith("Applied settings"))
                assertFalse(summary.text.toString().contains("Applied settings"))
                val version = activity.findViewById<TextView>(R.id.home_installed_version)
                assertTrue(summary.textSize > version.textSize)
                assertNotEquals(heading.currentTextColor, version.currentTextColor)
                val receivers = activity.findViewById<LinearLayout>(R.id.receiver_step)
                val capture = activity.findViewById<LinearLayout>(R.id.home_capture_settings)
                val parent = capture.parent as LinearLayout
                assertTrue("Receiver actions precede the detailed settings summary",
                    parent.indexOfChild(receivers) < parent.indexOfChild(capture))
                val scroll = activity.findViewById<View>(R.id.home_scroll)
                val bars = checkNotNull(scroll.rootWindowInsets).getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                assertTrue(scroll.paddingTop >= bars.top)
                val versionBounds = Rect()
                assertTrue(version.getGlobalVisibleRect(versionBounds))
                assertTrue(versionBounds.top >= bars.top)
                model.updateDiscovery(listOf(Discovery.Receiver("Readable receiver", "192.0.2.1",
                    27184, "available", System.nanoTime())))
                val rows = activity.findViewById<LinearLayout>(R.id.receiver_rows)
                val row = rows.getChildAt(0) as LinearLayout
                val description = row.getChildAt(0) as LinearLayout
                val name = description.getChildAt(0) as TextView
                val address = description.getChildAt(1) as TextView
                assertEquals("Readable receiver", name.text.toString())
                assertTrue(name.typeface.isBold)
                assertTrue(name.textSize > address.textSize)
                assertNotEquals(name.currentTextColor, address.currentTextColor)
                assertEquals("Play", (row.getChildAt(1) as Button).text.toString())
                assertEquals(saved, preferences.getString("capture", null))
                assertNull(model.snapshot().active)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish(); model.updateDiscovery(emptyList()) }
        }
    }

    @Test fun homeNoticePreviewKeepsCompleteDetailsWithoutChangingSavedSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Do not interfere with an active session",
            !CastService.active && ReceiverCasting.shared.snapshot().active == null)
        val preferences = instrumentation.targetContext.getSharedPreferences("settings", 0)
        val saved = preferences.getString("capture", null)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val message = "Synthetic session notice\n" + "Full diagnostic details remain available. ".repeat(20)
        val automation = instrumentation.uiAutomation
        try {
            instrumentation.runOnMainSync {
                val notice = activity.findViewById<TextView>(R.id.home_status_message)
                assertEquals(3, notice.maxLines)
                assertEquals(TextUtils.TruncateAt.END, notice.ellipsize)
                notice.text = message
                activity.findViewById<View>(R.id.home_status).visibility = View.VISIBLE
            }
            automation.executeAndWaitForEvent({
                instrumentation.runOnMainSync {
                    activity.findViewById<Button>(R.id.home_status_details).performClick()
                }
            }, { event ->
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    event.packageName?.toString() == activity.packageName
            }, 5000)
            automation.waitForIdle(100, 5000)
            val window = checkNotNull(automation.rootInActiveWindow)
            val details = window.findAccessibilityNodeInfosByViewId("android:id/message")
            assertEquals(1, details.size)
            assertEquals(message, details.single().text.toString())
            val close = window.findAccessibilityNodeInfosByViewId("android:id/button1").single()
            assertTrue(close.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            instrumentation.runOnMainSync {
                assertEquals(saved, preferences.getString("capture", null))
                assertFalse(CastService.active)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test fun fullScreenCheckboxAndLabelShareTheSameHorizontalCenterline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Do not interfere with an active session",
            !CastService.active && ReceiverCasting.shared.snapshot().active == null)
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        instrumentation.waitForIdleSync()
        try {
            instrumentation.runOnMainSync {
                val checkbox = activity.findViewById<CheckBox>(R.id.home_ignore_size_crop)
                assertEquals(checkbox.paddingTop, checkbox.paddingBottom)
                assertTrue(checkbox.width > 0 && checkbox.height > 0)
                val bitmap = Bitmap.createBitmap(checkbox.width, checkbox.height, Bitmap.Config.ARGB_8888)
                try {
                    checkbox.draw(Canvas(bitmap))
                    val icon = checkNotNull(checkbox.buttonDrawable).bounds
                    assertFalse(icon.isEmpty)
                    val layout = checkNotNull(checkbox.layout)
                    val textCenter = checkbox.totalPaddingTop +
                        (layout.getLineTop(0) + layout.getLineBottom(0)) / 2f
                    val delta = abs(icon.exactCenterY() - textCenter)
                    assertTrue("Checkbox icon and label differ by $delta pixels",
                        delta <= activity.resources.displayMetrics.density)
                    assertTrue(checkbox.height >= 48 * activity.resources.displayMetrics.density)
                } finally { bitmap.recycle() }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
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
        assertTrue(activity.findViewById<TextView>(R.id.home_applied_settings_heading).text.toString()
            .startsWith("Active cast request"))
        val summary = activity.findViewById<TextView>(R.id.home_applied_settings).text.toString()
        assertTrue(summary.contains("Maximum FPS: 24 FPS"))
        assertTrue(summary.contains("Full screen: size/crop ignored"))
        assertFalse(summary.contains("Crop:"))
        assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
    }
}
