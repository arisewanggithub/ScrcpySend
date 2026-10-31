package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test

class CaptureScopeTest {
    @Test fun delayedOldCancelAndCleanupCannotStopOrClearNewOwnership() {
        val scope = CaptureScope()
        scope.claim("00000001"); assertTrue(scope.cancel("00000001")); scope.finish("00000001")
        scope.claim("00000002")
        assertFalse(scope.cancel("00000001"))
        assertThrows(IllegalStateException::class.java) { scope.requireCleanup("00000001") }
        assertThrows(IllegalStateException::class.java) { scope.finish("00000001") }
        scope.requireActive("00000002")
        assertEquals("00000002", scope.id)
    }
    @Test fun stopBeforeNativeOwnershipAdoptionRejectsLateLaunchAndSocketOpen() {
        val scope = CaptureScope()
        scope.claim("00000001"); scope.cancel("00000001")
        assertThrows(IllegalStateException::class.java) { scope.requireActive("00000001") }
        assertThrows(IllegalStateException::class.java) { scope.claim("00000002") }
        scope.requireCleanup("00000001"); scope.finish("00000001")
        scope.claim("00000002"); scope.requireActive("00000002")
    }
    @Test fun duplicateStopIsIdempotentAndRecoveryWithoutLiveOwnerIsReadOnly() {
        val scope = CaptureScope()
        assertFalse(scope.cancel("00000001")); scope.requireCleanup("00000001")
        scope.claim("00000001")
        assertTrue(scope.cancel("00000001")); assertTrue(scope.cancel("00000001"))
        scope.finish("00000001"); assertNull(scope.id)
    }
    @Test fun cleanupCannotConfirmOrStartNewOwnershipBeforeCancelledDeploymentFinishes() {
        val scope = CaptureScope()
        scope.claim("00000001")
        val operation = scope.beginOperation("00000001")
        assertThrows(IllegalStateException::class.java) { scope.beginOperation("00000001") }
        scope.cancel("00000001")
        assertSame(operation, scope.pendingOperation("00000001"))
        assertThrows(IllegalStateException::class.java) { scope.finish("00000001") }
        assertThrows(IllegalStateException::class.java) { scope.claim("00000002") }
        operation.countDown()
        scope.finish("00000001")
        scope.claim("00000002")
        assertEquals(0L, scope.pendingOperation("00000002").count)
    }
}
