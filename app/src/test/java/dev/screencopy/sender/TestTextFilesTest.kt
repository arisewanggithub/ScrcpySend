package dev.screencopy.sender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.UUID

class TestTextFilesTest {
    @Test fun unicodeBomAndLineEndingsArePreserved() {
        val text = "\uFEFFfirst\r\nsecond\n雪 😀\t"
        withFile(text.toByteArray(StandardCharsets.UTF_8)) { path ->
            assertEquals(text, readUtf8Strict(path))
        }
    }

    @Test fun emptyFileRemainsEmpty() {
        withFile(byteArrayOf()) { path -> assertEquals("", readUtf8Strict(path)) }
    }

    @Test fun malformedAndTruncatedUtf8AreNotReplaced() {
        for (bytes in listOf(
            byteArrayOf(0xc3.toByte(), 0x28),
            byteArrayOf(0xe2.toByte(), 0x82.toByte()),
            byteArrayOf(0xc0.toByte(), 0xaf.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte())
        )) {
            withFile(bytes) { path ->
                assertThrows(CharacterCodingException::class.java) { readUtf8Strict(path) }
            }
        }
    }

    @Test fun missingFileStillFailsWithIoError() {
        val path = Path.of("build", "test-fixtures", "missing-${UUID.randomUUID()}.txt")
        assertThrows(NoSuchFileException::class.java) { readUtf8Strict(path) }
    }

    private fun withFile(bytes: ByteArray, check: (Path) -> Unit) {
        val directory = Files.createDirectories(Path.of("build", "test-fixtures", "utf8-${UUID.randomUUID()}"))
        val path = directory.resolve("fixture.txt")
        try {
            Files.write(path, bytes)
            check(path)
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }
}
