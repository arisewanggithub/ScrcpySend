package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CaptureCancellationTest {
    @Test fun productionDetachReleasesOwnershipMonitorBeforeAnyBlockingClose() {
        val lock = Any()
        val closing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val detached = CountDownLatch(1)
        val thread = Thread {
            CaptureCancellation.closeDetached(lock, { detached.countDown(); "owned" }) {
                assertFalse(Thread.holdsLock(lock))
                closing.countDown()
                assertTrue(release.await(2, TimeUnit.SECONDS))
            }
        }.apply { start() }
        assertTrue(closing.await(1, TimeUnit.SECONDS))
        val observed = CountDownLatch(1)
        val observer = Thread { synchronized(lock) { observed.countDown() } }.apply { start() }
        try { assertTrue(observed.await(1, TimeUnit.SECONDS)); assertEquals(0L, detached.count) }
        finally { release.countDown(); thread.join(2000); observer.join(2000) }
        assertFalse(thread.isAlive)
    }

    @Test fun actualBlockingSocketReadIsInterruptedAndConcurrentStopClosesOnce() {
        ServerSocket(0).use { listener ->
            Socket("127.0.0.1", listener.localPort).use { peer ->
                val socket = listener.accept()
                val lifetime = CaptureLifetime()
                val closes = AtomicInteger()
                lifetime.own(Closeable { closes.incrementAndGet(); socket.close() })
                val reading = CountDownLatch(1)
                val ended = CountDownLatch(1)
                val reader = Thread {
                    try { reading.countDown(); socket.getInputStream().read() }
                    catch (e: IOException) { assertTrue(socket.isClosed) }
                    finally { ended.countDown() }
                }.apply { start() }
                assertTrue(reading.await(1, TimeUnit.SECONDS))
                val stops = (1..8).map { Thread { lifetime.close() }.apply { start() } }
                stops.forEach { it.join(1000); assertFalse(it.isAlive) }
                assertTrue(ended.await(1, TimeUnit.SECONDS))
                reader.join(1000)
                assertEquals(1, closes.get())
                assertFalse(peer.isClosed)
            }
        }
    }

    @Test fun binderDeathCancellationRejectsLateStartupDescriptorsWithoutReopeningOwnership() {
        val lifetime = CaptureLifetime()
        val closes = AtomicInteger()
        lifetime.close()
        assertThrows(IOException::class.java) { lifetime.own(Closeable { closes.incrementAndGet() }) }
        assertEquals(1, closes.get())
        assertThrows(IOException::class.java) { lifetime.check() }
    }

    @Test fun failedSocketRetriesReleaseBudgetAndClosedOwnershipNeverAdoptsAgain() {
        val lifetime = CaptureLifetime(1)
        val closes = AtomicInteger()
        repeat(30) {
            val resource = Closeable { closes.incrementAndGet() }
            assertTrue(lifetime.tryOwn(resource))
            lifetime.forget(resource)
            resource.close()
        }
        val owned = Closeable { closes.incrementAndGet() }
        assertTrue(lifetime.tryOwn(owned))
        assertFalse(lifetime.tryOwn(Closeable { fail("Rejected resource must be closed outside the ownership monitor") }))
        lifetime.close()
        assertEquals(31, closes.get())
        assertFalse(lifetime.tryOwn(owned))
    }

    @Test fun privilegedStopAndUiLivenessUseSafeProductionSurfaces() {
        val sources = Path.of(System.getProperty("senderSources"))
        val service = Files.readString(sources.resolve("ShellCaptureService.kt"))
        val client = Files.readString(sources.resolve("ShizukuClient.kt"))
        val guardian = Files.readString(sources.resolve("CaptureGuardian.kt"))
        assertTrue(service.contains("CaptureCancellation.closeDetached"))
        assertTrue(service.contains("CaptureCancellation.closeOwnedProcess(owned.process, owned.guardian)"))
        assertTrue(service.contains("if (id != null && cleanupCommandId == id) return cleanup"))
        assertTrue(service.contains("ProcessBuilder(arguments)"))
        assertTrue(service.contains("ProcessBuilder(CaptureGuardian.arguments"))
        assertFalse(guardian.contains("kill -")); assertFalse(guardian.contains("trap "))
        val alive = client.substringAfter("val alive get()").substringBefore("fun connect()")
        assertFalse(alive.contains("pingBinder")); assertTrue(alive.contains("isBinderAlive"))
        assertTrue(service.contains("ownerDeath = IBinder.DeathRecipient { terminateService() }"))
        val destroy = service.substringAfter("override fun destroy()")
        assertFalse(destroy.contains("cancelOwned()")); assertTrue(destroy.contains("terminateService()"))
        val native = Files.readString(sources.resolve("../../../../cpp/supervisor.c").normalize())
        assertTrue(native.contains("PR_SET_PDEATHSIG, SIGKILL"))
        assertTrue(native.contains("setsid() < 0")); assertTrue(native.contains("supervisor_isolated(getpid(), getsid(0), getpgrp())"))
        assertTrue(native.indexOf("if (!start_authorized())") < native.indexOf("child = {fork()"))
        val session = Files.readString(sources.resolve("SenderSession.kt"))
        val closingWatchdog = session.substringAfter("private fun watchdog()").substringAfter("if (closing)")
            .substringBefore("if (phaseDeadline")
        assertTrue(closingWatchdog.contains("closeLink()"))
        assertFalse(closingWatchdog.contains("abortCapture()"))
    }
}
