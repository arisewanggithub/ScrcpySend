# ScreenCopy Sender — Shizuku capture, v3 family LAN

Primary Step1 is Shizuku, not embedded ADB pairing/ports. Receiver and frozen
[PROTOCOL.md](PROTOCOL.md) remain unchanged: plain TCP, no peer authentication
or encryption, name/actual-source-IP endpoint selection only. No human receiver
code, compare checkbox/modal or stale-code errors. Trusted private family LAN
required; names are labels, not identities. Internal positive cast session IDs,
tracks/generations/readiness/timestamps/budgets are unchanged.

## Code-free v3 endpoint protocol

The sender publishes the exact incompatible v3 contract/fixtures before coding
divergently. Discovery every2s is protocol/version3/name/TCP-port/available-or-busy;
source IP is authoritative, dedupe IP+port, expire6s and update name/state.
Direct Play on discovered rows, no manual IP/port UI, code fields or confirmation.
Transport port/endpoints remain internal. Same-endpoint refresh updates name/
availability; matching names never redirect. Idle results expire after6s;
network/interface changes reset them. An active endpoint remains pinned/actionable
until its own cleanup completes, including expired/own-BUSY advertisements.

40-byte header version3 rejects v1/v2 and reserved AUTH before allocation. HELLO
only role/versions[3]/name; unknown obsolete human code/sessionCode fields ignored.
Preserve BUSY at HELLO/session0 or requested cast session, no capture before
acceptance. Internal session IDs are never removed or confused with old UI codes.
Media formats/OpusHead/queues/cleanup remain the published contract.

## Privileged backend

### Opt-in SRT fragmented MP4 trial

`-PsrtTrial=true` links libsrt 1.5.7 transport only. Custom TCP remains the
default. Codecs are H.264, H.265 or AV1 plus Opus or AAC-LC,
or audio disabled. Enabled PCM selections fail explicitly, never fall back or alter
the user's capture configuration. Play and explicit SRT SESSION_ACCEPT precede
capture. The advertised endpoint binds the actual control-local numeric IP and
an ephemeral port; the accepted native peer is the actual control peer.
The frozen descriptor uses `container: fmp4`, 100 ms latency and a session-bound
stream ID. No STREAM_CONFIG/STREAM_READY or automatic retry is used.

SrtEncodedSink forwards raw Annex B parameter sets and access units unchanged.
One Java/Kotlin mux actor accepts the SRT caller, registers all selected tracks,
and owns the official Media3 1.11.1 FragmentedMp4Muxer. Its source JAR is pinned
and regenerated with context-checked narrow patches: standard AVC `avc3`, explicit fragment flush,
standard version-1 tfdt with matching moof offsets, nonzero single-video duration,
packet-derived Opus durations, complete HEVC parameter arrays/actual VPS temporal metadata, and standard big-endian dOps fields. It is not a
new MP4 writer/demuxer. Upstream headers remain intact. The receiver uses Media3
for demux, codecs and A/V clocks; the MP4 extractor's dOps-derived Opus Format
requires canonical OpusHead normalization before decoding.
On H.264 encoder restart/rotation, actual SPS/PPS precede the next real IDR
in-band, preserving all original access-unit bytes and flags. The init retains
the first configuration even if audio startup delays header creation. Media3's
SPS parser announces new CSD/profile/dimensions to its default receiver renderer;
codec adaptation/reinitialization leaves audio and session ownership intact.
Different video codec or changed Opus initialization still fails explicitly.

HEVC uses the official hvcC serializer and standard hev1 entry with one actual
ordered VPS/SPS/PPS buffer and official SPS metadata. Video hardware-only
selection, exact geometry/bitrate and receiver video/hevc decoder checks apply
before acceptance/capture. hev1 samples retain raw NAL bytes/IRAP flags/PTS;
configuration/dimension changes require complete actual VPS/SPS/PPS before an
authentic IRAP, never fake sync flags or repeated moov. The bounded receiver
bridge uses official HEVC SPS/profile metadata to announce actual CSD/dimensions
before the unchanged picture. Default Media3 decoder adaptation/reinitialization
preserves audio, timing, FIFO and session ownership. AV1/audio configuration
changes remain unsupported. Paired APK update, no wire-version bump.
User-reported initial portrait/landscape HEVC playback works; synthetic in-band
rotation validation is pending. Real OnePlus capture/Stop testing remains banned;
notification size-recast stays disabled and is not used for in-band rotation.

AAC uses the actual scrcpy stereo 48 kHz AAC-LC AudioSpecificConfig and raw
access units; ASC frameLengthFlag selects 1024/960 samples for exact MP4 trun
durations and a rational FIFO timeline anchored to the first source PTS.
ADTS, HE-AAC/extensions and changed initialization are rejected.
AV1 uses scrcpy ID 0x00617631 and actual sequence/config OBUs. Media3's pinned
ObuParser validates sequence features; the official muxer derives `av1C` from
the first access unit, prepending the real configuration OBUs only when absent.
No fabricated CSD/profile/dimensions/key flags are used. AV1 configuration changes
are unsupported, not AVC-style rotation. Receiver passes AV1 OBUs through to the
official renderer and validates track MIME against the accepted request.
AAC/AV1 hardware playback remains unvalidated until the coordinator's explicit
synthetic device probes pass; absence of AV1 hardware is NOT_VALIDATED, not PASS.

All selected configs and first samples are retained before header creation.
The common origin is the minimum first video/audio timestamp, preserving initial
offsets regardless of track arrival order. Video PTS must strictly increase and
the first frame must be key. Opus payloads and FIFO are never dropped/sorted;
timestamps follow parsed packet durations anchored once to the first source PTS.
Original regressing source PTS remain diagnostic observations, not a playback
clock. There is no clamp, slew, decode, resample or re-encode.

The actor's timed 100 ms flush is independent of new video/keyframes, so one
static I-frame and continuing audio emits ftyp/moov/moof/mdat without waiting
for the next video or close. Tracks need no global timestamp sort. Buffered
channel output batches box writes, then flushes at fragment boundaries; native
transport hides 1316-byte message boundaries and handles write remainders.
Transport packet dropping is disabled to protect container bytes. Admission
counts both actor-queued and mux-pending samples against 8 MiB / 240 video frames
and 1 MiB / 100 audio packets. Overflow, invalid frames and actual codec changes
fail explicitly; same-config repeats are harmless.

Cancellation closes native transport first, wakes the actor, and finalizes the
mux with typed cancellation before a bounded join. SenderSession then joins
capture readers. The existing first-terminal outcome wins over cleanup races.
Host tests roundtrip actual fragments through the official MP4 extractor to
verify exact payloads, track offsets after idle, video-only operation and dOps.
Synthetic native loopback tests never start capture or request permissions.

### Supported saved transports

Only `custom` and `srt` are valid. Removed transport selections remain invalid
until the user chooses a supported transport and explicitly applies it. The
editor reads the invalid draft without resetting the other capture fields;
ordinary reads and capture startup validate and reject it. No automatic
preference rewrite, downgrade or capture retry is performed.

### Shizuku UserService

Pin official Shizuku API/provider13.1.5 (MIT), protected manifest provider.
Require installed/running Binder, explicit runtime permission and supported
Shizuku13+. Bind a **non-daemon UserService** implementing generated AIDL Stub,
with reserved destroy transaction16777115, unique ownership tag and app Binder
death linkage. Verify real `Os.getuid()==2000` before any work. Root0 accepted
only with explicit user consent, never required; ordinary/unknown UIDs fail.
The service uses no ordinary-app Context-dependent APIs or deprecated command API.

App passes official server3.3.4 and a first-party native supervisor through read-only FDs; UserService bounds size,
checks SHA256, validates random session ID and writes shell-readable
`/data/local/tmp/screencopy_<id>`-style owned artifact (actual path
`/data/local/tmp/screencopy-<id>.jar`). Capability listing and later matching
`app_process` run under UserService shell/root identity, not app UID. The supervisor
is compiled with pinned NDK27.2.12479018 for four ABIs, selected using the privileged
process's actual ABI. Check transferred executable integrity and permissions.
Capability readiness also executes a harmless isolated exiting-child probe; a
non-executable/unsupported supervisor is a real readiness failure, not success.
Commands/settings are structured and validated, not arbitrary user shell text.

CaptureBackend depends on CaptureBridge. Primary Shizuku bridge uses Binder
methods and owned FDs, no ADB calls. UserService opens localabstract scrcpy socket
in video/audio order; it duplicates authorized FDs through Binder to the app.
After the last enabled media socket, open a PRIVATE lifecycle socket on the
same listener before waiting for device metadata. Use pinned `control=true`
solely so Controller's EOF triggers upstream Server.Completion. Never write a
ControlMessage or expose that FD through Binder/LAN; reject track3 and tracks
beyond configured media. Clipboard autosync/power-on remain false, no input
feature or new network messages. This is an intentional internal lifecycle
deviation from the earlier `control=false`, not remote control support.
Ordinary app never opens shell sockets directly. App shutdowns its media FDs
even when service Binder is dead; service shutdowns originals. Log pipe and
media sockets use bounded kernel buffering; one log worker/one queued log task.
Large bytes do not pass through Binder transactions.

## Lifecycle

Permission/UID alone is not readiness: actually bind, verify UID, deploy and list
official backend encoders/displays, then confirm cleanup before Step2.10s bind
deadline and10s deployment/capability check deadline. Readiness is in memory;
Binder death invalidates it and stops only the matching foreground session.
Cancel can unbind/close independently of blocked setup work; no late-ready after
cancellation. Keep process-wide setup/casting reservation and detachable observers.

All session startup/liveness/write budgets remain10s. Enabled audio startup is
mandatory; after valid config packetless silence is allowed. Stop/error/Binder
or owner death closes streams/guardian stdin. Interrupt video/audio/private
lifecycle first, then guardian stdin. Controller EOF completes fatally even
when media has no new output; Server quits its Looper, calls audio.stop and
SurfaceEncoder.stop/CaptureReset.signalEndOfInputStream, shuts connections and
joins/releases. Media broken-pipe alone was not an equivalent stop request.
Non-daemon/destroy lifecycle and
owner death exit only the supported UserService. Kernel FD close and native
parent-death handling avoid waiting on Java close monitors in destroy/death.
Independent native supervisor verifies UID/session/group isolation but NEVER
signals any process group. Its single thread retains its direct forked child,
checks wait ownership without reaping (WNOWAIT), and first allows up to2s for
socket-driven graceful exit without signaling. Pinned scrcpy3.3.4 treats broken
pipe as normal close and stops/releases audio/video codecs through its finally
paths. Reap an exited child without signals; only stalled startup/teardown gets
positive-child TERM, up to200ms observation, then KILL if still live. The
live/unreaped child prevents PID reuse. Supervisor
parent death, stdin EOF or5s silence stop it; child parent-death KILL covers an
abrupt supervisor death. No shell trap/reentry or setsid fallback.

Stop snapshots owned resources and completion under a short ownership lock;
all FD shutdown, pipe/socket close and waits occur after releasing it. Concurrent
Stop observes the same completion; no new work during cleanup. ID-scoped
cancellation/cleanup rejects stale old bridges. A native START handshake permits
fork only after UserService adoption; cancellation before adoption cannot launch
capture. TCP closing budgets do not abort the bounded codec cleanup. App FD ownership
uses the same detach-before-close rule, rejects late adoption and shuts down
socket duplicates to unblock reads. Readiness rendering uses local Binder death
state, never synchronous remote UserService ping. Stop's session cleanup remains
off the UI thread; ReceiverLink close does not acquire the writer monitor.
User/peer Stop commits terminal intent atomically with the first real failure.
Typed cancellation from the capture sink/owned FD/scope or expected EOF/socket/
interrupt after Stop cannot become fatal INTERNAL. Genuine prior faults remain
ERROR; unexpected late/cleanup failures stay visible locally without replacing
an already committed orderly terminal. Never blanket-suppress runtime failures.
Prioritize a single STOP/SESSION_END/ERROR by abandoning unsent queues only on
explicit termination; normal media/control limits and readiness remain unchanged.
Start the terminal-capable writer after SESSION_REQUEST (no pre-accept PING),
cancel pending accept asynchronously within500ms delivery opportunity, and do
not wait a10s read deadline or END acknowledgement on Stop. UI stays Stopping.
Terminate owned non-capture listing/command helpers BEFORE blocking stream close;
use direct argv for listing/probe. Do not Process.destroy real capture guardians.
Ignore duplicate cancellation targeting an in-progress cleanup command. Observe
privileged cleanup before capture-reader joins, preserving native2s/200ms grace.
Fixed-phase elapsed diagnostics retain cause class/origin without peer/media/
session data. README quantifies configured waits and the lack of a safe hard
deadline for platform Binder/kernel stalls; never fake early Idle or cleanup.
Persist exact session ownership for recovery; recovery observes external PIDs
but never signals them. Cleanup failure never becomes success.
Retain the actual owned guardian Process for normal cleanup: observe exit/reap
directly (6s outer confirmation budget, native2s/200ms unchanged), then remove
only six exact validated artifacts. Exit0 or pinned pre-fork cancellation77
can confirm cleanup; abnormal exit/timeout/interruption cannot. No shell/proc
scan when a live handle exists; without one, read bounded PID records and NUL
arguments directly under the privileged UID, never through shell pipelines.
Check a monotonic6s budget between reads; inaccessible/malformed ownership or
live owners fail closed. Reserve cancelled session ownership through recovery;
delete only the six validated artifacts after owners exit, never signal external
PIDs. Historical orphan helpers are not automatically terminated. Preserve failure records,
idempotent completion and distinct controlled cleanup wait/exit codes.

Reported whole-phone freeze on Stop cannot be forensically attributed from the
available logs. Code evidence identified the former group-wide EXIT trap
and ownership-lock-held closes as dangerous paths; both are removed/hardened.
Parent-reported panic property/history is not a diagnosis; diagnostic kernel
logs were unavailable and pstore access denied, with no privileged workaround.
Abrupt signaling can race upstream codec teardown; graceful-first ordering is
source-backed but not a proven fix for the physical panic.
No live reproduction, device kill/reboot/settings/install or capture retry is
part of this fix. Host validation does not prove our physical-device teardown; device
recovery and an explicit safe acceptance plan are required before execution.
Official Windows scrcpy reportedly works on the same OnePlus with H265/Stop:
our integration is the primary suspect, not a presumed universal platform defect.
Its exact version/settings are unknown. README's pinned3.3.4 divergence table
records startup flags, channel order, Windows close versus Unix SHUT_RDWR,
controller EOF/EOS/join, shell/cleanup lifetime and owned-process fallback.
The earlier2s no-signal grace did not add the missing controller wakeup; the
private lifecycle now does, without a supervisor rewrite or longer timeout.

## Automatic readiness and intent

Entry/resume, Binder-received and permission-granted edges automatically run
the bounded actual readiness check when Binder/permission/UID policy permits.
Valid proof is reused; Step1 stays hidden. Permission alone never unlocks Step2.
Normal authorized checking shows only compact progress/Cancel. Install/Open/
Help/Allow/Retry are conditional on missing/stopped/denied/unsupported/error
states, not permanently shown to authorized users.

Automatic gate is retained across configuration recreation. Foreground entry
after idle onStop release rechecks automatically, but waits until both old client
cleanup and setup lease finish. Cleanup-pending proof is never READY.
Setup token/client/generation guards reject late completion; queued UI callbacks
read the latest snapshot rather than replaying obsolete readiness. Binder identity
and attempt state dedupe resume/sticky Binder/grant triggers and stop repeated
failed-proof loops. Recheck permission/UID before reuse, invalidate on revocation.

Explicit setup Cancel pauses automatic checking; it is not lifecycle cleanup.
There is no Back-to-setup/edit setup control on the receiver list. Normal Android
Back leaves the app; idle foreground return still recovers automatically.
Resume/Retry or a new user permission action clears the pause. Revisioned
permission tickets prevent late/duplicate grants or denials from overriding newer
Cancel or stopping a newer cast. Root0 still requires deliberate consent, never
automatic. Stop sharing never starts another cast; still-valid proof may return
directly to receiver list. No capture on discovery/readiness, only Play/session acceptance.

## UI

Step1 explains Shizuku must be installed/started separately, restarted after
reboot for non-root, and permission granted explicitly. Official download/open/
help actions do not install/start/pair/grant/root/change Settings on behalf of
the user. No manual pairing/connection ports/codes in ScreenCopy primary UI.
Rooted Shizuku requires deliberate root-mode checkbox. After proof the receiver
list IS the main casting screen. Each row is name + actual UDP-source IP +
Play/Connecting(Stop)/Stop/Stopping. One active endpoint; other rows disabled
while pending, playing or cleaning up. Other BUSY receivers explicitly disabled.
Actual Streaming (all enabled tracks ready), not Intent dispatch, means Playing.
An active row survives missing discovery and own BUSY; Stop remains reachable
there and in the foreground notification. Errors become retryable after cleanup.

Production ReceiverCasting/ReceiverRowActions own projection and request tokens/
revisions shared by service, UI and deterministic tests. Freeze an effective copy
of last-applied settings in the exact discovered endpoint's request. The home
size/crop checkbox overrides only maxSize=0 and crop=null in that copy, never the
store. ReceiverCasting owns this process-runtime choice (fresh process unchecked)
and both the frozen saved baseline and effective request. Home recreation/resume
and the existing foreground notification read that same state. While a safe
owned cast is Playing, either home or the notification can request one explicit
size recast; connecting/stopping controls are disabled. All validation, preflight, session negotiation, backend
options and traces consume that effective request; unsupported native geometry
fails rather than downsizing. The home summary compares typed fields with a fresh
Settings default instance, hides irrelevant disabled-audio/PCM controls, and shows
Default settings when no differences exist. Apply/resume/checkbox/session-state
changes refresh it without reading editor drafts. During ownership it shows the
frozen active request, explicitly not measured output or resolved automatic
encoder diagnostics. Stale view/notification/progress/
completion callbacks cannot stop or clear a newer request. No manual address,
Continue, confirmation page, Step3 or navigation Back to Shizuku.
Claiming a request adopts a service-only identity lease, not a read-only query.
Pending UI failure/complete cannot release an adopted foreground session;
CastingServiceState finalizes only its matching lease after session cleanup.
The notification size action is an explicit non-exported CastService PendingIntent,
IMMUTABLE | UPDATE_CURRENT, with request token and size-action version in its URI.
Old/cold-process/unowned actions are ignored; no target is restored from preferences.
The action never opens MainActivity. Busy taps are deterministically ignored,
not queued, and identical effective geometry only updates runtime UI state.
SizeSwitchAuthorization permits one recast only after sender-initiated normal Stop,
receiver SESSION_END plus EOF, and completed successful owned cleanup. The existing
terminal watchdog and bounded receiver drain remain in force. Cleanup exceptions,
surviving reader threads, receiver Stop/disconnect, user Stop/home exit,
permission/readiness loss and backend failure revoke authorization; no retry.
Only then does CastingServiceState atomically replace the old request with one
fresh token pinned to the old endpoint/baseline, keeping the existing service
foreground. Home and notification retain the old frozen geometry until the new
cast reports Streaming. SenderSession's existing positive SESSION_REQUEST/SESSION_ACCEPT
boundary still precedes capture. H.264/H.265/AV1 use the same single user-tap
authorization, without preliminary warnings, confirmation steps or expiring consent.
Real H.265 capture/Stop validation remains NOT AUTOMATED and unverified because
of the historical kernel-panic risk; synthetic tests do not verify device safety.
Current audio/transport policies are unchanged. No fallback FPS/bitrate/resize, native reconfiguration, background
service, grants, overlay or Android foreground-start workaround is introduced.
The notification displays current frozen requested geometry, pending/failed state
without a codec-specific warning, never peer credentials. Disabled notifications/channel
are explained on home through normal Android UX; the home action does not depend
on notification permission. The size choice is not persisted.
ReceiverListBinding pulls current state on attach and queued callback execution,
invalidating detached/recreated observer jobs. Never infer ownership from BUSY.
Keep active endpoint first/pinned across name/availability/expiry changes.
Small Settings dialog retains all persisted controls/capability listing and
restores unsaved drafts across recreation. Disable the Settings ENTRY for every
pending/connecting/playing/stopping phase until real service cleanup. Dismiss an
already-open editor with synchronous draft capture; stale/dismissed Apply/Reset
handlers reject changes. Apply/Reset require inactive capture;
never live-change encoders or secretly restart. Discovery starts automatically
only after actual proof; empty-state Retry does not change Shizuku permission.
The same active snapshot controls FLAG_KEEP_SCREEN_ON only while this Activity
is visible. Clear on idle/failure cleanup, onStop and exit; adopt current service
state after recreation. No global screen timeout/stay_awake, wake lock, overlay,
device admin, keyguard dismissal/unlock or automatic screen-on. Manual Power
locking and normal background lock policy remain intact.
Screen selection uses actual noncontiguous display IDs from the authorized
UserService's DisplayManagerGlobal (the pinned scrcpy mechanism), enriched with
name/type/logical dimensions/rotation in a bounded INTERNAL capability report.
Default/Main screen is labeled only if present; never fabricate ID0 or choose it
when a saved ID disappears. Names/dimensions are labels; actual IDs remain
numeric in saved settings and wire/scrcpy options. A missing saved ID remains an
explicit invalid choice. One available selected screen needs no numeric entry.
Refresh on proof and asynchronous Settings open/Refresh; the UI reads cached
proof only, never blocks on display queries. A single pending query has a10s
UI deadline; stale dialog/generation results cannot reopen Settings or alter
an active cast. Apply/fresh backend/privileged launch validate the selected ID
and rotation-correct natural crop bounds, not app-UID display visibility.
Inventory failure is explicit; physical/OEM hidden-API support remains untested.
Settings retains the selected-next-cast footer. No development/bug-history or
codec stability warning copy in the form for either codec. The physical HEVC
Stop risk remains unresolved in documentation; normal capability/input errors
stay explicit. No codec is disabled/substituted or claimed device-certified.

Existing capture settings/defaults/media stay authoritative: H264 + enabled
Opus192kbps, actual sender/backend encoder verification, H265/AAC/PCM explicit
alternatives, crop/display/orientation/options, no silent fallback/downsize.
Video preflight projects pinned crop/display rotation/capture orientation,
max_size mask/limit and round8 exactly, without changing user settings.
100square crop becomes96square; no universal encoder minimum is assumed.
Privileged public VideoCapabilities joint size/rate/ranges/alignment checks run
before SESSION_REQUEST and again before launch. No codec allocation for queries.
Only video inventory and validation require official MediaCodecInfo
isEncoder/isHardwareAccelerated/!isSoftwareOnly flags plus MIME support, never
vendor-name heuristics. Opus/AAC audio accepts all matching-MIME encoders,
hardware or software. The verified UserService reports only eligible encoders,
intersected with exact pinned-backend names. Aliases preserve explicit selection;
canonical identity deduplicates automatic candidates without rewriting names.
Automatic resolves to the first eligible backend-listed name (hardware for video) before
SESSION_REQUEST, bitrate checks and geometry preflight. Launch receives that
explicit name and checks track-specific metadata again under the authorized identity.
Software preference properties cannot override the video policy; no compatible
second encoder is substituted on rate/size/allocation failure. Saved automatic
and unavailable/nonhardware video selections remain unchanged; invalid selections
require explicit user correction. Software-only Opus is valid for automatic and
manual selection; absent Opus never selects AAC/audio-off. Codec selectors retain
the user's choice with an explicit unavailable notice and block Apply/start.
Raw PCM remains unencoded, not a hardware encoder. Receiver decoder policy and
the wire format are unchanged. Flags are not proof of successful driver startup.
Unsupported errors include requested/normalized size and actual metadata example.
Crop copy states all four fields zero for full display, else positive dimensions
and top-left offsets in default display orientation; validation stays unchanged.
Bounded server output parsing retains only controlled first-observed category/
exception and EOF header/config/media/output-size progress, not raw logs/CSD/
credentials/vendor text. Persist startup evidence beyond cleanup/background;
EOF does not establish server cause. Idle release cannot close active/pending
capture; guardian stdin/control resources remain scoped until cancel/death.
Video UI uses decimal Mbps (default8); exact BigDecimal conversion to integer
bits/s rejects fractional bits/overflow/out-of-range input and preserves stored
values. Decimal point/comma accepted as decimal separators, never grouping.
Audio UI offers codec-specific recommended kbps presets, default Opus192.
Only Opus192/AAC128 receive the recommendation marker when permitted; it never
changes the current/persisted selection or labels it as a factory default.
Privileged MediaCodec audio/video bitrateRange metadata filters/checks limits;
unknown metadata is explicitly unproven. Automatic bitrate choices and checks
use the resolved eligible name, not a union of candidate ranges.
Keep existing nonpreset/invalid selections visibly rather than silently choose
a lower rate on codec/encoder switch. RAW bitrate is disabled/N/A (48k stereo
16bit PCM =1,536,000bits/s), retaining its unused positive wire placeholder.
Apply, fresh backend validation and privileged launch validate limits; persisted/
wire/scrcpy bitrate units stay integer bits/s. No FLAC transport/control added.
Adapter parses scrcpy3.3.4 dummy/device/codec metadata and packet flags/PTS,
canonical OpusHead, SPS dimensions and independent generations. Initial all-track
readiness, generation deadlines and8MiB/500ms bounded fair queues unchanged.
Do not guess a capture-origin PROTOCOL reason from successful initial config.
Classify config sequence, media generation and decreasing PTS distinctly; keep
strict generation/timestamp/length/format guards. Persist controlled first-fatal
reason/stage/track/codec enum/local generation counters/packet kind and relative
delta, including adapter repeat/config/media counts, without payload/CSD/absolute
PTS, peer/vendor-private text or session identity. Unknown paths remain explicit
UNCLASSIFIED. Synthetic full forward/startup concurrent-reader/TCP tests cover
both arrival orders and repeated/changed H265 config with all three audio codecs;
passing those does not identify the unreproduced physical startup failure.
Local admission may wait cancellably up to500ms for dequeue, without holding
network locks; admitted byte/count/PTS-span caps never increase. Source PTS
span is not queue age. Transient READY/VFR/source-phase backpressure can recover
without dropping or retiming packets; sustained stalls remain CONGESTION.
Report first rejection's actual track/limit, queued/attempted relative span,
age/wait, counts/bytes/capacities and writer in-flight/elapsed metrics. Persist
only controlled first-queue counters for idle UI recovery, not peer text/media/
absolute PTS or IDs. One pending source packet per reader and one write in flight
are bounded separately and measured explicitly.
No remote control/clipboard/projection/DRM bypass.

User reports H265 Stop freezes while H264 Stop did not: HEVC teardown remains a
known danger, not fixed/verified by guardian host tests. Default H264 unchanged.
No device capture/Stop/reproduction or deployment while HOLD applies.
For reported all-codec audio cancellation, preserve actual AudioRecord monotonic
PTS (timestamp refresh need not equal exact sample-count cadence), canonical CSD
and fatal receiver error text. Contextual unexpected-EOF errors distinguish
privileged audio/video capture closure. Do not mask failures or disable audio.
Receiver owner investigates actual output/clock/route; synthetic framing tests
are not runtime diagnosis or proof of audible playback.

User-visible nonsticky specialUse foreground service reflects Shizuku shell
capture, not MediaProjection. Distribution approval unestablished. Protected
content/OEM restrictions apply.

## Legacy and verification

Retain embedded ADB code/dependencies only for safe trial/regression continuity;
primary UI/session do not call it; legacy ADB capture launch explicitly fails
before starting anything (it cannot fall back to the retired group guardian).
Do not delete existing OS ADB authorization,
encrypted keys/certificates, prior pairing-port draft or Shizuku user settings.
Legacy removal deferred until live UserService acceptance, not claimed complete.

Official Shizuku API/UserService/setup docs were read. README records exact host/
APK/lint/interoperability counts and installed/running package evidence versus
ungranted/unexecuted device checks. Host tests use synthetic encoded media and
resource/death policies; they do not prove shell SELinux/FD/capture functionality
on hardware. Compile conditional authorized-device checks; no device operations
while user is actively testing. Live capture/audio/playback, Binder/app-death,
latency/drift and30minute acceptance remain unverified.
