package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class AdbSetupTest {
    private class Connection(override val capabilities: String = "displays and encoders") : AdbSetup.Connection {
        @Volatile override var connected = true
        val closed = AtomicInteger()
        val probes = AtomicInteger()
        override fun probe() {
            probes.incrementAndGet()
            if (!connected) throw IOException("Authorization revoked")
        }
        override fun close() { connected = false; closed.incrementAndGet() }
    }
    private fun awaitState(setup: AdbSetup, predicate: (AdbSetup.Snapshot) -> Boolean): AdbSetup.Snapshot {
        val reached = CountDownLatch(1)
        val listener: (AdbSetup.Snapshot) -> Unit = { if (predicate(it)) reached.countDown() }
        setup.observe(listener)
        try {
            assertTrue("ADB setup did not reach expected state: ${setup.snapshot()}", reached.await(3, TimeUnit.SECONDS))
            return setup.snapshot()
        } finally { setup.removeObserver(listener) }
    }

    @Test fun realPreparationAndProbeAreRequiredBeforeAnyReadySnapshot() {
        val access = CaptureAccess()
        AdbSetup(access).use { setup ->
            val entered = CountDownLatch(1)
            val finishCapabilities = CountDownLatch(1)
            val connection = Connection()
            setup.connect(37001) { attempt ->
                attempt.own(connection); entered.countDown()
                check(finishCapabilities.await(2, TimeUnit.SECONDS))
                connection
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(setup.snapshot().checking); assertFalse(setup.snapshot().ready)
            assertThrows(IllegalStateException::class.java) { setup.requireReady(37001) }
            assertThrows(IllegalStateException::class.java) { access.acquire("casting") }
            finishCapabilities.countDown()
            val ready = awaitState(setup) { it.ready }
            assertEquals(37001, ready.port); assertEquals(connection.capabilities, ready.capabilities)
            assertTrue(connection.probes.get() > 0)
            access.requireAvailable()
        }
    }

    @Test fun missingCapabilitiesOrFailedConnectStaysDisconnected() {
        for (factory in listOf<(AdbSetup.Attempt) -> AdbSetup.Connection>(
            { throw IOException("Pairing required") },
            { attempt -> Connection("").also(attempt::own) }
        )) AdbSetup(CaptureAccess()).use { setup ->
            setup.connect(37001, factory)
            val failed = awaitState(setup) { !it.checking && it.failure == AdbSetup.Failure.UNAVAILABLE }
            assertFalse(failed.ready); assertNull(failed.port)
            assertThrows(IllegalStateException::class.java) { setup.requireReady(37001) }
        }
    }

    @Test fun cancellationClosesOwnedWorkAndCannotPublishLateReady() {
        val access = CaptureAccess()
        AdbSetup(access).use { setup ->
            val entered = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val finished = CountDownLatch(1)
            setup.connect(37001) { attempt ->
                attempt.own(java.io.Closeable { cancelled.countDown() }); entered.countDown()
                try {
                    check(cancelled.await(2, TimeUnit.SECONDS))
                    attempt.check()
                    Connection()
                } finally { finished.countDown() }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            setup.disconnect()
            assertTrue(cancelled.await(2, TimeUnit.SECONDS)); assertTrue(finished.await(2, TimeUnit.SECONDS))
            val state = awaitState(setup) { !it.checking }
            assertFalse(state.ready)
            assertThrows(IllegalStateException::class.java) { setup.requireReady(37001) }
        }
    }

    @Test fun actualConnectionLossInvalidatesReadinessAndSignalsActiveSessionOwner() {
        AdbSetup(CaptureAccess()).use { setup ->
            val lost = AtomicInteger()
            val notified = CountDownLatch(1)
            val lostGeneration = AtomicLong()
            setup.onConnectionLost = { generation -> lostGeneration.set(generation); lost.incrementAndGet(); notified.countDown() }
            val connection = Connection()
            setup.connect(37001) { attempt -> connection.also(attempt::own) }
            val ready = awaitState(setup) { it.ready }
            connection.connected = false
            assertThrows(IllegalStateException::class.java) { setup.requireReady(37001) }
            assertFalse(setup.snapshot().ready)
            setup.checkConnection()
            assertTrue(notified.await(2, TimeUnit.SECONDS))
            assertEquals(ready.generation, lostGeneration.get())
            assertFalse(setup.snapshot().ready); assertEquals(1, lost.get())
            assertTrue(setup.snapshot().message.contains("disconnected"))
            setup.checkConnection(); assertEquals(1, lost.get())
        }
    }

    @Test fun savedOrDifferentPortIsNeverEvidenceOfReadiness() {
        AdbSetup(CaptureAccess()).use { setup ->
            assertThrows(IllegalStateException::class.java) { setup.requireReady(37001) }
            val connection = Connection()
            setup.connect(37001) { attempt -> connection.also(attempt::own) }
            awaitState(setup) { it.ready }
            assertThrows(IllegalStateException::class.java) { setup.requireReady(37002) }
            assertEquals(37001, setup.requireReady(37001).port)
        }
    }

    @Test fun completedServiceReleasesUnobservedReadinessButNotVisibleSetup() {
        AdbSetup(CaptureAccess()).use { setup ->
            val observer: (AdbSetup.Snapshot) -> Unit = {}
            setup.observe(observer)
            setup.connect(37001) { attempt -> Connection().also(attempt::own) }
            awaitState(setup) { it.ready }
            setup.releaseIfUnobserved()
            assertTrue(setup.snapshot().ready)
            setup.removeObserver(observer)
            setup.releaseIfUnobserved()
            assertFalse(setup.snapshot().ready)
            assertThrows(IllegalStateException::class.java) { setup.requireReady(37001) }
        }
    }

    @Test fun automaticDiscoveryUsesNewPortAndStillRequiresCapabilitiesAndProbe() {
        AdbSetup(CaptureAccess()).use { setup ->
            val probeCount = AtomicInteger()
            val connection = object : AdbSetup.Connection {
                override val port = 45678
                override var connected = true
                override val capabilities = "verified current displays and encoders"
                override fun probe() { probeCount.incrementAndGet() }
                override fun close() { connected = false }
            }
            setup.connect(null) { attempt -> connection.also(attempt::own) }
            val ready = awaitState(setup) { it.ready }
            assertEquals(45678, ready.port)
            assertTrue(probeCount.get() > 0)
            assertEquals(45678, setup.requireReady(45678).port)
            assertThrows(IllegalStateException::class.java) { setup.requireReady(37001) }
        }
    }

    @Test fun onlyExplicitAuthorizationFailuresRequirePairingNotTimeoutsOrRefusedPorts() {
        assertEquals(AdbSetup.Failure.PAIRING_REQUIRED, AdbSetup.failureKind(
            io.github.muntashirakon.adb.AdbPairingRequiredException("Pair")))
        assertEquals(AdbSetup.Failure.PAIRING_REQUIRED, AdbSetup.failureKind(
            IOException("wrapped", io.github.muntashirakon.adb.AdbAuthenticationFailedException())))
        for (error in listOf(java.net.ConnectException("refused"), java.net.SocketTimeoutException("timeout"),
            IOException("network unavailable"), javax.net.ssl.SSLHandshakeException("handshake failure"))) {
            assertEquals(AdbSetup.Failure.UNAVAILABLE, AdbSetup.failureKind(error))
        }
    }

    @Test fun automaticAuthorizationFailureExposesPairingWithoutMarkingReady() {
        AdbSetup(CaptureAccess()).use { setup ->
            setup.connect(null) { throw io.github.muntashirakon.adb.AdbPairingRequiredException("Pairing required") }
            val failed = awaitState(setup) { !it.checking && it.failure == AdbSetup.Failure.PAIRING_REQUIRED }
            assertFalse(failed.ready); assertTrue(failed.automatic); assertNull(failed.port)
            assertTrue(failed.message.contains("Pair this app"))
        }
    }

    @Test fun capabilityPreparationCannotOverlapAnotherSetupOrCaptureLease() {
        val access = CaptureAccess()
        AdbSetup(access).use { setup ->
            access.acquire("pairing").use {
                assertThrows(IllegalStateException::class.java) { setup.connect(37001) { Connection() } }
                assertFalse(setup.snapshot().checking)
            }
            access.acquire("casting").use {
                assertThrows(IllegalStateException::class.java) { setup.connect(37001) { Connection() } }
            }
        }
    }
}
