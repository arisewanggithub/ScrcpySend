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
| AndroidX Media3 common/container | 1.11.1 | Apache-2.0 |

Media3 container's AV1 OBU/sequence parser validates the Custom TCP video
configuration and packets. Media3 common/container and transitive Guava keep
their upstream Apache-2.0 notices. The complete Apache-2.0 text is bundled at
`assets/licenses/Media3-Apache-2.0.txt`. Codec tests include attributed short
upstream Apache-2.0 test vectors. No container muxer or codec decoder is bundled.

The LAN protocol does not use QR scanning, camera permission or peer certificate
generation. Crypto dependencies support the bundled Android wireless ADB utility.

Preserve [the bundled notices](app/src/main/assets/NOTICE.txt),
[license texts](app/src/main/assets/licenses), source copyrights, and
[local ADB changes](third_party/libadb/UPSTREAM.md) with distributions. Native
SPAKE2 and Conscrypt are distributed as pinned AAR dependencies. The first-party
direct-child capture supervisor requires **NDK27.2.12479018** and is built for
arm64-v8a, armeabi-v7a, x86 and x86_64. Its small C source is original first-party
code, not copied from Shizuku or scrcpy. App/state/UI remain Kotlin.
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
