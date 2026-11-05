# Dependencies and redistribution

Screen capture and encoding use Android framework MediaProjection, MediaCodec,
AudioRecord and EGL APIs. There is no bundled server, ADB library, Shizuku API,
pairing crypto, native capture supervisor or separately linked native library.

| Component | Pin | Terms |
|---|---|---|
| AndroidX Media3 common/container | 1.11.1 | Apache-2.0 |
| AndroidX annotations | 1.9.1 | Apache-2.0 |
| Kotlin stdlib / JetBrains annotations | 2.2.10 / 13.0 | Apache-2.0 |
| JUnit (tests only) | 4.13.2 | EPL-1.0 |
| org.json (tests only) | 20240303 | Public domain |
| Mockito (tests only) | 5.15.2 | MIT |
| AndroidX test runner / ext JUnit (device tests) | 1.6.2 / 1.2.1 | Apache-2.0 |

Media3 container's unmodified AV1 OBU/sequence parser validates encoded Custom
TCP configuration and samples. Media3 common/container and transitive Guava keep
their upstream Apache-2.0 notices. The complete Apache text is bundled in
`assets/licenses/Media3-Apache-2.0.txt`. Some codec tests contain attributed short
upstream Apache-2.0 test vectors. No container muxer or codec decoder is bundled
in the production app.

Original application code has no selected license/reuse grant; public
visibility alone is not permission to reuse it. Preserve all remaining
[bundled notices](app/src/main/assets/NOTICE.txt), license texts and source
copyrights with distributions. Gradle wrapper is Apache-2.0 build tooling.
Android SDK/JDK tooling is not bundled.

Removing old libraries from the current APK does not change the redistribution
obligations of previously released APKs or their corresponding source trees.
Codec patents, app-store policy and legal review remain separate from tests.
