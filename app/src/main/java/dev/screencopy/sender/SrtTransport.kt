package dev.screencopy.sender

import org.json.JSONObject
import java.io.IOException
import java.net.URI

internal object SrtTransport {
    const val LATENCY_MS = 100
    fun streamId(session: Long) = "sc-" + java.lang.Long.toHexString(session).padStart(16, '0')
    fun descriptor(address: String, port: Int, session: Long): JSONObject {
        require(port in 1..65535)
        return Wire.obj("kind" to "srt", "container" to "fmp4",
            "url" to URI("srt", null, address, port, null, null, null).toASCIIString(),
            "latencyMs" to LATENCY_MS, "streamId" to streamId(session))
    }
    fun requireAccepted(json: JSONObject) {
        if (json.optJSONObject("mediaTransport")?.optString("kind") != "srt")
            throw IOException("UNSUPPORTED_CONFIG: receiver did not explicitly accept SRT; install matching trial APKs")
    }
}
