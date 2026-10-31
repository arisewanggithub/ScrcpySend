package dev.screencopy.sender

internal class DiscoveryLifecycleTrace(private val emit: (String) -> Unit) {
    private var previous = ""

    fun state(visible: Boolean, ready: Boolean, setupState: String, cleanupPending: Boolean, listening: Boolean) {
        val state = "event=DISCOVERY_LIFECYCLE visible=$visible ready=$ready setup=$setupState cleanup=$cleanupPending listening=$listening"
        if (state != previous) {
            previous = state
            emit(state)
        }
    }
}
