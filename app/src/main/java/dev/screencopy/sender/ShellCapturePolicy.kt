package dev.screencopy.sender

import java.io.IOException

internal object ShellCapturePolicy {
    const val SERVER_HASH = "8588238c9a5a00aa542906b6ec7e6d5541d9ffb9b5d0f6e1bc0e365e2303079e"
    fun requireUid(uid: Int, allowRoot: Boolean) {
        if (uid != 2000 && !(uid == 0 && allowRoot))
            throw SecurityException(if (uid == 0) "Rooted Shizuku requires explicit root-mode consent" else "UserService must run as shell UID 2000")
    }
    fun path(id: String): String {
        if (!id.matches(Regex("[0-9a-f]{8}"))) throw IOException("Invalid capture session ID")
        return "/data/local/tmp/screencopy-$id.jar"
    }
    fun socket(id: String, track: Int): String {
        path(id)
        if (track !in 1..2) throw IOException("Invalid media track")
        return "scrcpy_$id"
    }
}
