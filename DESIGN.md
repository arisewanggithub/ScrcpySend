# ScrcpySend architecture

ScrcpySend captures Android screen/audio through a permission-gated Shizuku
service and sends it to ScrcpyRecv on a trusted LAN. Application ID
`dev.screencopy.sender` and `SCPY` framing are stable compatibility identifiers.
Wire protocol version 3 is separate from app release versions.
Exact schemas/limits are in [PROTOCOL.md](PROTOCOL.md);
setup, build profiles and release gates are in [README.md](README.md).

## Trust and endpoint ownership

Discovery, control and media are unauthenticated and unencrypted. SRT AES is
disabled. Receiver names are labels: actual UDP-source IP plus advertised TCP
port identifies the selected endpoint. Discovery refreshes names/availability,
expires idle rows and resets after network changes. Matching names cannot
redirect an active request. Trusted private LAN access is required.

One positive session ID owns the accepted cast. The active row remains pinned
and actionable through BUSY advertisements and discovery expiry until its own
cleanup finishes. A service-adopted request lease prevents late Activity,
discovery or previous-session callbacks from retiring another request.
Acceptance precedes capture; unsupported transport/configuration requests fail
without silent quality, codec, audio or preference changes.

## Capture boundary

```text
Activity / applied immutable request
  -> Shizuku Binder + explicit permission / identity verification
  -> non-daemon UserService / owned read-only artifact FDs
  -> checksum-pinned scrcpy-server3.3.4 + native supervisor
  -> owned video/audio FDs
  -> negotiated SRT or custom TCP sink
```

Shizuku API/provider13.1.5 provides generated AIDL and a protected provider.
The service verifies actual shell UID2000; root UID0 requires explicit consent.
Unknown/ordinary UIDs fail. Readiness includes deployment integrity,
encoder/display listing and owned cleanup, not merely a permission check.
Structured validated settings are not arbitrary shell command text.

The first-party single-threaded native supervisor retains kernel child/wait
ownership until termination/reap. Teardown affects only owned resources, not
unrelated processes. The app and service close their respective media FD
copies even if the Binder peer dies.

A private lifecycle socket uses the pinned backend's control EOF completion
mechanism. It carries no input/clipboard/power messages and is never exposed
as a LAN control channel or Binder media track. Media remains video/audio only.
Large payloads travel through FDs, not Binder transactions.

## SRT / fragmented MP4

```text
wire protocol version 3 request + explicit SRT SESSION_ACCEPT
  -> libsrt listener pinned to actual control peer/session
  -> bounded mux actor / Media3 FragmentedMp4Muxer1.11.1
  -> standard fMP4 / cancellable SRT transport
  -> ScrcpyRecv Media3 extraction/renderers
```

libsrt1.5.7 is transport only. Its pinned sources, checksum and MPL notices
remain distributed. Media3 muxer sources are pinned and prepared with
context-checked changes for fragment flush/timestamps and codec metadata;
upstream headers/notices are retained. No custom replacement MP4 demuxer or
receiver playback clock is introduced.

H.264/H.265/AV1 and Opus/AAC-LC/audio-off are admitted explicitly. PCM is
unsupported in this profile. AVC `avc3` uses actual in-band SPS/PPS before a
real IDR. HEVC `hev1`/hvcC uses complete actual VPS/SPS/PPS and authentic IRAP
flags. AV1 uses actual configuration/sequence OBUs. Metadata, payloads,
timestamps and key flags are not fabricated.

Audio duration parsing preserves Opus/AAC FIFO and a rational timeline anchored
once to source time; no decode/re-encode, resampling or packet sorting occurs.
The common first-track origin preserves audio/video offsets. A bounded periodic
flush permits static-video/audio progress without waiting for another keyframe.
Admission includes actor-queued and mux-pending samples: 8MiB/240 video frames
and 1MiB/100 audio packets. Overflow or unsupported configuration changes fail
explicitly. Late-packet dropping is disabled to protect container bytes.

AVC/HEVC configuration changes require complete real metadata before the
corresponding sync picture. AV1/audio configuration changes require a supported
new session. This does not certify hardware decoder adaptation or rotation.

## Custom TCP compatibility

The default local profile uses wire protocol version 3 bounded framing, explicit stream configuration
and readiness, internal session/track generations and negotiated codec metadata.
SRT-enabled builds retain this selectable path. Present unsupported transports
never choose custom TCP implicitly.

ScrcpyRecv validates real decoder support and track readiness. Source settings,
packet boundaries, canonical OpusHead and original payload order remain
authoritative. Requested audio is not silently disabled and no hidden recrop,
codec substitution or capture retry is performed.

## Lifecycle and UI

Play freezes applied settings and adopts a request lease. The foreground
service owns connecting/playing/stopping state; an Activity observes current
service state rather than a saved "playing" preference. Settings stay disabled
through startup, playback and teardown. A detached UI callback cannot clear
service-owned state or act on a newer receiver.

Stop, permission loss, network failure or backend termination retires only
matching resources. Native transport is closed first to wake blocked I/O,
then capture readers/mux work are joined with bounded ownership-aware cleanup.
The first terminal outcome wins over late teardown failures. Retry is explicit
and only becomes available after cleanup.

The runtime full-screen/saved-size choice does not rewrite saved settings.
Changing its effective request explicitly authorizes owned Stop followed by
new Play after receiver acknowledgement and successful cleanup. Cancellation
or failure prevents automatic retry. Visible screen-on and notification actions
use the same request lease and ordinary Android permission/lifecycle rules.

## Updates, licensing and verification

[UPDATES.md](UPDATES.md) defines the independent HTTPS stable-release updater,
single-signer/hash/package validation, scoped FileProvider and explicit
permission/install/retry actions. Checking/downloading never stops capture,
and installation is guarded by final idle-cast checks.

CI validates both build profiles and selects only the remote-update UI class
on a disposable API35 emulator. It does not run real screen capture/H.265 Stop
or claim hardware acceptance. H.265 capture/Stop/recast safety remains
unverified because kernel-panic risk has been reported; synthetic tests do
not resolve it. Hardware codec support, rotation, audio routes, latency and
endurance require measurements.

Original application code has no selected reuse grant. Preserve existing
copyright and third-party licenses/source materials. Binary publication requires
manual distribution approval and signing; passing tests is not legal approval.
See [THIRD_PARTY.md](THIRD_PARTY.md), including LGPL corresponding application
sources/build scripts and relinking/recombination obligations.
