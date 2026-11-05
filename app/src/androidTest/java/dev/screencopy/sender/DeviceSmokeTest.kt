package dev.screencopy.sender

import android.content.Intent
import android.media.MediaCodecList
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @Test fun publicProjectionInventoryAndPreflightWorkFromApplicationContextWithoutConsent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val snapshot = ProjectionInventory.read(context)
        val display = DisplayInventory.fromReport(snapshot.capabilities).displays.single()
        assertEquals(0, display.id)
        assertTrue(display.width > 0 && display.height > 0)
        val settings = Settings().apply { audio = false; maxSize = 640; maxFps = 30 }
        val resolved = ProjectionInventory.validate(context, settings, natural = false)
        assertTrue(resolved.videoEncoder.isNotEmpty())
        assertEquals(640, VideoEncoderPreflight.validate(resolved, ProjectionInventory.displays(context)).output.let {
            maxOf(it.width, it.height)
        })
    }
    @Test fun realCodecInventoryIsQueryableWithoutStartingCapture() {
        assertTrue(MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.isNotEmpty())
        assertFalse(HardwareEncoderDiscovery.read().names("h264").isEmpty())
        val result = Bundle().apply {
            putString("senderOpusEncoders", HardwareEncoderDiscovery.read().names("opus").toString())
            putString("senderH264Encoders", HardwareEncoderDiscovery.read().names("h264").toString())
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, result)
    }
    @Test fun nativeActivityLaunchesWithoutStartingCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        instrumentation.waitForIdleSync()
        assertFalse(CastService.active)
        instrumentation.runOnMainSync { activity.finish() }
    }
}
