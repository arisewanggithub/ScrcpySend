package dev.screencopy.sender

import java.io.IOException
import java.util.concurrent.TimeUnit

internal object OwnedGuardianExit {
    fun await(process: Process, log: (String) -> Unit) {
        val begin = System.nanoTime()
        log("shell_cleanup=GUARDIAN_WAIT_BEGIN wait_budget_ms=6000")
        val exited = try { process.waitFor(6, TimeUnit.SECONDS) }
        catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("CLEANUP_GUARDIAN_INTERRUPTED", e)
        }
        if (!exited) {
            log("shell_cleanup=GUARDIAN_TIMEOUT wait_ms=${(System.nanoTime() - begin) / 1_000_000}")
            throw IOException("CLEANUP_GUARDIAN_TIMEOUT wait_budget_ms=6000")
        }
        val exit = process.exitValue()
        log("shell_cleanup=GUARDIAN_EXIT exit=$exit wait_ms=${(System.nanoTime() - begin) / 1_000_000}")
        // 77 is the pinned supervisor's pre-fork START cancellation. It never created a child.
        if (exit != 0 && exit != 77) throw IOException("CLEANUP_GUARDIAN_EXIT exit=$exit")
    }
}
