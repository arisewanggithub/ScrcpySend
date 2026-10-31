package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.ArrayDeque

/** Actual Play handler, foreground-service state owner and Activity observer, without capture. */
class CastStateBindingTest {
    @Test fun productionSharedDiscoveryClockPublishesFreshPacketsThroughQueuedHomeBinding() {
        val model = ReceiverCasting.shared
        assertNull(model.snapshot().active)
        val queue = ArrayDeque<() -> Unit>()
        val frames = mutableListOf<List<ReceiverCasting.Row>>()
        val binding = ReceiverListBinding(model, { true }, queue::addLast) { _, rows -> frames.add(rows) }
        try {
            model.updateDiscovery(emptyList())
            model.setNextSizeOverride(false)
            binding.attach()
            val directory = ReceiverDirectory()
            val advertisement = """{"protocol":"screencopy","version":3,"name":"TV","port":27184,"state":"available"}"""
            val receiver = Discovery.decode(advertisement.toByteArray(), "192.0.2.1", System.nanoTime())
            directory.update(receiver)
            assertEquals(1, directory.snapshot(System.nanoTime()).size)
            model.updateDiscovery(directory.snapshot(System.nanoTime()))
            assertTrue("Discovery must not render views on its worker", frames.isEmpty())
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
            assertEquals("Play", frames.last().single().label)
            model.setNextSizeOverride(true)
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
            assertEquals(receiver.target(), frames.last().single().target)
            assertTrue(frames.last().single().enabled)
            binding.detach()
            model.updateDiscovery(emptyList())
            binding.attach()
            model.updateDiscovery(listOf(receiver.copy(seen = System.nanoTime())))
            while (queue.isNotEmpty()) queue.removeFirst().invoke()
            assertEquals(receiver.target(), frames.last().single().target)
        } finally {
            binding.detach()
            model.updateDiscovery(emptyList())
            model.setNextSizeOverride(false)
        }
    }

    private class Rig {
        val model = ReceiverCasting { 1L }
        val service = CastingServiceState(model)
        val queue = ArrayDeque<() -> Unit>()
        var ready = true
        var visible = true
        var starts = 0
        val stops = mutableListOf<Long>()
        val frames = mutableListOf<List<ReceiverCasting.Row>>()
        val effects = mutableListOf<ReceiverMainScreen.Effects>()
        var owner: ReceiverCasting.ServiceLease? = null
        val actions = ReceiverRowActions(model, { check(ready) }, { Settings() }, {
            starts++; owner = service.adopt(it.token)
            assertNotNull(owner)
        }, { if (service.owns(it.token)) stops.add(it.token) })
        fun bind() = ReceiverListBinding(model, { ready }, queue::addLast) { snapshot, rows ->
            frames.add(rows)
            effects.add(ReceiverMainScreen.effects(ready, visible, snapshot.active != null))
        }
        fun discover(name: String = "TV", state: String = "available") =
            model.updateDiscovery(listOf(Discovery.Receiver(name, "192.0.2.1", 27184, state, 1)))
        fun press() {
            val row = model.rows(ready).first()
            actions.press(ReceiverKey.of(row.target), row.requestToken, row.revision)
        }
        fun flush() { while (queue.isNotEmpty()) queue.removeFirst().invoke() }
        fun progress(message: String) = service.progress(checkNotNull(owner), message)
        fun finish(error: String? = null) = service.finish(checkNotNull(owner), error)
    }
    @Test fun productionPlayServiceStreamingObserverAndMatchingStopKeepSameAuthorityUntilCleanup() {
        val r = Rig(); r.discover()
        val binding = r.bind(); binding.attach(); r.flush()
        assertEquals("Play", r.frames.last().single().label)
        assertTrue(r.effects.last().settingsEnabled); assertFalse(r.effects.last().keepScreenOn)
        r.press(); r.flush()
        assertTrue(r.frames.last().single().label.startsWith("Connecting"))
        assertFalse(r.effects.last().settingsEnabled); assertTrue(r.effects.last().keepScreenOn)
        r.progress("Streaming"); r.flush()
        assertEquals("Stop", r.frames.last().single().label)
        assertEquals(1, r.starts)
        r.press(); r.flush()
        assertEquals(listOf(r.owner!!.request.token), r.stops)
        assertEquals("Stopping...", r.frames.last().single().label)
        assertFalse(r.effects.last().settingsEnabled); assertTrue(r.effects.last().keepScreenOn)
        r.model.complete(r.owner!!.request.token) // A late Activity/pending callback has no service authority.
        r.flush(); assertNotNull(r.model.snapshot().active)
        assertTrue(r.finish()); r.flush()
        assertEquals("Play", r.frames.last().single().label)
        assertTrue(r.effects.last().settingsEnabled); assertFalse(r.effects.last().keepScreenOn)
        binding.detach()
    }
    @Test fun actualServiceAlreadyRunningBeforeActivityAttachesReplaysStopWithoutNewStart() {
        val r = Rig(); r.discover(); r.press(); r.progress("Streaming")
        val binding = r.bind(); binding.attach(); r.flush()
        assertEquals("Stop", r.frames.single().single().label)
        assertFalse(r.effects.single().settingsEnabled); assertTrue(r.effects.single().keepScreenOn)
        assertEquals(1, r.starts)
        binding.detach(); r.visible = false
        assertFalse(ReceiverMainScreen.effects(true, r.visible, r.model.snapshot().active != null).keepScreenOn)
    }
    @Test fun detachRecreationAndDelayedUiJobsCannotReplayAnOldPlayFrameOverStreaming() {
        val r = Rig(); r.discover()
        val first = r.bind(); first.attach() // Old idle frame is deliberately still queued.
        first.detach()
        r.press(); r.progress("Streaming")
        val recreated = r.bind(); recreated.attach(); r.flush()
        assertEquals(1, r.frames.size)
        assertEquals("Stop", r.frames.single().single().label)
        assertFalse(r.effects.single().settingsEnabled); assertTrue(r.effects.single().keepScreenOn)
        recreated.detach()
    }
    @Test fun discoveryRefreshBusyRenameExpiryAndOtherEndpointsNeverEraseActiveStop() {
        val r = Rig(); r.discover(); r.press(); r.progress("Streaming")
        val binding = r.bind(); binding.attach()
        r.model.updateDiscovery(listOf(Discovery.Receiver("Other", "192.0.2.2", 27184, "available", 1),
            Discovery.Receiver("Renamed", "192.0.2.1", 27184, "busy", 1)))
        r.flush()
        assertEquals("192.0.2.1", r.frames.last().first().target.host)
        assertEquals("Renamed", r.frames.last().first().target.name)
        assertEquals("Stop", r.frames.last().first().label)
        assertFalse(r.frames.last()[1].enabled)
        val other = r.frames.last()[1]
        assertThrows(IllegalStateException::class.java) {
            r.actions.press(ReceiverKey.of(other.target), other.requestToken, other.revision)
        }
        r.model.updateDiscovery(emptyList()); r.flush()
        assertEquals("Stop", r.frames.last().single().label)
        assertEquals(r.owner!!.request.token, r.frames.last().single().requestToken)
        binding.detach()
    }
    @Test fun lateStartDeliveryErrorAfterRealServiceAdoptionCannotEnableDuplicatePlayOrSettings() {
        val r = Rig(); r.discover()
        val binding = r.bind(); binding.attach()
        val handler = ReceiverRowActions(r.model, {}, { Settings() }, {
            r.owner = r.service.adopt(it.token)
            r.progress("Streaming")
            throw IOException("Delivery reply failed after adoption")
        }, {})
        val row = r.model.rows(true).single()
        assertThrows(IOException::class.java) { handler.press(ReceiverKey.of(row.target), null, row.revision) }
        r.flush()
        assertEquals("Stop", r.frames.last().single().label)
        assertFalse(r.effects.last().settingsEnabled); assertTrue(r.effects.last().keepScreenOn)
        assertNull(r.service.adopt(r.owner!!.request.token))
        assertThrows(IllegalStateException::class.java) { handler.press(ReceiverKey.of(row.target), null, row.revision) }
        binding.detach()
    }
    @Test fun oldOwnerProgressAndFinishCannotClearNewServiceOrItsWindowAndSettingsState() {
        val r = Rig(); r.discover(); r.press(); val old = r.owner!!
        assertTrue(r.finish()); r.press(); r.progress("Streaming")
        val binding = r.bind(); binding.attach()
        r.service.progress(old, "Error: stale"); assertFalse(r.service.finish(old))
        assertFalse(r.service.owns(old)); assertTrue(r.service.owns(r.owner!!))
        r.flush()
        assertEquals("Stop", r.frames.last().single().label)
        assertFalse(r.effects.last().settingsEnabled); assertTrue(r.effects.last().keepScreenOn)
        binding.detach()
    }
    @Test fun serviceFailureRetainsStoppingControlsUntilActualCleanupThenRestoresIdle() {
        val r = Rig(); r.discover(); r.press()
        val binding = r.bind(); binding.attach()
        r.progress("Error: Encoder refused settings"); r.flush()
        assertEquals("Stopping...", r.frames.last().single().label)
        assertFalse(r.effects.last().settingsEnabled); assertTrue(r.effects.last().keepScreenOn)
        r.finish(); r.flush()
        assertEquals("Encoder refused settings", r.model.snapshot().error)
        assertEquals("Play", r.frames.last().single().label)
        assertTrue(r.effects.last().settingsEnabled); assertFalse(r.effects.last().keepScreenOn)
        binding.detach()
    }
    @Test fun cancelledPendingRequestCannotBeAdoptedAndDuplicateObserverAttachIsIdempotent() {
        val r = Rig(); r.discover()
        val binding = r.bind(); binding.attach(); binding.attach(); r.flush()
        assertEquals(1, r.frames.size)
        val request = r.model.reserve(ReceiverKey("192.0.2.1", 27184), Settings())
        r.model.requestStop(request.token)
        assertNull(r.service.adopt(request.token))
        r.model.complete(request.token); r.flush()
        assertEquals("Play", r.frames.last().single().label)
        assertFalse(r.effects.last().keepScreenOn)
        binding.detach(); binding.detach()
    }
    @Test fun readinessLossDoesNotReleaseServiceOrEnableSettingsBeforeItsCleanup() {
        val r = Rig(); r.discover(); r.press(); r.progress("Streaming")
        val binding = r.bind(); binding.attach(); r.ready = false
        r.progress("Stopping"); r.flush()
        assertEquals("Stopping...", r.frames.last().single().label)
        assertFalse(r.effects.last().settingsEnabled); assertTrue(r.effects.last().keepScreenOn)
        r.finish(); r.flush()
        assertFalse(r.effects.last().settingsEnabled); assertFalse(r.effects.last().keepScreenOn)
        assertNull(r.model.snapshot().active)
        binding.detach()
    }
}
