package dev.screencopy.sender

import android.media.MediaCodecList
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareEncoderInventoryDeviceTest {
    @Test fun inventoryMatchesOfficialFlagsForEveryEncodedTrackWithoutAllocatingCodecs() {
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        for ((codec, mime) in HardwareEncoderPolicy.mimeTypes) {
            val expected = infos.filter {
                it.isEncoder && (!mime.startsWith("video/") || it.isHardwareAccelerated && !it.isSoftwareOnly) &&
                    it.supportedTypes.any { type -> type.equals(mime, true) }
            }.map { it.name }.distinct()
            assertEquals(expected, CaptureBackend.encoders(codec))
            assertEquals(expected, HardwareEncoderDiscovery.codecs(codec).map { it.name }.distinct())
        }
        assertTrue(CaptureBackend.encoders("raw").isEmpty())
    }
}
