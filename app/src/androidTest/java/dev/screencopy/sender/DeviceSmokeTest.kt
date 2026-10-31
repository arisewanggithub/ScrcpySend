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
    @Test fun encryptedIdentityAndAdbCertificateCanBeCreatedOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Secrets(context)
        LocalAdb(store).use { adb ->
            assertEquals("127.0.0.1", adb.hostAddress)
            assertNotNull(adb.getCertificate())
            assertEquals("RSA", adb.getPrivateKey().algorithm)
            val encoded = adb.getCertificate().encoded
            LocalAdb(Secrets(context)).use { restored -> assertArrayEquals(encoded, restored.getCertificate().encoded) }
        }
    }
    @Test fun realCodecInventoryIsQueryableWithoutStartingCapture() {
        assertTrue(MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.isNotEmpty())
        assertFalse(CaptureBackend.encoders("h264").isEmpty())
        val result = Bundle().apply {
            putString("senderOpusEncoders", CaptureBackend.encoders("opus").toString())
            putString("senderH264Encoders", CaptureBackend.encoders("h264").toString())
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
