package dev.screencopy.sender

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Explicitly run only on a designated test device; harmless native children, never capture. */
@RunWith(AndroidJUnit4::class)
class GuardianDeviceTest {
    private class RunningGuardian(mode: String) : Closeable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val client = ShizukuClient(context, false) {}
        private val id = UUID.randomUUID().toString().replace("-", "").take(8)
        private val path = ShellCapturePolicy.path(id)
        private val bridge: CaptureBridge
        private val stdout: ParcelFileDescriptor.AutoCloseInputStream
        private val writer: ParcelFileDescriptor.AutoCloseOutputStream
        private val executor = Executors.newSingleThreadExecutor()
        private val ended: java.util.concurrent.Future<String>
        init {
            client.connect()
            bridge = client.bridge()
            bridge.deploy(id, context.assets.open("scrcpy-server-v${CaptureBackend.VERSION}").use { it.readBytes() })
            val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommandRw("sh")
            stdout = ParcelFileDescriptor.AutoCloseInputStream(pipes[0])
            writer = ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])
            writer.write(("exec $path.supervisor $path $mode\n").toByteArray()); writer.flush()
            val reader = stdout.bufferedReader()
            val ready = executor.submit<String> { reader.readLine() }
            assertTrue(ready.get(3, TimeUnit.SECONDS).startsWith("SCREENCOPY_GUARDIAN_READY:"))
            writer.write("START\n".toByteArray()); writer.flush()
            ended = executor.submit<String> { reader.readText() }
        }
        fun stop() { writer.close(); ended.get(3, TimeUnit.SECONDS) }
        fun timeout() { ended.get(9, TimeUnit.SECONDS) }
        override fun close() {
            try { writer.close(); ended.get(9, TimeUnit.SECONDS); bridge.cleanup(id) }
            finally { stdout.close(); executor.shutdownNow(); bridge.close(); client.close() }
        }
    }
    private fun requireShellGrant() {
        assumeTrue("Requires separately started/authorized non-root Shizuku on a designated test device",
            Shizuku.getBinder()?.isBinderAlive == true &&
                Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED && Shizuku.getUid() == 2000)
    }
    @Test(timeout = 15000) fun stdinDisconnectTerminatesOnlyUnreapedDirectChild() {
        requireShellGrant()
        RunningGuardian("probe-wait").use { it.stop(); it.stop() }
    }
    @Test(timeout = 15000) fun exitedUnreapedChildCannotRedirectSignals() {
        requireShellGrant()
        RunningGuardian("probe-exit").use { it.stop() }
    }
    @Test(timeout = 15000) fun ignoredTermStillStopsAndHeartbeatSilenceIsBounded() {
        requireShellGrant()
        RunningGuardian("probe-ignore-term").use { it.timeout() }
    }
}
