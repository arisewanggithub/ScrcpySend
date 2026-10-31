package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class WirelessPairingTest {
    private fun entered() = PairingUiState().apply { port = "37001"; code = "024680"; currentDialogConfirmed = true }

    @Test fun fullscreenPairingIsRejectedBeforeAuthentication() {
        val state = entered()
        val error = assertThrows(IllegalStateException::class.java) { state.input(false) }
        assertTrue(error.message.orEmpty().contains("visible"))
        assertFalse(state.inFlight)
    }

    @Test fun multiwindowAcceptsExactCurrentValuesWithoutLoggingThem() {
        val input = entered().input(true)
        assertEquals(37001, input.port); assertEquals("024680", input.code)
        assertFalse(input.toString().contains(input.code))
    }

    @Test fun floatingRequiresExplicitConfirmationAndLosesItWhenHidden() {
        val state = entered().apply { floatingConfirmed = true }
        assertEquals("024680", state.input(false).code)
        state.hidden()
        assertFalse(state.floatingConfirmed)
        assertEquals("37001", state.port); assertTrue(state.code.isEmpty())
        assertFalse(state.currentDialogConfirmed)
        assertThrows(IllegalStateException::class.java) { state.input(false) }
    }

    @Test fun openingSettingsInvalidatesOldValuesAndWarnsThatNewTaskIsNotEnough() {
        val state = entered()
        state.openingSettings()
        assertEquals("37001", state.port); assertTrue(state.code.isEmpty())
        assertFalse(state.currentDialogConfirmed)
        assertTrue(state.notice.contains("does not by itself"))
        assertTrue(WirelessPairing.GUIDE.contains("DIFFERENT"))
        assertTrue(WirelessPairing.GUIDE.contains("dialog OPEN"))
        assertTrue(WirelessPairing.GUIDE.contains("cannot force"))
    }

    @Test fun failedOrExpiredPairingClearsStaleCredentialsAndRequiresFreshDialog() {
        val state = entered()
        state.started(); state.failed("ConnectException")
        assertFalse(state.inFlight); assertTrue(state.code.isEmpty()); assertEquals("37001", state.port)
        assertFalse(state.currentDialogConfirmed)
        assertTrue(state.notice.contains("fresh")); assertTrue(state.notice.contains("reopen"))
        assertFalse(state.notice.contains("024680"))
        assertThrows(IllegalArgumentException::class.java) { state.input(true) }
    }

    @Test fun pendingPairingAndResultsSurviveConfigurationOwnerReplacement() {
        val state = entered()
        val old = Any(); val replacement = Any()
        var oldUpdates = 0; var newUpdates = 0
        state.observe(old) { oldUpdates++ }
        state.started()
        assertThrows(IllegalStateException::class.java) { state.input(true) }
        state.observe(replacement) { newUpdates++ }
        state.detach(old)
        assertEquals("024680", state.code)
        val before = oldUpdates
        state.succeeded()
        assertEquals(before, oldUpdates); assertEquals(2, newUpdates)
        assertFalse(state.inFlight); assertTrue(state.code.isEmpty())
        assertEquals("37001", state.port)
        assertTrue(state.notice.contains("DIFFERENT connection port"))
        state.detach(replacement)
    }

    @Test fun cancelledQueuedPairingCannotRemainPermanentlyPending() {
        val access = CaptureAccess()
        val state = entered()
        val pending = CaptureAccess.ReservedTask(access.acquire("pairing"),
            onCancelled = state::cancelledBeforeSubmission) { fail("Cancelled pairing must not authenticate") }
        state.started()
        pending.cancelPending(); pending.cancelPending(); pending.run()
        assertFalse(state.inFlight); assertTrue(state.code.isEmpty())
        assertEquals("37001", state.port)
        access.requireAvailable()
        assertTrue(state.notice.contains("fresh"))
    }

    @Test fun savedDraftIsExplicitlyNonSensitiveAndRetainsPartialSettings() {
        val draft = WirelessPairing.nonSensitiveDraft(mapOf(
            "pairCode" to "024680", "pairPort" to "37001", "pairPayload" to "secret QR",
            "futureSecret" to "private", "videoBitrate" to "", "cropWidth" to "12", "adbPort" to "45678"
        ))
        assertEquals(mapOf("pairPort" to "37001", "videoBitrate" to "", "cropWidth" to "12", "adbPort" to "45678"), draft)
    }

    @Test fun discoveredPairingPortNeverSuppliesCodeOrSilentlyOverwritesAnEditedDraft() {
        val state = PairingUiState().apply { port = "37" }
        assertTrue(state.acceptDiscoveredPort("37", 39123))
        assertEquals("39123", state.port); assertTrue(state.code.isEmpty()); assertFalse(state.currentDialogConfirmed)
        state.port = "49"
        assertFalse(state.acceptDiscoveredPort("39123", 40000))
        assertEquals("49", state.port)
        state.code = "024680"; state.currentDialogConfirmed = true
        state.started()
        assertFalse(state.acceptDiscoveredPort("49", 40000))
        assertEquals("49", state.port); assertEquals("024680", state.code)
    }

    @Test fun activitySupportsResizingWithoutAnOverlayPermission() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File(System.getProperty("senderManifest")))
        val namespace = "http://schemas.android.com/apk/res/android"
        val activities = manifest.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(namespace, "name") == ".MainActivity" }
        assertEquals("true", main.getAttributeNS(namespace, "resizeableActivity"))
        assertEquals("adjustResize", main.getAttributeNS(namespace, "windowSoftInputMode"))
        assertEquals("false", main.getAttributeNS(namespace, "enableOnBackInvokedCallback"))
        val permissions = manifest.getElementsByTagName("uses-permission")
        assertFalse((0 until permissions.length).any {
            (permissions.item(it) as org.w3c.dom.Element).getAttributeNS(namespace, "name") == "android.permission.SYSTEM_ALERT_WINDOW"
        })
    }

    @Test fun enteredPortSurvivesSettingsVisibilityLossReturnAndRecreationIncludingPartialText() {
        for (text in listOf("37001", "37", "000", "")) {
            val state = PairingUiState()
            val sender = Any()
            var displayedPort = ""
            state.observe(sender) { displayedPort = state.port }
            // TextWatcher synchronizes the typed draft before Settings is opened.
            state.port = text
            state.openingSettings()
            state.hidden()
            state.detach(sender)
            state.observe(Any()) { displayedPort = state.port }
            assertEquals("Port lost when returning from Settings", text, displayedPort)
            assertTrue(state.code.isEmpty()); assertFalse(state.currentDialogConfirmed)
            state.requireDialogRecheck()
            assertEquals(text, state.port)
            // A non-secret saved draft also survives a new instance after process/state restoration.
            val saved = WirelessPairing.nonSensitiveDraft(mapOf("pairPort" to state.port))
            val restored = PairingUiState().apply { restorePortDraft(checkNotNull(saved["pairPort"])) }
            restored.observe(Any()) { displayedPort = restored.port }
            assertEquals(text, displayedPort)
            assertFalse(restored.currentDialogConfirmed); assertTrue(restored.code.isEmpty())
        }
    }

    @Test fun retainedPortIsNotAnImplicitlyConfirmedEndpointEvenInMultiwindow() {
        val state = entered()
        state.hidden()
        state.code = "135790"
        assertEquals("37001", state.port)
        assertThrows(IllegalStateException::class.java) { state.input(true) }
        state.currentDialogConfirmed = true
        assertEquals(37001, state.input(true).port)
    }

    @Test fun editsAndConfigurationInvalidateConfirmationWithoutDiscardingDrafts() {
        val state = entered()
        state.port = "3700"
        assertFalse(state.currentDialogConfirmed)
        state.currentDialogConfirmed = true
        state.code = "135790"
        assertFalse(state.currentDialogConfirmed)
        state.currentDialogConfirmed = true
        state.requireDialogRecheck()
        assertEquals("3700", state.port); assertEquals("135790", state.code)
        assertFalse(state.currentDialogConfirmed)
        state.failed("ConnectException")
        assertEquals("3700", state.port); assertTrue(state.code.isEmpty())
    }
}
