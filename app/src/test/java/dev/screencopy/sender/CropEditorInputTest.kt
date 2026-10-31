package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test

class CropEditorInputTest {
    @Test fun onlyTrulyEmptyOffsetsBecomeZeroOnCommit() {
        for (field in CropEditorInput.Field.entries) {
            assertEquals(if (field.offset) "0" else "", CropEditorInput.committedText(field, ""))
            assertEquals(" ", CropEditorInput.committedText(field, " "))
            assertEquals("228", CropEditorInput.committedText(field, "228"))
            if (field.offset) assertEquals(0, CropEditorInput.parse(field, ""))
            else assertInvalid(field, "")
        }
    }

    @Test fun acceptsNaturalCoordinateOffsetsWithoutApplyingMaxSizeOrEncoderRules() {
        assertEquals(1080, CropEditorInput.parse(CropEditorInput.Field.WIDTH, "1080"))
        assertEquals(1000, CropEditorInput.parse(CropEditorInput.Field.HEIGHT, "1000"))
        assertEquals(0, CropEditorInput.parse(CropEditorInput.Field.X, "0"))
        assertEquals(228, CropEditorInput.parse(CropEditorInput.Field.Y, "228"))
        assertEquals(Int.MAX_VALUE, CropEditorInput.parse(CropEditorInput.Field.Y, Int.MAX_VALUE.toString()))
    }

    @Test fun invalidNonemptyNegativeAndOverflowAreExplicitFieldErrors() {
        for (field in CropEditorInput.Field.entries)
            for (text in listOf(" ", "\t", " 228", "228 ", "2 28", "+228", "2.28", "a", "-1",
                "-2147483649", "2147483648", "999999999999999999999999999999")) {
                val error = assertInvalid(field, text)
                assertTrue(error.message!!.contains(field.label))
            }
    }

    @Test fun normalizationDoesNotAlterPartialOrCompleteTypedText() {
        for (text in listOf("2", "22", "228", "00228", "-", " ", "2147483648"))
            assertEquals(text, CropEditorInput.committedText(CropEditorInput.Field.Y, text))
        assertEquals(228, CropEditorInput.parse(CropEditorInput.Field.Y, "00228"))
    }

    private fun assertInvalid(field: CropEditorInput.Field, text: String): CropEditorInput.Invalid =
        assertThrows(CropEditorInput.Invalid::class.java) { CropEditorInput.parse(field, text) }.also {
            assertEquals(field, it.field)
        }
}
