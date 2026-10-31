package dev.screencopy.sender

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Rect
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Production dialog handlers with synthetic readiness; not a physical capture reproduction. */
@RunWith(AndroidJUnit4::class)
class CropEditorDeviceTest {
    @Test fun emptyOffsetsCommitOnlyOnBlurOrApplyAndTypingKeepsItsCursor() = fixture { f ->
        f.main {
            val y = f.field(R.id.crop_y)
            y.requestFocus(); y.setText("")
            assertEquals("", y.text.toString())
            y.text.insert(0, "2"); y.text.insert(1, "28"); y.setSelection(1)
            assertEquals("228", y.text.toString()); assertEquals(1, y.selectionStart)
            f.field(R.id.crop_x).requestFocus()
            assertEquals("228", y.text.toString())
            y.requestFocus(); y.setText("")
            f.field(R.id.crop_x).requestFocus()
            assertEquals("0", y.text.toString())
            f.crop("1080", "1000", "", "")
            f.apply()
            assertFalse(f.editor.showing)
            assertEquals(0, f.store.read().cropX); assertEquals(0, f.store.read().cropY)
        }
    }

    @Test fun validNaturalY228SavesAndBoundsNegativeWhitespaceOverflowStayVisible() = fixture { f ->
        f.main {
            f.crop("1080", "1000", "0", "228")
            f.apply()
            assertFalse(f.editor.showing)
            assertEquals(1920, f.store.read().maxSize)
            assertEquals(228, f.store.read().cropY)
            f.dialog = f.editor.show()
        }
        f.idle()
        f.main {
            for (bad in listOf(" ", "-1", "2147483648", "1377")) {
                f.crop("1080", "1000", "0", bad)
                f.dialog.findViewById<ScrollView>(R.id.capture_settings_scroll).scrollTo(0, Int.MAX_VALUE)
                f.apply()
                assertTrue(f.editor.showing)
                assertEquals(228, f.store.read().cropY)
                assertNotNull(f.field(R.id.crop_y).error)
                assertTrue(f.field(R.id.crop_y).hasFocus())
                val notice = f.dialog.findViewById<TextView>(R.id.capture_settings_notice)
                assertTrue(notice.text.toString().startsWith("Not saved:"))
                assertTrue(notice.getGlobalVisibleRect(Rect()))
            }
            f.crop("", "1000", "0", "228"); f.apply()
            assertNotNull(f.field(R.id.crop_width).error)
            f.crop("0", "0", "0", "228"); f.apply()
            assertTrue(f.editor.showing); assertEquals(228, f.store.read().cropY)
        }
    }

    @Test fun refreshFailureRetryDuplicatesAndStaleDialogsCannotLockOrOverwriteDrafts() = fixture(refresh = true) { f ->
        f.main {
            assertFalse(f.applyButton().isEnabled)
            assertTrue(f.notice().contains("Refreshing"))
            f.crop("1080", "1000", "0", "228")
            val first = f.response!!
            first(Result.failure(IllegalStateException("fixture failure")))
            assertTrue(f.applyButton().isEnabled)
            assertTrue(f.notice().contains("Retry"))
            f.dialog.findViewById<Button>(R.id.refresh_capture_displays).performClick()
            assertFalse(f.applyButton().isEnabled)
            val second = f.response!!
            first(Result.success(f.inventory))
            assertFalse(f.applyButton().isEnabled)
            second(Result.success(f.inventory))
            assertTrue(f.applyButton().isEnabled)
            f.field(R.id.crop_y).requestFocus(); f.field(R.id.crop_y).setText("")
            val saved = f.editor.save()
            val staleApply = f.applyButton()
            val staleReset = f.dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
            f.editor.dismiss()
            f.editor.restore(saved); f.dialog = f.editor.show()
            assertEquals("", f.field(R.id.crop_y).text.toString())
            second(Result.failure(IllegalStateException("stale")))
            assertFalse(f.applyButton().isEnabled)
            staleApply.performClick()
            staleReset.performClick()
            assertTrue(f.editor.showing); assertEquals(0, f.store.read().cropWidth)
            assertEquals("", f.field(R.id.crop_y).text.toString())
            f.response!!(Result.success(f.inventory))
            f.active = true; f.editor.updateAvailability()
            assertFalse(f.editor.showing)
            f.applyButton().performClick()
            assertEquals(0, f.store.read().cropWidth)
        }
    }

    @Test fun everyInvalidCropFieldHasAnErrorAndOnlyAllZeroDisablesCrop() = fixture { f ->
        f.main {
            for (id in listOf(R.id.crop_width, R.id.crop_height, R.id.crop_x, R.id.crop_y)) {
                for (bad in listOf(" ", "-1", "2147483648")) {
                    f.crop("1080", "1000", "0", "228")
                    f.field(id).setText(bad)
                    f.apply()
                    assertTrue(f.editor.showing)
                    assertNotNull(f.field(id).error)
                    assertTrue(f.field(id).hasFocus())
                    assertTrue(f.notice().startsWith("Not saved:"))
                    assertEquals(0, f.store.read().cropWidth)
                }
            }
            f.crop("0", "0", "0", "228"); f.apply()
            assertTrue(f.editor.showing)
            assertTrue(f.notice().contains("all zero"))
            f.crop("0", "0", "", ""); f.apply()
            assertFalse(f.editor.showing)
            val saved = f.store.read()
            assertEquals(listOf(0, 0, 0, 0), listOf(saved.cropWidth, saved.cropHeight, saved.cropX, saved.cropY))
        }
    }

    @Test fun changedShizukuGenerationRejectsRefreshAndAllowsExplicitRetry() = fixture(refresh = true) { f ->
        f.main {
            f.crop("1080", "1000", "0", "228")
            val stale = f.response!!
            f.generation++
            stale(Result.success(f.inventory))
            assertTrue(f.applyButton().isEnabled)
            assertTrue(f.notice().contains("Shizuku changed"))
            assertTrue(f.notice().contains("Retry"))
            f.apply()
            assertTrue(f.editor.showing)
            assertEquals(0, f.store.read().cropWidth)
            f.dialog.findViewById<Button>(R.id.refresh_capture_displays).performClick()
            assertFalse(f.applyButton().isEnabled)
            stale(Result.success(f.inventory))
            assertFalse(f.applyButton().isEnabled)
            f.response!!(Result.success(f.inventory))
            assertTrue(f.applyButton().isEnabled)
            assertEquals("228", f.field(R.id.crop_y).text.toString())
            f.apply()
            assertFalse(f.editor.showing)
            assertEquals(228, f.store.read().cropY)
        }
    }

    @Test fun activeCastBlocksApplyAndResetEvenBeforeAvailabilityNotification() = fixture { f ->
        f.main {
            f.crop("1080", "1000", "0", "228")
            f.active = true
            f.apply()
            assertTrue(f.notice().contains("Stop sharing"))
            assertEquals(0, f.store.read().cropWidth)
            f.dialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
            assertEquals("228", f.field(R.id.crop_y).text.toString())
            f.editor.updateAvailability()
            assertFalse(f.editor.showing)
            assertThrows(IllegalStateException::class.java) { f.editor.show() }
        }
    }

    @Test fun receiverPresentationChoiceSurvivesDraftAndSavesWithoutChangingCaptureRotation() = fixture { f ->
        f.main {
            val orientation = f.dialog.findViewById<Spinner>(R.id.receiver_orientation)
            assertEquals("Automatic", orientation.selectedItem)
            orientation.setSelection(2)
        }
        f.idle()
        f.main {
            val saved = f.editor.save()
            f.editor.dismiss(); f.editor.restore(saved); f.dialog = f.editor.show()
            assertEquals("Portrait", f.dialog.findViewById<Spinner>(R.id.receiver_orientation).selectedItem)
            f.apply()
            assertEquals("portrait", f.store.read().receiverOrientation)
            assertEquals("0", f.store.read().orientation)
        }
    }

    private fun fixture(refresh: Boolean = false, test: (Fixture) -> Unit) {
        assumeTrue("Do not interfere with an active session", !CastService.active)
        val f = Fixture(refresh)
        try { test(f) } finally { f.close() }
    }

    private class Fixture(refresh: Boolean) {
        private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val context = instrumentation.targetContext
        private val name = "crop-editor-${UUID.randomUUID()}"
        private val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val store = CaptureSettingsStore(context.getSharedPreferences(name, 0))
        val inventory = DisplayInventory(listOf(CaptureDisplay(0, "Natural fixture", 1080, 2376, 0, 1)))
        var active = false
        var generation = 0L
        var response: ((Result<DisplayInventory>) -> Unit)? = null
        lateinit var editor: CaptureSettingsEditor
        lateinit var dialog: AlertDialog
        private fun fixtureHardware(): String {
            val entries = listOf(
                EncoderMetadata("video.fixture", "video.fixture", listOf("video/avc"), true, true, false),
                EncoderMetadata("audio.fixture", "audio.fixture", listOf("audio/opus"), true, true, false)
            )
            return "--video-codec=h264 --video-encoder='video.fixture'\n" +
                "--audio-codec=opus --audio-encoder='audio.fixture'\n" + HardwareEncoders(entries).report()
        }
        init {
            main {
                editor = CaptureSettingsEditor(activity, store,
                    { ShizukuSetup.Snapshot(generation = generation, state = ShizukuState.READY,
                        capabilities = inventory.report() + "\n" + fixtureHardware()) },
                    { active }, {}, if (refresh) { callback -> response = callback } else null)
                dialog = editor.show()
            }
            idle()
        }
        fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
        fun idle() = instrumentation.waitForIdleSync()
        fun field(id: Int): EditText = dialog.findViewById(id)
        fun applyButton(): Button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        fun apply() { applyButton().performClick() }
        fun notice() = dialog.findViewById<TextView>(R.id.capture_settings_notice).text.toString()
        fun crop(width: String, height: String, x: String, y: String) {
            field(R.id.crop_width).setText(width); field(R.id.crop_height).setText(height)
            field(R.id.crop_x).setText(x); field(R.id.crop_y).setText(y)
        }
        fun close() {
            main { editor.dismiss(); activity.finish() }
            assertTrue(context.deleteSharedPreferences(name))
        }
    }
}
