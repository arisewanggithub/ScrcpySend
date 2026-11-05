package dev.scrcpy.releasecheck

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaCodecList
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import java.util.zip.ZipFile
import org.junit.Assert.*
import org.junit.Test

class ProjectionApiCheck {
    @Test fun actualAppDeclaresProjectionAudioPermissionsAndServiceTypes() {
        val app = TargetApp()
        @Suppress("DEPRECATION")
        val info = app.harness.packageManager.getPackageInfo(TargetApp.PACKAGE_NAME, PackageManager.GET_PERMISSIONS)
        val permissions = checkNotNull(info.requestedPermissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION in permissions)
        assertTrue(Manifest.permission.RECORD_AUDIO in permissions)
        assertTrue(Manifest.permission.MODIFY_AUDIO_SETTINGS in permissions)
        val service = app.harness.packageManager.getServiceInfo(ComponentName(TargetApp.PACKAGE_NAME,
            "${TargetApp.PACKAGE_NAME}.CastService"), 0)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            service.foregroundServiceType)
    }
    @Test fun systemCanCreateConsentIntentWithoutStartingCapture() {
        val app = TargetApp()
        val manager = app.context.getSystemService(MediaProjectionManager::class.java)
        val intent = manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        assertNotNull(app.harness.packageManager.resolveActivity(intent, 0))
    }
    @Test fun androidCodecMetadataIsAvailableWithoutPrivilegedIdentityOrAllocation() {
        val app = TargetApp()
        assertNotNull(app.context.getSystemService(MediaProjectionManager::class.java))
        assertTrue(MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { it.isEncoder })
    }
    @Test fun actualAppContainsNoServerPairingArchiveOrNativeDependency() {
        val app = TargetApp()
        ZipFile(app.context.applicationInfo.sourceDir).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toList()
            assertFalse(names.any { it.contains("scrcpy-server") || it.contains("spake2") || it.endsWith(".so") })
            assertFalse(names.any { it.contains("supervisor") })
        }
    }
}
