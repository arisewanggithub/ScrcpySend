package dev.screencopy.sender

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import java.util.concurrent.TimeUnit

/** Synthetic encoder input only; no display, microphone, capture service or permissions. */
internal object SyntheticEncodedFixture {
    data class EncodedVideoFrame(val data: ByteArray, val key: Boolean)

    fun canonicalOpusHead() = "OpusHead".toByteArray(Charsets.US_ASCII) +
        byteArrayOf(1, 2, 0x38, 1, 0x80.toByte(), 0xbb.toByte(), 0, 0, 0, 0, 0)

    fun opusSilence() = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())

    fun encodeBlackFrame(): Pair<ByteArray, ByteArray> =
        encodeBlackFrames(1).let { (config, frames) -> config to frames.first().data }

    fun encodeBlackFrames(frameCount: Int, width: Int = 320, height: Int = 240,
        fps: Int = 30, bitrate: Int = 200_000, noise: Boolean = false,
        motion: Boolean = false, videoCodec: String = "h264"): Pair<ByteArray, List<EncodedVideoFrame>> {
        require(frameCount in 1..60 && fps in 1..60 && width in 16..1920 && height in 16..1920)
        require(videoCodec in setOf("h264", "h265", "av1"))
        val mime = HardwareEncoderPolicy.mimeTypes.getValue(videoCodec)
        val encoder = hardwareVideoEncoder(videoCodec)
            ?: throw UnsupportedOperationException("UNSUPPORTED_CONFIG: no advertised hardware $videoCodec encoder; NOT_VALIDATED")
        val codec = MediaCodec.createByCodecName(encoder.name)
        var started = false
        try {
            val caps: MediaCodecInfo.CodecCapabilities = encoder.getCapabilitiesForType(mime)
            val video: MediaCodecInfo.VideoCapabilities = checkNotNull(caps.videoCapabilities)
            check(video.areSizeAndRateSupported(width, height, fps.toDouble()) && bitrate in video.bitrateRange &&
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in caps.colorFormats) {
                "UNSUPPORTED_CONFIG: advertised $videoCodec encoder cannot honor exact fixture size/FPS/bitrate/input format; NOT_VALIDATED"
            }
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            started = true
            val black = ByteArray(width * height * 3 / 2) {
                if (it < width * height) {
                    if (noise) (16 + ((it * 1103515245 + 12345) ushr 24) % 220).toByte() else 16.toByte()
                } else 128.toByte()
            }
            var config = byteArrayOf()
            val frames = mutableListOf<EncodedVideoFrame>()
            var queued = 0
            var eos = false
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(5)
            while (SystemClock.elapsedRealtimeNanos() < deadline) {
                if (!eos) {
                    val slot = codec.dequeueInputBuffer(10_000)
                    if (slot >= 0) {
                        if (queued < frameCount) {
                            if (motion) {
                                for (row in 0 until height) {
                                    for (column in 0 until width) {
                                        val stripe = (column + queued * width / fps) % width < width / 4
                                        black[row * width + column] = if (stripe) 200.toByte() else 32.toByte()
                                    }
                                }
                            }
                            checkNotNull(codec.getInputBuffer(slot)).apply { clear(); put(black) }
                            codec.queueInputBuffer(slot, 0, black.size, 1_000_000 + queued * 1_000_000L / fps, 0)
                            queued++
                        } else {
                            codec.queueInputBuffer(slot, 0, 0, 1_000_000 + queued * 1_000_000L / fps,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eos = true
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(codec.outputFormat.getInteger(MediaFormat.KEY_WIDTH) == width &&
                        codec.outputFormat.getInteger(MediaFormat.KEY_HEIGHT) == height) {
                        "Synthetic $videoCodec encoder substituted dimensions"
                    }
                    config = listOf("csd-0", "csd-1").fold(byteArrayOf()) { bytes, key ->
                        val buffer = codec.outputFormat.getByteBuffer(key)
                        bytes + if (buffer == null) byteArrayOf() else ByteArray(buffer.remaining()).also(buffer::get)
                    }
                } else if (index >= 0) {
                    val buffer = checkNotNull(codec.getOutputBuffer(index))
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    val data = ByteArray(info.size).also(buffer::get)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) config = data
                    else if (data.isNotEmpty()) {
                        check(info.presentationTimeUs == 1_000_000 + frames.size * 1_000_000L / fps) {
                            "Synthetic $videoCodec output reordered or rewrote its input PTS"
                        }
                        frames += EncodedVideoFrame(data, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        if (frames.size == frameCount && config.isNotEmpty()) {
                            check(frames.first().key) { "Synthetic $videoCodec clip must begin with a keyframe" }
                            return config to frames
                        }
                    }
                }
            }
            error("Synthetic $videoCodec encoder produced ${frames.size}/$frameCount frames")
        } finally {
            try { if (started) codec.stop() }
            finally { codec.release() }
        }
    }

    fun hardwareVideoEncoder(codec: String): MediaCodecInfo? = HardwareEncoderDiscovery.codecs(codec).firstOrNull()
    fun hardwareAv1Encoder(): MediaCodecInfo? = hardwareVideoEncoder("av1")

    data class EncodedAudio(val config: ByteArray, val packets: List<ByteArray>, val samplesPerFrame: Int, val sampleRate: Int)

    fun encodeAacSilence(packetCount: Int = 48, bitrate: Int = 128_000): EncodedAudio {
        require(packetCount in 1..100)
        val encoder = HardwareEncoderDiscovery.codecs("aac").firstOrNull()
            ?: throw UnsupportedOperationException("UNSUPPORTED_CONFIG: no compatible AAC encoder; NOT_VALIDATED")
        val codec = MediaCodec.createByCodecName(encoder.name)
        var started = false
        try {
            val format = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start(); started = true
            var config = byteArrayOf()
            val packets = mutableListOf<ByteArray>()
            var samplesQueued = 0L
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtimeNanos() + TimeUnit.SECONDS.toNanos(5)
            while (SystemClock.elapsedRealtimeNanos() < deadline) {
                val slot = codec.dequeueInputBuffer(10_000)
                if (slot >= 0) {
                    val input = checkNotNull(codec.getInputBuffer(slot))
                    val samples = minOf(1024, input.capacity() / 4)
                    check(samples > 0)
                    val silence = ByteArray(samples * 4)
                    input.clear(); input.put(silence)
                    codec.queueInputBuffer(slot, 0, silence.size, 1_000_000 + samplesQueued * 1_000_000 / 48000, 0)
                    samplesQueued += samples
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val actual = codec.outputFormat
                    check(actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 48000 &&
                        actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 2) { "AAC fixture format substitution" }
                    val buffer = checkNotNull(actual.getByteBuffer("csd-0"))
                    config = ByteArray(buffer.remaining()).also(buffer::get)
                } else if (index >= 0) {
                    val output = checkNotNull(codec.getOutputBuffer(index))
                    output.position(info.offset); output.limit(info.offset + info.size)
                    val bytes = ByteArray(info.size).also(output::get)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) config = bytes
                    else if (bytes.isNotEmpty()) {
                        AacConfiguration.accessUnit(bytes)
                        packets += bytes
                    }
                }
                if (packets.size >= packetCount && config.isNotEmpty()) {
                    val actual = AacConfiguration.parse(config)
                    check(actual.sampleRate == 48000 && actual.channels == 2)
                    return EncodedAudio(config, packets, actual.samplesPerFrame, actual.sampleRate)
                }
            }
            error("Synthetic AAC encoder produced ${packets.size}/$packetCount packets")
        } finally {
            try { if (started) codec.stop() } finally { codec.release() }
        }
    }
}
