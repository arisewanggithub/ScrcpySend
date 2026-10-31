package dev.screencopy.sender

internal object CaptureGuardian {
    fun artifacts(remote: String, id: String): List<String> {
        Settings.require(remote == "/data/local/tmp/screencopy-$id.jar" && id.matches(Regex("[0-9a-f]{8}")), "Invalid capture ownership")
        return listOf(remote, "$remote.pid", "$remote.guardian", "$remote.supervisor", "$remote.probe.pid", "$remote.probe.guardian")
    }
    fun arguments(settings: Settings, id: String, remote: String): List<String> {
        Settings.require(id.matches(Regex("[0-9a-f]{8}")) && remote == "/data/local/tmp/screencopy-$id.jar", "Invalid capture ownership")
        return listOf("$remote.supervisor", remote, CaptureBackend.VERSION) +
            CaptureBackend.options(settings, id).trim().split(' ')
    }

}
