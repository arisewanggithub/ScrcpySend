package dev.screencopy.sender

internal object CropEditorInput {
    enum class Field(val key: String, val label: String, val offset: Boolean = false) {
        WIDTH("cropWidth", "Crop width"),
        HEIGHT("cropHeight", "Crop height"),
        X("cropX", "Crop X", true),
        Y("cropY", "Crop Y", true)
    }

    class Invalid(val field: Field, message: String) : IllegalArgumentException(message)

    fun committedText(field: Field, text: String): String =
        if (field.offset && text.isEmpty()) "0" else text

    fun parse(field: Field, text: String): Int {
        val committed = committedText(field, text)
        if (!committed.matches(Regex("-?[0-9]+")))
            throw Invalid(field, "${field.label} requires a whole number in pixels; spaces are not allowed.")
        if (committed.startsWith("-"))
            throw Invalid(field, "${field.label} must be 0 or greater.")
        return committed.toIntOrNull()
            ?: throw Invalid(field, "${field.label} is too large; enter at most ${Int.MAX_VALUE} pixels.")
    }
}
