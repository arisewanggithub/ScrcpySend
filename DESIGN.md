# ScrcpySend architecture

The Android sender uses public MediaProjection/MediaCodec/AudioRecord APIs and
Custom TCP wire protocol version 3. Application ID `dev.screencopy.sender` and
`SCPY` framing remain stable. See [PROTOCOL.md](PROTOCOL.md) for wire schemas.

## Capture boundary

```text
Play / immutable receiver request
  -> optional RECORD_AUDIO runtime permission
  -> Android MediaProjection consent (one token per session)
  -> foreground CastService with mediaProjection [+ microphone] type
  -> public encoder/display inventory and receiver SESSION_ACCEPT
  -> VirtualDisplay -> MediaCodec input Surface -> encoded video packets
  -> optional AudioPlaybackCapture / microphone AudioRecord -> PCM or MediaCodec
  -> bounded Custom TCP queues -> receiver
```

No shell service, ADB connection, Shizuku Binder, native supervisor or scrcpy
server participates. Their dependencies, archives, build tasks and runtime
permissions are removed. ADB remains a developer deployment/test tool only.

Projection consent is transient and never persisted. Activity results are
checked against the current request token. Cancel/deny/stale results cannot
start another request. The service enters foreground before consuming consent.
An Activity recreation preserves only the pending request identity, not a
reusable projection grant. Process loss requires a fresh Play.

`ProjectionOwner` registers the mandatory callback before creating its one
VirtualDisplay. Size switches detach the old Surface, clean up readers and
codecs, negotiate a new wire session, and resize/reattach the same display.
The token is never passed twice to getMediaProjection/createVirtualDisplay.
Final Stop, error, service destruction, or Android revocation ends capture.

## Video/audio

Only public codec metadata is queried before capture. Video encoders must be
hardware-accelerated; geometry, rate, bitrate and saved explicit encoder choices
remain authoritative. The legacy untransformed path has no application pixel-copy
step: the VirtualDisplay writes to the encoder Surface. Negotiated fixed-natural
video uses a GPU transform even without a crop, never CPU pixel readback.
Before sharing consent, validation checks settings, eligible encoders, bitrates
and natural display/crop bounds without assuming a receiver coordinate mode.
Encoder size/rate validation runs after HELLO selects legacy or fixed-natural
geometry and again before capture. Runtime size switches use the active capture's
negotiated mode, not a legacy default.

Nonzero crop/orientation uses a bounded GPU-only SurfaceTexture/EGL path.
Coordinates are mapped from natural-display bounds through current rotation.
Quarter-turn mapping follows Android's physical-to-logical convention: display
rotation 90 maps normalized `(u,v)` to `(v,1-u)`, while 270 maps to `(1-v,u)`.
Clockwise encoded/manual presentation compensation uses the opposite sign.
Asymmetric off-center crop tests verify both the sampled region and corner order.
Projection resize callbacks and public DisplayManager events (including Android
11-13 and rotations that do not change dimensions) are applied by the video worker.
When both HELLO peers negotiate `videoRotation`, video always uses the GPU path
to map the current display back into the fixed natural-coordinate crop/raster.
Display rotation changes only the source texture mapping and per-picture
presentation metadata, never encoder dimensions or codec generation. GPU frame
timestamps associate rotation with encoder output, including repeated frames;
manual orientation overrides are expressed by the metadata. Video pixels remain
on the GPU. Older receivers keep the existing orientation-following path.

On that legacy path, if output dimensions
change, it first waits for the previous video generation's readiness and queued/
in-progress writes to drain, then detaches the old Surface and releases/reconfigures only the video encoder
and optional renderer, then reattaches to the same VirtualDisplay. The next real
codec initialization announces a new generation, even for identical CSD after
restart. This keeps restart timestamp gaps from spanning old queued media without
changing the 500 ms whole-track PTS-span limit or discarding packets. The restart
barrier is interruptible, stops on session termination and fails explicitly after
the existing ten-second readiness budget. Audio continues independently. Resize
notifications received during the wait are coalesced, and the wait does not hold
the capture lifecycle lock. Audio timestamps/ownership remain active. Locked orientation
can keep output dimensions fixed while the renderer updates source coordinates.
Public real-display dimensions avoid stale Activity WindowMetrics rotation.
Renderer callbacks and cleanup use an owned GL thread; failures are explicit.

AudioRecord captures stereo PCM at 48 kHz. Playback uses eligible app audio
under Android's capture policies; microphone mode uses the microphone source
and corresponding foreground-service type. Legacy output is explicitly treated
as restricted playback, not privileged mixed output. Excluded playback may
produce silence; no bypass or silent switch to microphone exists. A projection-
owned media-stream mute suppresses local playback only for playback/output
capture. It survives size-switch restarts and restores on final cleanup or
revocation. A persisted ownership marker supports recovery on the next launch
after process death without changing the saved volume index.

RAW audio is sent unchanged. Encoded audio uses the requested compatible encoder.
Small nonblocking PCM reads accumulate into fixed 20 ms packets; no resampling
or sample dropping is introduced. Wire writes combine the 40-byte header with
up to 16 KiB of payload, and send any large remainder directly. This bounds
copying while avoiding dozens of tiny writes on TCP_NODELAY connections.
Android Opus initialization is converted to the receiver's canonical OpusHead;
AAC uses actual AAC-LC configuration. Video CSD, key flags, FIFO payloads and
encoder PTS remain authentic. Audio input uses the monotonic capture timestamp
when available, otherwise a recording-start monotonic anchor plus sample count.
The receiver continues to own playback timing.

## Ownership and transport

The immutable request and service lease prevent late Activity callbacks or old
workers from retiring a new request. Only one receiver is active. Explicit
SESSION_ACCEPT precedes frame production; STREAM_CONFIG/STREAM_READY precede
media on each track. Queue bounds and congestion errors are unchanged.
Sender heartbeat probes are scheduled every two seconds independently of media
queue occupancy, so continuous PCM/video cannot starve bidirectional liveness.

Stop detaches projection output, closes the optional GL path, stops AudioRecord,
joins workers, and releases encoders/Surfaces before the next capture can start.
Cleanup errors are not treated as successful size-switch authorization.
The projection callback reports revocation through the same first-failure
path. Notification Stop remains available independently of Activity lifetime.

Discovery/control/media remain unauthenticated and unencrypted. Pin to the
actual UDP-source IP and port on a trusted LAN. Names are not identities.
No saved receiver trust, remote input, hidden fallback or automatic reconnect
is introduced.

## Verification

JVM tests cover framing, codec initialization, raw/encoded packets, coordinate
transforms, ownership, cancellation and bounded queues. UI tests use synthetic
metadata without starting capture. An explicit manual-consent H.264 device test
exercises the real service, projection, receiver-side decode, playback PCM and
Stop. Do not select real H.265 screen capture/Stop in automated acceptance.

Release CI uses an isolated self-instrumenting harness and disposable API35
emulator to check real optimized resources, manifest, public API availability
and absence of old server/native assets. It does not claim capture consent,
codec performance or hardware safety. See [UPDATES.md](UPDATES.md).
