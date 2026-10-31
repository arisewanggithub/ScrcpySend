package dev.screencopy.sender

import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Native editor fixture only; synthetic UI readiness does not claim capture capability. */
@RunWith(AndroidJUnit4::class)
class CaptureSettingsDeviceTest {
    @Before fun requireUnlockedScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Visible settings tests require the user to unlock the phone",
            !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
    }

    @Test fun spinnerRipplesAreBoundedAndScrollingCannotPaintCaretOverApply() {
        assumeTrue("Do not interfere with an active session", !CastService.active)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "settings-caret-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, 0)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var editor: CaptureSettingsEditor? = null
        lateinit var dialog: AlertDialog
        lateinit var scroll: ScrollView
        lateinit var footer: Rect
        var offsets = emptyList<Int>()
        try {
            instrumentation.runOnMainSync {
                editor = CaptureSettingsEditor(activity, CaptureSettingsStore(preferences),
                    { ShizukuSetup.Snapshot(state = ShizukuState.READY, capabilities = fixtureDisplays()) },
                    { false }, {})
                dialog = editor!!.show()
                scroll = dialog.findViewById(R.id.capture_settings_scroll)
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertTrue("Exercise hardware background projection, not software rendering", scroll.isHardwareAccelerated)
                assertEquals(View.LAYER_TYPE_NONE, scroll.layerType)
                val form = scroll.getChildAt(0) as ViewGroup
                val spinners = (0 until form.childCount).map(form::getChildAt).filterIsInstance<Spinner>()
                assertTrue(spinners.isNotEmpty())
                fun assertBounded(drawable: Drawable): Int = when (drawable) {
                    is RippleDrawable -> {
                        assertTrue("An unbounded ripple projects the spinner caret outside the scroll",
                            drawable.numberOfLayers > 0)
                        drawable.findDrawableByLayerId(android.R.id.mask)?.let {
                            assertEquals("Keep ripple feedback visible inside its bounds", PixelFormat.OPAQUE, it.opacity)
                        }
                        1
                    }
                    is LayerDrawable -> (0 until drawable.numberOfLayers).sumOf { assertBounded(drawable.getDrawable(it)) }
                    else -> 0
                }
                for (spinner in spinners) {
                    assertEquals(View.LAYER_TYPE_NONE, spinner.layerType)
                    assertTrue("Keep the native layered caret background", spinner.background is LayerDrawable)
                    assertTrue("Keep native pressed/focused ripple feedback", assertBounded(spinner.background) > 0)
                }
                val apply = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                val applyPosition = IntArray(2).also(apply::getLocationInWindow)
                footer = Rect(applyPosition[0], applyPosition[1],
                    applyPosition[0] + apply.width, applyPosition[1] + apply.height)
                val scrollPosition = IntArray(2).also(scroll::getLocationInWindow)
                assertTrue("Buttons must be outside the scrolling viewport", scrollPosition[1] + scroll.height <= footer.top)
                val maxScroll = (form.height - scroll.height + scroll.paddingTop + scroll.paddingBottom).coerceAtLeast(0)
                assertTrue("The regression requires scrollable settings", maxScroll > 0)
                // Put each off-screen spinner's caret at Apply's Y position if projection leaks.
                offsets = (listOf(0, maxScroll / 2, maxScroll) + spinners.map {
                    (scrollPosition[1] + it.top + it.height / 2 - footer.centerY()).coerceIn(0, maxScroll)
                }).distinct()
                scroll.scrollTo(0, maxScroll)
            }
            fun copyFooter(): Bitmap {
                assumeTrue("PixelCopy requires the user to keep the phone unlocked",
                    !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                val frames = CountDownLatch(1)
                instrumentation.runOnMainSync {
                    scroll.postOnAnimation { scroll.postOnAnimation { frames.countDown() } }
                }
                assertTrue("Wait for hardware drawing", frames.await(5, TimeUnit.SECONDS))
                val bitmap = Bitmap.createBitmap(footer.width(), footer.height(), Bitmap.Config.ARGB_8888)
                val copied = CountDownLatch(1)
                var result = PixelCopy.ERROR_UNKNOWN
                // Android 11 offsets this Rect in place by the floating window's surface insets.
                // Reusing footer would copy a different region on every request.
                PixelCopy.request(checkNotNull(dialog.window), Rect(footer), bitmap, {
                    result = it
                    copied.countDown()
                }, Handler(Looper.getMainLooper()))
                assertTrue("Wait for window pixels", copied.await(5, TimeUnit.SECONDS))
                assertEquals(PixelCopy.SUCCESS, result)
                return bitmap
            }
            val baseline = copyFooter()
            try {
                for (offset in offsets + offsets.asReversed()) {
                    instrumentation.runOnMainSync { scroll.scrollTo(0, offset) }
                    val actual = copyFooter()
                    try {
                        if (!baseline.sameAs(actual)) {
                            val expectedPixels = IntArray(baseline.width * baseline.height)
                            val actualPixels = IntArray(expectedPixels.size)
                            baseline.getPixels(expectedPixels, 0, baseline.width, 0, 0, baseline.width, baseline.height)
                            actual.getPixels(actualPixels, 0, actual.width, 0, 0, actual.width, actual.height)
                            var count = 0
                            var left = baseline.width
                            var top = baseline.height
                            var right = 0
                            var bottom = 0
                            for (index in expectedPixels.indices) if (expectedPixels[index] != actualPixels[index]) {
                                count++
                                val x = index % baseline.width
                                val y = index / baseline.width
                                left = minOf(left, x); top = minOf(top, y)
                                right = maxOf(right, x + 1); bottom = maxOf(bottom, y + 1)
                            }
                            val bounds = if (count == 0) Rect() else Rect(left, top, right, bottom)
                            val baselineFile = File(context.cacheDir, "capture-settings-caret-baseline.png")
                            val actualFile = File(context.cacheDir, "capture-settings-caret-actual.png")
                            baselineFile.outputStream().use { baseline.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            actualFile.outputStream().use { actual.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            val diagnostic = "offset=$offset footerWindowRect=$footer " +
                                "bitmap=${baseline.width}x${baseline.height} differingPixels=$count " +
                                "diffBoundsInBitmap=$bounds baseline=${baselineFile.absolutePath} " +
                                "actual=${actualFile.absolutePath}"
                            File(context.cacheDir, "capture-settings-caret-diff.txt").writeText(diagnostic)
                            fail("Scrolling to $offset must not draw spinner carets over Apply; $diagnostic")
                        }
                    } finally { actual.recycle() }
                }
            } finally { baseline.recycle() }
        } finally {
            instrumentation.runOnMainSync { editor?.dismiss(); activity.finish() }
            assertTrue(context.deleteSharedPreferences(name))
        }
    }

    @Test fun mediaTransportSurvivesApplyReopenFreshEditorDraftRestoreAndReset() {
        assumeTrue("Do not interfere with an active session", !CastService.active)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "transport-ui-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, 0)
        val store = CaptureSettingsStore(preferences)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        fun newEditor() = CaptureSettingsEditor(activity, store,
            { ShizukuSetup.Snapshot(state = ShizukuState.READY, capabilities = fixtureDisplays()) }, { false }, {})
        var editor: CaptureSettingsEditor? = null
        var dialog: AlertDialog? = null
        try {
            for ((mode, label) in listOf(
                "srt" to "SRT (fMP4)",
                "custom" to "Custom TCP"
            )) {
                instrumentation.runOnMainSync {
                    editor = newEditor()
                    dialog = editor!!.show()
                    val transport = dialog!!.findViewById<Spinner>(R.id.media_transport)
                    transport.setSelection((0 until transport.count).single {
                        transport.getItemAtPosition(it) == label
                    })
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertFalse(editor!!.showing)
                    assertEquals(mode, CaptureSettingsStore(preferences).read().mediaTransportMode)
                    dialog = editor!!.show()
                    assertEquals("Reopening must retain $mode", label,
                        dialog!!.findViewById<Spinner>(R.id.media_transport).selectedItem)
                    editor!!.dismiss()
                    editor = newEditor()
                    dialog = editor!!.show()
                    assertEquals("Loading saved settings must retain $mode", label,
                        dialog!!.findViewById<Spinner>(R.id.media_transport).selectedItem)
                    val draft = editor!!.save()
                    editor!!.dismiss()
                    editor = newEditor().apply { restore(draft) }
                    dialog = editor!!.show()
                    assertEquals("Restoring a draft must retain $mode", label,
                        dialog!!.findViewById<Spinner>(R.id.media_transport).selectedItem)
                    dialog!!.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                    assertEquals("Reset must restore Custom TCP", "Custom TCP",
                        dialog!!.findViewById<Spinner>(R.id.media_transport).selectedItem)
                }
                instrumentation.waitForIdleSync()
                instrumentation.runOnMainSync {
                    dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                    assertFalse(editor!!.showing)
                    assertEquals("custom", store.read().mediaTransportMode)
                }
            }
        } finally {
            instrumentation.runOnMainSync { editor?.dismiss(); activity.finish() }
            assertTrue(context.deleteSharedPreferences(name))
        }
    }

    @Test fun removedSavedTransportRequiresUserChoiceAndPreservesOtherFields() {
        assumeTrue("Do not interfere with an active session", !CastService.active)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "removed-transport-ui-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, 0)
        val saved = Settings().apply {
            mediaTransportMode = "rtsp-tcp"; maxFps = 30; videoBitrate = 20_000_000
            orientation = "@90"; receiverOrientation = "portrait"
            cropWidth = 640; cropHeight = 480; cropX = 12; cropY = 24
        }.request().toString()
        assertTrue(preferences.edit().putString("capture", saved).commit())
        val store = CaptureSettingsStore(preferences)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var editor: CaptureSettingsEditor? = null
        try {
            instrumentation.runOnMainSync {
                editor = CaptureSettingsEditor(activity, store,
                    { ShizukuSetup.Snapshot(state = ShizukuState.READY, capabilities = fixtureDisplays()) }, { false }, {})
                val dialog = editor!!.show()
                val transport = dialog.findViewById<Spinner>(R.id.media_transport)
                assertTrue(transport.selectedItem.toString().startsWith("Unavailable saved transport"))
                assertTrue(dialog.findViewById<TextView>(R.id.capture_settings_notice).text.toString().contains("unavailable"))
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(editor!!.showing)
                assertEquals(saved, preferences.getString("capture", null))
                val draft = editor!!.save()
                editor!!.dismiss()
                editor = CaptureSettingsEditor(activity, store,
                    { ShizukuSetup.Snapshot(state = ShizukuState.READY, capabilities = fixtureDisplays()) }, { false }, {})
                    .apply { restore(draft) }
                val reopened = editor!!.show()
                val restored = reopened.findViewById<Spinner>(R.id.media_transport)
                assertTrue(restored.selectedItem.toString().startsWith("Unavailable saved transport"))
                restored.setSelection((0 until restored.count).single { restored.getItemAtPosition(it) == "SRT (fMP4)" })
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                editor!!.show().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertFalse(editor!!.showing)
                assertEquals(Settings.from(org.json.JSONObject(saved).put("mediaTransportMode", "srt")).request().toString(),
                    store.read().request().toString())
            }
        } finally {
            instrumentation.runOnMainSync { editor?.dismiss(); activity.finish() }
            assertTrue(context.deleteSharedPreferences(name))
        }
    }

    @Test fun productionDisplayPickerMapsFreshInventoryByIdAndNoCodecStabilityWarningAppears() {
        assumeTrue("Do not interfere with an active session", !CastService.active)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "display-ui-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, 0)
        val store = CaptureSettingsStore(preferences)
        store.write(Settings().apply { displayId = 7 })
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var inventory = DisplayInventory(listOf(CaptureDisplay(0, "Phone", 1080, 2400, 0, 1),
            CaptureDisplay(7, "HDMI monitor", 1920, 1080, 0, 2)))
        var response: ((Result<DisplayInventory>) -> Unit)? = null
        var active = false
        var editor: CaptureSettingsEditor? = null
        var dialog: AlertDialog? = null
        try {
            instrumentation.runOnMainSync {
                editor = CaptureSettingsEditor(activity, store,
                    { ShizukuSetup.Snapshot(state = ShizukuState.READY, capabilities = inventory.report() + "\n" + fixtureHardware()) },
                    { active }, {}, { response = it })
                dialog = editor!!.show()
                val displays = dialog!!.findViewById<Spinner>(R.id.capture_display)
                assertEquals(7, (displays.selectedItem as DisplayInventory.Choice).id)
                assertTrue(displays.selectedItem.toString().contains("HDMI monitor"))
                assertTrue(displays.selectedItem.toString().contains("1920x1080"))
                assertFalse(dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled)
                assertNoStabilityWarnings(dialog!!)
                val codec = dialog!!.findViewById<Spinner>(R.id.video_codec)
                codec.setSelection((0 until codec.count).single { codec.getItemAtPosition(it) == "h265" })
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertNoStabilityWarnings(dialog!!)
                val codec = dialog!!.findViewById<Spinner>(R.id.video_codec)
                codec.setSelection((0 until codec.count).single { codec.getItemAtPosition(it) == "h264" })
                inventory = DisplayInventory(listOf(CaptureDisplay(0, "Phone", 1080, 2400, 0, 1),
                    CaptureDisplay(42, "Presentation", 800, 600, 0, 5)))
                response!!.invoke(Result.success(inventory))
                val displays = dialog!!.findViewById<Spinner>(R.id.capture_display)
                val missing = displays.selectedItem as DisplayInventory.Choice
                assertEquals(7, missing.id); assertNull(missing.display)
                dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(editor!!.showing); assertEquals(7, store.read().displayId)
                displays.setSelection((0 until displays.count).single { (displays.getItemAtPosition(it) as DisplayInventory.Choice).id == 42 })
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertNoStabilityWarnings(dialog!!)
                dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertFalse(editor!!.showing)
                assertEquals(42, store.read().displayId)
                assertTrue(store.read().request().getJSONObject("video").get("displayId") is Int)
                dialog = editor!!.show()
                val late = response!!
                active = true; editor!!.updateAvailability()
                late(Result.success(DisplayInventory(listOf(CaptureDisplay(99, "Late", 640, 480, 0, 5)))))
                assertFalse(editor!!.showing); assertEquals(42, store.read().displayId)
            }
        } finally {
            instrumentation.runOnMainSync { editor?.dismiss(); activity.finish() }
            assertTrue(context.deleteSharedPreferences(name))
        }
    }

    @Test fun productionAudioPresetsKeepInvalidSelectionUntilExplicitChoiceAndRawIsNotEditable() {
        assumeTrue("Do not interfere with an active session", !CastService.active)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "bitrate-ui-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, 0)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val store = CaptureSettingsStore(preferences)
        val metadata = CodecBitrates(listOf(EncoderBitrate("opus", "opus.fixture", 32_000, 256_000),
            EncoderBitrate("aac", "aac.fixture", 64_000, 128_000)))
        var editor: CaptureSettingsEditor? = null
        var dialog: AlertDialog? = null
        try {
            instrumentation.runOnMainSync {
                editor = CaptureSettingsEditor(activity, store,
                    { ShizukuSetup.Snapshot(state = ShizukuState.READY, capabilities = metadata.report() + "\n" + fixtureDisplays()) }, { false }, {})
                dialog = editor!!.show()
                val opusRates = dialog!!.findViewById<Spinner>(R.id.audio_bitrate)
                assertEquals(1, (0 until opusRates.count).count { opusRates.getItemAtPosition(it).toString().contains("recommended") })
                val codec = dialog!!.findViewById<Spinner>(R.id.audio_codec)
                codec.setSelection((0 until codec.count).single { codec.getItemAtPosition(it) == "aac" })
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val rate = dialog!!.findViewById<Spinner>(R.id.audio_bitrate)
                val retained = rate.selectedItem as Bitrates.AudioChoice
                assertEquals(1, (0 until rate.count).count { rate.getItemAtPosition(it).toString().contains("recommended") })
                assertEquals("128 kbps (recommended)", (0 until rate.count).map { rate.getItemAtPosition(it) as Bitrates.AudioChoice }
                    .single { it.bps == 128_000 }.label)
                assertEquals(192_000, retained.bps); assertFalse(retained.valid)
                dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertTrue(editor!!.showing); assertEquals("opus", store.read().audioCodec)
                rate.setSelection((0 until rate.count).single { (rate.getItemAtPosition(it) as Bitrates.AudioChoice).bps == 128_000 })
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertFalse(editor!!.showing); assertEquals("aac", store.read().audioCodec)
                assertEquals(128_000, store.read().audioBitrate)
                dialog = editor!!.show()
                val codec = dialog!!.findViewById<Spinner>(R.id.audio_codec)
                codec.setSelection((0 until codec.count).single { codec.getItemAtPosition(it) == "raw" })
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val rate = dialog!!.findViewById<Spinner>(R.id.audio_bitrate)
                assertFalse(rate.isEnabled)
                assertNull((rate.selectedItem as Bitrates.AudioChoice).bps)
                dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
                assertEquals("raw", store.read().audioCodec)
                assertEquals(128_000, store.read().audioBitrate) // Unused placeholder is not silently rewritten.
            }
        } finally {
            instrumentation.runOnMainSync { editor?.dismiss(); activity.finish() }
            assertTrue(context.deleteSharedPreferences(name))
        }
    }

    @Test fun productionSettingsDraftSurvivesRecreationAndApplyRequiresStoppedSession() {
        assumeTrue("Do not interfere with an active session", !CastService.active)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "settings-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(name, 0)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var editor: CaptureSettingsEditor? = null
        try {
            instrumentation.runOnMainSync {
                var active = false
                val store = CaptureSettingsStore(preferences)
                val proof = { ShizukuSetup.Snapshot(state = ShizukuState.READY, capabilities = fixtureDisplays()) }
                var applied = 0
                var appliedSummary = HomeCaptureSettings.summary(store.read())
                val onApplied = {
                    applied++
                    appliedSummary = HomeCaptureSettings.summary(store.read())
                }
                val original = CaptureSettingsEditor(activity, store, proof, { active }, {}, onApplied = onApplied)
                val dialog = original.show()
                assertEquals("8", dialog.findViewById<EditText>(R.id.video_bitrate).text.toString())
                dialog.findViewById<EditText>(R.id.video_bitrate).setText("12.000001")
                val saved = original.save()
                original.dismiss()
                assertEquals(0, applied)
                assertEquals("Default settings", appliedSummary)
                val restored = CaptureSettingsEditor(activity, store, proof, { active }, {}, onApplied = onApplied)
                editor = restored
                restored.restore(saved)
                active = true
                assertThrows(IllegalStateException::class.java) { restored.show() }
                active = false
                val reopened = restored.show()
                assertEquals("12.000001", reopened.findViewById<EditText>(R.id.video_bitrate).text.toString())
                val apply = reopened.getButton(AlertDialog.BUTTON_POSITIVE)
                active = true; restored.updateAvailability()
                assertFalse(restored.showing)
                assertFalse(apply.isEnabled)
                apply.performClick()
                reopened.getButton(AlertDialog.BUTTON_NEUTRAL).performClick()
                assertEquals(8_000_000, store.read().videoBitrate)
                assertEquals(0, applied)
                assertEquals("Default settings", appliedSummary)
                active = false
                val idleDialog = restored.show()
                val idleApply = idleDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                assertEquals("12.000001", idleDialog.findViewById<EditText>(R.id.video_bitrate).text.toString())
                assertTrue(idleApply.isEnabled); idleApply.performClick()
                assertEquals(12_000_001, store.read().videoBitrate)
                assertEquals(1, applied)
                assertEquals("Video bitrate: 12.000001 Mbps", appliedSummary)
                assertFalse(restored.showing)
            }
        } finally {
            instrumentation.runOnMainSync { editor?.dismiss(); activity.finish() }
            assertTrue(context.deleteSharedPreferences(name))
        }
    }

    private fun fixtureDisplays() = DisplayInventory(listOf(CaptureDisplay(0, "Test panel", 1080, 2400, 0, 1))).report() +
        "\n" + fixtureHardware()
    private fun fixtureHardware(): String {
        val entries = HardwareEncoderPolicy.mimeTypes.map { (codec, mime) ->
            val video = mime.startsWith("video/")
            EncoderMetadata("$codec.fixture", "$codec.fixture", listOf(mime), true, video, !video)
        }
        val backend = entries.zip(HardwareEncoderPolicy.mimeTypes.keys).joinToString("\n") { (entry, codec) ->
            val track = if (codec in setOf("h264", "h265", "av1")) "video" else "audio"
            "--$track-codec=$codec --$track-encoder='${entry.name}'"
        }
        return backend + "\n" + HardwareEncoders(entries).report()
    }
    private fun assertNoStabilityWarnings(dialog: AlertDialog) {
        fun text(view: View): String = buildString {
            if (view is TextView) append(view.text).append('\n')
            if (view is ViewGroup) for (index in 0 until view.childCount) append(text(view.getChildAt(index)))
        }
        val visibleText = text(checkNotNull(dialog.window).decorView)
        for (copy in listOf("reported phone freeze", "not verified fixed", "unstable", "H.264 is recommended"))
            assertFalse(visibleText.contains(copy, ignoreCase = true))
    }
}
