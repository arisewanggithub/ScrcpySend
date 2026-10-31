# ScrcpySend

Android screen/audio sender for [ScrcpyRecv](https://github.com/arisewanggithub/ScrcpyRecv)
on a trusted family LAN. ScrcpySend uses Shizuku and the pinned scrcpy backend
for capture, with **SRT / fragmented MP4** transport and a **custom TCP**
compatibility option. These are independent applications, not official scrcpy
applications.

The Android application ID is `dev.screencopy.sender`. The on-wire
`SCPY` framing uses **wire protocol version 3**, a compatibility discriminator
separate from application release versions.

## Architecture and supported formats

- Shizuku API/provider **13.1.5** supplies a permission-gated, non-daemon
  UserService. It verifies shell identity, deploys the checksum-pinned
  **scrcpy-server 3.3.4**, and checks available encoders/displays before Play.
  A first-party native supervisor owns capture-process lifetime and cleanup.
- SRT uses **libsrt 1.5.7** as transport only. The sender's **Media3 1.11.1**
  fragmented MP4 muxer produces standard container data; ScrcpyRecv uses
  Media3 ExoPlayer for extraction, decoding and playback timing.
- SRT supports **H.264, H.265 or AV1**, with **Opus, AAC-LC or audio off**.
  PCM is available through custom TCP, not SRT. Unsupported requests fail
  explicitly without changing the user's codec, quality or audio selection.
- The SRT build includes custom TCP compatibility. Local builds enable SRT
  with `-PsrtTrial=true`; the default build profile is custom TCP.
- SRT fragments flush on a bounded timer, including static-video/audio-only
  progress. The configured 100 ms SRT latency is not a measured end-to-end
  latency guarantee.

See [DESIGN.md](DESIGN.md) for ownership and pipeline details and
[PROTOCOL.md](PROTOCOL.md) for exact interoperability schemas and bounds.

## Trust and interoperability

Use a reachable **trusted private LAN**. Discovery, control and media do not
authenticate peers or encrypt the cast. SRT encryption is disabled. Names are
labels, not identities: discovery selects the actual UDP-source IP and TCP
port, never a name-based redirect. Do not expose the service to the internet
or assume protection against LAN observers or spoofed discovery.

Both applications must implement **wire protocol version 3**. Positive internal session
IDs and track generations remain mandatory; they are not user pairing codes.
Only one receiver endpoint is active, and explicit acceptance precedes capture.
Present unsupported transports/configurations are rejected, not silently
interpreted as custom TCP. Wi-Fi/AP client isolation may prevent discovery
or connections.

## Install and send

1. Install ScrcpySend and ScrcpyRecv normally. Connect them to the same
   reachable LAN and leave ScrcpyRecv visible.
2. Install [Shizuku](https://shizuku.rikka.app/download/) yourself and follow
   its [official setup guide](https://shizuku.rikka.app/guide/setup/).
   Shell-mode operation on Android 11+ does not require root. Shizuku must
   be started again after reboot.
3. Allow ScrcpySend in Shizuku. The app checks Binder, permission, shell UID,
   backend deployment and capability readiness automatically when available.
   Failure offers explicit recovery; the app does not install/start Shizuku,
   approve permissions or change Developer options on your behalf.
4. Choose a discovered receiver and **Play**. Its row shows Connecting, Stop
   while playing, and Stopping until owned cleanup finishes. Other receivers
   are disabled while a cast is active; busy endpoints are identified.
5. Stop using the receiver row or ongoing cast notification. Retry is available
   only after cleanup. Old callbacks cannot stop or clear a newer request.

Root mode is not required. If Shizuku runs as root, the app requires explicit
root-mode consent; ordinary or unknown UIDs are rejected.

### Settings and presentation

Settings cover display, size/crop, orientation, FPS, bitrates, codecs, encoders,
audio and validated typed options. Apply saves the next request; an active
cast keeps its immutable applied configuration. Settings are disabled during
startup, playback and teardown. Unsupported selections fail without a hidden
fallback or automatic preference rewrite.

**Ignore size and crop (full screen)** is a runtime choice shared by the home
screen and notification. It does not rewrite saved settings. Switching between
full-screen and saved size/crop during a cast explicitly performs owned Stop
and a new Play after acknowledgement/cleanup, rather than changing a live
encoder. Failures require a fresh user action.

The visible sender screen stays awake while its request is active. Notification
actions require the normal Android notification permission/channel; the app
does not change system power, permission or notification settings.

## Current limitations

- Real-device **H.265 capture/Stop/recast safety remains unverified** because
  kernel-panic risk has been reported. JVM tests, synthetic codec input and
  successful compilation are not device-safety evidence. Do not automate real
  H.265 screen capture/Stop as an acceptance shortcut.
- H.264/H.265 in-band configuration handling does not imply every device's
  rotation/decoder adaptation works. AV1/audio configuration changes require a
  new supported session; AAC/AV1 hardware playback needs device validation.
- Decoder support, audio routes, long-run A/V sync, interruption cleanup and
  glass-to-glass latency require hardware measurements. No performance or
  Android TV/store certification is claimed.
- No remote input injection, clipboard control or internet traversal is
  provided by the LAN protocol.

## Build and verification

Use **JDK21**, **Node22**, public **Android SDK36 / Build Tools36.0.0**,
**NDK27.2.12479018**, and **CMake3.22.1**. The **Gradle9.8.1** wrapper is
checksum-pinned; **AGP9.4.1** provides built-in Kotlin support, with JVM17
targets. App, streaming-muxer and libadb explicitly compile against SDK36;
the app's minimum SDK is30 and target SDK is35.
Media3 1.11.1's AAR metadata requires minimum compile SDK36.
Test fixture/source reads use `Files.readAllBytes` with strict UTF-8 decoding;
the SDK36 API stubs do not declare the JDK's `Files.readString` method.

From your checkout, with `JAVA_HOME` set to JDK21:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk" # Or your existing SDK location.
.\gradlew.bat :app:checkDebugAarMetadata :app:testDebugUnitTest :streaming-muxer:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest -PsrtTrial=true --no-daemon --console plain
```

Also validate the default profile with `-PsrtTrial=false`. Override Node with
`-PnodeExecutable=C:\path\to\node.exe` or `ORG_GRADLE_PROJECT_nodeExecutable`.
The debug app is `app\build\outputs\apk\debug\app-debug.apk`.
The optional `.\tools\interop.ps1 -ReceiverRoot ..\ScrcpyRecv` reads an already
built receiver classes JAR; `SCRCPY_RECV_ROOT` can override its sibling default.

CI checks JVM tests, lint, debug builds and unsigned optimized release assembly
in both transport profiles. Release assembly runs R8/resource shrinking before
the publisher can use signing secrets; it does not prove runtime compatibility.
Its disposable Linux **API35 x86_64** emulator runs only
`dev.scrcpy.update.AppUpdateRemoteUiTest`, not capture/Shizuku/codec acceptance
tests. APKs are built before booting that emulator. Failure/timeout preserves
partial instrumentation output, targeted test logcat and the original exit
status; that emulator job uploads only disposable UI diagnostics. Build and test results
must come from the current run, not historical totals.
CI also retains app/streaming-muxer JUnit XML when available for exact failure
messages and stacks; this does not upload APKs or signing material.

### Release optimization

Only `release` enables R8 code optimization/obfuscation and resource shrinking,
using `proguard-android-optimize.txt` plus targeted JNI, Shizuku UserService and
ADB identity-provider rules. Debug and its test fixtures are unchanged.
Upstream AAR consumer rules are reused; vendored libadb supplies the missing
SPAKE2 JNI registration rule. No whole app/updater/Media3/crypto keep or blanket
warning suppression is added. Two exact missing platform-private parameter
types are suppressed for Conscrypt's unused pre-API22 socket adapters, below
minSdk30; bundled modern Conscrypt remains intact.
Required backend, supervisor, licenses and source assets,
ABIs and native link options are unchanged.

For runner validation, in a shell with **no release-signing environment variables
or Gradle signing properties configured**, run in both transport profiles:

```powershell
.\tools\test-publication.ps1
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug :app:lintRelease :app:assembleRelease -PsrtTrial=true -PrequireReleaseSigning=false --no-daemon --console plain
```

Repeat with `-PsrtTrial=false`. `requireReleaseSigning=false` permits unsigned
assembly; it does **not** override any supplied signing configuration.
Inspect `app\build\outputs\apk\release\app-release-unsigned.apk` and
`app\build\outputs\mapping\release\mapping.txt`: confirm JNI declaring/method
names, Shizuku constructor, BC mappings/SPIs, manifest components, absence of
`UpdateTestActivity`, and required assets/resources. Check native JNI exports
and 16 KiB ELF alignment for all three SRT ABIs. AGP's release CMake variant
normally uses optimized `RelWithDebInfo`; confirm its generated flags rather
than forcing global `-O3`/LTO. The supervisor already builds with `-O2`.

Unsigned assembly and the existing **debug-only** updater emulator tests are
not optimized-release smoke tests. The separate `optimized-native-smoke` CI job
builds the actual non-debuggable release with R8/resource shrinking and a
disposable test signer. Publication waits for that job as well as existing checks.
See [UPDATES.md](UPDATES.md) for coverage, limitations and mapping retention.

To assemble its safe APK cohort only (no emulator/device operation), from a
shell with no production signing variables/properties:

```powershell
.\gradlew.bat :app:assembleRelease :release-check:assembleRelease -PreleaseSmokeTest=true -PsrtTrial=true -PrequireReleaseSigning=false --no-daemon --console plain
.\tools\verify-release-smoke-apks.ps1 -App app\build\outputs\apk\release\app-release.apk -TestApk release-check\build\outputs\apk\release\release-check-release.apk -ApplicationId dev.screencopy.sender
```

The strict property defaults to false. Smoke mode rejects publication signing,
any supplied production-signing configuration (even empty/masked paths), and
non-SRT builds. It changes only the app release signer and conditionally includes
the `release-check` module; the app's normal debug AndroidTest cohort is untouched.
The standalone `com.android.test` module uses AGP's self-instrumenting mode:
no app compile dependency/mapping or runtime dependency subtraction. Its Kotlin
runtime is independently minified/repackaged, not borrowed from the optimized app.
All app code is invoked through the actual target package's distinct classloader.
The harness contains no app implementations or native libraries.

**Optimized coverage: eight tests** — three actual SRT JNI loopback/cancellation/
stale-handle tests, four reflected identity/SPAKE2/Conscrypt/Binder tests, and one
actual manifest-resource/launcher check. CI separately launches the real app
process without selecting Play and verifies it survives startup.
**Separate debug coverage: six original `NativeSrtTest` tests**, including the
high-level muxer/rotation/payload assertions, run afterward on a fresh emulator
with debug APKs. They are not claimed as optimized coverage. No hardware fixture
or capture test is selected in either cohort.

Only the test module supplies Error Prone annotations 2.41.0 and suppresses the
compiler-only `javax.lang.model.element.Modifier` reference. No production Kotlin
keeps, test hooks, JDK compiler stubs or production dependencies are added.
Standalone AGP test components consume `testProguardFiles`, not ordinary
`proguardFiles`; optimize defaults and the custom harness rules use that API.
DEX policy rejects any app implementation, duplicate class definition or native
library in the harness.

## Updates and signed releases

The D-pad-friendly updater checks the repository's latest stable release.
Checking/downloading does not stop an active cast. Permission and installation
require explicit user actions; installer cancellation/failure remains
recoverable without automatically reinstalling. See [UPDATES.md](UPDATES.md).

CI supplies `releaseVersionCode=10000+github.run_number` and a
`releaseVersionName` from the stable tag (without `v`) or `dev-<short-sha>`.
Local defaults are versionCode1/versionName1.0. Preserve run-number continuity; a
replacement workflow/repository must choose an offset above installed versions.

Stable releases use `vMAJOR.MINOR.PATCH` tags. Main prereleases additionally
require `ENABLE_MAIN_PRERELEASES=true` and are excluded from latest-stable
automatic updates. **Both binary release paths are blocked by default** until
the user manually sets `BINARY_RELEASE_APPROVED=true` after reviewing the
original-code license/distribution choice and third-party obligations,
including LGPL corresponding application source/build scripts and
relinking/recombination materials in [THIRD_PARTY.md](THIRD_PARTY.md).
Adding a root LICENSE or configuring signing secrets alone is not approval
or a compliance guarantee.

Publication also requires manually configured Actions secrets:
`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEY_ALIAS`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_PASSWORD`. Approval is checked before decoding signing secrets.
The runner privately restores and cleans the keystore; no unsigned/debug
fallback, signing-key upload or automatic secret configuration is provided.
Manual signed builds use `ANDROID_KEYSTORE_PATH` or
`-PreleaseSigningKeystorePath=...`, the three signing environment variables,
and `-PrequireReleaseSigning=true`. Debug development needs no release key.

In-place updates require the **same application ID and signing certificate**
and a strictly higher versionCode. Debug-key installations need the matching
key for continuity, or an explicitly planned one-time migration/backup.
Changing certificates cannot preserve an in-place installation; never
automatically uninstall to bypass this requirement.

Release assets are exactly **ScrcpySend.apk** and **update.json**. Mandatory
manifest fields are `schema:1`, `applicationId:"dev.screencopy.sender"`,
integer `versionCode`, `versionName`, `apkAssetName:"ScrcpySend.apk"`,
lowercase 64-hex `sha256`, lowercase 64-hex `signingCertificateSha256`, and
`protocolVersion:3`. The manifest generator verifies the actual APK signature,
package and version. The receiver uses the same schema with
`org.screencopy.receiver` and `ScrcpyRecv.apk`.

## Source export and licensing

Run `.\tools\export-publication.ps1` for a deterministic path/size/SHA-256
inventory; add `-Destination .\publication-snapshot` to copy into a **new**
directory. Re-run and review after changes stabilize. Required hidden CI files,
the wrapper JAR, pinned backend/source archives, updater sources and licensing
materials remain included. History, local SDK/IDE/device settings, caches,
outputs and signing material are excluded even if tracked.

No root license or reuse grant has been selected for the original application
code; public visibility alone does not grant permission to reuse it. Existing
copyright notices remain unchanged. Third-party components retain their own
licenses and distribution obligations; technical export does not invent or
require a new original-code license.

Preserve **all** bundled notices and matching complete sources when distributing
APKs. The dependency graph is not Apache-only: libadb, SPAKE2, SRT, Media3,
Shizuku and other components have their own terms. The APK includes the pinned
complete SPAKE2 source bundle, and the repository retains modified SRT sources
and source/build materials. Distributors must actually supply required
corresponding-source/recombination materials, not merely reference a build.
Codec patents, app-store policy and legal/security review remain separate
from successful tests. See [THIRD_PARTY.md](THIRD_PARTY.md) and
[bundled notices](app/src/main/assets/NOTICE.txt).
