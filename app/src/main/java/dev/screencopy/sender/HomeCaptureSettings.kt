package dev.screencopy.sender

import java.math.BigDecimal

internal object HomeCaptureSettings {
    fun effective(saved: Settings, ignoreSizeAndCrop: Boolean = false): Settings {
        if (!ignoreSizeAndCrop) saved.validate()
        return Settings.from(saved.request(), validate = false).apply {
            if (ignoreSizeAndCrop) {
                maxSize = 0
                cropWidth = 0; cropHeight = 0; cropX = 0; cropY = 0
            }
            validate()
        }
    }

    fun summary(settings: Settings, ignoreSizeAndCrop: Boolean = false): String {
        val defaults = Settings()
        val lines = mutableListOf<String>()
        fun changed(label: String, value: Any, default: Any, display: String = value.toString()) {
            if (value != default) lines += "$label: $display"
        }
        if (ignoreSizeAndCrop) lines += "Full screen: size/crop ignored"
        changed("Media transport", settings.mediaTransportMode, defaults.mediaTransportMode,
            when (settings.mediaTransportMode) { "custom" -> "Custom TCP"; "srt" -> "SRT (fMP4)"; else -> settings.mediaTransportMode })
        changed("Video codec", settings.videoCodec, defaults.videoCodec)
        changed("Video encoder", settings.videoEncoder, defaults.videoEncoder)
        changed("Video bitrate", settings.videoBitrate, defaults.videoBitrate, "${Bitrates.videoMbps(settings.videoBitrate)} Mbps")
        if (!ignoreSizeAndCrop) {
            changed("Maximum size", settings.maxSize, defaults.maxSize,
                if (settings.maxSize == 0) "Unlimited" else "${settings.maxSize} pixels")
            if (listOf(settings.cropWidth, settings.cropHeight, settings.cropX, settings.cropY) !=
                listOf(defaults.cropWidth, defaults.cropHeight, defaults.cropX, defaults.cropY))
                lines += "Crop: ${settings.cropWidth} × ${settings.cropHeight} pixels at (${settings.cropX}, ${settings.cropY})"
        }
        changed("Maximum FPS", settings.maxFps, defaults.maxFps, "${settings.maxFps} FPS")
        changed("Screen to share", settings.displayId, defaults.displayId, "Display ${settings.displayId}")
        changed("Capture orientation", settings.orientation, defaults.orientation,
            "${settings.orientation.removePrefix("@")}°" + if (settings.orientation.startsWith("@")) " (locked)" else "")
        changed("Receiver orientation", settings.receiverOrientation, defaults.receiverOrientation,
            settings.receiverOrientation.replaceFirstChar { it.uppercase() })
        changed("Video codec options", settings.videoOptions, defaults.videoOptions)
        if (!settings.audio) {
            changed("Audio", settings.audio, defaults.audio, "disabled")
        } else {
            changed("Audio codec", settings.audioCodec, defaults.audioCodec,
                if (settings.audioCodec == "raw") "Raw PCM" else settings.audioCodec)
            changed("Audio source", settings.audioSource, defaults.audioSource)
            if (settings.audioCodec != "raw") {
                changed("Audio encoder", settings.audioEncoder, defaults.audioEncoder)
                val kbps = BigDecimal(settings.audioBitrate).divide(BigDecimal(1000)).stripTrailingZeros().toPlainString()
                changed("Audio bitrate", settings.audioBitrate, defaults.audioBitrate, "$kbps kbps")
                changed("Audio codec options", settings.audioOptions, defaults.audioOptions)
            }
        }
        return lines.joinToString("\n").ifEmpty { "Default settings" }
    }
}
