param([string]$ReceiverRoot = $(if ($env:SCRCPY_RECV_ROOT) { $env:SCRCPY_RECV_ROOT } else {
    Join-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) 'ScrcpyRecv'
}))
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$receiverJar = Join-Path $ReceiverRoot 'app\build\intermediates\runtime_app_classes_jar\debug\bundleDebugClassesToRuntimeJar\classes.jar'
if (!(Test-Path $receiverJar)) {
    throw 'The receiver owner must first build its debug app. This script never builds/writes the receiver repository.'
}
if (!$env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 21 and ANDROID_HOME to the Android SDK.' }
& (Join-Path $root 'gradlew.bat') -p $root :app:testDebugUnitTest "-PreceiverClasses=$receiverJar" --tests dev.screencopy.sender.InteropTest --no-daemon --console plain
if ($LASTEXITCODE -ne 0) { throw 'Cross-implementation conformance failed' }
