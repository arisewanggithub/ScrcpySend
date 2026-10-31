# ScreenCopy Sender — Shizuku + trusted family LAN

Native Kotlin Android11+ sender. The **primary capture backend is Shizuku's
supported UserService API**, not embedded ADB. Official scrcpy-server **3.3.4**,
H264/H265 video and default-enabled **Opus192kbps** device audio.

**ScreenCopy v3 uses plain TCP: no LAN encryption/authentication.** Use only a
trusted private family LAN. Anyone on the LAN can observe or forge traffic.
Receiver selection is name/IP-only: no human code, pairing or comparison
confirmation. Names are labels, not authenticated identities. Internal cast
session IDs and per-track generations still exist. No remote phone input/control.

## Setup

### Independent SRT trial

Use `-PsrtTrial=true` and select **SRT (fMP4)**, then Apply.
Custom TCP remains the default. This independent H.264/H.265/AV1 + Opus/AAC-LC/audio-off trial
uses libsrt 1.5.7 for transport and the pinned official Media3 1.11.1 fragmented
MP4 muxer; the receiver's Media3 ExoPlayer owns demuxing, decoding and A/V playback.
Enabled PCM fails explicitly;
there is no codec fallback, second encoder, playback clock or resampler.
The existing scrcpy/Shizuku capture still requires user Play AND SESSION_ACCEPT.
All selected bitrates (including 20 Mbps), FPS, crop and encoder settings remain
unchanged. Apply/load/drafts/Reset retain their normal behavior; no auto-retry.

The sender listens on an ephemeral port on the actual control connection's
numeric local IP. Its exact media descriptor is `kind: srt`, `container: fmp4`,
`url: srt://<local-IP>:<port>`, `latencyMs: 100`,
`streamId: sc-<16-lowercase-hex-session>`. SESSION_ACCEPT must explicitly accept
`mediaTransport: {"kind":"srt"}`. This path uses no STREAM_CONFIG/STREAM_READY;
receiver `STATUS state=srt-playing` indicates playback. Control and SRT are
unauthenticated and unencrypted: trusted private LAN only.

A single mux worker owns tracks and emits fragments every 100 ms independently
of new video/keyframes, including the first static frame while audio continues.
Opus and AAC access units are marked as MP4 sync samples; audio wire flags remain zero.
Video keyframe flags remain encoder-authentic; no video keys are fabricated.
Media3 converts raw Annex B AVC/HEVC; Opus payloads/FIFO and canonical pre-skip/gain
are retained. Opus presentation timestamps count packet durations from the first
source observation because scrcpy source PTS can regress; source observations
remain in diagnostics. A shared origin preserves the initial A/V offset.
Queues include mux-pending samples and are bounded to 8 MiB / 240 video frames
and 1 MiB / 100 audio packets. A first video keyframe is required. Identical
H.264 rotation/config changes use standard `avc3` in-band SPS/PPS followed by
a real IDR, preserving the session and raw video PTS. Identical config restarts
work too; changed audio/codec and queue overflow still fail explicitly.
Stop closes SRT before bounded mux/capture joins.
SRT failures surface a controlled root category, native operation and numeric
error code in the sender error/terminal message. `ScreenCopySender` logs
`srt_failed`, bounded `srt_failure_cause` chains and allowlisted
`srt_failure_stack` frames without exception/library text, filenames, peer URLs,
credentials or media payloads. The original exception chain remains attached;
later cancellation/cleanup cannot replace the first failure. The actual
`SenderSession` first-failure path also commits `lastSessionFailure` in
`sender-diagnostics`: timestamp, session/phase/origin, safe reason, bounded
cause/stack fields and public codec/crop settings. Only the first failure of
the current session can update it; older callbacks and teardown cannot replace
it. UI reattachment shows this timestamped reason. Previous sessions and
untimestamped legacy records are labelled historical rather than current;
queue/capture history is retained separately. This is a diagnostic fix, not a
claim that the underlying mux/transport fault has been repaired.
Session IDs/times use decimal int64 strings and exact integer parsing, avoiding
Android `JSONObject.getLong` rounding through double. Terminal native failures
and explicit close-owner sampling include safe socket state and pinned
`srt_bstats(clear=0)` packet/byte/loss/retransmission, ACK/NAK, buffer and RTT fields.
Stats are sampled only on error/close, before socket retirement; unavailable
closed/busy/stat-query results remain explicit. The pinned public API does not
expose the last peer-response timestamp, so that field is `unavailable`.
No peer-idle, drop, buffer, codec, or protocol behavior is changed.

| SRT codec | Container/capture contract | Validation state |
| --- | --- | --- |
| H.264 | Annex B → official `avc3`/`avcC`; real IDR rotation | Existing two-phone rotation/static/audio-off verified |
| H.265 / HEVC | Actual VPS/SPS/PPS → official `hev1`/`hvcC`; in-band rotation before authentic IRAP, raw NAL conversion | User reports initial portrait/landscape playback works; in-band rotation validation pending, Stop safety unresolved |
| Opus | Actual OpusHead and packet durations; `dOps` | Existing two-phone audio advancing verified |
| AAC-LC | Actual stereo 48 kHz ASC, raw access units, `mp4a`/`esds`; 1024 or 960 samples from ASC | Implementation/host fixtures added; device validation pending |
| AV1 | Actual sequence/config OBUs, official `av01`/`av1C`; authentic encoder key flags | Implementation/host fixtures added; hardware validation pending |

AAC duration arithmetic uses an exact sample-count timeline anchored to the first
source observation, retaining FIFO bytes and the common video/audio origin even
when source audio PTS regresses. AAC configuration changes and AV1 parameter/CSD/
dimension changes fail explicitly. HEVC uses its own official SPS parser for rotation,
never AVC SPS parsing.
Encoder inventory, exact size/FPS/bitrate preflight and receiver platform decoder
availability gates never substitute codecs or lower requested settings.

The [streaming muxer build](streaming-muxer/build.gradle) verifies the upstream
source checksum and applies narrow `avc3`/`hev1`, actual HEVC VPS temporal metadata, live-flush, `tfdt`, duration and standard
Opus `dOps` byte-order fixes, retaining Apache headers. See
[DESIGN.md](DESIGN.md) and [THIRD_PARTY.md](THIRD_PARTY.md).
Synthetic-only host coverage includes `LiveFragmentTest`, `SrtTransportTest`
and `SessionStopTest` plus `SrtAvcConfigurationTest`; `NativeSrtTest` adds loopback transport coverage without
capture, Shizuku, UI or permission changes. The coordinating session owns
serial APK/native/device validation.

HEVC uses one actual, complete VPS/SPS/PPS initialization buffer for the official
`hvcC` serializer and standard `hev1` sample entry. Valid configuration/dimension
changes prepend the complete actual VPS/SPS/PPS before the next authentic IRAP
picture, retaining one init segment, raw PTS, audio, FIFO and the SRT session.
Missing/mismatched parameters or a non-IRAP activation fail explicitly.
The bounded receiver bridge uses Media3's HEVC SPS/profile parser to announce
actual CSD/dimensions before the unchanged access unit; default Media3 renderers
adapt/reinitialize their decoder without changing the audio sink or clock.
Rebuild both trial apps together; no v3 version bump. The user confirms initial
portrait and landscape HEVC playback works; synthetic rotation validation remains
pending. Only synthetic HEVC probes are permitted for this investigation:
historical OnePlus real display-capture/Stop kernel-panic safety is unresolved.
In-band rotation never invokes capture Stop; notification size-recast remains disabled.

#### Bounded synthetic SRT fixtures (no screen/microphone capture)

The instrumentation-only `SrtFixtureDeviceTest` uses an actual synthetic AVC encoder
fixture. Methods `serveStaticFixtureForReceiverProbe` and
`serveMovingFixtureForReceiverProbe` require explicit `srtFixtureHost` (the
Source's numeric active LAN address) and `srtFixturePeer` (the Receiver's numeric
address). Optional `srtFixtureSeconds` defaults to 20 and must be 16..90;
`srtFixtureProfile` is `small` (320x240/30fps) or `screen`
(1080x1920/60fps/20Mbps); `srtFixtureSession` defaults to `0000000000000001`.
Keep the receiver connected for the entire configured duration.
Method `serveAudioOffFixtureForReceiverProbe` uses the same explicit LAN
arguments but sets audio off: no audio configuration or packets are produced.
Other fixture methods continue to default to Opus; there is no substitution.

New `serveCodecFixtureForReceiverProbe` / `saveCodecFixtureForReceiverActivity`
reuse the generator and bounded transport helpers. Select
`srtFixtureVideoCodec=h264|h265|av1`, `srtFixtureAudioCodec=aac|opus` (defaults H.264/AAC)
and the same LAN/export/profile/duration arguments. AAC silence comes from a real
MediaCodec encoder and actual ASC, not ADTS or an invented header.
AV1 requires an advertised hardware encoder and exact profile size/FPS/bitrate/
input-format support; absence logs `phase=unsupported`, `NOT_VALIDATED` and a
JUnit assumption skip, **never a codec PASS**. An advertised but unusable encoder
is a fixture failure, not a smaller/slower fallback. Run H.264/AAC first, then
HEVC/Opus, HEVC/AAC, AV1/Opus or AV1/AAC only if the exact hardware video encoder, compatible audio encoder and
receiver decoder are available. Unsupported fixture input fails explicitly,
never switches encoder/codec/settings or reports a skipped PASS. Synthetic
owned-codec release is not real OnePlus display-capture/Stop validation.
`serveCodecAudioOffFixtureForReceiverProbe` additionally accepts the explicit
HEVC codec argument without creating any audio encoder; pair it with receiver
`headlessLiveSrtAudioOffRendersFirstFrameWithoutAudioProof` and the same
`srtFixtureVideoCodec=h265`. These are synthetic MediaCodec input/owned-release
probes, never the Shizuku display-capture pipeline.

`serveHevcRotationFixtureForReceiverProbe` requires `srtFixtureVideoCodec=h265`;
use `srtFixtureProfile=screen`, `srtFixtureSeconds=16`, the existing explicit
host/peer/session arguments and `srtFixtureAudioCodec=opus|aac`. Actual synthetic
hardware clips change portrait→landscape→portrait at 4s/8s with authentic IRAPs,
continuous audio and one SRT connection. Pair with receiver
`headlessLiveSrtHevcRotationRetainsVideoAudioAndSession` and
`srtRotationTestUrl`/`srtRotationTestStreamId`; real dimensions, Surface frames,
audio advancement and unchanged session are required, not inferred from connection.

Instrumentation status and logcat tag `ScreenCopySrtFixture` publish
`srtFixtureUrl`, `srtFixtureStreamId`, `srtFixtureLatencyMs` and the complete
`srtFixtureDescriptor`, plus boolean `srtFixtureAudio`, before the caller connects. Native acceptance is bounded
to 10 seconds. Only then does the elapsedRealtime-based media lifetime begin;
the total wall lifetime is bounded by that startup plus the configured duration
and cleanup. Static mode sends exactly one real keyframe with ongoing valid
20ms Opus silence, supporting a receiver's >=6-second no-new-video observation.
Moving mode repeats a complete real GOP with advancing PTS and a moving stripe.
No capture permission, Shizuku, Activity navigation or real content is used.

Method `saveStaticFixtureForReceiverActivity`, with explicit
`srtFixtureExport=true`, runs the same sender through native loopback and saves
the exact received fMP4 stream (maximum 16 MiB) in the app external files
directory as `srt-static-fmp4.mp4`. Status/logs report `srtFixturePath` and size
after the reader has stopped and the file is atomically published. The
coordinator can transfer that file into the receiver's test-only Activity seam;
no private inter-app file access or phone actions are performed by the tests.

### Saved transport choices

Settings offers Custom TCP and SRT (fMP4) only. RTSP/GStreamer integration has
been removed. An old saved transport is rejected before capture, not silently
converted to Custom TCP. Settings retains the other saved capture fields and
shows an unavailable selection: choose a supported transport and explicitly
Apply. Closing the editor does not rewrite preferences. Reset defaults remains
an explicit unsaved draft until Apply.

1. Install official [Shizuku](https://shizuku.rikka.app/download/) yourself.
   Start it using [its official setup guide](https://shizuku.rikka.app/guide/setup/).
   Non-root Android11+ uses Shizuku's wireless-debugging setup; start Shizuku
   again after reboot. A computer ADB start is also supported by Shizuku. Root
   is **not required**. ScreenCopy does not install/start Shizuku, approve its
   permissions, change Developer options or pair on your behalf.
2. **Step 1: Shizuku.** Open Shizuku from the app if needed, return, then tap
   **Allow ScreenCopy in Shizuku**. If denied permanently, change ScreenCopy's
   authorization in Shizuku yourself. If Shizuku is already running/authorized,
   ScreenCopy checks privileged capture **automatically** on entry/resume,
   Binder arrival or permission grant—no extra Check button. During proof it
   shows compact progress and Cancel, not install/open/help instructions. A live
   valid proof is reused and Step 1 stays hidden. No ADB pairing/connection ports
   or codes are entered in ScreenCopy's primary UI.
3. This binds a non-daemon UserService, verifies its real UID is **shell2000**,
   transfers/checks/deploys the pinned server through a file descriptor, actually
   runs its encoder/display listing under shell identity, and confirms cleanup.
   Only successful permission, Binder and capability checks reveal Step 2.
   Missing/stopped Shizuku, denied permission, dead Binder and startup/deployment
   failures are explicit. Cancel closes/unbinds only ScreenCopy's UserService.
   A genuine readiness failure shows Retry rather than busy-looping. Open,
   official download, Help and permission actions appear only when relevant.
   No repeated automatic permission dialogs or automatic root consent.
4. If Shizuku was deliberately started as root, ScreenCopy defaults to refusing
   UID0. You must check the explicit **root-mode** consent before proceeding.
   Prefer shell mode; root compatibility is not device-verified. Unknown UIDs
   are rejected. ScreenCopy never starts `su` or requires root.
5. **Main screen: Receivers.** Discovery starts automatically after real
   privileged readiness proof. Each row shows name, actual UDP-source IP and
   **Play**. Play immediately starts that endpoint using the applied settings
   and the home screen's next-cast size/crop override, if checked.
   There is no manual address/port, Continue, Back-to-setup or separate Step3.
   Update both apps to v3. Empty state: open Receiver on the same Wi-Fi; Retry
   restarts discovery only. Network/interface changes clear stale results.
   Discovery runs only while Sender is started and privileged readiness is valid.
   `ScreenCopySender` logs `DISCOVERY_LIFECYCLE` gates, UDP start/stop/failure,
   and ten-second receive counters without advertisement payloads or peer names.
   Startup includes the actual bind address; first-packet/accept events identify
   the UDP source IP immediately, without waiting for the counter interval.
   Discovery timestamps, directory expiry and the shared receiver-row model all
   use `System.nanoTime()`. Mixing in Android's suspend-inclusive elapsed clock
   would make freshly received peers appear expired after device sleep.
6. Only one receiver is active. Its row shows **Connecting... (Stop)**, then
   **Stop** only when all enabled tracks actually become ready, then
   **Stopping...** through cleanup. Other rows are disabled; other busy receivers
   are explicitly Busy. The active row stays visible/actionable despite expiry
   or its own BUSY advertisement. Stop via that row or foreground notification;
   retries become available only after cleanup. Old callbacks cannot stop or
   clear a newer request. Normal Android Back leaves the app.
7. The small **Settings** action retains every video/audio/encoder/crop/display/
   orientation/typed-options control and capability listing. Successful Apply
   refreshes the home screen's compact nondefault-only summary; unapplied
   edits never appear there. All-default requests show **Default settings**.
   **Ignore size and crop (full screen)** starts unchecked in a fresh app process
   and is shared by home and the ongoing cast notification across Activity
   recreation. It sets only the effective request's maxSize to0 and crop to null, sharing
   the entire selected display without size limiting/cropping; saved settings,
   display/orientation/FPS/bitrate/codecs remain unchanged. The active summary
   shows the frozen cast request, not subsequent saved edits or measured encoder
   output. Automatic encoders still resolve through existing hardware policy;
   effective full-screen geometry/bitrate preflight fails explicitly if unsupported.
   While playing, either the home checkbox or the notification's **Full screen** /
   **Saved size/crop** button explicitly authorizes one clean Stop and new Play to
   the currently owned receiver endpoint, without opening Sender from the notification.
   **Applying size...** retains the old request summary until the new cast is playing;
   taps while busy are ignored. Identical effective size/crop changes only the label,
   without restarting. All supported video codecs (H.264/H.265/AV1) use this same
   one-tap reconnect, with no preliminary warning or confirmation.
   Real H.265 capture/Stop validation remains **NOT AUTOMATED** and unverified
   because of the historical kernel-panic risk; synthetic tests are not device-safety proof.
   Restart requires receiver SESSION_END acknowledgment, control connection closure,
   and successful owned capture/session cleanup. Stop, receiver Stop/disconnect,
   permission loss, backend errors or closing home cancel it; failures require
   a fresh manual Play, never an implicit retry. FPS/bitrate/codecs are not reduced
   as fallback. The runtime choice is never saved to Settings.
   If Android notification permission or the casting channel is disabled, home
   explains that the panel action is unavailable; its checkbox remains usable.
   No grants, overlays, channel/DND changes or background-start bypasses are used.
   The switch reuses the already-running foreground service; Android refusal
   of foreground service/readiness/negotiation is an explicit error, not a retry.
   Apply saves settings
   for the next Play. The **Settings entry itself is disabled** from starting
   through sharing/stopping/cleanup. An already-open editor closes and retains
   its unsaved draft; stale Apply/Reset callbacks cannot save or edit it. No live
   encoder switch or hidden restart from the settings editor. Unsaved drafts survive configuration
   recreation without altering the active immutable settings snapshot.

### Service-owned rows and visible-screen behavior

The foreground service adopts an identity-checked request lease. Only that
lease's final cleanup can release the active endpoint; pending Activity start
failures, discovery updates and old callbacks cannot clear a live service.
Previously `claim` only read the pending request, so a late start-delivery error
could return the row to Play even after Streaming. A host regression reproduced
that production-handler failure before the fix. The reported phone's exact
callback sequence was not captured; no live reproduction was performed.

Activity attach/resume/recreation observes the current service-owned state,
not a saved "playing" preference or receiver BUSY flag. Detached queued UI work
is discarded. The active source-IP/port row stays first, pinned and actionable
through name changes, BUSY and advertisement expiry; other endpoints stay
disabled. Connecting/Stop/Stopping remain until the matching service cleanup.
Settings and window behavior use that same snapshot, including pending starts
and errors still cleaning up.

While this sender Activity is **visible** with an active request, its window
uses `FLAG_KEEP_SCREEN_ON` to prevent automatic screen-off. It clears on idle/
failure cleanup, backgrounding and exit, and recomputes from the active service
after recreation. This **does not disable secure keyguard, unlock or turn on the
screen, block manual Power locking, or keep the screen awake in background**.
The foreground service can continue while backgrounded under normal Android
lock/screen policy. No device-admin, wake lock, overlay, global timeout change
or scrcpy `stay_awake` setting is used.

### Screen selection

**Screen selection:** Settings lists actual screens from the authorized Shizuku
display inventory: **Main screen** (only when the default display is present),
named external/virtual screens, dimensions and a secondary ID. There is no numeric
Display ID entry. A single valid screen is shown without an unnecessary choice.
Saved IDs remain numeric in preferences/wire data; an absent saved ID stays
visibly **Unavailable** until you explicitly choose another screen.

The inventory refreshes with capability proof and asynchronously when Settings
opens or **Refresh screens** is tapped. No display Binder query runs on the UI
thread. Apply waits for refresh and checks the chosen screen and natural crop
bounds; startup checks fresh privileged metadata again. Missing/unsupported
inventory gives refresh/reconnect guidance, never a fabricated main screen.
The query uses the same DisplayManagerGlobal mechanism as pinned scrcpy, under
the verified UserService identity. OEM hidden-API/display access and physical
external-display inventories have **not** been verified on-device here.

Normal Settings text describes the selected next-cast settings, not hardcoded
defaults, development history or codec stability warnings. Both H.264 and H.265
remain selectable without warning copy. The unresolved physical HEVC Stop risk
and investigation history remain documented below; removing product-UI warnings
does not claim the issue fixed or imply device certification. Capability and
invalid-input errors remain explicit; no automatic codec substitution is added.

### Crop, encoded size and encoder checks

Crop width/height/X/Y must **all be0** for the whole display; otherwise both
dimensions must be positive, with nonnegative offsets measured from the top-left
in the display's default orientation. Labels use pixels; there is no new crop
mode or implicit reset of saved coordinates.

Receiver orientation is a separate next-session presentation preference:
**Automatic** (default), **Landscape**, or **Portrait**. It is persisted as
top-level `receiverOrientation` (`auto`/`landscape`/`portrait`); older saved settings
without the field use `auto`. The receiver applies it when Playing and restores
its normal policy after the session. It does not change capture orientation,
encoded dimensions, codecs, crop, track generations or aspect-fit content.

Preflight models pinned scrcpy3.3.4's exact order: transpose natural crop for
current display rotation, apply capture orientation/lock, limit aspect-ratio
size, then `round8`. `max_size` is masked to a multiple of8 by upstream.
A100x100 crop therefore encodes as96x96; a scalar maxSize100 means a96-pixel
major-axis limit, **not** a square. For example1440x3168 at maxSize1920 becomes
872x1920. MaxSize0 is unlimited; tiny maxSize1..7 is rejected explicitly instead
of silently becoming upstream's unlimited0.

Before SESSION_REQUEST and again before server launch, the authorized UserService
queries the resolved hardware encoder's VideoCapabilities: width/height ranges/alignment,
joint `isSizeSupported`/`areSizeAndRateSupported` and size-specific width/height/
rate ranges for a metadata-checked example. No codec is created/configured for
this metadata query. Known-unsupported size/FPS reports requested versus
normalized output and actual limits/suggestion; no resize, codec switch, bitrate
or FPS downgrade is applied.100x100 is **not globally forbidden**:96x96 is allowed
if the selected encoder reports support. Persisted settings are rechecked.

### Video hardware-only / audio compatible-encoder policy

Only video (H.264/H.265/AV1) requires Android's official
`isEncoder && isHardwareAccelerated && !isSoftwareOnly` flags and matching MIME.
Audio (Opus/AAC) lists and accepts all matching-MIME encoders, hardware or software.
Names are not hardware heuristics; unknown/nonhardware and contradictory video flags
are rejected. The verified Shizuku UserService supplies the encoder inventory,
intersected with the pinned backend's exact names. Settings, capability listings,
bitrate discovery and video preflight share this policy; receiver decoders are
unchanged.

**Automatic is a saved preference, not scrcpy's MIME-only default.** Before
SESSION_REQUEST it resolves to the first eligible encoder in backend listing
order, preserving aliases/canonical identity and passing an explicit encoder
name to launch. Bitrate and geometry checks use that actual resolved name.
The UserService checks official flags again before launch. No software-preference
property can switch video to software, and allocation/configuration failure does not
select another encoder, codec, FPS or bitrate.

Existing saved software video/unavailable names remain visibly unavailable and fail
Apply/start until the user explicitly selects an eligible encoder or Automatic.
Explicit software audio names are valid. Choices and defaults are not rewritten.
A video codec without hardware or audio codec without a compatible encoder remains
selectable with an explicit unavailable notice, but cannot Apply/start when required.
Software-only Opus is accepted for both Automatic and explicit selection; missing
Opus never silently selects AAC or turns audio off. PCM on Custom
TCP remains the raw, unencoded audio path and is not labeled hardware-accelerated.
Hardware flags/ranges are metadata, not proof of successful vendor allocation.

### Bitrate units and choices

- Video is displayed/entered in **Mbps**, default **8**. Decimal point or decimal
  comma is accepted (no grouping/exponents), including exact fractional rates
  such as `2.000001` Mbps =2,000,001 bits/s. Decimal arithmetic rejects fractional
  bits, overflow and rates outside0.1..200Mbps; it never rounds or uses floating
  point. Existing stored8,000,000 restores as8, not8,000,000Mbps.
- Audio uses **discrete kbps choices**, not free text. Only **Opus192kbps** or
  **AAC128kbps** has the codec-specific **recommended** marker,
  and only if permitted by the encoder filter. If removed, no other rate is
  marked and the current selection is not changed. Compatibility is explained
  once below the list; retained custom/invalid values and RAW N/A are not marked.
  A recommendation is not a forced selection or a claim that the current value
  is a factory default: switching codecs retains the user's current bitrate.
  Opus includes
  16/24/32/48/64/96/128/160/**192**/256/320/384/512; AAC includes
  64/96/128/160/192/256/320/384/512, subject to encoder-range filtering.
  The verified privileged UserService enumerates each encoder's actual
  `AudioCapabilities.bitrateRange` (and video equivalent) with an internal
  capability report. Missing/query-rejected ranges are explicitly unproven,
  not invented hardware support.
- These are recommendations within published ranges, **not guaranteed discrete
  encoder-supported rates** or guarantees for every profile/channel/sample-rate
  combination. Automatic presets and startup validation use the same resolved
  eligible encoder's range (or its explicitly unknown limits), not a union of
  candidates. That named encoder may still refuse the requested rate.
  There is no encoder substitution or automatic quality downgrade on refusal.
- Switching codec/encoder retains the chosen rate. If outside the new range it
  remains visibly **invalid** until an explicit choice; enabled audio cannot Apply
  or start at that rate. Valid old nonpreset rates remain labeled **existing**
  (including fractional kbps), not silently overwritten.
- RAW has a disabled **N/A** bitrate control: pinned scrcpy's48kHz x2channels
  x16bits =**1,536,000 bits/s** PCM. Its positive persisted/wire placeholder is
  preserved but ignored by capture. No FLAC selector is added: although upstream
  scrcpy supports FLAC, this sender/v3 media adapter supports Opus/AAC/PCM only.
- Apply checks current privileged limits; fresh backend validation before
  negotiation and privileged launch check them again. Persistence, v3 JSON and
  scrcpy arguments remain **integer bits/s**, with no protocol change.

The incompatible v3 listener uses default TCP27184 and UDP discovery27183. TCP
port remains internal/discovery metadata, not sender setup. Old TLS/v1/v2 clients fail early; there is no version/security
mode fallback. The receiver has no dependency on sender's Shizuku backend.

## Actual backend path

```text
Activity / foreground SenderSession
 -> Shizuku runtime permission + bindUserService (daemon=false)
 -> ShellCaptureService (AIDL Binder, verified UID2000 or explicitly allowed0)
 -> FD-delivered, SHA256-checked scrcpy deployment in /data/local/tmp
 -> native direct-child supervisor -> matching app_process under THAT identity
 -> privileged LocalSocket(localabstract:scrcpy_<id>), video then audio
 -> private EOF-only lifecycle socket (never exported, no input messages)
 -> authorized duplicated media FDs through Binder to app
 -> exact scrcpy3.3.4 parser / bounded fair mux / ScreenCopy v3 plain TCP
```

The ordinary app does **not** open shell localabstract sockets or run privileged
`app_process` itself. No deprecated `Shizuku.newProcess` command API is used.
The UserService exposes structured deployment/capture methods, not arbitrary
shell commands. It validates session IDs/settings and the official server hash.
Large server/media bytes travel by file descriptor, never Binder byte arrays.

LocalSocket FD duplication preserves original media boundaries/timestamps with
no media re-encoding or unbounded bridge queue. Both privileged and app copies
are owned; shutdown/close unblocks readers on cancellation/Binder loss. Startup/
capability phases have10s deadlines. Initialized audio silence has no command
read timeout; readiness/liveness still bound failures. Video dummy byte,64-byte
device name, codec IDs/config/12-byte packets and independent track generations
remain version-specific, not guessed header stripping.

Owner Binder death/reserved destroy exits only the supported UserService
process; kernel FD closure and the independent supervisor's parent-death signal
perform cleanup without waiting on Java I/O monitors. Explicit Stop detaches
ownership under a short lock, then shuts down privileged/app socket duplicates,
closes guardian stdin and verifies cleanup off the UI thread.
The supervisor requires an isolated `setsid` session, but **never signals a
process group**. Its single native thread retains its direct forked child,
validates ownership with `waitid(WNOWAIT)`, sends only positive-child TERM/KILL,
then reaps it. A live child/unreaped zombie reserves that PID; caller/system/
Shizuku groups, zero/negative PIDs and externally reused numeric IDs are not
signal targets. After media socket shutdown it first observes the child for up
to2s **without any signal**, letting scrcpy handle broken pipe and stop/release
its codecs normally. An exited child is reaped without signaling. Only stalled
startup/teardown uses TERM, then up to200ms observation before KILL if still live.
This is a bounded opportunity for graceful teardown, not a guarantee against an
integration-specific failure. EOF/5s silence and parent death stop it; child parent-death KILL
also covers abrupt supervisor loss. Readiness runs a harmless exiting-child
probe of the real executable before listing capabilities; it never captures.
The executable cannot fork capture until its adopted owner sends `START`.
Cancellation/cleanup RPCs carry the capture ID; an old bridge cannot cancel a
newer capture on the same UserService. TCP closing budgets do not interrupt the
independently bounded codec-cleanup path.
Persistent session ownership enables recovery on next setup; recovery only
observes old ownership, never signals external PIDs. No capture before receiver
SESSION_ACCEPT, and no media before all enabled tracks ready.

Foreground service remains user-visible, nonsticky `specialUse` (not a fake
MediaProjection token). Distribution approval is not established. Binder death/
lost readiness stops the matching active session. Leaving idle UI unbinds its
UserService; configuration recreation retains the actual in-process connection,
not a saved "ready" flag.

Automatic lifecycle recovery distinguishes system cleanup from user intent.
Idle backgrounding can release the UserService; returning while Shizuku remains
available automatically waits for cleanup/lease release and rechecks capabilities.
Configuration recreation keeps the in-flight check/proof and does not start
another UserService. Duplicate resume/Binder/grant callbacks do not duplicate
checks; failed proof on the same Binder does not repeatedly retry. A new Binder,
fresh foreground visit, explicit Retry or fresh grant can retry.
Explicit setup **Cancel** pauses automatic checks until Resume/Retry
or a fresh user permission action. Late/duplicate permission callbacks cannot
undo a newer cancellation. Stop sharing never automatically restarts a cast:
it returns to the receiver list and reuses a still-valid readiness proof.

## Settings and limits

Defaults: H264 automatic encoder,8Mbps, maxSize1920, max60FPS, display0, no crop,
orientation0; output audio on, Opus192000bits/s. These are engineering defaults,
not measured latency guarantees. H265, encoder selection, crop/natural display
coordinates, relative/locked orientation, typed numeric codec options, AAC-LC48k
stereo and PCM16LE stereo are explicit alternatives. Sender Opus encoder is
checked against device AND pinned backend; failure never silently drops audio
or substitutes codec/settings. Raw PCM ignores bitrate and disables inapplicable
controls. AV1 encoding uses the exact scrcpy AV1 ID and actual OBU sequence
configuration; audio duplication remains explicitly unsupported.

Output capture may suppress local playback. Android11 must be unlocked during
audio startup. Playback source needs Android13+; mic is an explicit choice.
Protected content/OEM restrictions may produce blank/blocked capture; no bypass.
Mux limits8MiB aggregate queued media/500ms PTS span per track, control64/256KiB, fair media, explicit congestion
stop rather than dropping dependent frames. Source A/V timestamps and Opus
pre-skip/gain/startup metadata are preserved.

### Temporarily retained legacy ADB code/data

Embedded ADB helpers and their regressions/dependencies are retained during this
Shizuku trial to avoid deleting working authorization or destabilizing the
common media adapter without live device proof. **They do not power Step 1 or
SenderSession**; source-wiring tests enforce that. No automatic ADB fallback.
Existing OS authorizations, encrypted ADB key/certificate, exact/partial
`pairPortDraft` and Shizuku settings are untouched. The old port/code UI is gone;
its privacy/draft regression helpers remain only as legacy code/tests. Removal
of those dependencies is deferred until Shizuku physical acceptance, not falsely
claimed as completed.

## Build and verification

### Public snapshot and signed releases

The public migration targets fresh repositories `arisewanggithub/ScrcpySend`
and `arisewanggithub/ScrcpyRecv`, not the existing repository history.
From each checkout, run `.\tools\export-publication.ps1` for the deterministic
path/size/SHA-256 inventory; add `-Destination .\publication-snapshot` to copy
only that allowlist into a **new** directory. Re-run after concurrent changes
and review the resulting snapshot before publishing. Git history, local SDK
settings, IDE/device preferences, caches, outputs and signing material are
excluded even if tracked. Pinned archives, backend assets, wrapper JAR and
third-party sources/notices are retained; see **THIRD_PARTY.md** and bundled
licenses. Distribute the matching complete sources with each APK, including
the modified MPL SRT and other applicable source obligations.

No root license or reuse grant has been selected for the original application
code; public visibility alone does not grant permission to reuse it. Existing
copyright notices remain unchanged, and third-party code retains its own
licenses and their distribution obligations. The exporter does not invent a
license or require a new original-code license for technical publication.

GitHub Actions checks JVM tests, lint and debug builds on PRs/main pushes for
both default custom TCP and `-PsrtTrial=true`. A separate disposable Linux
API35 x86_64 emulator runs **only** `AppUpdateRemoteUiTest` with the explicit
instrumentation class selector. No physical-device, Shizuku, capture or codec
acceptance tests run; release publication also requires this UI job to pass.
Pinned tools: JDK21, Node22, SDK37/BuildTools37.0.0, NDK27.2.12479018,
CMake3.22.1 and the checksum-verified Gradle wrapper.
Only `vMAJOR.MINOR.PATCH` tag pushes publish stable **signed SRT/custom TCP**
APKs; main prereleases are opt-in with repository variable
`ENABLE_MAIN_PRERELEASES=true` and are excluded from latest-stable auto-updates.

Both stable and prerelease APK publication are **blocked by default** until
the user explicitly sets repository variable `BINARY_RELEASE_APPROVED=true`.
This manual approval requires review of the original-code license/distribution
choice and third-party obligations, including LGPL corresponding application
sources/build scripts and relinking/recombination materials (see
**THIRD_PARTY.md**). Merely adding a root LICENSE or configuring signing secrets
is not approval or a compliance guarantee. CI fails explicitly before decoding
signing secrets when approval is unset/false; it never substitutes unsigned or
debug APKs. Source export, JVM tests, lint and isolated emulator checks remain
unaffected. No license is selected or granted automatically.

Manually configure Actions secrets `ANDROID_KEYSTORE_BASE64`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_PASSWORD`.
The agent does not create, read, upload or configure keys. CI requires all
secrets before publication, restores the keystore privately on the runner,
cleans it on exit and never publishes an unsigned/debug APK or keystore.
For manual signed builds use `ANDROID_KEYSTORE_PATH` (or
`-PreleaseSigningKeystorePath=...`) plus the three signing environment variables,
and `-PrequireReleaseSigning=true`. Missing signing does not break ordinary
debug development. Never commit keys, passwords or machine-specific paths.

CI supplies `releaseVersionCode=10000+github.run_number` and
`releaseVersionName` from the tag (without `v`) or `dev-<short-sha>`, without
rewriting source versions. Retain this workflow/run-number continuity; a
replacement repository/workflow must choose a higher offset than all installed
versions. Local defaults remain versionCode1.
In-place updates require the **same application ID and signing certificate**
and a higher versionCode. If existing installs use a debug certificate, the
user must manually supply that matching key for continuity, or explicitly plan
a one-time migration/backup for a new key. A different certificate cannot
preserve an in-place installation; never automatically uninstall to bypass it.

Each release publishes only `ScrcpySend.apk` and `update.json`.
The manifest has exactly `schema:1`, `applicationId:"dev.screencopy.sender"`,
integer `versionCode`, `versionName`, `apkAssetName:"ScrcpySend.apk"`,
lowercase 64-hex `sha256` and `signingCertificateSha256`, and
`protocolVersion:3`. The script verifies the APK signature and actual package
identity/version before emitting this public certificate fingerprint.
The receiver uses the same schema with its unchanged application ID
`org.screencopy.receiver` and asset `ScrcpyRecv.apk`; update both apps to v3.
Signing secrets and actual public repository creation remain manual setup.

JDK21; SDK37/BuildTools37.0.0; Gradle wrapper9.8.1/SHA256; AGP9.4.1 built-in
Kotlin2.2.10/JVM17. Official `dev.rikka.shizuku:api` and `provider` **13.1.5** from
Maven Central, MIT license. Install **NDK27.2.12479018** and **CMake3.22.1**
for the current native SRT build, plus
**Node22** for native host regressions. Override a portable Node location with
`-PnodeExecutable=C:\path\to\node.exe` or `ORG_GRADLE_PROJECT_nodeExecutable`.

```powershell
Set-Location .\ScrcpySend # Run from the directory containing your clones.
$env:JAVA_HOME = 'C:\path\to\jdk-21'
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" # Or your existing SDK location.
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon --console plain
.\tools\interop.ps1 -ReceiverRoot ..\ScrcpyRecv
```

APK `app\build\outputs\apk\debug\app-debug.apk`. Interop uses only the receiver
owner's compiled JAR read-only. App/state/UI/tests are Kotlin, plus a small
first-party C supervisor: a standalone single-thread process is necessary to
retain a kernel child PID rather than Java/shell automatic reaping. AIDL
generates Java under build outputs only; no first-party Java source remains.
Official server3.3.4 SHA256 checked during build/deployment:
`8588238c9a5a00aa542906b6ec7e6d5541d9ffb9b5d0f6e1bc0e365e2303079e`.
Re-fetch via `tools\fetch-backend.ps1`.

### Host verification and device limitations

The results below are the last single-writer geometry/EOF snapshot. Subsequent
parallel UI/discovery changes and ranked-automatic regressions require the
parent-coordinated combined build; these results do not certify that newer tree
or its APK.

| Check | Result |
|---|---|
| Full JVM regressions | **211 passed, 0 failures/errors/skips**, retaining all195 prior cases plus pinned sizing/100square/range/alignment/FPS/auto ambiguity, EOF/log progression, background ownership and durable startup evidence |
| Debug + instrumentation APK builds | **Both pass**, official API/provider and AIDL compile |
| Android lint | **0 errors, 16 warnings** (English UI, API compatibility/pinned tools and legacy ADB crypto trust machinery) |
| `tools\interop.ps1` | **4 passed**:13 v3 framing fixtures/HELLO/media cross-parser cases; actual sender-link/receiver-parser TCP request/ping/Stop/BUSY; pending/accepted cancellation emits Stop, while a genuine prior fault remains fatal ERROR |
| Receiver-owner application loopback | Independently reported actual current sender `ReceiverLink` -> actual receiver `ReceiverServer` code-free HELLO/request/BUSY/ping/Stop/restart and codec compatibility passing in JVM harness |
| Kotlin source gate | **0 first-party Java paths**; app/tests/harness Kotlin, AIDL and the disclosed native supervisor |
| Native supervisor | Four Android ABI executables build; actual production stop/UID/session/START validation functions pass host WebAssembly syscall-model regressions, including graceful no-signal exit and timeout-only TERM/KILL. This is not a real Android-kernel execution claim |
| New Shizuku/native UI checks | **Compiled, not executed**; Activity rows/window flags/recreation and stale Settings handlers included; no install, grant or navigation |

New regressions cover missing/stopped/denied/unknown/root UID policy, owner-death/cancellation
resource closure and late-resource rejection, official protected provider wiring,
no primary embedded-ADB/deprecated-command calls, actual transport-seam deployment,
video/audio ordering, scrcpy config/Opus/PTS parsing and cleanup. Existing
protocol/queue/guardian/capture-setting/legacy ADB regressions remain.

Read-only OnePlus package/process inspection found official package
`moe.shizuku.privileged.api`, version **13.6.0.r1086.2650830c** and a running
`shizuku_server` process as **UID2000** (PID26152 at inspection). That does **not**
prove ScreenCopy permission, UserService
UID/SELinux access, successful capture/audio or playback. No install, permission
grant, Shizuku start, device navigation or Developer/root setting change was made.
The new APK and compiled device checks must be deployed/run only when the user
authorizes; the user must approve ScreenCopy in Shizuku.

For the reported authorized-but-stuck Step 1 case, parent-provided read-only UI
inspection showed an enabled manual Check button and idle-disconnected status;
code inspection confirmed onStop released proof with no automatic onResume
rebind. Read-only inspection here confirmed app/server processes, but the
`uiautomator /dev/tty` dump was unavailable. No taps, navigation, install, Shizuku
grant/start or new on-device capture/readiness check was performed for this fix.
User authorization is reported; actual new automatic proof must be confirmed
after the parent's updated APK deployment, not inferred from that permission.

Compiled instrumentation includes: primary UI without pairing ports, ordinary
app UID rejected by privileged service, and an actual shell UserService deploy/
capability check that runs only if Shizuku is already running, explicitly granted
and UID2000. It neither grants nor starts Shizuku. These new checks were **not
executed**. An additional compiled authorized-entry check expects automatic real
capability proof and Step 1 hidden without a button; it never starts a cast.
Prior harmless-child guardian/Keystore tests do not establish this
new backend's physical acceptance.

Still unverified: live Shizuku UserService loading/permission on each OEM,
shell/root localabstract FD transfer during capture, audio startup/silence,
Binder/app-death while real scrcpy runs, cross-device playback, latency/drift,
30-minute congestion/rotation and process-death acceptance. Host mocks/synthetic
media are not presented as physical success. Targets<=150ms latency/<=50ms A/V
offset remain unmeasured.

### Reported Stop freeze: deployment hold

**Known danger: the user reports H.265/HEVC Stop freezes the phone, whereas their
H.264 Stop did not.** This narrows investigation toward HEVC/vendor codec teardown;
it does not establish which driver/codec operation causes the panic. H.264 remains
the default; this unresolved risk stays in documentation, not normal Settings warning copy. No H.265 capture/Stop reproduction is
authorized, and neither codec is declared device-verified safe by host tests.

The user reported a whole-phone freeze after Stop; the parent's read-only ADB
check found OnePlus offline. **No phone logs were available**, and no destructive
reproduction, new capture, install, remote kill/reboot/settings or navigation
was attempted here. The exact physical cause is unproven. Code inspection found
the prior shell guardian's group-wide EXIT-trap TERM/KILL and privileged closes
under its ownership lock; both risks are removed in the implementation above.
Parent later reported `sys.oplus.panic=true` and an existing vendor kernel-panic
history string. Kernel/pstore diagnostic evidence was unavailable: pstore access
was denied and no SYSTEM_LAST_KMSG Dropbox entry was found. No privilege
workaround was attempted. This is not sufficient to attribute the panic.

Pinned scrcpy3.3.4 source explicitly treats client socket broken pipe as normal
closure: [SurfaceEncoder](https://github.com/Genymobile/scrcpy/blob/v3.3.4/server/src/main/java/com/genymobile/scrcpy/video/SurfaceEncoder.java)
stops capture/codec, resets/releases the surface and releases codec/capture;
[AudioEncoder](https://github.com/Genymobile/scrcpy/blob/v3.3.4/server/src/main/java/com/genymobile/scrcpy/audio/AudioEncoder.java)
ends/joins its workers, stops/releases the codec and stops capture.
[Server](https://github.com/Genymobile/scrcpy/blob/v3.3.4/server/src/main/java/com/genymobile/scrcpy/Server.java)
stops processors, shuts down the connection, joins and closes. Immediate guardian
signaling could race that real teardown; Stop now prioritizes socket-driven exit
and signals only as the bounded owned-child fallback described above. Native
regressions check natural exit has zero signals and both fallback deadlines.
Do not equate host tests with a verified device fix. Keep deployment/capture/
Stop retries on hold until the device has recovered and an explicit safe
acceptance plan is approved; do not force-stop/replace an active old capture
merely to test this patch. Galaxy/receiver are unchanged.

### Orderly Stop and latency diagnostics

#### Capture-start EOF and server evidence

The later dual-stream EOF event is **CAPTURE_FAILED**, not a recurrence proved
to be the earlier PROTOCOL invariant. The parent's earlier07:13 settings snapshot
confirmed H265 Automatic, crop100x100, X/Y0, max1920/FPS60/@0/display0. The later
08:07 snapshot instead had crop1080x1000 with those other video settings unchanged;
do not confuse it with the failing100x100 attempt. Neither snapshot establishes
actual encoder limits, and the historical server message was discarded.
Unsupported size is therefore not asserted as the proven historical cause.
The new geometry preflight prevents *known* unsupported output from reaching
negotiation/capture instead of hiding it with H264/audio-off/default settings.

Server stdout/stderr is continuously drained through a bounded1024-byte line
classifier. Only first **observed** error category and whitelisted exception
classification/guardian-ready/output-end flags are retained; no raw lines,
device/encoder/vendor-private text, media/CSD or credentials are logged/stored
or forwarded over LAN. An observed log error is context, not proof it caused EOF.
Early stream EOF gets up to100ms for the log classifier; output EOF gives the
specific stream EOF a bounded opportunity to provide header progress. A secondary
closed reader never replaces an already-retained genuine failure.

`sender_startup_failure` persists requested crop/maxSize/FPS/orientation, selected
video/audio formats, track, codec-header/dimensions/config/media progress, parsed
output dimensions, first log observation and explicit preflight reason/limits.
No absolute timestamps or payload. App-private `lastStartupFailure`/`lastFailure`
survive cleanup/background/log-ring loss. Normal Stop still filters expected EOF.
The log parser never owns/closes guardian stdin. UserService lifecycle retains
its input stream, private control FD and process handle until scoped cancellation/
owner death; idle readiness release now also refuses pending/adopted capture.
Host tests exercise actual startup EOF on both streams with controlled synthetic
server errors, not an actual Android codec failure. Physical cause remains open.

#### Capture startup PROTOCOL investigation

A later live event reported sender capture-origin PROTOCOL after initial H265
video/audio configuration. The old class-only log cannot distinguish the source
track or invariant, and the selected audio codec was not captured. The specific
live cause remains **unreproduced**; no B-frame, source-malformation or
H265-specific explanation is assumed. The same event's reported guardian exit0/
~201ms wait and ~282ms finish separate cleanup from this startup failure.

Capture-origin PROTOCOL is now classified as CONFIG_SEQUENCE, MEDIA_GENERATION
or PTS_DECREASE, with readiness/invalid-track guards separately identified.
The first fatal controlled record includes code/reason/stage, VIDEO/AUDIO/UNKNOWN,
selected codec enum, local generation/expected/announced/ready counters, packet
kind/count/length, repeated-config boolean and **relative** PTS delta/step.
No payload/CSD/absolute PTS, peer/encoder/vendor-private text or session identity.
Unknown capture-protocol exceptions get an explicit UNCLASSIFIED record rather
than a guessed track. Records survive cleanup/log-ring churn in app-private
`lastCaptureFailure` and the most-recent `lastFailure`, preferred over older
queue history on idle UI attach. Config progress no longer prints CSD JSON.

The host matrix exercises actual CaptureBackend forward dummy/name/header setup,
both concurrent readers, MediaBudget and ReceiverLink TCP for Opus/AAC/PCM,
audio-first/video-first, identical H265 CSD before/after media, changed CSD,
keyframe and generation transitions. All12 legitimate sequencing variants pass.
Injected video/audio PTS decreases and malformed sequencing/length/config cases
still fail specifically. No timestamp/generation/format check is relaxed, no
media reordered/dropped, and no codec or audio fallback is added. These tests
use synthetic encoded fixtures, not physical MediaCodec capture/decoding.

#### Sender congestion and normal cleanup follow-up

A later live receiver report identified **sender-originated CONGESTION** and
about10s specifically in SHELL_CLEANUP. The old event did not retain the sender
track/check/queue counters or actual guardian exit state. It is not evidence
of Wi-Fi overload or another receiver audio-clock failure.

The existing500ms limit is **source PTS span, not wall-clock queue age**. It is
unchanged. Immediate rejection could kill an otherwise recoverable stream when
an older queued frame was about to drain (including READY flush, VFR gaps and
valid source-timestamp refreshes). Admission now waits cancellably for local
dequeue for at most500ms, never on a socket lock. Every admitted queue still
obeys8MiB aggregate/1024 track messages/500ms PTS span and control64/256KiB.
No media is dropped/retimed, no rate/codec is substituted, and sustained stalls
still fail. One pending packet per capture reader and one in-flight write remain
separately bounded; in-flight bytes are reported, not hidden in queue counters.

First-failure diagnostics identify track/limit, admitted versus attempted PTS
span, relative source step, count/bytes/capacities, wall admission age/wait,
READY state and in-flight/last write timing. No absolute PTS, endpoints, media
or session/generation IDs. Controlled counters are also retained in app-private
`sender-diagnostics` as `lastQueueFailure`, shown as the last sender queue cause
on the next idle UI attach, so cleanup/log-ring churn cannot erase the evidence.
Starting a new request clears stale on-screen error text, not the retained record.

Normal cleanup now retains the **actual owned guardian Process handle** and
observes its exit directly (up to6s) before deleting the six exact owned artifacts.
The pinned supervisor exits0 only after its child is reaped; exit77 is a
pre-fork START cancellation. Other exits/timeouts/interruption fail explicitly
and preserve recovery ownership. There is no Process.destroy/group signal or
shorter native codec grace. Duplicate successful cleanup is idempotent.
Read-only PID-record/proc recovery remains only when no live handle exists.
It now reads bounded PID files and NUL-delimited process arguments directly in
the privileged UserService, without spawning shell/tr/grep helpers. A monotonic6s
budget is checked between bounded reads; this cannot preempt a stalled kernel
read. Unreadable/malformed ownership and still-live owners fail closed, retaining
session authority for retry. Only the six exact artifacts can be removed, and
external PIDs are never signaled. Legacy ADB cleanup is disabled alongside its
already-disabled capture launch. Historical orphan helpers are not terminated
automatically; their presence alone does not establish battery-drain causation.
Distinct controlled cleanup codes separate guardian wait/exit,
cancellation/deployment waits and recovery timeout/read failures.

Source-build tests use actual ScrcpyAdapter readers, gated READY, real
ReceiverLink TCP, initial queued media, steady20ms PCM, VFR/source-phase changes
and deliberately blocked TCP. All synthetic packets/PTS are retained when the
consumer recovers; each hard limit and sustained stall still rejects explicitly.
This fixes a demonstrated admission failure class and removes the normal
shell-recovery dependency, but does **not** prove which limit fired in that
historical live event or whether its guardian had already exited.

The reported receiver `INTERNAL / PEER_REPORTED` events can be explained by a
specific sender race: Stop set `stopping`, the next capture packet's sink check
threw generic `IOException("Session stopped")`, and the capture-reader callback
recorded that exception before the session entered `finally`. Its unclassified
message became a fatal `INTERNAL` frame. The actual historical stack was not
captured; the production sink/callback regression now reproduces the cancellation
path and verifies an orderly Stop instead.

Cancellation now has a distinct exception type. First terminal intent and the
first real failure are retained atomically: a genuine fault already present
before Stop still sends ERROR; expected cancellation EOF/socket/interrupt does
not become a new failure. Unexpected later faults/cleanup failures are retained
in sender diagnostics/UI, not silently discarded and not substituted for an
already committed orderly Stop. Receiver-requested Stop uses the same bounded
cancellation path and responds with SESSION_END.

Stop reports Stopping immediately and queues its terminal ahead of unsent media.
Explicit session termination abandons queued media/controls within the existing
budgets; it does not drop frames during normal streaming or relax congestion
limits. A writer exists after SESSION_REQUEST, so pending acceptance can receive
Stop too; PING/media do not run before acceptance. Before a request exists,
connection cancellation simply closes the pending socket. The sender does not
wait10s for an END acknowledgement.

The confirmed helper-latency hazard was closing a command's read stream before
destroying its process, while another thread could hold that stream's read
monitor until the10s command deadline. Only **owned non-capture command helpers**
are now destroyed before stream close; listing/probe commands use direct argv,
not an extra shell. Real capture supervisors are **never** destroyed this way:
private-controller EOF and native2s/200ms graceful fallback remain unchanged.
Late duplicate cancellation cannot kill an already-running cleanup command.
Privileged process cleanup is observed before joining capture readers, avoiding
an unnecessary join-before-grace interval and false reader-still-running error.

| Phase | Existing source ceiling / cancellation behavior |
|---|---|
| UI/stream loop | Stopping synchronous; no network/Binder wait in Stop itself; stream loop notices within its100ms sleep |
| Connect/HELLO/pending accept | Normal startup budget10s; Stop closes asynchronously, allowing up to500ms terminal delivery after a request rather than waiting for that deadline |
| Native capture teardown | Natural exit first; up to2s grace +200ms TERM interval before owned-child fallback. No5s heartbeat wait on explicit stdin EOF |
| Privileged cleanup | Shared cancellation wait2s; deployment wait2s only if active. Normal cleanup observes the owned guardian directly for up to6s, no shell scan. Handle-less recovery retains60 x100ms sleeps plus process/proc work under10s command deadline and1s exit check |
| Capture reader joins | Up to4 x250ms, after privileged cleanup, not before its codec grace |
| TCP completion/reader joins | At most500ms delivery wait, not END-ACK wait; closing watchdog2s (500ms polling); up to2 x1s joins after socket close |
| Binder/kernel stalls | No safe universal hard wall-clock bound; an in-flight platform Binder/FD call may remain blocked. Never cut codec grace, signal a group or report Idle early to hide it |

A conservative sum of configured wait ceilings without credit for overlap is
about19s for a pathological startup/handle-less recovery case; it is **not** a normal Stop
delay or a wall-clock guarantee. Most guards are absent/already satisfied during
healthy streaming Stop, and native/TCP work overlaps. No physical elapsed Stop
measurement was taken here. The fake blocked-command test completes within500ms
once destruction unblocks its close; real Binder/OEM behavior remains unverified.

Controlled `ScreenCopySender` diagnostics record fixed `stop_stage`, phase,
relative `elapsed_ms`, error origin and exception/cause **class only**, bounded
once per stage/origin/outcome. No peer text, media, session/track IDs, addresses
or secrets are logged. REQUESTED/STARTUP_CANCEL, bridge close, shell cleanup,
reader joins and FINISHED distinguish the remaining wait instead of guessing
Wi-Fi. The authoritative row/foreground lease remains Stopping until cleanup
returns; real cleanup failure stays explicit with recovery ownership retained.

### Pinned desktop Stop differential

The user reports official Windows scrcpy works on the **same OnePlus with
H.265**, including Stop. Our integration is therefore the primary suspect, not
a presumed universal OEM defect. The official executable/server version, CLI,
chosen encoder, audio/source/bitrate and display/crop/orientation in that working
run are unknown. This comparison uses **exact upstream3.3.4 source**, not a claim
that the working Windows configuration was identical. Installed99A1 behavior
below is the previously recorded source trace, not a newly extracted APK or
device forensics.

| Surface | Pinned upstream3.3.4 reference | Installed99A1 / earlier hardened sender | Current correction or remaining difference |
|---|---|---|---|
| Launch and identity | [`server.c:217`](https://github.com/Genymobile/scrcpy/blob/v3.3.4/app/src/server.c#L217) runs persistent `adb shell CLASSPATH=... app_process`; control defaults true | Shizuku shell UID2000 (or deliberate root opt-in), local owned supervisor; `control=false` | Same pinned server; **control=true only for a private EOF lifecycle**. No control messages, clipboard sync or power action; no lifecycle FD over Binder/LAN. Root still opt-in. [`options`](app/src/main/java/dev/screencopy/sender/CaptureBackend.kt#L184), [`private socket`](app/src/main/java/dev/screencopy/sender/ShellCaptureService.kt#L249) |
| Capture options | [`Options.java:35`](https://github.com/Genymobile/scrcpy/blob/v3.3.4/server/src/main/java/com/genymobile/scrcpy/Options.java#L35): default8Mbps, audio Opus128kbps, no size/FPS cap; cleanup true. Desktop options can override | Defaults8Mbps, size1920/FPS60, Opus192kbps/output, automatic encoders, display0/no crop/orientation0; selected alternatives retained. B-frames forced0, no downsizing | Preserved exactly; not silently matched to unknown Windows settings. Cleanup true; power_on/clipboard_autosync false intentionally |
| Tunnel/startup | [`DesktopConnection:67`](https://github.com/Genymobile/scrcpy/blob/v3.3.4/server/src/main/java/com/genymobile/scrcpy/device/DesktopConnection.java#L67): forward accept video, audio, control; only first socket gets dummy;64-byte device metadata after all accepts. Desktop normally tries reverse; forward is supported | Forced forward/direct privileged Unix sockets; video/audio order and dummy/header parsing match, but no third channel | Connect private lifecycle after last enabled media and **before** returning that media FD; audio-off still opens lifecycle after video. No new track/wire metadata. [`startup`](app/src/main/java/dev/screencopy/sender/CaptureBackend.kt#L95), [`service`](app/src/main/java/dev/screencopy/sender/ShellCaptureService.kt#L202) |
| Stop channel operation | [`server.c:1111`](https://github.com/Genymobile/scrcpy/blob/v3.3.4/app/src/server.c#L1111) interrupts video, audio **and control**. [`net.c:240`](https://github.com/Genymobile/scrcpy/blob/v3.3.4/app/src/util/net.c#L240)/net.h: Windows closes sockets, Linux SHUT_RDWR | Only media shutdown; app/privileged duplicates close separately. Old guardian immediately signals group; hardened version waits but still omitted controller EOF | Interrupt video/audio/private lifecycle, then guardian stdin. Direct Unix SHUT_RDWR+close yields controller EOF; no claim identical ADB transport implementation. [`ordering`](app/src/main/java/dev/screencopy/sender/CaptureCancellation.kt#L7) |
| Idle producer / normal teardown | [`Controller:235`](https://github.com/Genymobile/scrcpy/blob/v3.3.4/server/src/main/java/com/genymobile/scrcpy/control/Controller.java#L235) reports fatal completion on EOF; [`Server:60`](https://github.com/Genymobile/scrcpy/blob/v3.3.4/server/src/main/java/com/genymobile/scrcpy/Server.java#L60) quits Looper; stop processors, shutdown, join. [`CaptureReset:22`](https://github.com/Genymobile/scrcpy/blob/v3.3.4/server/src/main/java/com/genymobile/scrcpy/video/CaptureReset.java#L22) signals video EOS | No controller: shutdown requires a subsequent media write/failure to reach completion; a blocked/idle encoder can instead hit guardian fallback. Repeat-frame requests may normally produce output, so this is not proof of the actual stall | Private EOF drives **the upstream processor-stop/EOS/join mechanism**, even with zero new video/audio packets. Not another supervisor rewrite or timeout increase |
| Audio and client workers | Desktop normally does not require audio and can continue video when upstream disables audio; demuxers exit on socket EOS. Server still stops **both** enabled processors before joining them | Enabled audio deliberately mandatory, no codec/quality fallback; per-track READY +8MiB/500ms fair queues. Backend shuts sockets before joining app readers, off UI/ownership locks | Preserved as requested. Private EOF does not wait for an audio packet/silence to finish or for a client reader join; it wakes server completion first. Audio failure may still explicitly end the session |
| Process fallback and joins | [`server.c:1125`](https://github.com/Genymobile/scrcpy/blob/v3.3.4/app/src/server.c#L1125) waits1s for observed local adb process, then terminates it if needed; [`scrcpy.c:1011`](https://github.com/Genymobile/scrcpy/blob/v3.3.4/app/src/scrcpy.c#L1011) joins demuxers after socket interruption | Old shell group TERM/sleep/KILL; hardened native direct-child retention +2s graceful/200ms TERM interval, off-UI cleanup | Existing native deadlines/positive-owned-child-only fallback **unchanged**; not equivalent to killing a Windows adb process. Controller EOF is now the primary teardown trigger |
| Shell stdin / cleanup / owner death | Scrcpy server does not use caller stdin as a Stop command. [`CleanUp:142`](https://github.com/Genymobile/scrcpy/blob/v3.3.4/server/src/main/java/com/genymobile/scrcpy/CleanUp.java#L142) has its own restoration-helper pipe; cleanup is not an encoder-stop request | Guardian stdin is our watchdog, not the upstream Stop API. UserService destroy/owner death closes its FDs and triggers native parent-death handling | Kernel close now also closes the private lifecycle socket, waking the same upstream completion path; native ownership protection and scoped cancellation remain |

Most likely **integration** chain supported by source: missing controller EOF
left Stop dependent on future codec output; old immediate guardian group signals
could then interrupt HEVC codec teardown rather than request upstream EOS.
The earlier2s wait removed immediate signaling but not the missing wakeup. This
patch eliminates that specific missing mechanism, without claiming it proves the
physical panic chain. Other differing settings/encoder/version remain unmeasured.
Windows success rules out treating failure as universally unavoidable, not every
configuration-dependent vendor interaction.

`CaptureLifecycleTest` executes the production channel-interruption helper over
real TCP sockets with both media producers deliberately idle: media-only shutdown
does not wake the lifecycle reader; adding its EOF stops the modeled processors
without any control byte/new media packet. Video-only/repeated Stop and owner FD
close are covered. These callbacks model pinned Completion, **not Android
MediaCodec execution**. An actual Android socket-pair lifecycle EOF regression is
compiled, not executed. Native retained-child/no-signal graceful-exit tests remain.
The lifecycle-only stage passed22 targeted and141 full host tests; current
combined totals are recorded above. No physical HEVC Stop fix is claimed.

### Audio failure investigation (not a device-fix claim)

The user reports intermittent cancellation/playback failure across audio codecs.
Source inspection of pinned Streamer/AudioConfig/AudioRecordReader and sender
adapters confirms codec IDs, canonical OpusHead, AAC-LC ASC/raw AUs,48k stereo
s16le PCM,12-byte packet framing and unmodified monotonic microsecond PTS.
Fresh AudioRecord timestamp observations may differ from sample-count extrapolation;
receivers must not assume exact sample-count cadence. All enabled tracks remain
mandatory: no audio disable or video-only fallback. Initialized silence does not
have a capture-reader deadline. The8MiB/500ms fair queue still fails explicitly on
congestion; receiver readiness/consumption delays may therefore cancel sharing.

Receiver fatal ERROR code/message is retained through cleanup and surfaced in
sender UI. Unexpected scrcpy EOF now carries a track-specific AUDIO_FAILED or
CAPTURE_FAILED reason rather than an unhelpful generic EOF message. Synthetic
all-codec tests cover CSD/PTS/readiness/alignment/disable failures, not real sound.
Receiver owner reproduced a codec-independent production AudioPlayer defect:
a valid6ms monotonic timestamp refresh triggered a fatal exact-sample-continuity
check. Its owner reports a timing correction with targeted regressions while
retaining large-discontinuity/output-failure checks. That confirmed receiver
defect does not prove it caused the user's session; no receiver code was changed
in this repository and no sender timestamp rebasing was added.
No current runtime trace here proves whether the reported cancellation originated
in sender capture, receiver output/clock/route, or backpressure; no device audio
fix or HEVC-panic fix is claimed.

## References / notices

[Frozen LAN v3 protocol](PROTOCOL.md) · [design](DESIGN.md) ·
[third-party obligations](THIRD_PARTY.md) · [Shizuku UserService/API guide](https://github.com/RikkaApps/Shizuku-API)

Shizuku API/provider MIT notices are bundled. Legacy crypto/SPAKE2 obligations
remain while that code is bundled; codec patents, app-store policy and security
review are separate from builds.
