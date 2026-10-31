package dev.screencopy.sender

import androidx.media3.container.ObuParser
import java.io.IOException
import java.nio.ByteBuffer

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object Av1Configuration {
    fun obus(bytes: ByteArray): List<ObuParser.Obu> {
        if (bytes.isEmpty() || bytes.size > Wire.MAX_VIDEO) throw IOException("CAPTURE_FAILED: invalid AV1 OBU sample size")
        var offset = 0
        var count = 0
        while (offset < bytes.size) {
            if (++count > 4096) throw IOException("CAPTURE_FAILED: AV1 OBU count exceeds bound")
            val header = bytes[offset++].toInt() and 255
            val type = (header ushr 3) and 15
            if (header and 0x81 != 0 || type !in 1..8 && type != 15)
                throw IOException("CAPTURE_FAILED: invalid AV1 OBU header")
            if (header and 4 != 0) {
                if (offset >= bytes.size || bytes[offset++].toInt() and 7 != 0)
                    throw IOException("CAPTURE_FAILED: malformed AV1 OBU extension")
            }
            if (header and 2 == 0) { offset = bytes.size; continue }
            var size = 0L
            var shift = 0
            while (true) {
                if (offset >= bytes.size || shift >= 56) throw IOException("CAPTURE_FAILED: truncated AV1 OBU size")
                val value = bytes[offset++].toInt() and 255
                size = size or ((value and 127).toLong() shl shift)
                if (value and 128 == 0) break
                shift += 7
            }
            if (size > bytes.size - offset) throw IOException("CAPTURE_FAILED: truncated AV1 OBU payload")
            offset += size.toInt()
        }
        return try {
            ObuParser.split(ByteBuffer.wrap(bytes)).also { obus ->
                if (obus.isEmpty()) throw IOException("CAPTURE_FAILED: empty AV1 OBU sample")
                if (obus.sumOf { it.header.remaining().toLong() + it.payload.remaining() } != bytes.size.toLong())
                    throw IOException("CAPTURE_FAILED: incomplete AV1 OBU parsing")
            }
        } catch (e: RuntimeException) { throw IOException("CAPTURE_FAILED: malformed AV1 OBUs", e) }
    }

    fun dimensions(bytes: ByteArray): Pair<Int, Int> {
        val sequence = obus(bytes).filter { it.type == ObuParser.OBU_SEQUENCE_HEADER }
        if (sequence.size != 1) throw IOException("UNSUPPORTED_CONFIG: AV1 requires one sequence header")
        val obu = sequence.single()
        val parsed = try { ObuParser.SequenceHeader.parse(obu) }
        catch (e: RuntimeException) { throw IOException("CAPTURE_FAILED: malformed AV1 sequence header", e) }
        if (parsed == null) throw IOException("UNSUPPORTED_CONFIG: AV1 sequence features unsupported by Media3 muxer")
        val data = ByteArray(obu.payload.remaining()).also { obu.payload.duplicate().get(it) }
        val bits = CodecBits(data)
        bits.skip(4)
        val reduced = bits.read(1) != 0
        if (reduced) bits.skip(5) else {
            if (bits.read(1) != 0) {
                bits.skip(64)
                if (bits.read(1) != 0) bits.uv()
                if (bits.read(1) != 0)
                    throw IOException("UNSUPPORTED_CONFIG: AV1 decoder model timing unsupported")
            }
            val displayDelay = bits.read(1) != 0
            repeat(bits.read(5) + 1) {
                bits.skip(12)
                if (bits.read(5) > 7) bits.skip(1)
                if (displayDelay && bits.read(1) != 0) bits.skip(4)
            }
        }
        val widthBits = bits.read(4) + 1
        val heightBits = bits.read(4) + 1
        val width = bits.read(widthBits) + 1
        val height = bits.read(heightBits) + 1
        if (width !in 1..16384 || height !in 1..16384)
            throw IOException("CAPTURE_FAILED: invalid AV1 sequence dimensions")
        return width to height
    }

    fun sequence(bytes: ByteArray): ByteArray {
        val headers = obus(bytes).filter { it.type == ObuParser.OBU_SEQUENCE_HEADER }
        if (headers.size != 1) throw IOException("UNSUPPORTED_CONFIG: AV1 requires one sequence header")
        return ByteArray(headers.single().payload.remaining()).also { headers.single().payload.duplicate().get(it) }
    }

    fun firstSample(config: ByteArray, sample: ByteArray): ByteArray {
        val obus = obus(sample)
        if (obus.any { it.type == ObuParser.OBU_SEQUENCE_HEADER }) return sample
        val prefix = try { androidx.media3.muxer.StreamingCodecConfig.av1ConfigObus(config) }
        catch (e: RuntimeException) { throw IOException("UNSUPPORTED_CONFIG: AV1 configuration rejected by official muxer", e) }
        val delimiters = obus.takeWhile { it.type == ObuParser.OBU_TEMPORAL_DELIMITER }
        val offset = delimiters.sumOf { it.header.remaining() + it.payload.remaining() }
        return sample.copyOfRange(0, offset) + prefix + sample.copyOfRange(offset, sample.size)
    }
}
