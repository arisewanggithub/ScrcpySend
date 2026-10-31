package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class RecoveryArtifactCleanupTest {
    private val id = "00000001"
    private val remote = "/data/local/tmp/screencopy-$id.jar"
    private fun args(vararg values: String) = (values.joinToString("\u0000") + "\u0000").toByteArray()
    private inner class Fixture : RecoveryArtifactCleanup.Access {
        val data = mutableMapOf("/proc/self/cmdline" to args("user-service"))
        val removed = mutableListOf<String>()
        val reads = mutableListOf<Pair<String, Int>>()
        var denied: String? = null
        var deleteFailure: String? = null
        var time = 0L
        var sleeps = 0
        var afterPause: () -> Unit = {}
        var afterRead: () -> Unit = {}
        override fun read(path: String, limit: Int): ByteArray? {
            reads.add(path to limit)
            if (path == denied) throw IOException("permission denied")
            afterRead()
            return data[path]
        }
        override fun delete(path: String) {
            if (path == deleteFailure) throw IOException("delete denied")
            removed.add(path)
        }
        fun run(log: (String) -> Unit = {}) = RecoveryArtifactCleanup.run(remote, id, this,
            now = { time }, pause = { sleeps++; time += 100_000_000; afterPause() }, log = log)
        fun pid(suffix: String = ".pid", command: ByteArray) {
            data[remote + suffix] = "123\n".toByteArray()
            data["/proc/123/cmdline"] = command
        }
    }

    @Test fun absentOwnersRemoveExactlySixValidatedArtifactsWithoutProcesses() {
        val f = Fixture()
        f.run()
        assertEquals(CaptureGuardian.artifacts(remote, id), f.removed)
        assertEquals(0, f.sleeps)
        assertTrue(f.reads.all { it.second in listOf(32, 65536) })
        assertThrows(IllegalArgumentException::class.java) {
            RecoveryArtifactCleanup.run("/unowned", id, f)
        }
    }

    @Test fun liveServerFailsClosedAtMonotonicDeadlineWithoutRemovingAnything() {
        val f = Fixture()
        f.pid(command = args("app_process", "/", "com.genymobile.scrcpy.Server", "scid=$id"))
        val error = assertThrows(IOException::class.java) { f.run() }
        assertTrue(error.message!!.startsWith("CLEANUP_RECOVERY_TIMEOUT"))
        assertEquals(60, f.sleeps)
        assertTrue(f.removed.isEmpty())
    }

    @Test fun exitedServerIsObservedAgainBeforeArtifactDeletion() {
        val f = Fixture()
        f.pid(command = args("app_process", "com.genymobile.scrcpy.Server", "scid=$id"))
        f.afterPause = { f.data.remove("/proc/123/cmdline") }
        f.run()
        assertEquals(1, f.sleeps)
        assertEquals(6, f.removed.size)
    }

    @Test fun reusedPidAndNonMatchingNulArgumentsDoNotAuthorizeSignalsOrBlockCleanup() {
        for (command in listOf(
            args("com.genymobile.scrcpy.Server", "scid=00000002"),
            args("prefixcom.genymobile.scrcpy.Server", "scid=$id"),
            args("com.genymobile.scrcpy.Server", "scid=${id}0"),
            args("unrelated"))) {
            val f = Fixture()
            f.pid(command = command)
            f.run()
            assertEquals(6, f.removed.size)
            assertEquals(0, f.sleeps)
        }
    }

    @Test fun nativeAndLegacyGuardianMetadataAreConservativelyObservedNeverKilled() {
        for ((suffix, command) in listOf(
            ".guardian" to args("$remote.supervisor", remote, "3.3.4"),
            ".probe.guardian" to args("$remote.supervisor", remote, "probe-exit"),
            ".guardian" to args("sh", "-c", "old helper referencing $remote"))) {
            val f = Fixture()
            f.pid(suffix, command)
            val logs = mutableListOf<String>()
            assertThrows(IOException::class.java) { f.run(logs::add) }
            assertTrue(f.removed.isEmpty())
            if (String(command).contains("-c"))
                assertTrue(logs.any { it.contains("RECOVERY_LEGACY_OWNER_PRESENT") })
        }
    }

    @Test fun missingOrDeniedProcAndUnreadableMetadataNeverLookLikeExitedOwners() {
        for (denied in listOf("/proc/self/cmdline", "$remote.pid", "/proc/123/cmdline")) {
            val f = Fixture()
            f.pid(command = args("unrelated"))
            f.denied = denied
            assertThrows(IOException::class.java) { f.run() }
            assertTrue(f.removed.isEmpty())
        }
        val f = Fixture()
        f.data.remove("/proc/self/cmdline")
        assertThrows(IOException::class.java) { f.run() }
        assertTrue(f.removed.isEmpty())
    }

    @Test fun invalidPidFilesAndIncompleteOrOversizedArgumentsFailClosed() {
        for (value in listOf("", "0", "1", "-2", "123;echo x", "123\n\n", "2147483648", "9".repeat(33))) {
            val f = Fixture()
            f.data["$remote.pid"] = value.toByteArray()
            assertThrows(IOException::class.java) { f.run() }
            assertTrue(f.removed.isEmpty())
        }
        for (command in listOf(byteArrayOf(), "not-nul-terminated".toByteArray(), ByteArray(65537))) {
            val f = Fixture()
            f.pid(command = command)
            assertThrows(IOException::class.java) { f.run() }
            assertTrue(f.removed.isEmpty())
        }
    }

    @Test fun readTimeCountsTowardDeadlineAndDeleteFailureCannotReportSuccess() {
        val f = Fixture()
        f.afterRead = { f.time += 6_000_000_000 }
        assertThrows(IOException::class.java) { f.run() }
        assertTrue(f.removed.isEmpty())
        val failed = Fixture()
        failed.deleteFailure = remote
        val logs = mutableListOf<String>()
        assertThrows(IOException::class.java) { failed.run(logs::add) }
        assertFalse(logs.any { it.endsWith("RECOVERY_CLEAN") })
    }

    @Test fun cancellationRetainsInterruptAndNoArtifactsAreRemoved() {
        val f = Fixture()
        try {
            Thread.currentThread().interrupt()
            assertThrows(IOException::class.java) { f.run() }
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(f.removed.isEmpty())
        } finally { Thread.interrupted() }
    }

    @Test fun failedRecoveryRetainsCancelledScopeUntilSuccessfulRetry() {
        val scope = CaptureScope()
        val f = Fixture()
        scope.claim(id)
        scope.cancel(id)
        f.denied = "/proc/self/cmdline"
        assertThrows(IOException::class.java) { f.run(); scope.finish(id) }
        assertEquals(id, scope.id)
        assertThrows(IllegalStateException::class.java) { scope.claim("00000002") }
        assertThrows(IllegalStateException::class.java) { scope.requireActive(id) }
        f.denied = null
        f.run()
        scope.finish(id)
        scope.claim("00000002")
        scope.requireActive("00000002")
    }

    @Test fun serviceReservesRecoveryBeforeIoAndOnlyFinishesAfterSuccessfulCleanup() {
        val source = Files.readString(Path.of(System.getProperty("senderSources"), "ShellCaptureService.kt"))
        val recovery = source.substringAfter("val completion = synchronized(lock) {")
        assertTrue(recovery.contains("scope.pendingOperation(id).count != 0L"))
        assertTrue(recovery.indexOf("scope.claim(id)") < recovery.indexOf("RecoveryArtifactCleanup.run"))
        assertTrue(recovery.indexOf("scope.cancel(id)") < recovery.indexOf("RecoveryArtifactCleanup.run"))
        assertTrue(recovery.indexOf("cleanup = it; cleanupCommandId = id") < recovery.indexOf("RecoveryArtifactCleanup.run"))
        assertTrue(recovery.indexOf("RecoveryArtifactCleanup.run") < recovery.indexOf("scope.finish(id)"))
        assertTrue(recovery.contains("finally"))
        assertTrue(recovery.contains("completion.countDown()"))
        val helper = Files.readString(Path.of(System.getProperty("senderSources"), "RecoveryArtifactCleanup.kt"))
        assertFalse(helper.contains("ProcessBuilder"))
        assertFalse(helper.contains("Runtime.getRuntime"))
        assertFalse(helper.contains("kill("))
        val bridge = Files.readString(Path.of(System.getProperty("senderSources"), "CaptureBridge.kt"))
        val legacyCleanup = bridge.substringAfter("override fun cleanup(id: String)").substringBefore("override fun close()")
        assertTrue(legacyCleanup.contains("throw java.io.IOException"))
        assertFalse(legacyCleanup.contains("adb.shell"))
    }
}
