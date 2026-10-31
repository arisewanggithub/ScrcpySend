package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test

class HomeCaptureSettingsTest {
    private fun selected() = Settings().apply {
        mediaTransportMode = "srt"
        videoCodec = "av1"; videoEncoder = "c2.test.av1"
        videoBitrate = 2_500_000; maxSize = 1200; maxFps = 30; displayId = 2
        cropWidth = 640; cropHeight = 480; cropX = 12; cropY = 14
        orientation = "@270"; receiverOrientation = "portrait"; videoOptions = "profile=1"
        audioCodec = "aac"; audioEncoder = "c2.test.aac"; audioBitrate = 128_500
        audioSource = "mic"; audioOptions = "aac-profile=2"
    }

    @Test fun freshHomeDefaultsToUncheckedAndDoesNotListDefaults() {
        val saved = Settings()
        val effective = HomeCaptureSettings.effective(saved)
        assertNotSame(saved, effective)
        assertEquals(saved.maxSize, effective.maxSize)
        assertEquals("Default settings", HomeCaptureSettings.summary(effective))
    }

    @Test fun everyNondefaultParameterHasReadableUnitsAndRequestedLabels() {
        val summary = HomeCaptureSettings.summary(selected())
        for (line in listOf("Media transport: SRT (fMP4)", "Video codec: av1",
            "Video encoder: c2.test.av1", "Video bitrate: 2.5 Mbps", "Maximum size: 1200 pixels",
            "Maximum FPS: 30 FPS", "Screen to share: Display 2", "Crop: 640 × 480 pixels at (12, 14)",
            "Capture orientation: 270° (locked)", "Receiver orientation: Portrait",
            "Video codec options: profile=1", "Audio codec: aac", "Audio encoder: c2.test.aac",
            "Audio bitrate: 128.5 kbps", "Audio source: mic", "Audio codec options: aac-profile=2")) {
            assertTrue(line, summary.contains(line))
        }
        assertFalse(summary.contains("Default settings"))
        assertFalse(summary.contains("Audio: enabled"))
    }

    @Test fun isolatedChangeDoesNotListOtherDefaultsOrPretendAutomaticIsActualEncoder() {
        val saved = Settings().apply { maxFps = 24 }
        assertEquals("Maximum FPS: 24 FPS", HomeCaptureSettings.summary(saved))
        saved.videoCodec = "av1"
        assertFalse(HomeCaptureSettings.summary(saved).contains("Video encoder"))
    }

    @Test fun disabledAudioAndPcmHideInapplicableSubsettings() {
        val saved = selected().apply { audio = false }
        val disabled = HomeCaptureSettings.summary(saved)
        assertTrue(disabled.contains("Audio: disabled"))
        assertFalse(disabled.contains("Audio codec"))
        assertFalse(disabled.contains("Audio source"))
        assertFalse(disabled.contains("Audio bitrate"))
        assertFalse(disabled.contains("Audio encoder"))
        saved.audio = true; saved.mediaTransportMode = "custom"; saved.audioCodec = "raw"
        val pcm = HomeCaptureSettings.summary(saved)
        assertTrue(pcm.contains("Audio codec: Raw PCM"))
        assertTrue(pcm.contains("Audio source: mic"))
        assertFalse(pcm.contains("Audio bitrate"))
        assertFalse(pcm.contains("Audio encoder"))
        assertFalse(pcm.contains("Audio codec options"))
    }

    @Test fun fullScreenCopyOnlyChangesSizeAndCropAndNeverWritesSavedSettings() {
        val saved = selected()
        val snapshot = saved.request().toString()
        val effective = HomeCaptureSettings.effective(saved, true)
        assertNotSame(saved, effective)
        assertEquals(0, effective.maxSize)
        assertEquals(listOf(0, 0, 0, 0), listOf(effective.cropWidth, effective.cropHeight, effective.cropX, effective.cropY))
        assertEquals(saved.mediaTransportMode, effective.mediaTransportMode)
        assertEquals(saved.videoCodec, effective.videoCodec)
        assertEquals(saved.videoEncoder, effective.videoEncoder)
        assertEquals(saved.videoBitrate, effective.videoBitrate)
        assertEquals(saved.maxFps, effective.maxFps)
        assertEquals(saved.displayId, effective.displayId)
        assertEquals(saved.orientation, effective.orientation)
        assertEquals(saved.receiverOrientation, effective.receiverOrientation)
        assertEquals(saved.videoOptions, effective.videoOptions)
        assertEquals(saved.audio, effective.audio)
        assertEquals(saved.audioCodec, effective.audioCodec)
        assertEquals(saved.audioEncoder, effective.audioEncoder)
        assertEquals(saved.audioBitrate, effective.audioBitrate)
        assertEquals(saved.audioSource, effective.audioSource)
        assertEquals(saved.audioOptions, effective.audioOptions)
        assertEquals(snapshot, saved.request().toString())
        effective.videoBitrate = 3_000_000
        assertEquals(2_500_000, saved.videoBitrate)
        assertEquals(snapshot, HomeCaptureSettings.effective(saved, false).request().toString())
    }

    @Test fun overrideIsExplainedEvenWithoutSavedCropAndUnlimitedSizeIsReadable() {
        val saved = Settings().apply { maxSize = 0 }
        assertEquals("Maximum size: Unlimited", HomeCaptureSettings.summary(saved))
        assertEquals("Full screen: size/crop ignored",
            HomeCaptureSettings.summary(HomeCaptureSettings.effective(saved, true), true))
        val summary = HomeCaptureSettings.summary(HomeCaptureSettings.effective(selected(), true), true)
        assertTrue(summary.contains("Full screen: size/crop ignored"))
        assertFalse(summary.contains("Maximum size:"))
        assertFalse(summary.contains("Crop:"))
        assertTrue(summary.contains("Maximum FPS: 30 FPS"))
    }

    @Test fun onlyEffectiveGeometryIsValidatedAndOtherInvalidParametersStillFailExplicitly() {
        val saved = Settings().apply { maxSize = -1; cropWidth = 10; cropHeight = -1 }
        assertThrows(IllegalArgumentException::class.java) { HomeCaptureSettings.effective(saved) }
        val full = HomeCaptureSettings.effective(saved, true)
        assertEquals(0, full.maxSize); assertEquals(0, full.cropHeight)
        assertEquals(-1, saved.maxSize); assertEquals(-1, saved.cropHeight)
        saved.mediaTransportMode = "rtsp"
        assertThrows(IllegalArgumentException::class.java) { HomeCaptureSettings.effective(saved, true) }
        saved.mediaTransportMode = "custom"; saved.videoOptions = "max-bframes=1"
        assertThrows(IllegalArgumentException::class.java) { HomeCaptureSettings.effective(saved, true) }
    }

    @Test fun playFreezesEffectiveWireAndBackendContractAndCheckboxChangesOnlyNextCast() {
        val saved = selected()
        var ignore = true
        val casting = ReceiverCasting { 1L }
        casting.updateDiscovery(listOf(Discovery.Receiver("TV", "192.0.2.1", 27184, "available", 1)))
        val starts = mutableListOf<ReceiverCasting.Request>()
        val actions = ReceiverRowActions(casting, {},
            { HomeCaptureSettings.effective(saved, ignore) }, { starts.add(it) }, {})
        val row = casting.rows(true).single()
        actions.press(ReceiverKey.of(row.target), null, row.revision)
        val request = starts.single()
        val parsed = Settings.from(Wire.parseJson(request.settings.toByteArray()))
        assertEquals(0, parsed.maxSize)
        assertTrue(parsed.request().getJSONObject("video").isNull("crop"))
        val options = CaptureBackend.options(parsed, "12345678")
        assertTrue(options.contains(" max_size=0 "))
        assertFalse(options.contains(" crop="))
        assertTrue(options.contains(" max_fps=30 "))
        assertTrue(options.contains(" display_id=2 "))
        assertTrue(options.contains(" capture_orientation=@270 "))
        assertTrue(options.contains(" video_bit_rate=2500000"))
        assertTrue(options.contains(" downsize_on_error=false "))
        ignore = false
        saved.maxFps = 24
        assertEquals(request.settings, casting.snapshot().active!!.request.settings)
        assertEquals(30, parsed.maxFps)
        assertTrue(casting.complete(request.token))
        val next = casting.rows(true).single()
        actions.press(ReceiverKey.of(next.target), null, next.revision)
        val nextSettings = Settings.from(Wire.parseJson(starts.last().settings.toByteArray()))
        assertEquals(1200, nextSettings.maxSize)
        assertEquals(640, nextSettings.cropWidth)
        assertEquals(24, nextSettings.maxFps)
    }

    @Test fun nativeDisplayPreflightRejectsUnsupportedFullSizeWithoutApplyingASuggestion() {
        val saved = Settings().apply { cropWidth = 640; cropHeight = 480; maxSize = 1000 }
        val before = saved.request().toString()
        val inventory = DisplayInventory(listOf(CaptureDisplay(0, "Panel", 1440, 3168, 0, 1)))
        val cropped = ScrcpyVideoSizing.calculate(HomeCaptureSettings.effective(saved), inventory)
        assertEquals(VideoSize(640, 480), cropped.output)
        val effective = HomeCaptureSettings.effective(saved, true)
        val full = ScrcpyVideoSizing.calculate(effective, inventory)
        assertEquals(VideoSize(1440, 3168), full.requested)
        assertEquals(VideoSize(1440, 3168), full.output)
        val limits = VideoLimits(32, 1920, 32, 1920, 2, 2, 1.0, 60.0)
        val suggestion = VideoSize(872, 1920) to 60
        val error = assertThrows(VideoGeometryException::class.java) {
            VideoGeometryValidation.validate(full, listOf(EncoderGeometryCheck(limits, false, false, suggestion)), false)
        }
        assertEquals(VideoGeometryReason.SIZE_OR_ALIGNMENT, error.reason)
        assertEquals(suggestion, error.suggestion)
        assertEquals(VideoSize(1440, 3168), full.output)
        assertEquals(0, effective.maxSize)
        assertEquals(before, saved.request().toString())
    }
}
