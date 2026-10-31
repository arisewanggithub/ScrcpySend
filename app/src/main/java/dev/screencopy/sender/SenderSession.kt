package dev.screencopy.sender

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SenderSession internal constructor(
    private val context: Context, settings: Settings, private val receiver: ReceiverTarget,
    private val status: (String) -> Unit, private val finished: () -> Unit, private val diagnostic: (String) -> Unit
) : Runnable, ScrcpyAdapter.Sink {
    constructor(context: Context, settings: Settings, receiver: ReceiverTarget,
                status: (String) -> Unit, finished: () -> Unit) :
        this(context, settings, receiver, status, finished, {
            android.util.Log.i("ScreenCopySender", it)
            try { SenderFailureStore.recordDiagnostic(context, it) }
            catch (e: Exception) { android.util.Log.w("ScreenCopySender", "diagnostic_store=${e.javaClass.simpleName}") }
        })
    private var settings = Settings.from(settings.request(), validate = false)
    private val session = (SecureRandom().nextLong() and Long.MAX_VALUE) or 1
    private val mux = MediaBudget(settings.audio)
    private val outcome = SessionOutcome()
    private val trace = SessionStopTrace(diagnostic)
    private val terminalQueued = AtomicBoolean()
    private val threads = mutableListOf<Thread>()
    private val terminalSent = CountDownLatch(1)
    private val receiverReleased = CountDownLatch(1)
    private val sizeSwitch = SizeSwitchAuthorization()
    @Volatile private var linkClosed = false
    @Volatile private var playing = false
    @Volatile private var cleanupComplete = false
    @Volatile private var cleanupSucceeded = true
    internal val cleanSizeSwitch get() = cleanupComplete &&
        sizeSwitch.permitsRestart(cleanupSucceeded && outcome.failure == null)
    private val timer = Executors.newSingleThreadScheduledExecutor()
    @Volatile private var link: ReceiverLink? = null
    @Volatile private var privileged: CaptureBridge? = null
    private var backend: CaptureBackend? = null
    @Volatile private var srt: SrtSender? = null
    @Volatile private var srtReady = false
    private val isSrt get() = settings.mediaTransportMode == "srt"
    private val stopping get() = outcome.stopping
    @Volatile private var requestSent = false
    @Volatile private var acceptedSession = false
    @Volatile private var remoteStop = false
    @Volatile private var closing = false
    @Volatile private var lastIncoming = 0L
    @Volatile private var writeStarted = 0L
    @Volatile private var phaseDeadline = 0L

    private fun phase(value: String, stage: SessionStopTrace.Phase) {
        trace.phase(stage)
        check(); status(value); phaseDeadline = now() + 10_000_000_000L
    }
    private fun check() {
        if (closing) throw SessionCancelledException()
        outcome.checkRunning()
    }
    @Synchronized fun stop() {
        sizeSwitch.cancel()
        requestStop()
    }
    @Synchronized internal fun stopForSizeSwitch(): Boolean {
        if (stopping || closing || !sizeSwitch.request(settings.videoCodec, playing && acceptedSession, outcome.failure != null))
            return false
        requestStop()
        return true
    }
    private fun requestStop() {
        if (!outcome.requestStop()) return
        trace.mark(SessionStopTrace.Stage.REQUESTED)
        status("Stopping")
        queueTerminal()
        if (!acceptedSession || phaseDeadline > 0 && privileged != null) Thread({
            trace.mark(SessionStopTrace.Stage.STARTUP_CANCEL)
            if (!acceptedSession) {
                if (requestSent) try { terminalSent.await(500, TimeUnit.MILLISECONDS) }
                catch (e: InterruptedException) { Thread.currentThread().interrupt(); fail(e, SessionOutcome.Origin.CLEANUP) }
                closeLink()
            }
            abortCapture()
        }, "cancel-privileged-startup").start()
    }
    private fun fail(error: Exception, origin: SessionOutcome.Origin) {
        if (origin == SessionOutcome.Origin.CLEANUP) cleanupSucceeded = false
        val atFailure = trace.currentPhase()
        val classified = if (origin == SessionOutcome.Origin.CAPTURE && error !is SenderProtocolException &&
            SessionOutcome.errorCode(error) == "PROTOCOL") SenderProtocolException(CaptureProtocolFailure.unknown(error), error) else error
        val retained = outcome.record(classified, origin, closing)
        if (retained) sizeSwitch.cancel()
        if (retained) trace.firstFailure(classified, atFailure, origin, session, settings,
            lastReceiveAgeMs = lastIncoming.takeIf { it > 0 }?.let { ((now() - it) / 1_000_000).coerceAtLeast(0) },
            writeElapsedMs = writeStarted.takeIf { it > 0 }?.let { ((now() - it) / 1_000_000).coerceAtLeast(0) })
        trace.cause(origin, classified, retained, atFailure)
        if (retained && classified is SenderCongestionException) trace.congestion(classified.snapshot, atFailure)
        if (retained && classified is CaptureStreamEof) trace.startup(classified, atFailure,
            captureFormat(settings.videoCodec), captureFormat(settings.audioCodec), settings.audio, settings)
        if (retained && classified is VideoGeometryException) trace.geometry(classified, captureFormat(settings.videoCodec), atFailure)
        if (retained && classified is SenderProtocolException) {
            val evidence = classified.evidence
            val codec = when (evidence.track) {
                CaptureTrack.VIDEO -> settings.videoCodec
                CaptureTrack.AUDIO -> settings.audioCodec
                else -> ""
            }
            val format = when (codec) {
                "h264" -> CaptureFormat.H264; "h265" -> CaptureFormat.H265; "av1" -> CaptureFormat.AV1
                "opus" -> CaptureFormat.OPUS; "aac" -> CaptureFormat.AAC; "raw" -> CaptureFormat.PCM
                else -> CaptureFormat.UNKNOWN
            }
            trace.captureProtocol(evidence, format, atFailure, origin)
        }
    }
    internal fun captureFailed(error: Exception) = fail(error, SessionOutcome.Origin.CAPTURE)
    internal fun terminalMessage(): Wire.Message = outcome.terminal(session, remoteStop)
    private fun queueTerminal() {
        if (requestSent && terminalQueued.compareAndSet(false, true)) {
            mux.finish(terminalMessage())
            trace.mark(SessionStopTrace.Stage.TERMINAL_QUEUED)
        }
    }

    override fun run() {
        try {
            trace.sessionStarted(session)
            settings.validate()
            timer.scheduleWithFixedDelay(::watchdog, 500, 500, TimeUnit.MILLISECONDS)
            phase("Checking Shizuku shell capture", SessionStopTrace.Phase.ACCESS)
            val shell = ShizukuSetup.shared.bridge().also { privileged = it }
            phase("Preparing pinned capture backend", SessionStopTrace.Phase.DEPLOYING)
            val capture = CaptureBackend(context, shell, ::captureFailed, trace).also { backend = it }
            capture.deploy()
            trace.phase(SessionStopTrace.Phase.CAPABILITIES)
            settings = capture.validate(settings, capture.capabilities())
            phase("ConnectingReceiver", SessionStopTrace.Phase.CONNECTING)
            val connected = ReceiverLink(receiver).also { link = it }
            check()
            connected.connect()
            lastIncoming = now()
            phase("Negotiating", SessionStopTrace.Phase.NEGOTIATING)
            val request = settings.request().also { it.remove("mediaTransportMode") }
            if (isSrt) {
                val server = SrtSender(connected.localAddress, connected.peerAddress, session, settings, diagnostic).also { srt = it }
                request.put("mediaTransport", SrtTransport.descriptor(connected.localAddress, server.port, session))
            }
            connected.send(Wire.control(Wire.SESSION_REQUEST, session, request))
            worker("receiver-write", ::writeLoop)
            requestSent = true
            if (stopping) queueTerminal()
            val accepted = connected.receive()
            lastIncoming = now()
            if (accepted.session != session) throw IOException("PROTOCOL: negotiation session mismatch")
            if (accepted.type == Wire.REJECT || accepted.type == Wire.ERROR)
                throw IOException("${Json.string(accepted.json(), "code")}: ${Json.string(accepted.json(), "message")}")
            check()
            if (accepted.type != Wire.SESSION_ACCEPT) throw IOException("PROTOCOL: expected SESSION_ACCEPT")
            if (isSrt) SrtTransport.requireAccepted(accepted.json())
            val tracks = accepted.json().getJSONArray("tracks")
            if (tracks.length() != (if (settings.audio) 2 else 1) || tracks.get(0) !is Int || tracks.getInt(0) != 1 ||
                settings.audio && (tracks.get(1) !is Int || tracks.getInt(1) != 2))
                throw IOException("PROTOCOL: receiver did not accept all selected tracks")
            acceptedSession = true
            worker("receiver-read", ::readLoop)
            phase("StartingCapture", SessionStopTrace.Phase.CAPTURE_START)
            capture.start(settings, session, this)
            while (!stopping && outcome.failure == null) {
                if (if (isSrt) srtReady else mux.allReady()) phaseDeadline = 0
                srt?.checkHealth()
                Thread.sleep(100)
            }
        } catch (e: Exception) {
            fail(e, SessionOutcome.Origin.STARTUP)
            if (e is InterruptedException) Thread.currentThread().interrupt()
        } finally {
            closing = true; trace.phase(SessionStopTrace.Phase.CLEANUP)
            phaseDeadline = now() + 2_000_000_000L; status("Stopping")
            trace.mark(SessionStopTrace.Stage.CLOSING)
            queueTerminal()
            // Cancel native media before capture cleanup waits for its encoded readers.
            try { srt?.close() } catch (e: Exception) { fail(e, SessionOutcome.Origin.CLEANUP) }
            try { backend?.close() } catch (e: Exception) { fail(e, SessionOutcome.Origin.CLEANUP) }
            if (requestSent) try { terminalSent.await(500, TimeUnit.MILLISECONDS) }
            catch (e: InterruptedException) { Thread.currentThread().interrupt(); fail(e, SessionOutcome.Origin.CLEANUP) }
            if (sizeSwitch.requested) try {
                if (!receiverReleased.await(2000, TimeUnit.MILLISECONDS))
                    fail(IOException("TIMEOUT: size change receiver cleanup was not acknowledged"), SessionOutcome.Origin.CLEANUP)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                fail(IOException("Size change cleanup interrupted", e), SessionOutcome.Origin.CLEANUP)
            }
            trace.mark(SessionStopTrace.Stage.RECEIVER_DRAIN_DONE)
            closeLink()
            timer.shutdownNow()
            threads.forEach(Thread::interrupt)
            for (thread in threads) if (thread != Thread.currentThread()) {
                try {
                    thread.join(1000)
                    if (thread.isAlive) fail(IOException("Session reader did not stop"), SessionOutcome.Origin.CLEANUP)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    fail(IOException("Session reader cleanup interrupted", e), SessionOutcome.Origin.CLEANUP)
                }
            }
            trace.mark(SessionStopTrace.Stage.READERS_JOINED)
            status(outcome.failure?.let { "Error: ${SessionOutcome.safeMessage(it)}" } ?: "Idle")
            trace.mark(SessionStopTrace.Stage.FINISHED)
            cleanupComplete = true
            finished()
        }
    }

    private fun watchdog() {
        val current = now()
        if (closing) {
            // Bound terminal TCP delivery without interrupting independently bounded codec cleanup.
            if (current > phaseDeadline) {
                if (sizeSwitch.requested && !sizeSwitch.receiverClosed)
                    fail(IOException("TIMEOUT: size change receiver cleanup was not acknowledged"), SessionOutcome.Origin.CLEANUP)
                closeLink()
            }
            return
        }
        if (stopping) return
        if (phaseDeadline > 0 && current > phaseDeadline || lastIncoming > 0 && current - lastIncoming > 10_000_000_000L ||
            writeStarted > 0 && current - writeStarted > 10_000_000_000L) {
            fail(IOException("TIMEOUT: receiver/capture did not make progress within 10 seconds"), SessionOutcome.Origin.WATCHDOG)
            closeLink(); abortCapture(); return
        }
        mux.expiredTrack()?.let { track ->
            fail(IOException("TIMEOUT: receiver did not initialize track $track"), SessionOutcome.Origin.WATCHDOG); closeLink(); abortCapture()
        }
    }
    private fun abortCapture() {
        trace.mark(SessionStopTrace.Stage.STARTUP_BRIDGE_CLOSE_BEGIN)
        try { privileged?.close() } catch (e: IOException) { fail(e, SessionOutcome.Origin.CLEANUP) }
        finally { trace.mark(SessionStopTrace.Stage.STARTUP_BRIDGE_CLOSE_END) }
    }
    private fun closeLink() {
        linkClosed = true
        try { link?.close() } catch (e: IOException) { fail(e, SessionOutcome.Origin.CLEANUP) }
        trace.mark(SessionStopTrace.Stage.LINK_CLOSED)
    }
    private fun worker(name: String, action: () -> Unit) { threads.add(Thread(action, name).also { it.start() }) }
    private fun readLoop() {
        try {
            val connected = checkNotNull(link) { "Receiver connection missing" }
            while (!linkClosed && (!closing || sizeSwitch.requested)) {
                val message = connected.receive()
                lastIncoming = now()
                if (message.session != session) throw IOException("PROTOCOL: wrong session")
                val json = message.json()
                when (message.type) {
                    Wire.STREAM_READY -> {
                        if (isSrt) throw IOException("PROTOCOL: SRT transport does not use STREAM_READY")
                        if (!Json.bool(json, "ok")) throw IOException("DECODER_FAILED: track not ready")
                        mux.ready(message.track, message.generation)
                        if (mux.allReady() && !stopping && !closing && outcome.failure == null) {
                            phaseDeadline = 0
                            playing = true
                            trace.phase(SessionStopTrace.Phase.STREAMING); status("Streaming")
                        }
                    }
                    Wire.PING -> {
                        val nonce = Json.integer(json, "nonce")
                        if (!stopping && !closing) try {
                            mux.offer(Wire.control(Wire.PONG, session, Wire.obj("nonce" to nonce)))
                        } catch (e: SessionCancelledException) {
                            if (!stopping && !closing) throw e
                        }
                    }
                    Wire.PONG -> Json.integer(json, "nonce")
                    Wire.STATUS -> {
                        val state = Json.string(json, "state"); Json.string(json, "message")
                        if (isSrt && state == "srt-playing" &&
                            !stopping && !closing && outcome.failure == null) {
                            phaseDeadline = 0
                            srtReady = true; playing = true
                            trace.phase(SessionStopTrace.Phase.STREAMING); status("Streaming")
                        }
                    }
                    Wire.ERROR -> {
                        val code = Json.string(json, "code"); val text = Json.string(json, "message")
                        if (Json.bool(json, "fatal")) throw IOException("$code: $text")
                        status("Receiver warning: $code: $text")
                    }
                    Wire.SESSION_END -> {
                        Json.string(json, "reason")
                        if (sizeSwitch.requested) sizeSwitch.acknowledge()
                        else { remoteStop = true; stop() }
                    }
                    Wire.STOP -> { Json.string(json, "reason"); remoteStop = true; stop() }
                    else -> throw IOException("PROTOCOL: unexpected receiver message ${message.type}")
                }
            }
        } catch (e: Exception) {
            if (sizeSwitch.requested && !linkClosed) {
                if (e is java.io.EOFException && sizeSwitch.acknowledged) sizeSwitch.peerClosed()
                else fail(IOException("Size change lost receiver before confirmed cleanup", e), SessionOutcome.Origin.RECEIVER_READ)
            } else fail(e, SessionOutcome.Origin.RECEIVER_READ)
        } finally { receiverReleased.countDown() }
    }
    private fun writeLoop() {
        var ping = 0L
        try {
            val connected = checkNotNull(link) { "Receiver connection missing" }
            while (!Thread.currentThread().isInterrupted) {
                var message = mux.poll(100)
                if (message == null && now() - ping > 2_000_000_000L && !closing && !stopping && acceptedSession) {
                    message = Wire.control(Wire.PING, session, Wire.obj("nonce" to (now() and 0x7fffffff).toInt()))
                    ping = now()
                }
                if (message != null) {
                    writeStarted = now(); mux.beginWrite(message)
                    try { connected.send(message) } finally { mux.endWrite(); writeStarted = 0 }
                    if (message.type in setOf(Wire.STOP, Wire.SESSION_END, Wire.ERROR)) {
                        trace.mark(SessionStopTrace.Stage.TERMINAL_SENT); terminalSent.countDown(); return
                    }
                }
                if (closing && terminalSent.count == 0L) return
            }
        } catch (e: Exception) { fail(e, SessionOutcome.Origin.RECEIVER_WRITE); terminalSent.countDown() }
    }
    override fun config(track: Int, generation: Int, config: JSONObject) {
        check()
        if (isSrt) checkNotNull(srt).config(track, generation, config)
        else mux.offer(Wire.config(session, track, generation, config))
        if (!isSrt || !srtReady) status(if (track == 1) "Preparing video" else "Preparing audio")
    }
    override fun packet(packet: Wire.Message) {
        check()
        if (isSrt) checkNotNull(srt).packet(packet)
        else mux.offer(packet)
    }

    companion object {
        private fun now() = System.nanoTime()
    }
}
