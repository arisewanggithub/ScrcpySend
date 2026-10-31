package dev.screencopy.sender

/** Automatic checks are edge-triggered, never launched by view rendering. */
internal class ShizukuAutoConnect(paused: Boolean = false) {
    enum class Action { NONE, CHECK, REUSE }
    var paused = paused
        private set
    private var foreground = false
    private var attempted = false
    private var binder: Any? = null
    private var unavailable = false
    private var intentRevision = 0L
    var pendingPermission: Long? = null
        private set

    fun enter() {
        if (!foreground && !paused) attempted = false
        foreground = true
    }
    fun leave(recreating: Boolean) { if (!recreating) foreground = false }
    fun pause() { paused = true; intentRevision++; pendingPermission = null }
    fun userRetry() { paused = false; attempted = false; intentRevision++; pendingPermission = null }
    fun permissionRequested(): Long = (++intentRevision).also { pendingPermission = it }
    fun permissionGranted(ticket: Long): Boolean {
        if (pendingPermission != ticket || intentRevision != ticket) return false
        userRetry()
        return true
    }
    fun permissionDenied(ticket: Long): Boolean {
        if (pendingPermission != ticket) return false
        pendingPermission = null; intentRevision++
        return true
    }

    fun decide(availability: ShizukuState, proofReady: Boolean, checking: Boolean,
               cleanupPending: Boolean, casting: Boolean, binderIdentity: Any?): Action {
        if (!foreground || paused || casting) return Action.NONE
        if (proofReady && availability == ShizukuState.CONNECTING) {
            binder = binderIdentity; attempted = true
            return Action.REUSE
        }
        if (cleanupPending) return Action.NONE
        if (checking) { attempted = true; return Action.NONE }
        if (availability != ShizukuState.CONNECTING) { unavailable = true; return Action.NONE }
        if (unavailable) { unavailable = false; attempted = false }
        if (binderIdentity != binder) { binder = binderIdentity; attempted = false }
        if (attempted) return Action.NONE
        attempted = true
        return Action.CHECK
    }
}
