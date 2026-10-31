package dev.screencopy.sender

internal object WirelessPairing {
    const val GUIDE = "If Developer options are missing, open system Settings manually > About phone (or Software information), " +
        "tap Build number seven times and follow the device's unlock prompt. OEM names differ; managed devices may block debugging.\n\n" +
        "Enable Developer options > Wireless debugging on Wi-Fi. Pairing and connection ports are DIFFERENT.\n\n" +
        "Keep Settings and ScreenCopy visible together. " +
        "1. Use Overview/Recent apps and your device's Split screen option to put ScreenCopy in one pane and Settings in the other. " +
        "An OEM floating window is an alternative only if BOTH apps and the pairing dialog stay visible.\n\n" +
        "2. In Settings open Developer options > Wireless debugging > Pair device with pairing code AFTER arranging the windows.\n\n" +
        "3. Leave that dialog OPEN. Enter its current pairing port and six-digit code in ScreenCopy, tap Pair, then confirm the visible dialog's values. " +
        "Do not switch full-screen, press Back, or close the dialog to return here: that may invalidate the port/code immediately. " +
        "If it closes or changes, open a fresh dialog and re-check the retained port draft against its current port. Enter the fresh code, " +
        "then confirm that both values match the currently open dialog.\n\n" +
        "4. After successful pairing, ScreenCopy discovers the separate connection port automatically. " +
        "If discovery is unavailable, enter the connection port from the main Wireless debugging page manually. " +
        "Find pairing port can discover the dialog's port, but its code is always manual.\n\n" +
        "ScreenCopy cannot force split-screen or floating windows on every Android/OEM. If either app cannot stay visible, " +
        "this manual pairing flow is not supported in that window layout."

    private val savedFields = setOf("pairPort", "adbPort", "videoBitrate", "maxSize", "maxFps", "displayId",
        "cropWidth", "cropHeight", "cropX", "cropY", "videoOptions", "audioBitrate", "audioOptions")
    fun nonSensitiveDraft(fields: Map<String, String>): Map<String, String> = fields.filterKeys { it in savedFields }
}

internal class PairingInput(val port: Int, val code: String) {
    override fun toString() = "PairingInput(redacted)"
}

/** Code and confirmations are memory-only; the non-secret port draft may be saved separately. */
internal class PairingUiState {
    var port = ""
        set(value) { if (field != value) currentDialogConfirmed = false; field = value }
    var code = ""
        set(value) { if (field != value) currentDialogConfirmed = false; field = value }
    var floatingConfirmed = false
    var currentDialogConfirmed = false
    var inFlight = false
        private set
    var successRevision = 0
        private set
    var notice = "Arrange split-screen before opening the Settings pairing dialog. Keep it open until pairing finishes."
        private set
    private var observerOwner: Any? = null
    private var observer: (() -> Unit)? = null

    fun observe(owner: Any, update: () -> Unit) { observerOwner = owner; observer = update; update() }
    fun detach(owner: Any) { if (observerOwner === owner) { observerOwner = null; observer = null } }
    fun input(multiWindow: Boolean): PairingInput {
        check(!inFlight) { "A pairing attempt is already finishing; wait for its result." }
        check(multiWindow || floatingConfirmed) {
            "Keep the Settings pairing dialog visible beside ScreenCopy using split-screen, or confirm a supported floating window. " +
                "Full-screen switching may invalidate its port/code."
        }
        val number = port.toIntOrNull() ?: throw IllegalArgumentException("Enter the current pairing dialog port")
        Settings.range(number, 1, 65535, "Pairing port")
        Settings.require(code.matches(Regex("[0-9]{6}")), "Enter the current six-digit pairing code")
        check(currentDialogConfirmed) {
            "The retained pairing port is an unconfirmed draft. Re-check the port and fresh code against the currently OPEN Settings dialog, then confirm they match."
        }
        return PairingInput(number, code)
    }
    fun started() {
        inFlight = true
        notice = "Pairing... keep the Settings dialog open and both windows visible."
        observer?.invoke()
    }
    fun succeeded() {
        inFlight = false; code = ""; currentDialogConfirmed = false
        successRevision++
        notice = "Local ADB paired. Discovering the DIFFERENT connection port automatically; manual connection remains available if discovery fails."
        observer?.invoke()
    }
    fun failed(errorType: String) {
        inFlight = false; code = ""; currentDialogConfirmed = false
        notice = "Local ADB pairing failed ($errorType). The dialog may have closed, expired, or its port may be unreachable. " +
            "The port text is retained only as an unconfirmed draft. Check Wireless debugging/Wi-Fi, arrange both windows, reopen Pair device with pairing code, " +
            "compare/update the port, enter the fresh code and confirm both match. Do not retry stale credentials."
        observer?.invoke()
    }
    fun cancelledBeforeSubmission() {
        inFlight = false; code = ""; currentDialogConfirmed = false
        notice = "Pairing was not submitted because the window closed/recreated. The port draft is retained but unconfirmed. " +
            "Wait for setup cleanup, reopen the dialog, re-check the port and enter its fresh code."
        observer?.invoke()
    }
    fun hidden() {
        code = ""; floatingConfirmed = false; currentDialogConfirmed = false
        notice = "ScreenCopy was hidden or left its pairing layout. The Settings dialog may have closed. " +
            "Your pairing-port draft is retained but unconfirmed. Arrange both windows, reopen the pairing dialog, " +
            "compare/update the port and enter its fresh code before confirming both values."
        observer?.invoke()
    }
    fun openingSettings() {
        code = ""; floatingConfirmed = false; currentDialogConfirmed = false
        notice = "Settings opens in its own task. Arrange both windows BEFORE opening Pair device with pairing code. " +
            "Opening Settings full-screen does not by itself keep a pairing dialog alive. Your port draft is retained but unconfirmed; re-check it against the open dialog."
        observer?.invoke()
    }
    fun restorePortDraft(text: String) {
        port = text; code = ""; floatingConfirmed = false; currentDialogConfirmed = false
        notice = "Pairing-port draft restored, not validated. Reopen the Settings pairing dialog, compare/update the port, enter its fresh code and confirm both values."
    }
    fun requireDialogRecheck() {
        currentDialogConfirmed = false
        notice = "Window recreated: the port draft and in-memory code are retained, but the Settings dialog may have changed. " +
            "Verify it is still open, compare the current values, and confirm again before submitting."
        observer?.invoke()
    }

    fun acceptDiscoveredPort(expectedDraft: String, discovered: Int): Boolean {
        if (inFlight || port != expectedDraft) return false
        Settings.range(discovered, 1, 65535, "Discovered pairing port")
        port = discovered.toString()
        currentDialogConfirmed = false
        notice = "Pairing port discovered. Verify it against the open dialog and enter its code manually."
        observer?.invoke()
        return true
    }
}
