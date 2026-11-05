package dev.screencopy.sender

import android.content.Intent
import android.app.KeyguardManager
import android.net.InetAddresses
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProjectionNetworkDeviceTest {
    @Test fun compareNetworkBeforeAndAfterConsentedProjectionActivationWithoutCapturingData() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Requires real user consent", arguments.getString("manualProjectionConsent") == "true")
        val peer = checkNotNull(arguments.getString("probePeer"))
        check(InetAddresses.isNumericAddress(peer))
        val port = arguments.getString("probePort")?.toInt() ?: 27184
        check(port in 1..65535)
        check(!CastService.active) { "Do not interrupt an active cast" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val before = ProjectionNetworkChecks.probe(context, peer, port, "before_consent")
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        instrumentation.addResults(Bundle().apply { putString("networkBeforeConsent", before.toString()) })
        assumeTrue("Unlock the Galaxy before launching the consent diagnostic", !keyguard.isKeyguardLocked)
        ProjectionNetworkProbeState.reset()
        val activity = instrumentation.startActivitySync(Intent(context, ProjectionConsentTestActivity::class.java)
            .putExtra("requestAudio", false).putExtra("networkOnly", true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ProjectionConsentTestActivity
        try {
            val approved = activity.completed.await(180, TimeUnit.SECONDS) && activity.consent != null
            instrumentation.addResults(Bundle().apply {
                putString("networkBeforeConsent", before.toString())
                putString("projectionDiagnosticConsent", if (approved) "approved" else "not_approved_or_timed_out")
            })
            assumeTrue("Unlock the phone and approve Android's screen-sharing dialog", approved)
            instrumentation.runOnMainSync {
                activity.startForegroundService(Intent(activity, ProjectionNetworkProbeService::class.java)
                    .putExtra("peer", peer).putExtra("port", port).putExtra("projection", activity.consent))
            }
            assertTrue("Bounded projection diagnostic did not finish", ProjectionNetworkProbeState.completed.await(45, TimeUnit.SECONDS))
            val result = checkNotNull(ProjectionNetworkProbeState.results)
            instrumentation.addResults(Bundle().apply {
                putString("projectionNetworkProbe", result)
                ProjectionNetworkProbeState.failure?.let { putString("projectionNetworkProbeFailure", it) }
            })
            assertNull(ProjectionNetworkProbeState.failure)
            assertEquals(4, JSONArray(result).length())
        } finally {
            context.stopService(Intent(context, ProjectionNetworkProbeService::class.java))
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
