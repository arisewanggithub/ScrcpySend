package dev.screencopy.sender

/** Byte-only protocol fixtures, not hardware encoder/decoder acceptance evidence. */
internal object EncodedTestPackets {
    fun canonicalOpusHead() = "OpusHead".toByteArray(Charsets.US_ASCII) +
        byteArrayOf(1, 2, 0x38, 1, 0x80.toByte(), 0xbb.toByte(), 0, 0, 0, 0, 0)

    fun opusSilence() = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
}
