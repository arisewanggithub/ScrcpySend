package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test

class DiscoveryPublicationTest {
    @Test fun suspendClockOffsetExplainsDirectoryOneHomeZeroAndMatchingClockKeepsFreshPeer() {
        var uptime = 20_000_000_000L
        val suspended = 60_000_000_000L
        val directory = ReceiverDirectory()
        val receiver = Discovery.Receiver("TV", "192.0.2.1", 27184, "available", uptime)
        directory.update(receiver)
        val packets = directory.snapshot(uptime)
        assertEquals(1, packets.size)
        val mismatched = ReceiverCasting { uptime + suspended }
        mismatched.updateDiscovery(packets)
        assertTrue("The previous shared elapsedRealtime clock prematurely expired nanoTime packets", mismatched.rows(true).isEmpty())
        val aligned = ReceiverCasting { uptime }
        aligned.updateDiscovery(packets)
        assertEquals("Play", aligned.rows(true).single().label)
        uptime += 6_000_000_001L
        assertTrue(directory.snapshot(uptime).isEmpty())
        assertTrue(aligned.rows(true).isEmpty())
        directory.update(receiver.copy(seen = uptime))
        aligned.updateDiscovery(directory.snapshot(uptime))
        assertEquals("Play", aligned.rows(true).single().label)
    }

    private class Rig {
        var now = 1L
        val model = ReceiverCasting { now }
        var callbacks = 0
        val observer: () -> Unit = { callbacks++ }
        init { model.observe(observer) }
        fun receiver(name: String = "TV", host: String = "192.0.2.1", port: Int = 27184,
                     state: String = "available", seen: Long = now) =
            Discovery.Receiver(name, host, port, state, seen)
        fun update(vararg receivers: Discovery.Receiver) = model.updateDiscovery(receivers.toList())
    }

    @Test fun quietEmptySnapshotsAndFreshnessOnlyUpdatesDoNotPublish() {
        val r = Rig()
        repeat(3) { r.update() }
        assertEquals(1, r.callbacks) // Initial observer attachment only.
        r.update(r.receiver())
        val snapshot = r.model.snapshot()
        repeat(3) { r.now++; r.update(r.receiver()) }
        assertEquals(2, r.callbacks)
        assertEquals(snapshot, r.model.snapshot())
        r.update()
        repeat(3) { r.update() }
        assertEquals(3, r.callbacks)
    }

    @Test fun suppressedRefreshRetainsFreshBackingDataForRowsAttachAndPlay() {
        val r = Rig()
        r.update(r.receiver())
        r.now = 5_000_000_001L
        r.update(r.receiver())
        assertEquals(2, r.callbacks)
        r.model.removeObserver(r.observer)
        r.now = 7_000_000_001L // The original advertisement would now be expired.
        var attachedRows = emptyList<ReceiverCasting.Row>()
        val observer: () -> Unit = { attachedRows = r.model.rows(true) }
        r.model.observe(observer)
        assertEquals("Play", attachedRows.single().label)
        assertTrue(attachedRows.single().enabled)
        val request = r.model.reserve(ReceiverKey.of(attachedRows.single().target), Settings())
        assertEquals(r.receiver().target(), request.target)
        r.model.removeObserver(observer)
    }

    @Test fun arrivalRemovalNameStateHostAndPortChangesPublishImmediately() {
        val r = Rig()
        var receiver = r.receiver()
        r.update(receiver)
        assertEquals(2, r.callbacks)
        receiver = receiver.copy(name = "Renamed")
        r.update(receiver)
        assertEquals(3, r.callbacks)
        assertEquals("Renamed", r.model.rows(true).single().target.name)
        receiver = receiver.copy(state = "busy")
        r.update(receiver)
        assertEquals(4, r.callbacks)
        assertEquals("Busy", r.model.rows(true).single().label)
        receiver = receiver.copy(host = "192.0.2.2")
        r.update(receiver)
        assertEquals(5, r.callbacks)
        assertEquals("192.0.2.2", r.model.rows(true).single().target.host)
        receiver = receiver.copy(port = 27185)
        r.update(receiver)
        assertEquals(6, r.callbacks)
        assertEquals(27185, r.model.rows(true).single().target.port)
        r.update(receiver, r.receiver())
        assertEquals(7, r.callbacks)
        assertEquals(2, r.model.rows(true).size)
        r.update(receiver)
        assertEquals(8, r.callbacks)
        r.update()
        assertEquals(9, r.callbacks)
        assertTrue(r.model.rows(true).isEmpty())
    }

    @Test fun quietExpiryPublishesOnceEvenWhenInputListIsUnchanged() {
        val r = Rig()
        val receiver = r.receiver()
        r.update(receiver)
        r.now += 6_000_000_000L
        r.update(receiver)
        assertEquals(2, r.callbacks)
        assertEquals(1, r.model.rows(true).size)
        r.now++
        r.update(receiver)
        assertEquals(3, r.callbacks)
        assertTrue(r.model.rows(true).isEmpty())
        r.update(receiver)
        r.update()
        assertEquals(3, r.callbacks)
        r.update(r.receiver())
        assertEquals(4, r.callbacks)
        assertEquals(1, r.model.rows(true).size)
    }

    @Test fun ownBusyRefreshAndExpiryKeepPinnedStopAndServiceLeaseAuthority() {
        val r = Rig()
        r.update(r.receiver())
        val request = r.model.reserve(ReceiverKey.of(r.receiver().target()), Settings())
        val owner = checkNotNull(r.model.claim(request.token))
        r.model.progress(request.token, "Streaming")
        r.update(r.receiver(state = "busy"), r.receiver(name = "Other", host = "192.0.2.2"))
        val busyCallbacks = r.callbacks
        r.now++
        r.update(r.receiver(state = "busy"), r.receiver(name = "Other", host = "192.0.2.2"))
        assertEquals(busyCallbacks, r.callbacks)
        val rows = r.model.rows(true)
        assertEquals("Stop", rows.first().label)
        assertTrue(rows.first().active)
        assertTrue(rows.first().enabled)
        assertFalse(rows.last().enabled)
        r.now += 6_000_000_001L
        r.update()
        assertEquals(busyCallbacks + 1, r.callbacks)
        val pinned = r.model.rows(false).single()
        assertEquals(request.target, pinned.target)
        assertEquals("Stop", pinned.label)
        assertTrue(pinned.enabled)
        val stops = mutableListOf<ReceiverCasting.Request>()
        val actions = ReceiverRowActions(r.model, { fail("Stop must not require readiness") },
            { Settings() }, { fail("Stop must not start another session") }, stops::add)
        actions.press(ReceiverKey.of(pinned.target), pinned.requestToken, pinned.revision)
        assertEquals(listOf(request), stops)
        assertEquals("Stopping...", r.model.rows(false).single().label)
        assertFalse(r.model.complete(request.token))
        assertSame(request, r.model.snapshot().active?.request)
        assertTrue(r.model.completeService(owner))
        assertFalse(r.model.completeService(owner))
        assertTrue(r.model.rows(true).isEmpty())
    }

    @Test fun changedEndpointNotifiesWithoutRedirectingActiveRequest() {
        val r = Rig()
        r.update(r.receiver())
        val request = r.model.reserve(ReceiverKey.of(r.receiver().target()), Settings())
        val before = r.callbacks
        r.update(r.receiver(host = "192.0.2.9", port = 27185))
        assertEquals(before + 1, r.callbacks)
        assertSame(request, r.model.snapshot().active?.request)
        val rows = r.model.rows(true)
        assertEquals(request.target, rows.first().target)
        assertTrue(rows.first().active)
        assertEquals(ReceiverTarget("192.0.2.9", 27185, "TV"), rows.last().target)
        assertFalse(rows.last().enabled)
    }

    @Test fun phaseDetailErrorAndCleanupPublicationsAreNotDeduplicated() {
        val r = Rig()
        r.update(r.receiver())
        val request = r.model.reserve(ReceiverKey.of(r.receiver().target()), Settings())
        assertEquals(3, r.callbacks)
        r.update(r.receiver())
        assertEquals(3, r.callbacks)
        r.model.progress(request.token, "Negotiating")
        assertEquals(4, r.callbacks)
        assertEquals("Negotiating", r.model.snapshot().active?.detail)
        r.model.progress(request.token, "Streaming")
        assertEquals(5, r.callbacks)
        assertEquals("Stop", r.model.rows(true).single().label)
        r.model.requestStop(request.token)
        assertEquals(6, r.callbacks)
        r.model.stopDispatchFailed(request.token, ReceiverCasting.Phase.PLAYING, "Retry Stop")
        assertEquals(7, r.callbacks)
        assertEquals("Retry Stop", r.model.snapshot().error)
        assertEquals("Stop", r.model.rows(true).single().label)
        r.model.progress(request.token, "Error: Encoder failed")
        assertEquals(8, r.callbacks)
        assertEquals("Stopping...", r.model.rows(true).single().label)
        r.update(r.receiver())
        assertEquals(8, r.callbacks)
        assertTrue(r.model.complete(request.token))
        assertEquals(9, r.callbacks)
        assertEquals("Encoder failed", r.model.snapshot().error)
        assertEquals("Play", r.model.rows(true).single().label)
    }
}
