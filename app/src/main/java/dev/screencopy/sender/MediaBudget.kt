package dev.screencopy.sender

import java.io.IOException
import java.util.ArrayDeque

/** Capture readers wait only for bounded local admission, never while holding a network writer lock. */
class MediaBudget internal constructor(private val withAudio: Boolean, private val clock: () -> Long,
                                       private val admissionNanos: Long) {
    constructor(withAudio: Boolean, clock: () -> Long = System::nanoTime) : this(withAudio, clock, 500_000_000L)
    init { require(admissionNanos in 1..500_000_000L) }
    private data class Entry(val message: Wire.Message, val admitted: Long)
    private val controls = ArrayDeque<Entry>()
    private val video = ArrayDeque<Entry>()
    private val audio = ArrayDeque<Entry>()
    private val monitor = Object()
    private var mediaBytes = 0
    private var controlBytes = 0
    private var configCount = 0
    private var turn = 1
    private val generation = IntArray(3)
    private val announced = IntArray(3)
    private val ready = IntArray(3)
    private val readinessDeadline = LongArray(3)
    private val lastPts = LongArray(3) { -1 }
    private val opusSourcePts = BooleanArray(3)
    private var started = false
    private var ending = false
    private var dequeued: Entry? = null
    private var writerQueue: SenderQueue? = null
    private var writerBytes = 0
    private var writeBegin = 0L
    private var writeAdmitted = 0L
    private var lastWriteMs = 0L

    fun offer(message: Wire.Message) = synchronized(monitor) {
        val begin = System.nanoTime()
        val track = message.track
        val opusConfig = message.type == Wire.STREAM_CONFIG && track == 2 &&
            message.json().opt("codec") == "opus"
        while (true) {
            if (ending) throw SessionCancelledException()
            if (message.type in setOf(Wire.STREAM_CONFIG, Wire.VIDEO_FRAME, Wire.AUDIO_PACKET) && track !in 1..2)
                throw protocol(message, CaptureProtocolReason.INVALID_TRACK)
            if (message.type == Wire.STREAM_CONFIG && message.generation != generation[track] + 1)
                throw protocol(message, CaptureProtocolReason.CONFIG_SEQUENCE)
            if (message.type in setOf(Wire.VIDEO_FRAME, Wire.AUDIO_PACKET)) {
                if (message.generation != generation[track]) throw protocol(message, CaptureProtocolReason.MEDIA_GENERATION)
                val opus = message.type == Wire.AUDIO_PACKET && track == 2 && opusSourcePts[track]
                if (message.pts < 0 || !opus && message.pts < lastPts[track])
                    throw protocol(message, CaptureProtocolReason.PTS_DECREASE)
            }
            val limit = violation(message) ?: break
            val remaining = admissionNanos - (System.nanoTime() - begin)
            if (remaining <= 0 || message.payload.size > 8 * 1024 * 1024 ||
                message.type !in setOf(Wire.VIDEO_FRAME, Wire.AUDIO_PACKET) && message.payload.size > 256 * 1024)
                throw SenderCongestionException(snapshot(message, limit, (System.nanoTime() - begin) / 1_000_000))
            monitor.wait(remaining / 1_000_000, (remaining % 1_000_000).toInt())
        }
        val entry = Entry(message, clock())
        if (message.type == Wire.STREAM_CONFIG) {
            generation[track] = message.generation; lastPts[track] = -1
            opusSourcePts[track] = opusConfig
            val queue = if (track == 1) video else audio
            queue.add(entry); configCount++; controlBytes += message.payload.size
        } else if (message.type != Wire.VIDEO_FRAME && message.type != Wire.AUDIO_PACKET) {
            controls.add(entry); controlBytes += message.payload.size
        } else {
            lastPts[track] = message.pts
            val queue = if (track == 1) video else audio
            queue.add(entry); mediaBytes += message.payload.size
        }
        monitor.notifyAll()
    }
    private fun protocol(message: Wire.Message, reason: CaptureProtocolReason): SenderProtocolException {
        val track = message.track
        val valid = track in 1..2
        val stage = when (message.type) {
            Wire.STREAM_CONFIG -> CaptureProtocolStage.MUX_CONFIG
            Wire.STREAM_READY -> CaptureProtocolStage.MUX_READY
            else -> CaptureProtocolStage.MUX_MEDIA
        }
        val current = if (valid) generation[track] else 0
        val delta = if (valid && message.type in setOf(Wire.VIDEO_FRAME, Wire.AUDIO_PACKET) && lastPts[track] >= 0)
            message.pts - lastPts[track] else 0
        val sourceTrack = when (track) { 1 -> CaptureTrack.VIDEO; 2 -> CaptureTrack.AUDIO; else -> CaptureTrack.UNKNOWN }
        return SenderProtocolException(CaptureProtocolFailure(reason, stage, sourceTrack, message.generation,
            if (message.type == Wire.STREAM_CONFIG) current + 1 else if (message.type == Wire.STREAM_READY && valid) announced[track] else current,
            current, if (valid) announced[track] else 0, if (valid) ready[track] else 0, delta))
    }
    private fun violation(message: Wire.Message): SenderLimit? {
        val media = message.type in setOf(Wire.VIDEO_FRAME, Wire.AUDIO_PACKET)
        val queue = if (message.track == 1) video else audio
        if (!media) {
            if (controls.size + configCount >= 64) return SenderLimit.CONTROL_COUNT
            if (controlBytes.toLong() + message.payload.size > 256 * 1024) return SenderLimit.CONTROL_BYTES
        }
        if (media || message.type == Wire.STREAM_CONFIG) {
            if (queue.size >= 1024) return SenderLimit.TRACK_COUNT
        }
        if (media) {
            if (mediaBytes.toLong() + message.payload.size > 8 * 1024 * 1024) return SenderLimit.MEDIA_BYTES
            if (sourceSpan(queue, message.pts) > 500_000) return SenderLimit.PTS_SPAN
        }
        return null
    }
    private fun snapshot(message: Wire.Message, limit: SenderLimit, waited: Long): SenderQueueFailure {
        val control = limit in setOf(SenderLimit.CONTROL_COUNT, SenderLimit.CONTROL_BYTES)
        val media = message.type in setOf(Wire.VIDEO_FRAME, Wire.AUDIO_PACKET)
        val queue = when (message.track) { 1 -> video; 2 -> audio; else -> controls }
        val frames = queue.filter { it.message.type in setOf(Wire.VIDEO_FRAME, Wire.AUDIO_PACKET) }
        return SenderQueueFailure(kind(message), limit, if (control) controls.size + configCount else queue.size,
            if (control) 64 else 1024, if (control) controlBytes else mediaBytes,
            if (control) 256 * 1024 else 8 * 1024 * 1024, message.payload.size, queue.size,
            frames.sumOf { it.message.payload.size }, sourceSpan(queue),
            if (media) sourceSpan(queue, message.pts) else 0,
            if (media && message.track in 1..2 && lastPts[message.track] >= 0) message.pts - lastPts[message.track] else 0,
            queue.peek()?.let { ((clock() - it.admitted) / 1_000_000).coerceAtLeast(0) } ?: 0,
            waited, started && (message.track == 0 || ready[message.track] == message.generation),
            writerQueue, writerBytes, if (writerQueue == null) 0 else ((clock() - writeBegin) / 1_000_000).coerceAtLeast(0),
            if (writerQueue == null) 0 else ((clock() - writeAdmitted) / 1_000_000).coerceAtLeast(0),
            lastWriteMs)
    }
    private fun kind(message: Wire.Message) = when (message.track) {
        1 -> SenderQueue.VIDEO; 2 -> SenderQueue.AUDIO; else -> SenderQueue.CONTROL
    }
    private fun sourceSpan(queue: ArrayDeque<Entry>, incoming: Long? = null): Long {
        var minimum = incoming ?: Long.MAX_VALUE
        var maximum = incoming ?: Long.MIN_VALUE
        for (entry in queue) {
            if (entry.message.type != Wire.VIDEO_FRAME && entry.message.type != Wire.AUDIO_PACKET) continue
            minimum = minOf(minimum, entry.message.pts)
            maximum = maxOf(maximum, entry.message.pts)
        }
        return if (maximum == Long.MIN_VALUE) 0 else maximum - minimum
    }
    fun beginWrite(message: Wire.Message) = synchronized(monitor) {
        writerQueue = kind(message); writerBytes = message.payload.size; writeBegin = clock()
        writeAdmitted = dequeued?.takeIf { it.message === message }?.admitted ?: writeBegin
    }
    fun endWrite() = synchronized(monitor) {
        lastWriteMs = ((clock() - writeBegin) / 1_000_000).coerceAtLeast(0)
        writerQueue = null; writerBytes = 0; dequeued = null
    }

    fun ready(track: Int, gen: Int) = synchronized(monitor) {
        if (track !in 1..2 || track == 2 && !withAudio || gen != announced[track] || ready[track] == gen)
            throw protocol(Wire.Message(Wire.STREAM_READY, 0, 0, track, gen, -1, byteArrayOf()), CaptureProtocolReason.READY_MISMATCH)
        ready[track] = gen
        readinessDeadline[track] = 0
        if (ready[1] > 0 && (!withAudio || ready[2] > 0)) started = true
        monitor.notifyAll()
    }
    fun poll(waitMs: Long): Wire.Message? = synchronized(monitor) {
        take() ?: run { monitor.wait(waitMs); take() }
    }
    fun finish(message: Wire.Message) = synchronized(monitor) {
        require(message.type in setOf(Wire.STOP, Wire.SESSION_END, Wire.ERROR))
        if (ending) return
        // Explicit session termination abandons unsent media, never normal congestion recovery.
        ending = true
        controls.clear(); video.clear(); audio.clear()
        mediaBytes = 0; controlBytes = message.payload.size; configCount = 0
        controls.add(Entry(message, clock()))
        monitor.notifyAll()
    }
    private fun take(): Wire.Message? {
        if (controls.isNotEmpty()) {
            val entry = controls.remove()
            controlBytes -= entry.message.payload.size; dequeued = entry; monitor.notifyAll()
            return entry.message
        }
        repeat(2) {
            val track = turn
            turn = if (turn == 1) 2 else 1
            val queue = if (track == 1) video else audio
            val entry = queue.peek()
            val message = entry?.message
            if (message != null) {
                if (message.type == Wire.STREAM_CONFIG) {
                    if (announced[track] == ready[track]) {
                        queue.remove(); announced[track] = message.generation
                        readinessDeadline[track] = clock() + 10_000_000_000L
                        configCount--; controlBytes -= message.payload.size
                        dequeued = entry; monitor.notifyAll()
                        return message
                    }
                } else if (started && ready[track] == message.generation) {
                    queue.remove(); mediaBytes -= message.payload.size
                    dequeued = entry; monitor.notifyAll()
                    return message
                }
            }
        }
        return null
    }
    fun queuedBytes(): Int = synchronized(monitor) { mediaBytes }
    internal fun queuedSpanUs(track: Int): Long = synchronized(monitor) {
        sourceSpan(if (track == 1) video else audio)
    }
    fun allReady(): Boolean = synchronized(monitor) {
        ready[1] == generation[1] && generation[1] > 0 &&
            (!withAudio || ready[2] == generation[2] && generation[2] > 0)
    }
    fun expiredTrack(): Int? = synchronized(monitor) {
        (1..2).firstOrNull { readinessDeadline[it] != 0L && clock() >= readinessDeadline[it] }
    }
}
