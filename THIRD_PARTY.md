# Dependencies and redistribution

ScrcpySend's primary capture backend uses official **Shizuku API/provider13.1.5**, MIT,
published by RikkaApps at Maven Central. Its API/aidl/shared/provider components
are covered by the bundled `assets/licenses/Shizuku-API-MIT.txt`. Official guide:
https://github.com/RikkaApps/Shizuku-API (UserService, permission and provider);
user setup: https://shizuku.rikka.app/guide/setup/.
Shizuku Manager/server itself is installed separately by the user, not bundled.
No deprecated `Shizuku.newProcess` is used.

Bundled ADB utility code is **not** the primary capture backend. Its dependencies,
local identity/key storage and redistribution obligations apply to that utility
code; there is no hidden ADB fallback behind the Shizuku UI.

The bundled ADB implementation supports pairing.
`AbsAdbConnectionManager.pair` delegates to
SPAKE2 + TLS-exporter-bound pairing in `PairingConnectionCtx`; connection uses
ADB STLS/TLS 1.3. These implementation details do not establish device pairing success.
The wrapper permits only loopback and rejects legacy plaintext ADB connections.
ADB's own pairing trust is required for the ADB utility. ScrcpySend / ScrcpyRecv
control and media traffic is unencrypted on a trusted family LAN; it has no TLS certificates or saved peer
credentials. Conscrypt, Bouncy Castle and SPAKE2 remain for local Android ADB,
not ScrcpySend / ScrcpyRecv peer trust.

| Component | Pin | Terms |
|---|---|---|
| Shizuku API/provider/aidl/shared | 13.1.5 | MIT |
| scrcpy-server | 3.3.4, official release SHA-256 in app build | Apache-2.0 |
| libadb-android | 3.1.1 / c849886ebc6d48e7b46d967e78a6bb65c90c3b74 | Apache-2.0 alternative + BSD/MIT portions |
| spake2-android | 2.2.1 / 7615ddd680b990e14513ebb66eac4cb0dbf82464 | **LGPL-3.0**, EdDSA CC0 portions |
| Conscrypt Android | 2.5.3 | Apache-2.0 + bundled BoringSSL notices |
| Bouncy Castle bcprov/bcpkix/bcutil | 1.81 | MIT |
| AndroidX annotations | 1.9.1 | Apache-2.0 |
| Kotlin stdlib / JetBrains annotations | 2.2.10 / 13.0 | Apache-2.0 |
| JUnit (tests only) | 4.13.2 | EPL-1.0 |
| org.json (tests only) | 20240303 | Public domain |
| Mockito (tests only) | 5.15.2 | MIT |
| libsrt (SRT-enabled builds; encryption disabled) | 1.5.7 | MPL-2.0 |
| AndroidX Media3 common/container/muxer | 1.11.1 | Apache-2.0 |
| Robolectric / Media3 extractor (host tests only) | 4.16.1 / 1.11.1 | MIT / Apache-2.0 |

The independent `-PsrtTrial=true` build statically links only pinned libsrt into
`screencopy_srt`; CMake verifies its SHA-256 manifest before extraction into the
build directory. It does not link libwebm or libopus.
Preserve the complete upstream licenses/notices and supply the pinned libsrt
source (including any modifications) alongside distributions to satisfy MPL-2.0.
The APK bundles the complete upstream MPL text in
`assets/licenses/SRT-1.5.7-MPL-2.0.txt`, SRT/UDT/MD5 notices in
`assets/licenses/SRT-1.5.7-NOTICES.txt`, and the complete unmodified corresponding
source in `assets/sources/srt-1.5.7.tar.gz` (SHA-256
`017cd1e437ef2073a4dd10ddf7b55e86bc3d6ebac0393d13bd22f6a57055d32b`).
The archive retains every upstream source-file copyright and license notice;
ScrcpySend's separate JNI adapter/build integration does not modify libsrt.
The Java sender uses Media3 container OpusUtil for packet duration/initialization,
not an Opus decoder. Media3 common/container and transitive Guava keep their
upstream Apache-2.0 notices.

The muxer source origin is the official Google Maven artifact
`androidx.media3:media3-muxer:1.11.1:sources`:
https://dl.google.com/dl/android/maven2/androidx/media3/media3-muxer/1.11.1/media3-muxer-1.11.1-sources.jar
SHA-256: `eefa3f69ad13ccc7c59988bd934cc90653fe24ea64f1eb072db5cf9a674ea47e`.
[The streaming module](streaming-muxer/build.gradle) verifies this pin and retains
upstream Apache headers while applying strict-context patches for live fragment
flush, standard AVC `avc3` / HEVC `hev1` sample entries, actual HEVC VPS temporal
metadata, version-1 tfdt/moof offsets, video/Opus/AAC durations and dOps byte order.
The small StreamingBoxes helper is an explicitly marked local modification,
not a replacement MP4 implementation. Supply these patches/source with the
original notices. No upstream NOTICE file is present in the official source JAR;
the bundled app notice records the source origin and modifications.
The streaming module also bundles the complete Apache-2.0 text as
`assets/licenses/Media3-Apache-2.0.txt`.
AAC uses actual ASC frameLengthFlag for 1024/960 sample durations. The local
StreamingCodecConfig adapter calls the unmodified official Av1ConfigUtil to
serialize actual sequence/metadata OBUs; official Media3 still constructs av1C
and av01. Codec tests include attributed short upstream Apache-2.0 test vectors.
No codec decoder or copied custom container is bundled in the streaming module.
Generated Java sources use a concrete source-set directory, matching AGP 9's
supported pattern, with an explicit JavaCompile dependency on preparation.
This module compiles its complete pinned source cohort
non-incrementally: regeneration/patching must not leave javac resolving a partial
changed subset without its unchanged same-package classes. No classes are excluded.
The compilation task tracks this build script so compilation-model fixes invalidate
stale outputs; changing Gradle's internal incremental option alone does not do so.
Raw Android AV1 CSD is kept as actual first-sample sequence/metadata OBUs, not passed
as Format initialization data: upstream interprets nonempty AV1 initialization
as an already serialized av1C record. Empty muxer Format initialization invokes
the official record parser from the nonempty real first sample.

The LAN protocol does not use QR scanning, camera permission or peer certificate
generation. Crypto dependencies support the bundled Android wireless ADB utility.

Preserve [the bundled notices](app/src/main/assets/NOTICE.txt),
[license texts](app/src/main/assets/licenses), source copyrights, and
[local ADB changes](third_party/libadb/UPSTREAM.md) with distributions. Native
SPAKE2 and Conscrypt are distributed as pinned AAR dependencies. The first-party
direct-child capture supervisor requires **NDK27.2.12479018** and is built for
arm64-v8a, armeabi-v7a, x86 and x86_64. Its small C source is original first-party
code, not copied from Shizuku or scrcpy. App/state/UI remain Kotlin; the streaming
module contains the pinned upstream Java muxer and a narrow Java box helper.
NDK toolchain/sysroot redistribution notices are
copied from that pinned SDK package into generated APK assets/licenses. Node22
is a host-test tool only, not bundled; it executes the freestanding native
stop-function regression under WebAssembly with explicitly simulated syscalls.

## LGPL component

Do NOT treat the whole dependency graph as Apache-only. The complete archive of
unmodified pinned SPAKE2 Java and native sources is bundled in the APK at
`assets/sources/spake2-2.2.1-source.zip` (SHA-256
`a9b546f56190a8a9a9fa2f3bc683d41fabf9ac88681871371fec2eee3a59d9d4`).
Unlike GitHub's non-recursive source ZIP, this includes the **contents** of
`android/src/main/cpp/spake2-c` at gitlink revision
`0d15933e5ba3e662cb01245a7ac0dc9fca3eac31`, including C sources, headers and
its LGPL notice. `SOURCE_ORIGINS.json` records both repositories, revisions and
input archive checksums. Re-fetch/reassemble deterministically with
`tools\fetch-pairing-sources.ps1`; each ordinary app build verifies the complete
bundle digest and required native inputs.

The archive includes library build scripts and notices. Supply this application's
corresponding source/build scripts alongside binary releases so users can
recombine with a modified library (LGPL section 4d0). This repository provides
those scripts and source, but a distributor must actually deliver them.

To replace the library: extract source, build the Android library with its
documented Gradle/NDK requirements, place the resulting AAR locally, replace
the dependency in `third_party/libadb/build.gradle` with `files(...)`, and run
the app build. Install a personally re-signed APK; uninstalling the old app may
be necessary for a different signing key (this deletes pairing credentials).
There is no signature enforcement in ScrcpySend code preventing library replacement.
Reverse engineering to debug library modifications is not prohibited.

### Native rebuild instructions

The app uses the original pinned AAR. To build its complete bundled native source
for **arm64-v8a / API 30** using NDK27.2.12479018 and CMake3.22.1, run the following
in PowerShell after setting `ANDROID_HOME`:

```powershell
Expand-Archive .\app\src\main\assets\sources\spake2-2.2.1-source.zip .\.gradle\spake2-rebuild -Force
$source = '.\.gradle\spake2-rebuild\spake2-java-7615ddd680b990e14513ebb66eac4cb0dbf82464\android\src\main\cpp'
$output = Join-Path (Get-Location) '.gradle\spake2-native-rebuild'
$cmake = "$env:ANDROID_HOME\cmake\3.22.1\bin\cmake.exe"
& $cmake -S $source -B $output -G Ninja "-DCMAKE_TOOLCHAIN_FILE=$env:ANDROID_HOME\ndk\27.2.12479018\build\cmake\android.toolchain.cmake" "-DCMAKE_MAKE_PROGRAM=$env:ANDROID_HOME\cmake\3.22.1\bin\ninja.exe" "-DCMAKE_LIBRARY_OUTPUT_DIRECTORY=$output\lib" -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 -DCMAKE_BUILD_TYPE=Release
& $cmake --build $output
```

The explicit library output directory is required by upstream's post-build strip
step when building outside Android Gradle. The example selects one ABI;
changing a distributed native library requires appropriate
ABI builds and the full recombination/distribution materials.

LGPL legal compliance, codec patents, managed-device policy and Play review
(especially foreground `specialUse`) need distributor review before release.
No approval is implied by a successful debug build. Neither upstream ADB nor
SPAKE2 claims to have been security audited.
