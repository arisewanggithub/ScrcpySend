package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test

/** Exact lifecycle/intent decisions; real UserService proof remains mandatory downstream. */
class ShizukuAutoConnectTest {
    private val binder = Any()
    private fun ShizukuAutoConnect.tick(
        availability: ShizukuState = ShizukuState.CONNECTING, ready: Boolean = false,
        checking: Boolean = false, cleanup: Boolean = false, casting: Boolean = false,
        identity: Any? = binder
    ) = decide(availability, ready, checking, cleanup, casting, identity)

    @Test fun startupAlreadyGrantedStartsOneCheckAndHidesSetupOnlyAfterProof() {
        val automatic = ShizukuAutoConnect()
        automatic.enter()
        assertEquals(ShizukuAutoConnect.Action.CHECK, automatic.tick())
        assertFalse(ReceiverMainScreen.listVisible(false, false)) // Permission by itself is not proof.
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(checking = true))
        assertEquals(ShizukuAutoConnect.Action.REUSE, automatic.tick(ready = true))
        assertTrue(ReceiverMainScreen.listVisible(true, false))
    }

    @Test fun authorizeInShizukuThenReturnAutomaticallyChecksAndAdvances() {
        val automatic = ShizukuAutoConnect()
        automatic.enter()
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(ShizukuState.DENIED))
        automatic.leave(false) // Open Shizuku; user grants permission there.
        automatic.enter()
        assertEquals(ShizukuAutoConnect.Action.CHECK, automatic.tick())
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(checking = true))
        assertFalse(ReceiverMainScreen.listVisible(false, false))
        assertEquals(ShizukuAutoConnect.Action.REUSE, automatic.tick(ready = true))
        assertTrue(ReceiverMainScreen.listVisible(true, false))
    }

    @Test fun repeatedResumeBinderAndGrantCallbacksCannotDuplicateInFlightChecks() {
        val automatic = ShizukuAutoConnect()
        automatic.enter()
        assertEquals(ShizukuAutoConnect.Action.CHECK, automatic.tick())
        automatic.enter() // Repeated onResume/entry callback while still foreground.
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(checking = true))
        assertTrue(automatic.permissionGranted(automatic.permissionRequested()))
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(checking = true))
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(checking = true))
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick()) // A real failure does not busy-loop.
    }

    @Test fun idleBackgroundReleaseRechecksOnReturnWithoutAButtonAndWaitsForCleanup() {
        val automatic = ShizukuAutoConnect()
        automatic.enter()
        automatic.tick()
        assertEquals(ShizukuAutoConnect.Action.REUSE, automatic.tick(ready = true))
        automatic.leave(false)
        automatic.enter()
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(cleanup = true))
        assertEquals(ShizukuAutoConnect.Action.CHECK, automatic.tick())
        assertEquals(ShizukuAutoConnect.Action.REUSE, automatic.tick(ready = true))
    }

    @Test fun aStillLiveProofIsReusedWithoutNewUserServiceOrSetupScreen() {
        val automatic = ShizukuAutoConnect()
        automatic.enter()
        assertEquals(ShizukuAutoConnect.Action.REUSE, automatic.tick(ready = true))
        automatic.leave(false); automatic.enter()
        assertEquals(ShizukuAutoConnect.Action.REUSE, automatic.tick(ready = true))
        assertEquals(ShizukuAutoConnect.Action.REUSE, automatic.tick(ready = true))
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick()) // Same Binder's failed proof cannot self-loop.
    }

    @Test fun configurationRecreationPreservesAttemptAndDoesNotRestartFailedReadiness() {
        val automatic = ShizukuAutoConnect()
        automatic.enter()
        assertEquals(ShizukuAutoConnect.Action.CHECK, automatic.tick())
        automatic.leave(true)
        automatic.enter()
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick())
        automatic.userRetry()
        assertEquals(ShizukuAutoConnect.Action.CHECK, automatic.tick())
    }

    @Test fun actualNewBinderArrivalRetriesButStickyDuplicateOfFailedBinderDoesNot() {
        val automatic = ShizukuAutoConnect()
        automatic.enter()
        automatic.tick()
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick())
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(ShizukuState.STOPPED, identity = null))
        assertEquals(ShizukuAutoConnect.Action.CHECK, automatic.tick(identity = Any()))
    }

    @Test fun explicitCancelOrBackRemainsPausedAcrossResumeAndBinderEventsUntilUserRetry() {
        val automatic = ShizukuAutoConnect()
        automatic.enter(); automatic.tick()
        automatic.pause()
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(cleanup = true))
        automatic.leave(false); automatic.enter()
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(identity = Any()))
        assertTrue(automatic.paused)
        automatic.userRetry()
        assertEquals(ShizukuAutoConnect.Action.CHECK, automatic.tick())
    }

    @Test fun pausedIntentSurvivesSavedStateRestorationWithoutTreatingProofAsPermission() {
        val restored = ShizukuAutoConnect(paused = true)
        restored.enter()
        assertEquals(ShizukuAutoConnect.Action.NONE, restored.tick())
        assertTrue(restored.permissionGranted(restored.permissionRequested()))
        assertEquals(ShizukuAutoConnect.Action.CHECK, restored.tick())
    }

    @Test fun missingDeniedStoppedAndRootWithoutConsentNeverAutoPromptOrApprove() {
        for (blocked in listOf(ShizukuState.MISSING, ShizukuState.STOPPED, ShizukuState.DENIED,
            ShizukuState.ROOT_CONFIRMATION, ShizukuState.ERROR)) {
            val automatic = ShizukuAutoConnect()
            automatic.enter()
            assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(blocked))
            assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(blocked, ready = true))
        }
        val root = ShizukuAutoConnect()
        root.enter()
        assertEquals(ShizukuAutoConnect.Action.NONE, root.tick(ShizukuState.ROOT_CONFIRMATION))
        root.userRetry() // Explicit checkbox changes policy to permitted root; never automatic approval.
        assertEquals(ShizukuAutoConnect.Action.CHECK, root.tick())
    }

    @Test fun activeCastOrStopDoesNotTriggerAnotherCheckOrAutomaticallyStartCapture() {
        val automatic = ShizukuAutoConnect()
        automatic.enter()
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(casting = true))
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(ready = true, casting = true))
        assertEquals(ShizukuAutoConnect.Action.REUSE, automatic.tick(ready = true, casting = false))
        assertTrue(ReceiverMainScreen.listVisible(true, false))
        assertNull(ReceiverCasting().snapshot().active)
        val replacement = Any()
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(casting = true, identity = replacement))
        assertEquals(ShizukuAutoConnect.Action.CHECK, automatic.tick(identity = replacement))
    }

    @Test fun lateOrDuplicateGrantCannotUndoNewerCancelOrRetryAFailedProof() {
        val automatic = ShizukuAutoConnect()
        automatic.enter()
        val cancelled = automatic.permissionRequested()
        automatic.pause()
        assertFalse(automatic.permissionGranted(cancelled))
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick())
        val fresh = automatic.permissionRequested()
        assertTrue(automatic.permissionGranted(fresh))
        assertEquals(ShizukuAutoConnect.Action.CHECK, automatic.tick())
        assertFalse(automatic.permissionGranted(fresh))
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick())
        val denied = automatic.permissionRequested()
        automatic.permissionDenied(denied)
        assertFalse(automatic.permissionGranted(denied))
    }

    @Test fun externallyRestoredPermissionInSameForegroundCanTriggerOneFreshProof() {
        val automatic = ShizukuAutoConnect()
        automatic.enter()
        assertEquals(ShizukuAutoConnect.Action.CHECK, automatic.tick())
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(ShizukuState.DENIED))
        assertEquals(ShizukuAutoConnect.Action.CHECK, automatic.tick())
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick())
    }

    @Test fun cleanupPendingProofCannotBeRenderedOrUsedAsReady() {
        val pending = ShizukuSetup.Snapshot(7, ShizukuState.READY, "actual capabilities", cleanupPending = true)
        assertFalse(pending.ready); assertTrue(pending.checking)
        val released = pending.copy(cleanupPending = false)
        assertTrue(released.ready); assertFalse(released.checking)
        val automatic = ShizukuAutoConnect()
        automatic.enter()
        assertEquals(ShizukuAutoConnect.Action.NONE, automatic.tick(cleanup = true))
        assertEquals(ShizukuAutoConnect.Action.CHECK, automatic.tick())
    }
}
