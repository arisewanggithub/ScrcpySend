package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

class OwnedGuardianExitTest {
    private class ObservedProcess(private val done: Boolean, private val code: Int, private val interrupted: Boolean = false) : Process() {
        var destroyed = false
        var seconds = 0L
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            seconds = unit.toSeconds(timeout)
            if (interrupted) throw InterruptedException()
            return done
        }
        override fun waitFor() = code
        override fun exitValue() = code
        override fun destroy() { destroyed = true }
        override fun getInputStream() = InputStream.nullInputStream()
        override fun getErrorStream() = InputStream.nullInputStream()
        override fun getOutputStream() = ByteArrayOutputStream()
    }
    @Test fun ownedNaturalExitAndPreForkCancellationNeedNoProcCommandOrSignal() {
        for (code in listOf(0, 77)) {
            val process = ObservedProcess(true, code)
            val logs = mutableListOf<String>()
            OwnedGuardianExit.await(process, logs::add)
            assertFalse(process.destroyed); assertEquals(6, process.seconds)
            assertTrue(logs.last().contains("exit=$code"))
        }
    }
    @Test fun timeoutAndAbnormalSupervisorExitNeverPretendCleanupSucceeded() {
        val waiting = ObservedProcess(false, 0)
        assertTrue(assertThrows(IOException::class.java) { OwnedGuardianExit.await(waiting) {} }.message!!.startsWith("CLEANUP_GUARDIAN_TIMEOUT"))
        val abnormal = ObservedProcess(true, 75)
        assertTrue(assertThrows(IOException::class.java) { OwnedGuardianExit.await(abnormal) {} }.message!!.startsWith("CLEANUP_GUARDIAN_EXIT"))
        assertFalse(waiting.destroyed); assertFalse(abnormal.destroyed)
    }
    @Test fun onlyExactOwnedArtifactsCanBeRemovedAfterExitAndSourceRetainsLiveProcessAuthority() {
        val paths = CaptureGuardian.artifacts("/data/local/tmp/screencopy-00000001.jar", "00000001")
        assertEquals(6, paths.distinct().size)
        assertTrue(paths.all { it.startsWith("/data/local/tmp/screencopy-00000001.jar") })
        assertThrows(IllegalArgumentException::class.java) { CaptureGuardian.artifacts("/data/local/tmp/other", "00000001") }
        val source = Files.readString(Path.of(System.getProperty("senderSources"), "ShellCaptureService.kt"))
        assertTrue(source.contains("guardianHandle = GuardianHandle(id, child)"))
        assertTrue(source.contains("OwnedGuardianExit.await(guardian.process)"))
        assertTrue(source.indexOf("OwnedGuardianExit.await(guardian.process)") < source.indexOf("file.delete()"))
        assertTrue(source.contains("lastCleanedId == id"))
        assertTrue(source.contains("RecoveryArtifactCleanup.run(path, id)"))
        assertFalse(source.contains("CaptureGuardian.cleanup("))
        assertFalse(source.contains("/system/bin/sh"))
    }
    @Test fun interruptedExitObservationRetainsCancellationAndNeverClaimsCleanupOrSignals() {
        val process = ObservedProcess(false, 0, true)
        try {
            val error = assertThrows(IOException::class.java) { OwnedGuardianExit.await(process) {} }
            assertEquals("CLEANUP_GUARDIAN_INTERRUPTED", error.message)
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(process.destroyed)
        } finally { Thread.interrupted() }
    }
}
