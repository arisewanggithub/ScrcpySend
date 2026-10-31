package dev.screencopy.sender

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Paths

/** Recovery observes reusable PIDs but never signals them or starts another process. */
internal object RecoveryArtifactCleanup {
    internal interface Access {
        fun read(path: String, limit: Int): ByteArray?
        fun delete(path: String)
    }

    private object Disk : Access {
        override fun read(path: String, limit: Int): ByteArray? = try {
            Files.newInputStream(Paths.get(path), LinkOption.NOFOLLOW_LINKS).use { input ->
                val data = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(minOf(limit + 1, 8192))
                while (true) {
                    val count = input.read(buffer, 0, minOf(buffer.size, limit + 1 - data.size()))
                    if (count < 0) break
                    data.write(buffer, 0, count)
                    if (data.size() > limit) throw IOException("CLEANUP_READ_LIMIT")
                }
                data.toByteArray()
            }
        } catch (_: NoSuchFileException) { null }

        override fun delete(path: String) { Files.deleteIfExists(Paths.get(path)) }
    }

    fun run(remote: String, id: String, access: Access = Disk,
            now: () -> Long = System::nanoTime, pause: () -> Unit = { Thread.sleep(100) },
            log: (String) -> Unit = {}) {
        val artifacts = CaptureGuardian.artifacts(remote, id)
        val begin = now()
        fun budget() {
            if (Thread.currentThread().isInterrupted) throw IOException("CLEANUP_RECOVERY_INTERRUPTED")
            if (now() - begin >= 6_000_000_000L) throw IOException("CLEANUP_RECOVERY_TIMEOUT wait_budget_ms=6000")
        }
        fun read(path: String, limit: Int): ByteArray? {
            budget()
            val bytes = try { access.read(path, limit) }
            catch (e: IOException) { throw IOException("CLEANUP_RECOVERY_READ_UNAVAILABLE path=$path", e) }
            budget()
            if (bytes != null && bytes.size > limit) throw IOException("CLEANUP_READ_LIMIT")
            return bytes
        }
        fun arguments(path: String): List<String>? {
            val bytes = read(path, 64 * 1024) ?: return null
            if (bytes.isEmpty() || bytes.last() != 0.toByte())
                throw IOException("CLEANUP_PROC_ARGUMENTS_UNAVAILABLE")
            return String(bytes, Charsets.UTF_8).dropLast(1).split('\u0000')
        }
        // A missing/inaccessible proc mount must never look like three exited owners.
        if (arguments("/proc/self/cmdline") == null) throw IOException("CLEANUP_PROC_UNAVAILABLE")
        log("shell_cleanup=RECOVERY_BEGIN wait_budget_ms=6000")
        var legacyLogged = false
        while (true) {
            var live = false
            for (suffix in listOf(".pid", ".guardian", ".probe.guardian")) {
                val bytes = read(remote + suffix, 32) ?: continue
                val text = String(bytes, Charsets.US_ASCII)
                val pid = text.removeSuffix("\n")
                val number = pid.toLongOrNull()
                if (!pid.matches(Regex("[1-9][0-9]{0,9}")) || number == null || number !in 2L..Int.MAX_VALUE.toLong())
                    throw IOException("CLEANUP_INVALID_PID")
                val args = arguments("/proc/$pid/cmdline") ?: continue
                val server = "com.genymobile.scrcpy.Server" in args && "scid=$id" in args
                val guardian = remote in args || "$remote.supervisor" in args
                // Retired shell guardians may embed the path inside their -c argument.
                // Refuse ambiguous deletion; never attempt to kill/recover those helpers here.
                val legacy = args.any { it.contains(remote) }
                if (server || guardian || legacy) {
                    live = true
                    if (legacy && !server && !guardian && !legacyLogged) {
                        log("shell_cleanup=RECOVERY_LEGACY_OWNER_PRESENT")
                        legacyLogged = true
                    }
                }
            }
            if (!live) break
            budget()
            try { pause() }
            catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("CLEANUP_RECOVERY_INTERRUPTED", e)
            }
        }
        for (artifact in artifacts) {
            budget()
            try { access.delete(artifact) }
            catch (e: IOException) { throw IOException("CLEANUP_ARTIFACT_REMOVE", e) }
        }
        budget()
        log("shell_cleanup=RECOVERY_CLEAN")
    }
}
