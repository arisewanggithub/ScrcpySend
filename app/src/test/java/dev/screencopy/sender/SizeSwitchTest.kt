package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test

class SizeSwitchTest {
    private class Fixture(codec: String = "h264", size: Int = 1200) {
        val saved = Settings().apply {
            maxSize = size; videoCodec = codec; maxFps = 24; videoBitrate = 2_500_000
            if (size != 0) { cropWidth = 640; cropHeight = 480; cropX = 12; cropY = 14 }
        }
        val wire = saved.request().toString()
        val casting = ReceiverCasting { 1L }
        val state = CastingServiceState(casting)
        val request: ReceiverCasting.Request
        val owner: ReceiverCasting.ServiceLease
        val authorization = SizeSwitchAuthorization()
        init {
            casting.updateDiscovery(listOf(Discovery.Receiver("Pinned TV", "192.0.2.1", 27184, "available", 1)))
            request = casting.reserve(ReceiverKey("192.0.2.1", 27184), saved, applyRuntimeOverride = true)
            owner = checkNotNull(state.adopt(request.token))
            state.progress(owner, "Streaming")
        }
        fun tap() = casting.requestSizeSwitch(request.token, casting.snapshot().size.version, true)
        fun authorize() {
            assertEquals(ReceiverCasting.SizeAction.Restart, tap())
            assertTrue(authorization.request(saved.videoCodec, playing = true, failed = false))
        }
        fun acknowledge() { authorization.acknowledge(); authorization.peerClosed() }
    }

    @Test fun freshRuntimeIsUncheckedAndIdleToggleNeverReservesOrPersistsAnything() {
        val casting = ReceiverCasting()
        assertFalse(casting.snapshot().size.requested)
        casting.setNextSizeOverride(true)
        assertTrue(casting.snapshot().size.requested)
        assertNull(casting.snapshot().active)
        assertFalse(ReceiverCasting().snapshot().size.requested)
        assertEquals(ReceiverCasting.SizeAction.Ignored, casting.requestSizeSwitch(123, 0, false))
        assertNull(casting.snapshot().active)
    }

    @Test fun playFreezesSavedAndEffectiveCopiesAndFullScreenDoesNotChangeOtherFields() {
        val f = Fixture()
        f.authorize()
        assertEquals(f.wire, f.saved.request().toString())
        assertEquals(f.wire, f.casting.snapshot().active!!.request.settings)
        f.saved.maxFps = 60; f.saved.videoBitrate = 9_000_000
        f.acknowledge()
        val next = checkNotNull(f.state.finishSizeSwitch(f.owner, f.authorization.permitsRestart(true)))
        assertEquals(f.request.target, next.target)
        assertEquals(f.wire, next.savedSettings)
        val effective = Settings.from(Wire.parseJson(next.settings.toByteArray()))
        assertEquals(0, effective.maxSize)
        assertTrue(effective.request().getJSONObject("video").isNull("crop"))
        assertEquals(24, effective.maxFps)
        assertEquals(2_500_000, effective.videoBitrate)
        assertEquals(HomeCaptureSettings.effective(
            Settings.from(Wire.parseJson(f.wire.toByteArray())), true).request().toString(), next.settings)
    }

    @Test fun userTapWaitsForStopAckPeerCloseAndOwnedCleanupBeforeOneNewNegotiation() {
        val f = Fixture()
        f.authorize()
        assertEquals(ReceiverCasting.Phase.STOPPING, f.casting.snapshot().active!!.phase)
        assertTrue(f.casting.snapshot().size.busy)
        assertFalse(f.casting.snapshot().size.enabled)
        assertFalse(f.authorization.permitsRestart(true))
        f.authorization.acknowledge()
        assertFalse(f.authorization.permitsRestart(true))
        f.authorization.peerClosed()
        assertFalse(f.authorization.permitsRestart(false))
        assertTrue(f.authorization.permitsRestart(true))
        val next = checkNotNull(f.state.finishSizeSwitch(f.owner, f.authorization.permitsRestart(true)))
        assertNotEquals(f.request.token, next.token)
        assertTrue(next.token > 0)
        assertEquals(ReceiverCasting.Phase.CONNECTING, f.casting.snapshot().active!!.phase)
        assertEquals(f.wire, f.casting.snapshot().active!!.appliedRequest.settings)
        assertFalse(f.casting.snapshot().size.applied)
        assertFalse(f.casting.snapshot().size.enabled)
        assertNull(f.state.finishSizeSwitch(f.owner, true))
        val newOwner = checkNotNull(f.state.adopt(next.token))
        f.state.progress(newOwner, "Negotiating")
        assertEquals(ReceiverCasting.Phase.CONNECTING, f.casting.snapshot().active!!.phase)
        f.state.progress(newOwner, "StartingCapture") // Existing SenderSession's SESSION_ACCEPT boundary.
        assertEquals(ReceiverCasting.Phase.CONNECTING, f.casting.snapshot().active!!.phase)
        f.state.progress(newOwner, "Streaming")
        assertTrue(f.casting.snapshot().size.applied)
        assertEquals("Size applied", f.casting.snapshot().size.message)
    }

    @Test fun repeatedTapsAreDebouncedAndOldNotificationVersionCannotFlipTwice() {
        val f = Fixture()
        val version = f.casting.snapshot().size.version
        f.authorize()
        repeat(20) {
            assertEquals(ReceiverCasting.SizeAction.Ignored,
                f.casting.requestSizeSwitch(f.request.token, version, false))
        }
        f.acknowledge()
        val next = checkNotNull(f.state.finishSizeSwitch(f.owner, true))
        val nextOwner = checkNotNull(f.state.adopt(next.token))
        f.state.progress(nextOwner, "Streaming")
        assertEquals(ReceiverCasting.SizeAction.Ignored,
            f.casting.requestSizeSwitch(f.request.token, version, false))
        f.state.progress(f.owner, "Error: obsolete callback")
        assertFalse(f.state.finish(f.owner, "obsolete failure"))
        assertEquals(next.token, f.casting.snapshot().active!!.request.token)
        assertEquals("", f.casting.snapshot().error)
        assertTrue(f.casting.snapshot().size.applied)
    }

    @Test fun stopButtonOrHomeCloseCancelsEvenAfterStopPhaseAlreadyStarted() {
        for (homeClose in listOf(false, true)) {
            val f = Fixture()
            f.authorize(); f.acknowledge()
            if (homeClose) f.casting.cancelSizeSwitch(f.request.token)
            else f.casting.requestStop(f.request.token)
            assertNull(f.state.finishSizeSwitch(f.owner, true))
            assertNull(f.casting.snapshot().active)
            assertTrue(f.casting.snapshot().size.requested) // Next manual Play only.
        }
    }

    @Test fun applyingRowKeepsUserStopReachableAndDispatchesCancellationNotAnotherPlay() {
        val f = Fixture()
        f.authorize()
        val stopped = mutableListOf<Long>()
        val actions = ReceiverRowActions(f.casting, {}, { error("Must not read settings on Stop") },
            { error("Must not start on Stop") }, { stopped.add(it.token) })
        val row = f.casting.rows(true).single()
        assertEquals("Applying size... (Stop)", row.label)
        assertTrue(row.enabled)
        actions.press(ReceiverKey.of(row.target), row.requestToken, row.revision)
        assertEquals(listOf(f.request.token), stopped)
        assertFalse(f.casting.snapshot().size.busy)
        f.acknowledge()
        assertNull(f.state.finishSizeSwitch(f.owner, true))
        assertNull(f.casting.snapshot().active)
    }

    @Test fun switchingBackRestoresTheFrozenSavedBaselineNotLaterEditorChanges() {
        val f = Fixture()
        f.authorize(); f.acknowledge()
        val full = checkNotNull(f.state.finishSizeSwitch(f.owner, true))
        val owner = checkNotNull(f.state.adopt(full.token))
        f.state.progress(owner, "Streaming")
        f.saved.cropWidth = 800; f.saved.maxFps = 60
        val snapshot = f.casting.snapshot()
        assertEquals(ReceiverCasting.SizeAction.Restart,
            f.casting.requestSizeSwitch(full.token, snapshot.size.version, false))
        assertEquals(full.settings, f.casting.snapshot().active!!.request.settings)
        val restored = checkNotNull(f.state.finishSizeSwitch(owner, true))
        assertEquals(f.wire, restored.settings)
        assertEquals(f.wire, restored.savedSettings)
        assertFalse(restored.fullScreen)
        assertEquals(f.request.target, restored.target)
    }

    @Test fun receiverStopDisconnectPermissionLossAndBackendFailureCannotAuthorizeRestart() {
        for (cause in listOf("receiver Stop", "disconnect", "permission revoked", "backend error")) {
            val f = Fixture()
            f.authorize()
            f.authorization.cancel()
            f.acknowledge()
            assertFalse(cause, f.authorization.permitsRestart(true))
            assertNull(f.state.finishSizeSwitch(f.owner, f.authorization.permitsRestart(true)))
            assertNull(f.casting.snapshot().active)
        }
    }

    @Test fun cleanupFailureOrMissingReceiverAckNeverRestartsAndFirstFailureSurvives() {
        val f = Fixture()
        f.authorize(); f.acknowledge()
        f.state.progress(f.owner, "Error: CAPTURE_FAILED: original fault")
        f.state.progress(f.owner, "Error: INTERNAL: later cleanup fault")
        assertNull(f.state.finishSizeSwitch(f.owner, false))
        assertEquals("CAPTURE_FAILED: original fault", f.casting.snapshot().error)
        f.state.progress(f.owner, "Error: late failure")
        assertEquals("CAPTURE_FAILED: original fault", f.casting.snapshot().error)
        val missingAck = Fixture()
        missingAck.authorize()
        missingAck.authorization.peerClosed()
        assertFalse(missingAck.authorization.permitsRestart(true))
        assertNull(missingAck.state.finishSizeSwitch(missingAck.owner, false))
        assertTrue(missingAck.casting.snapshot().error.contains("cleanup unconfirmed"))
        val cleanup = Fixture()
        cleanup.authorize(); cleanup.acknowledge()
        assertNull(cleanup.state.finishSizeSwitch(cleanup.owner, cleanup.authorization.permitsRestart(false),
            "Cleanup failed"))
        assertEquals("Cleanup failed", cleanup.casting.snapshot().error)
    }

    @Test fun everySupportedCodecUsesOneTapAndTheSameAckEofCleanupAndFreshSessionGates() {
        for (codec in listOf("h264", "h265", "av1")) {
            val f = Fixture(codec)
            val before = f.casting.snapshot()
            assertTrue(before.size.enabled)
            assertEquals("", before.size.message)
            f.authorize()
            assertFalse(f.authorization.request(codec, true, false))
            assertEquals(f.wire, f.saved.request().toString())
            assertEquals(f.wire, f.casting.snapshot().active!!.request.settings)
            assertEquals(ReceiverCasting.SizeAction.Ignored,
                f.casting.requestSizeSwitch(f.request.token, before.size.version, true))
            assertFalse(f.authorization.permitsRestart(true))
            f.authorization.peerClosed() // EOF without ACK cannot authorize anything.
            assertFalse(f.authorization.permitsRestart(true))
            f.authorization.acknowledge()
            assertFalse(f.authorization.permitsRestart(true))
            f.authorization.peerClosed()
            assertFalse(f.authorization.permitsRestart(false))
            val next = checkNotNull(f.state.finishSizeSwitch(f.owner, f.authorization.permitsRestart(true)))
            assertTrue(next.token > 0)
            assertNotEquals(f.request.token, next.token)
            assertEquals(f.request.target, next.target)
            assertEquals(f.wire, next.savedSettings)
            val effective = Settings.from(Wire.parseJson(next.settings.toByteArray()))
            assertEquals(codec, effective.videoCodec)
            assertEquals(24, effective.maxFps)
            assertEquals(2_500_000, effective.videoBitrate)
            assertEquals(0, effective.maxSize)
            assertTrue(effective.request().getJSONObject("video").isNull("crop"))
            assertNull(f.state.finishSizeSwitch(f.owner, true))
            val owner = checkNotNull(f.state.adopt(next.token))
            f.state.progress(owner, "Negotiating")
            assertFalse(f.casting.snapshot().size.enabled)
            assertFalse(f.casting.snapshot().size.applied)
            assertEquals(f.wire, f.casting.snapshot().active!!.appliedRequest.settings)
            f.state.progress(owner, "Streaming")
            assertTrue(f.casting.snapshot().size.applied)
            assertEquals(next.settings, f.casting.snapshot().active!!.appliedRequest.settings)
            assertEquals(ReceiverCasting.SizeAction.Ignored,
                f.casting.requestSizeSwitch(next.token, before.size.version, false))
            assertEquals(ReceiverCasting.SizeAction.Restart,
                f.casting.requestSizeSwitch(next.token, f.casting.snapshot().size.version, false))
            val restored = checkNotNull(f.state.finishSizeSwitch(owner, true))
            assertEquals(f.wire, restored.settings)
            assertEquals(f.wire, restored.savedSettings)
        }
        for (codec in listOf("h264", "h265", "av1")) {
            assertFalse(SizeSwitchAuthorization().request(codec, playing = false, failed = false))
            assertFalse(SizeSwitchAuthorization().request(codec, playing = true, failed = true))
        }
        assertFalse(SizeSwitchAuthorization().request("unsupported", playing = true, failed = false))
    }

    @Test fun h265CancellationAndCleanupFailureCannotProduceAnAutomaticRetry() {
        for (cause in listOf("user Stop", "receiver Stop", "disconnect", "permission loss", "backend error")) {
            val f = Fixture("h265")
            f.authorize()
            f.authorization.cancel()
            f.acknowledge()
            assertFalse(cause, f.authorization.permitsRestart(true))
            assertNull(f.state.finishSizeSwitch(f.owner, f.authorization.permitsRestart(true)))
            assertNull(f.casting.snapshot().active)
            assertEquals(ReceiverCasting.SizeAction.Ignored, f.tap())
        }
        val f = Fixture("h265")
        f.authorize(); f.acknowledge()
        assertNull(f.state.finishSizeSwitch(f.owner, f.authorization.permitsRestart(false)))
        assertNull(f.casting.snapshot().active)
        assertNull(f.state.finishSizeSwitch(f.owner, true))
        assertEquals(f.wire, f.saved.request().toString())
    }

    @Test fun alreadyFullScreenH265NeverAuthorizesAnUnnecessaryStopOrPreflight() {
        val f = Fixture("h265", size = 0)
        val before = f.casting.snapshot()
        assertEquals(ReceiverCasting.SizeAction.Unchanged,
            f.casting.requestSizeSwitch(f.request.token, before.size.version, true,
                preflight = { error("Identical geometry must not preflight or Stop") }))
        val after = f.casting.snapshot()
        assertEquals(before.active!!.request.token, after.active!!.request.token)
        assertEquals(f.wire, after.active.request.settings)
        assertEquals(ReceiverCasting.Phase.PLAYING, after.active.phase)
        assertTrue(after.size.applied)
        assertFalse(after.size.busy)
        assertFalse(f.authorization.requested)
    }

    @Test fun alreadyUnlimitedAndUncroppedOnlyUpdatesRuntimeLabelWithoutFakeRestart() {
        val f = Fixture(size = 0)
        val version = f.casting.snapshot().size.version
        assertEquals(ReceiverCasting.SizeAction.Unchanged, f.tap())
        val current = f.casting.snapshot()
        assertTrue(current.size.applied)
        assertTrue(current.size.requested)
        assertFalse(current.size.busy)
        assertEquals(f.request.token, current.active!!.request.token)
        assertEquals(f.wire, current.active.request.settings)
        assertEquals(f.wire, f.saved.request().toString())
        assertEquals(ReceiverCasting.Phase.PLAYING, current.active.phase)
        assertEquals(ReceiverCasting.SizeAction.Ignored,
            f.casting.requestSizeSwitch(f.request.token, version, false))
        assertEquals(ReceiverCasting.SizeAction.Unchanged,
            f.casting.requestSizeSwitch(f.request.token, current.size.version, false))
    }

    @Test fun pinnedPeerSurvivesDiscoveryExpiryRedirectAndBusyAdvertisement() {
        val f = Fixture()
        f.authorize()
        f.casting.updateDiscovery(listOf(Discovery.Receiver("Pinned TV", "192.0.2.99", 29111, "available", 1),
            Discovery.Receiver("Renamed", "192.0.2.1", 27184, "busy", 1)))
        f.acknowledge()
        val next = checkNotNull(f.state.finishSizeSwitch(f.owner, true))
        assertEquals(f.request.target, next.target)
        assertEquals("192.0.2.1", next.target.host)
        assertEquals(27184, next.target.port)
        assertEquals("Pinned TV", next.target.name)
    }

    @Test fun unsupportedFullResolutionOrReconnectFailureIsExplicitAndNeverRetried() {
        val f = Fixture()
        f.authorize(); f.acknowledge()
        val next = checkNotNull(f.state.finishSizeSwitch(f.owner, true))
        val owner = checkNotNull(f.state.adopt(next.token))
        f.state.progress(owner, "Error: UNSUPPORTED_CONFIG: hardware cannot encode full physical resolution")
        assertNull(f.state.finishSizeSwitch(owner, false))
        assertNull(f.casting.snapshot().active)
        assertTrue(f.casting.snapshot().error.startsWith("UNSUPPORTED_CONFIG:"))
        assertNull(f.state.finishSizeSwitch(owner, true))
        assertNull(f.casting.snapshot().active)
        assertEquals(f.wire, f.saved.request().toString())
    }

    @Test fun unclaimedAndColdProcessNotificationCannotStartOrLoadSavedTarget() {
        val f = Fixture()
        val cold = ReceiverCasting()
        assertEquals(ReceiverCasting.SizeAction.Ignored,
            cold.requestSizeSwitch(f.request.token, f.casting.snapshot().size.version, true))
        assertNull(cold.snapshot().active)
        val unclaimed = ReceiverCasting { 1L }
        unclaimed.updateDiscovery(listOf(Discovery.Receiver("TV", "192.0.2.1", 27184, "available", 1)))
        val request = unclaimed.reserve(ReceiverKey("192.0.2.1", 27184), Settings())
        unclaimed.progress(request.token, "Streaming")
        assertFalse(unclaimed.snapshot().size.enabled)
        assertEquals(ReceiverCasting.SizeAction.Ignored,
            unclaimed.requestSizeSwitch(request.token, unclaimed.snapshot().size.version, true))
    }
}
