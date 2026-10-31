package dev.screencopy.sender

import android.content.SharedPreferences
import java.io.IOException

internal class CaptureSettingsStore(private val preferences: SharedPreferences) {
    fun read(): Settings = readForEditing().also { it.validate() }

    fun readForEditing(): Settings {
        val saved = preferences.getString("capture", null) ?: return Settings()
        return Settings.from(Wire.parseJson(saved.toByteArray(Charsets.UTF_8)), validate = false)
    }
    fun write(settings: Settings) {
        settings.validate()
        if (!preferences.edit().putString("capture", settings.request().toString()).putBoolean("validCapture", true).commit())
            throw IOException("Could not save capture settings")
    }
}
