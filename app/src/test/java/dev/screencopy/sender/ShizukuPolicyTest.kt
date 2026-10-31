package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException

class ShizukuPolicyTest {
    @Test fun missingStoppedAndPermissionDeniedNeverGrantReadiness() {
        assertEquals(ShizukuState.MISSING, ShizukuPolicy.state(false, false, false, -1, false))
        assertEquals(ShizukuState.STOPPED, ShizukuPolicy.state(true, false, false, -1, false))
        assertEquals(ShizukuState.DENIED, ShizukuPolicy.state(true, true, false, -1, false))
        assertEquals(ShizukuState.CONNECTING, ShizukuPolicy.state(true, true, true, 2000, false))
        assertNotEquals(ShizukuState.READY, ShizukuPolicy.state(true, true, true, 2000, false))
    }
    @Test fun rootIsOnlyAcceptedWithDeliberateConsentAndShellNeverRequiresRoot() {
        assertEquals(ShizukuState.ROOT_CONFIRMATION, ShizukuPolicy.state(true, true, true, 0, false))
        assertEquals(ShizukuState.CONNECTING, ShizukuPolicy.state(true, true, true, 0, true))
        ShellCapturePolicy.requireUid(2000, false)
        ShellCapturePolicy.requireUid(0, true)
        assertThrows(SecurityException::class.java) { ShellCapturePolicy.requireUid(0, false) }
        assertThrows(SecurityException::class.java) { ShellCapturePolicy.requireUid(10123, true) }
        assertEquals(ShizukuState.ERROR, ShizukuPolicy.state(true, true, true, 1000, true))
    }
    @Test fun deploymentAndLocalabstractNamesAreBoundToValidatedSessionOnly() {
        assertEquals("/data/local/tmp/screencopy-0123abcd.jar", ShellCapturePolicy.path("0123abcd"))
        assertEquals("scrcpy_0123abcd", ShellCapturePolicy.socket("0123abcd", 1))
        assertEquals("scrcpy_0123abcd", ShellCapturePolicy.socket("0123abcd", 2))
        for (invalid in listOf("../other", "abc", "0123ABCD", "0123abcd;id", "")) {
            assertThrows(IOException::class.java) { ShellCapturePolicy.path(invalid) }
        }
        assertThrows(IOException::class.java) { ShellCapturePolicy.socket("0123abcd", 3) }
    }
    @Test fun appOrBinderDeathClosesEveryOwnedStreamAndRejectsLateSocketAdoption() {
        val lifetime = CaptureLifetime()
        val closed = mutableListOf<Int>()
        lifetime.own(Closeable { closed.add(1) })
        lifetime.own(Closeable { closed.add(2) })
        lifetime.close()
        assertEquals(listOf(2, 1), closed)
        lifetime.close()
        assertThrows(IOException::class.java) { lifetime.check() }
        assertThrows(IOException::class.java) { lifetime.own(Closeable { closed.add(3) }) }
        assertEquals(listOf(2, 1, 3), closed)
    }
    @Test fun cancellationStillClosesOtherResourcesWhenOneSocketFails() {
        val lifetime = CaptureLifetime()
        var closed = false
        lifetime.own(Closeable { closed = true })
        lifetime.own(Closeable { throw IOException("Socket close") })
        assertThrows(IOException::class.java) { lifetime.close() }
        assertTrue(closed)
        assertThrows(IOException::class.java) { lifetime.check() }
    }
    @Test fun privilegedResourceBudgetCannotGrowUnbounded() {
        val lifetime = CaptureLifetime(1)
        lifetime.own(Closeable {})
        var rejectedClosed = false
        assertThrows(IOException::class.java) { lifetime.own(Closeable { rejectedClosed = true }) }
        assertTrue(rejectedClosed)
        lifetime.close()
    }
}
