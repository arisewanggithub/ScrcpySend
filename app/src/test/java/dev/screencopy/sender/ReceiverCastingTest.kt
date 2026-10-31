package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ReceiverCastingTest {
    @Test fun delayedStartDispatchFailureMustNotEraseAlreadyClaimedStreamingService() {
        val model = ReceiverCasting { 1L }
        model.updateDiscovery(listOf(Discovery.Receiver("TV", "192.0.2.1", 27184, "available", 1)))
        var serviceClaimed = false
        val actions = ReceiverRowActions(model, {}, { Settings() }, { request ->
            serviceClaimed = model.claim(request.token) != null
            model.progress(request.token, "Streaming")
            throw IOException("Late start delivery failure after service adoption")
        }, {})
        val row = model.rows(true).single()
        assertThrows(IOException::class.java) { actions.press(ReceiverKey.of(row.target), null, row.revision) }
        assertTrue(serviceClaimed)
        assertNotNull("A live service must remain authoritative after the Activity's start call returns an error", model.snapshot().active)
        assertEquals("Stop", model.rows(true).single().label)
    }

    private class Rig {
        var now = 1L
        var ready = true
        var saved = Settings()
        val model = ReceiverCasting { now }
        val starts = mutableListOf<ReceiverCasting.Request>()
        val stops = mutableListOf<ReceiverCasting.Request>()
        val actions = ReceiverRowActions(model, { check(ready) { "Shizuku unavailable" } }, { saved }, starts::add, stops::add)
        fun discover(state: String = "available", host: String = "192.0.2.1", name: String = "TV") {
            model.updateDiscovery(listOf(Discovery.Receiver(name, host, 27184, state, now)))
        }
        fun row() = model.rows(ready).first()
        fun press(row: ReceiverCasting.Row = row()) = actions.press(ReceiverKey.of(row.target), row.requestToken, row.revision)
    }

    @Test fun playImmediatelyFreezesExactDiscoveredEndpointAndPersistedSettings() {
        val r = Rig(); r.saved.cropWidth = 640; r.saved.cropHeight = 480
        r.saved.videoEncoder = "c2.test.encoder"; r.saved.orientation = "@90"; r.discover()
        r.press()
        val request = r.starts.single()
        assertEquals(ReceiverTarget("192.0.2.1", 27184, "TV"), request.target)
        assertEquals(r.saved.request().toString(), request.settings)
        r.saved.audio = false
        assertTrue(Settings.from(Wire.parseJson(request.settings.toByteArray())).audio)
        assertEquals(ReceiverCasting.Phase.CONNECTING, r.model.snapshot().active?.phase)
        assertTrue(r.row().label.startsWith("Connecting"))
        r.model.progress(request.token, "Negotiating")
        assertEquals(ReceiverCasting.Phase.CONNECTING, r.model.snapshot().active?.phase)
        r.model.progress(request.token, "Streaming")
        assertEquals("Stop", r.row().label)
    }

    @Test fun ownBusyAndExpiredAdvertisementKeepStopReachableAndOthersDisabled() {
        val r = Rig(); r.discover(); r.press()
        r.model.progress(r.starts.single().token, "Streaming")
        r.model.updateDiscovery(listOf(Discovery.Receiver("TV", "192.0.2.1", 27184, "busy", r.now),
            Discovery.Receiver("Other", "192.0.2.2", 27184, "available", r.now)))
        val rows = r.model.rows(true)
        assertTrue(rows.first { it.active }.enabled)
        assertFalse(rows.first { !it.active }.enabled)
        r.now += 6_000_000_001
        assertEquals(1, r.model.rows(true).size)
        r.press()
        assertEquals(1, r.stops.size)
        assertEquals("Stopping...", r.row().label)
        assertFalse(r.row().enabled)
        r.model.complete(r.starts.single().token)
        assertTrue(r.model.rows(true).isEmpty())
    }

    @Test fun busyOtherCannotPlayAndSameSourceEndpointRefreshesNameWithoutRedirect() {
        val r = Rig(); r.discover("busy")
        assertFalse(r.row().enabled)
        assertThrows(IllegalStateException::class.java) { r.press() }
        assertTrue(r.starts.isEmpty())
        r.discover(name = "Renamed"); r.press()
        assertEquals("Renamed", r.starts.single().target.name)
        r.discover(host = "192.0.2.9", name = "Renamed")
        assertEquals("192.0.2.1", r.model.snapshot().active?.request?.target?.host)
        assertTrue(r.model.rows(true).first { it.active }.enabled)
    }

    @Test fun doubleTapPlayRejectedAndDoubleTapStopDispatchesOnlyOnce() {
        val r = Rig(); r.discover(); val old = r.row(); r.press(old)
        assertThrows(IllegalStateException::class.java) { r.press(old) }
        assertEquals(1, r.starts.size)
        val current = r.row(); r.press(current); r.press(current)
        assertEquals(1, r.stops.size)
    }

    @Test fun cancelPendingStartCannotBeClaimedAndHoldsRowUntilCleanupCompletes() {
        val r = Rig(); r.discover(); r.press(); r.press()
        val token = r.starts.single().token
        assertNull(r.model.claim(token))
        assertNotNull(r.model.snapshot().active)
        assertFalse(r.row().enabled)
        r.model.complete(token)
        assertEquals("Play", r.row().label)
    }

    @Test fun oldStopProgressAndCompletionCannotAffectNewerSession() {
        val r = Rig(); r.discover(); r.press()
        val old = r.row(); val first = r.starts.single().token
        r.model.complete(first); r.press(); val latest = r.starts.last().token
        r.model.progress(first, "Streaming"); r.model.progress(first, "Error: stale")
        r.model.complete(first, "stale")
        assertThrows(IllegalStateException::class.java) { r.press(old) }
        assertEquals(latest, r.model.snapshot().active?.request?.token)
        assertTrue(r.stops.isEmpty()); assertEquals("", r.model.snapshot().error)
    }

    @Test fun errorsRetainActiveDuringCleanupThenAllowExplicitPlayRetry() {
        val r = Rig(); r.discover(); r.press(); val token = r.starts.single().token
        r.model.progress(token, "Error: Encoder unavailable")
        assertNotNull(r.model.snapshot().active); assertFalse(r.row().enabled)
        r.model.complete(token)
        assertEquals("Encoder unavailable", r.model.snapshot().error)
        r.press(); assertEquals(2, r.starts.size); assertEquals("", r.model.snapshot().error)
    }

    @Test fun lostShizukuStillAllowsOwnedStopThenReturnsToSetupAndNeverAutoStarts() {
        val r = Rig(); r.discover(); r.press(); r.ready = false
        assertTrue(ReceiverMainScreen.listVisible(false, true)); assertTrue(r.row().enabled)
        r.press(); r.model.complete(r.starts.single().token)
        assertFalse(ReceiverMainScreen.listVisible(false, false)); assertFalse(r.row().enabled)
        assertThrows(IllegalStateException::class.java) { r.press() }
        assertEquals(1, r.starts.size)
    }

    @Test fun recreationAndDiscoveryResetRetainSameOwnedTokenUntilStopCompletion() {
        val r = Rig(); r.discover(); r.press(); val token = r.starts.single().token
        r.model.updateDiscovery(emptyList())
        val recreated = ReceiverRowActions(r.model, {}, { Settings() }, { fail("Unexpected restart") }, r.stops::add)
        val row = r.row()
        recreated.press(ReceiverKey.of(row.target), row.requestToken, row.revision)
        assertEquals(token, r.stops.single().token)
        r.model.complete(token)
        assertTrue(r.model.rows(true).isEmpty())
    }

    @Test fun dispatchFailureIsExplicitAndCanBeRetriedWithoutHiddenSession() {
        val r = Rig(); r.discover()
        val bad = ReceiverRowActions(r.model, {}, { Settings() }, { throw IOException("Service unavailable") }, {})
        assertThrows(IOException::class.java) { val row = r.row(); bad.press(ReceiverKey.of(row.target), null, row.revision) }
        assertNull(r.model.snapshot().active)
        assertTrue(r.model.snapshot().error.contains("Service unavailable"))
        r.press(); assertEquals(1, r.starts.size)
    }

    @Test fun invalidSettingsAndUnreadyCaptureNeverReserveOrLaunch() {
        val r = Rig(); r.discover(); r.saved.videoOptions = "profile=1;id"
        assertThrows(IllegalArgumentException::class.java) { r.press() }
        assertNull(r.model.snapshot().active); assertTrue(r.starts.isEmpty())
        r.saved = Settings(); r.ready = false
        assertThrows(IllegalStateException::class.java) { r.press() }
        assertNull(r.model.snapshot().active)
    }

    @Test fun stopDispatchFailureKeepsActualSessionActionableRatherThanFakingStopped() {
        val r = Rig(); r.discover(); r.press(); val token = r.starts.single().token
        r.model.progress(token, "Streaming")
        val bad = ReceiverRowActions(r.model, {}, { Settings() }, {}, {
            r.model.progress(token, "Receiver warning: temporary status raced Stop")
            throw IOException("Stop dispatch failed")
        })
        val row = r.row()
        assertThrows(IOException::class.java) { bad.press(ReceiverKey.of(row.target), row.requestToken, row.revision) }
        assertEquals(ReceiverCasting.Phase.PLAYING, r.model.snapshot().active?.phase)
        assertTrue(r.row().enabled); assertTrue(r.model.snapshot().error.contains("Retry Stop"))
    }

    @Test fun directoryResetsOnNetworkChangeAndExpiresSourceEndpointsWithoutNameRedirection() {
        val directory = ReceiverDirectory()
        directory.update(Discovery.Receiver("TV", "192.0.2.1", 27184, "available", 1))
        directory.update(Discovery.Receiver("Renamed", "192.0.2.1", 27184, "busy", 2))
        assertEquals(1, directory.snapshot(3).size)
        assertEquals("Renamed", directory.snapshot(3).single().name)
        directory.update(Discovery.Receiver("Renamed", "192.0.2.2", 27184, "available", 3))
        assertEquals(2, directory.snapshot(4).size)
        assertTrue(directory.snapshot(6_000_000_004).isEmpty())
        directory.update(Discovery.Receiver("TV", "192.0.2.3", 27184, "available", 7))
        directory.networkChanged(); assertTrue(directory.snapshot(8).isEmpty())
    }
}
