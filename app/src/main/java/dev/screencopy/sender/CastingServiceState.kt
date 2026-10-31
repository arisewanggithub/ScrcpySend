package dev.screencopy.sender

/** The actual foreground-service adoption/progress/cleanup authority, shared with host tests. */
internal class CastingServiceState(private val casting: ReceiverCasting) {
    @Volatile private var owner: ReceiverCasting.ServiceLease? = null
    val request get() = owner?.request
    fun adopt(token: Long): ReceiverCasting.ServiceLease? {
        if (owner != null) return null
        return casting.claim(token)?.also { owner = it }
    }
    fun progress(lease: ReceiverCasting.ServiceLease, message: String) {
        if (owner === lease) casting.progress(lease.request.token, message)
    }
    fun finish(lease: ReceiverCasting.ServiceLease, failure: String? = null): Boolean {
        if (owner !== lease) return false
        owner = null
        return casting.completeService(lease, failure)
    }
    fun finishSizeSwitch(lease: ReceiverCasting.ServiceLease, clean: Boolean, failure: String? = null): ReceiverCasting.Request? {
        if (owner !== lease) return null
        owner = null
        return casting.finishSizeSwitch(lease, clean, failure)
    }
    fun owns(token: Long) = owner?.request?.token == token
    fun owns(lease: ReceiverCasting.ServiceLease) = owner === lease
}
