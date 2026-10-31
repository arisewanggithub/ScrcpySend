package dev.screencopy.sender

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.SystemClock
import android.view.View
import android.widget.Button
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku

/** Compiled acceptance checks; running does not grant permission or start Shizuku. */
@RunWith(AndroidJUnit4::class)
class ShizukuDeviceTest {
    @Test fun ordinaryAppUidCannotMasqueradeAsShellUserService() {
        assertThrows(SecurityException::class.java) { ShellCaptureService().attach(Binder(), false) }
    }

    @Test fun unavailablePrimaryScreenExposesShizukuNotEmbeddedPairingPorts() {
        assumeTrue("Run UI checks with an idle test app", !CastService.active && !ShizukuSetup.shared.snapshot().ready)
        assumeTrue("Setup form is relevant only before permission/availability", !Shizuku.pingBinder() ||
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                assertNotNull(activity.findViewById<Button>(R.id.shizuku_connect))
                assertNotNull(activity.findViewById<Button>(R.id.shizuku_permission))
                assertNull(activity.findViewById<View>(R.id.adb_pairing_code))
                assertNull(activity.findViewById<View>(R.id.adb_pairing_port))
                assertFalse(activity.findViewById<View>(R.id.receiver_step).isShown)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun authorizedEntryChecksAutomaticallyAndHidesSetupAfterRealProof() {
        assumeTrue("User must first start Shizuku and grant ScreenCopy", Shizuku.pingBinder())
        assumeTrue("No automatic permission grant", Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
        assumeTrue("This test accepts shell only, never automatic root consent", Shizuku.getUid() == 2000)
        assumeTrue("Do not interrupt an active cast", !CastService.active)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val initialGeneration = ShizukuSetup.shared.snapshot().generation
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            var receiverVisible = false
            val deadline = SystemClock.uptimeMillis() + 22000
            while (SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    receiverVisible = activity.findViewById<View>(R.id.receiver_step).isShown
                }
                val latest = ShizukuSetup.shared.snapshot()
                if (receiverVisible || (latest.generation > initialGeneration &&
                        latest.state == ShizukuState.ERROR && !latest.cleanupPending)) break
                SystemClock.sleep(30)
            }
            assertTrue("Real automatic capture proof failed: ${ShizukuSetup.shared.snapshot().message}", receiverVisible)
            instrumentation.runOnMainSync {
                assertFalse(activity.findViewById<View>(R.id.adb_step).isShown)
                assertNotNull(activity.findViewById<View>(R.id.receiver_rows))
                assertNotNull(activity.findViewById<Button>(R.id.capture_settings))
                for (id in listOf(R.id.receiver_ip, R.id.receiver_tcp_port, R.id.continue_receiver,
                    R.id.cast_step, R.id.start_cast, R.id.stop_cast))
                    assertNull(activity.findViewById<View>(id))
                assertFalse(CastService.active)
            }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun authorizedShellUserServiceReallyDeploysAndEnumeratesPinnedBackend() {
        assumeTrue("User must first start Shizuku and grant this app explicitly", Shizuku.pingBinder())
        assumeTrue("User permission required", Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
        assumeTrue("Test deliberately accepts shell mode only", Shizuku.getUid() == 2000)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ShizukuClient(context, false) {}.use { client ->
            client.connect()
            assertTrue(client.alive)
            CaptureBackend(context, client.bridge()) { throw it }.use {
                it.deploy()
                val report = it.capabilities()
                assertTrue(report.contains("--display-id="))
                assertTrue(report.contains("--video-codec="))
            }
        }
    }
}
