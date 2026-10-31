package dev.screencopy.sender

/** Used under the UserService ownership monitor; old bridge RPCs cannot affect a new capture. */
internal class CaptureScope {
    var id: String? = null
        private set
    private var cancelled = false
    private var operation = java.util.concurrent.CountDownLatch(0)
    fun claim(id: String) {
        check(this.id == null) { "Previous capture ownership has not finished cleanup" }
        this.id = id; cancelled = false
    }
    fun requireActive(id: String) {
        check(this.id == id && !cancelled) { "CAPTURE_CANCELLED: Capture session changed or was cancelled" }
    }
    fun requireCleanup(id: String) {
        check(this.id == null || this.id == id) { "Stale cleanup cannot affect another capture" }
    }
    fun cancel(id: String): Boolean {
        if (this.id != id) return false
        cancelled = true
        return true
    }
    fun beginOperation(id: String): java.util.concurrent.CountDownLatch {
        requireActive(id)
        check(operation.count == 0L) { "Capture deployment is already in progress" }
        return java.util.concurrent.CountDownLatch(1).also { operation = it }
    }
    fun pendingOperation(id: String): java.util.concurrent.CountDownLatch {
        requireCleanup(id)
        return operation
    }
    fun finish(id: String) {
        requireCleanup(id)
        check(operation.count == 0L) { "Capture deployment must finish before cleanup" }
        this.id = null; cancelled = false
    }
}
