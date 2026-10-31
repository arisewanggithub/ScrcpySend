package dev.screencopy.sender

/** Activity observers always pull current service-owned state, never replay a queued old frame. */
internal class ReceiverListBinding(
    private val casting: ReceiverCasting, private val ready: () -> Boolean,
    private val dispatch: (() -> Unit) -> Unit,
    private val render: (ReceiverCasting.Snapshot, List<ReceiverCasting.Row>) -> Unit
) {
    private val lock = Any()
    private var attached = false
    private var generation = 0L
    private val observer: () -> Unit = observer@{
        val ticket = synchronized(lock) { if (!attached) return@observer; generation }
        dispatch {
            if (synchronized(lock) { attached && generation == ticket })
                render(casting.snapshot(), casting.rows(ready()))
        }
    }
    fun attach() {
        synchronized(lock) { if (attached) return; attached = true; generation++ }
        casting.observe(observer)
    }
    fun detach() {
        synchronized(lock) { attached = false; generation++ }
        casting.removeObserver(observer)
    }
}
