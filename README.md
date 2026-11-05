# ScrcpySend

With an updated receiver that negotiates `videoRotation`, phone rotation keeps
the natural-coordinate crop and encoder dimensions fixed. Rotation travels as
per-picture metadata and is applied by the receiver without restarting codecs.
Saved capture-orientation choices remain manual presentation overrides. Older
receivers retain legacy rotation/reconfiguration behavior; update both apps to
use fixed-raster rotation.
On updated receivers, Automatic receiver orientation also follows the displayed
video aspect; explicit Portrait/Landscape receiver choices still override it.

Android screen/audio sender for [ScrcpyRecv](https://github.com/arisewanggithub/ScrcpyRecv)
on a trusted private LAN. Capture uses Android's public **MediaProjection**,
**MediaCodec** and **AudioRecord** APIs. No developer options, ADB, Shizuku,
root, downloaded server, or privileged process is needed to use the app.
These are independent applications, not official scrcpy applications.

The application ID remains `dev.screencopy.sender`. Custom TCP uses `SCPY`
wire protocol **version 3**, independent of application release versions.

## Install and share

1. Install both apps normally and connect them to the same reachable LAN.
2. Leave ScrcpyRecv visible. Select its discovered row in ScrcpySend and press **Play**.
3. If audio is enabled, allow Android's audio-recording permission. Then approve
   Android's screen-sharing dialog. Consent is required for every new sharing
   session; cancelling never starts capture.
4. Stop from the receiver row, the ongoing notification, or Android's sharing
   controls. Revoking consent or locking the device can end sharing. A new
   attempt requires a new user action and consent.

The foreground service owns the projection and encoder resources. Capture
starts only after the receiver accepts the requested tracks. Returning home
does not stop an active cast. Lost network, encoder errors and consent
revocation fail explicitly; there is no hidden backend or codec fallback.

## Capture and settings

- H.264, H.265 and AV1 use hardware encoders when available. Unsupported
  encoder, resolution, rate and bitrate requests fail explicitly.
- Negotiated fixed-natural video uses a GPU SurfaceTexture/EGL transform.
  Legacy untransformed full-display video feeds a MediaCodec input Surface directly:
  no CPU bitmap capture or decode/re-encode pipeline.
- The default display is supported. MediaProjection does not provide the old
  privileged arbitrary-display capture API. An unavailable saved display must
  be corrected explicitly.
- Settings cover video codec/encoder, bitrate, size, maximum FPS, natural-display
  crop, capture/receiver orientation, and audio. Maximum FPS offers 15, 24, 25,
  30, 48, 50, 60, 90 and 120. It limits capture, not display refresh.
- Custom TCP is fixed. Legacy unavailable transports require Settings + Apply.
  Legacy nonstandard FPS values require an explicit supported selection.
- Advanced tuning is not exposed. Previously saved codec options remain
  authoritative until Reset defaults is explicitly applied.
- Apply affects the next cast. Active settings remain immutable. Full-screen
  size/crop switching renegotiates with the same receiver and reuses the
  existing projection's VirtualDisplay instead of reusing a consent token to
  create a second display.
- With negotiated rotation, only GPU source coordinates and presentation metadata
  change; the encoder, decoder and video generation stay unchanged. Legacy peers
  still adapt encoded dimensions and announce a new video generation, draining old
  video before replacement. Audio and the sharing grant stay active in either mode.
  Natural-display crop and manual orientation remain authoritative.

### Audio restrictions

New settings default to **playback** capture and **RAW PCM**: stereo, 48 kHz,
16-bit little endian (1.536 Mbps). Opus and AAC-LC encoding are also supported.
Partial AudioRecord reads are batched into 20 ms RAW packets without changing
samples or their source clock. TCP framing uses bulk writes with a bounded
header/payload prefix instead of many tiny header writes.
Mic captures the microphone instead. Legacy `output` uses the public playback
API, not privileged system-output capture; this is explained in Settings.

Playback capture only includes eligible media/game/unknown audio usages from
apps in the same profile that permit capture. Apps may opt out; calls and
protected content are not captured. Android can return silence for excluded
audio; the sender cannot override another app's policy or reliably distinguish
silence from an opted-out player. Playback capture does not intentionally mute
the phone by itself. During playback-audio sharing, the sender mutes the local
media stream and restores it on Stop, error or permission revocation. Already
muted/zero-volume media stays untouched; microphone and audio-off modes do not
mute playback. The media volume index is never rewritten. If the process is
killed before cleanup, the next app launch retries restoration of its owned
mute; users can also unmute manually. User testing on the Galaxy confirmed that
capture stays audible while the sender is muted; this can vary on other devices.
All audio modes require `RECORD_AUDIO`; microphone mode also uses
the microphone foreground-service type.

## Trust and limitations

Discovery, control and media are **unauthenticated and unencrypted**. Use only a
trusted private LAN, not the internet. Receiver names are labels: the actual
UDP-source IP and TCP port select the peer. Wi-Fi client isolation may prevent
discovery or connections. No remote input, clipboard, or internet traversal is
provided.

Protected/secure windows may be blank. Android's system capture indicators,
consent prompts and foreground-service restrictions are intentional.
Similar performance to the previous backend is a target, not a measured
guarantee. Hardware codec behavior, thermal/power cost, long-run A/V sync and
glass-to-glass latency need device measurements. Real H.265 capture/Stop/recast
safety remains unverified; do not automate it as an acceptance shortcut.

## Build and verification

Use **JDK21**, **Android SDK36 / Build Tools36.0.0**, and the pinned
**Gradle9.8.1 / AGP9.4.1** toolchain. Minimum SDK30, target SDK35, JVM17.
Native SDKs, Node, pairing archives and server downloads are no longer needed.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console plain
.\tools\test-publication.ps1
```

JVM tests exercise framing, bounded queues, codec metadata, Android Opus
initialization, crop/orientation coordinates, ownership and cancellation.
Device UI fixtures do not start capture. The separately selected
`ProjectionCaptureDeviceTest` requires `manualProjectionConsent=true` and a
person to approve Android's real permission dialogs; it tests H.264 decode,
playback PCM, Custom TCP delivery, timestamp compatibility and cleanup. It is
not part of unattended CI.

### Release optimization

Release builds use R8 and resource shrinking; debug does not. No privileged
reflection/JNI roots or crypto warning suppressions are required.

```powershell
.\gradlew.bat :app:lintRelease :app:assembleRelease -PrequireReleaseSigning=false --no-daemon --console plain
.\gradlew.bat :app:assembleRelease :release-check:assembleRelease -PreleaseSmokeTest=true -PrequireReleaseSigning=false --no-daemon --console plain
```

Smoke mode rejects production signing configuration and uses a disposable
test signer. The standalone harness verifies manifest permissions/service types,
consent-intent resolution, codec metadata, absence of native/server assets, and
actual resources/launcher. It never approves consent or starts capture.
PR CI must pass before publication; retained source-tree proof ties a future
signed release to tested code. See [UPDATES.md](UPDATES.md).

## Updates, source export and licensing

The updater opens official GitHub APK downloads in the external browser.
Android owns downloading and install confirmation; the app does not silently
install updates or stop capture. Preserve the package ID and permanent signing
key for in-place upgrades.

`.\tools\export-publication.ps1` produces a deterministic source inventory;
`-Destination .\publication-snapshot` copies into a new directory. Local SDK
settings, build outputs, devices and signing material are excluded.
No license/reuse grant has been selected for original application code.
Preserve remaining third-party notices when distributing APKs.

See [DESIGN.md](DESIGN.md), [PROTOCOL.md](PROTOCOL.md) and
[THIRD_PARTY.md](THIRD_PARTY.md).
