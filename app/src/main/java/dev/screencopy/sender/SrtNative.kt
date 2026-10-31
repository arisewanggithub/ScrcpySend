package dev.screencopy.sender

/** Transport calls throw IOException; close interrupts pending native I/O. */
internal object SrtNative {
    init { System.loadLibrary("screencopy_srt") }
    external fun listen(address: String, expectedPeer: String, streamId: String, latencyMs: Int): Long
    external fun localPort(handle: Long): Int
    external fun awaitCaller(handle: Long, timeoutMs: Int): Boolean
    external fun createCaller(streamId: String, latencyMs: Int): Long
    external fun connect(handle: Long, address: String, port: Int, timeoutMs: Int)
    external fun read(handle: Long, bytes: ByteArray, offset: Int, length: Int): Int
    external fun write(handle: Long, bytes: ByteArray, offset: Int, length: Int)
    external fun close(handle: Long)
    external fun snapshot(handle: Long): String
    external fun version(): String
}
