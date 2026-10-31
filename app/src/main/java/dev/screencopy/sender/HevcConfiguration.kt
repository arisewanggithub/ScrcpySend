package dev.screencopy.sender

import androidx.media3.container.NalUnitUtil
import java.io.IOException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class HevcConfiguration private constructor(
    val csd: ByteArray, val width: Int, val height: Int,
    private val parameters: Map<Int, ByteArray>
) {
    fun sample(key: Boolean, bytes: ByteArray): ByteArray {
        val nals = nalUnits(bytes)
        val video = nals.filter { type(it) < 32 }
        if (video.isEmpty()) throw IOException("CAPTURE_FAILED: HEVC access unit has no coded picture")
        val randomAccess = video.any { type(it) in 16..23 }
        if (key != randomAccess)
            throw IOException("CAPTURE_FAILED: HEVC key flag differs from actual IRAP picture")
        for (nal in nals.filter { type(it) in 32..34 }) {
            if (!nal.contentEquals(parameters.getValue(type(nal))))
                throw IOException("CAPTURE_FAILED: HEVC in-band parameters differ from the announced configuration")
        }
        if (nals.any { type(it) in 32..34 } && !randomAccess)
            throw IOException("CAPTURE_FAILED: HEVC in-band parameters require an authentic IRAP picture")
        return annexB(nals.filter { type(it) !in 32..34 })
    }

    companion object {
        fun parse(bytes: ByteArray): HevcConfiguration {
            val nals = nalUnits(bytes)
            if (nals.any { type(it) !in 32..34 })
                throw IOException("UNSUPPORTED_CONFIG: HEVC initialization requires VPS/SPS/PPS only")
            val parameters = (32..34).associateWith { kind ->
                val matching = nals.filter { type(it) == kind }
                if (matching.isEmpty() || matching.any { !it.contentEquals(matching.first()) })
                    throw IOException("CAPTURE_FAILED: HEVC initialization lacks unique VPS/SPS/PPS")
                matching.first()
            }
            val vps = parameters.getValue(32)
            val vpsData = CodecConfig.rbsp(vps)
            if (vpsData.size < 18)
                throw IOException("CAPTURE_FAILED: truncated HEVC VPS profile/level")
            if (vpsData[2].toInt() and 3 != 0 || (vpsData[3].toInt() and 0xf0) != 0 ||
                ((vpsData[3].toInt() ushr 1) and 7) > 6)
                throw IOException("UNSUPPORTED_CONFIG: SRT HEVC requires single-layer VPS with valid temporal layers")
            val sps = parameters.getValue(33)
            val parsed = try { NalUnitUtil.parseH265SpsNalUnit(sps, 0, sps.size, null) }
            catch (e: IllegalArgumentException) { throw IOException("CAPTURE_FAILED: invalid HEVC SPS", e) }
            catch (e: IllegalStateException) { throw IOException("CAPTURE_FAILED: invalid HEVC SPS", e) }
            catch (e: IndexOutOfBoundsException) { throw IOException("CAPTURE_FAILED: truncated HEVC SPS", e) }
            if (parsed.width !in 1..16384 || parsed.height !in 1..16384)
                throw IOException("CAPTURE_FAILED: invalid HEVC dimensions")
            val csd = annexB(parameters.values.toList())
            return HevcConfiguration(csd, parsed.width, parsed.height, parameters)
        }

        fun nalUnits(bytes: ByteArray): List<ByteArray> = CodecConfig.annexB(bytes).also { nals ->
            if (nals.any { it.size < 2 || it[0].toInt() and 0x80 != 0 ||
                    it[0].toInt() and 1 != 0 || it[1].toInt() and 0xf8 != 0 ||
                    it[1].toInt() and 7 == 0 })
                throw IOException("UNSUPPORTED_CONFIG: invalid or multilayer HEVC NAL header")
        }
        private fun type(nal: ByteArray) = (nal[0].toInt() ushr 1) and 63
        private fun annexB(nals: List<ByteArray>) =
            nals.fold(byteArrayOf()) { bytes, nal -> bytes + byteArrayOf(0, 0, 0, 1) + nal }
    }
}

internal class SrtHevcConfiguration {
    private var current: HevcConfiguration? = null
    private var awaitingIrap = false
    val requiresIrap: Boolean get() = awaitingIrap
    fun configure(bytes: ByteArray) {
        val config = HevcConfiguration.parse(bytes)
        if (current?.csd?.contentEquals(config.csd) != true) awaitingIrap = true
        current = config
    }
    fun sample(key: Boolean, bytes: ByteArray): ByteArray {
        val config = current ?: throw IOException("CAPTURE_FAILED: HEVC sample before configuration")
        val media = config.sample(key, bytes)
        if (awaitingIrap && !key)
            throw IOException("CAPTURE_FAILED: HEVC configuration change requires an authentic IRAP picture")
        val withParameters = awaitingIrap || HevcConfiguration.nalUnits(bytes).any {
            ((it[0].toInt() ushr 1) and 63) in 32..34
        }
        awaitingIrap = false
        // hev1 permits the real VPS/SPS/PPS before the access unit that activates them.
        return if (withParameters) config.csd + media else media
    }
}
