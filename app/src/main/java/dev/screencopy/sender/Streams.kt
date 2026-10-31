package dev.screencopy.sender

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

internal object Streams {
    fun read(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) return out.toByteArray()
            if (out.size() + count > limit) throw IOException("Input exceeded bounded read")
            out.write(buffer, 0, count)
        }
    }
}
