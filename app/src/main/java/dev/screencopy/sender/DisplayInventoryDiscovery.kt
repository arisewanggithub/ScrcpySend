package dev.screencopy.sender

import android.annotation.SuppressLint
import java.io.IOException

/** Same DisplayManagerGlobal query as pinned scrcpy, only inside the authorized UserService. */
internal object DisplayInventoryDiscovery {
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    fun read(): DisplayInventory = try {
        val type = Class.forName("android.hardware.display.DisplayManagerGlobal")
        val manager = type.getDeclaredMethod("getInstance").invoke(null)
            ?: throw IOException("Privileged display manager unavailable")
        read(manager)
    } catch (e: ReflectiveOperationException) {
        throw IOException("Privileged display inventory unavailable; reconnect Shizuku and retry.", e)
    }
    fun read(manager: Any): DisplayInventory = try {
        val ids = manager.javaClass.getMethod("getDisplayIds").invoke(manager) as? IntArray
            ?: throw IOException("Privileged display IDs unavailable")
        if (ids.size > 128) throw IOException("Display inventory exceeds limit")
        val getInfo = manager.javaClass.getMethod("getDisplayInfo", Int::class.javaPrimitiveType)
        DisplayInventory(ids.map { id ->
            val info = getInfo.invoke(manager, id)
                ?: throw IOException("Display disappeared during discovery. Refresh displays.")
            val fields = info.javaClass
            CaptureDisplay(id, fields.getField("name").get(info) as? String ?: "",
                fields.getField("logicalWidth").getInt(info), fields.getField("logicalHeight").getInt(info),
                fields.getField("rotation").getInt(info), fields.getField("type").getInt(info))
        })
    } catch (e: ReflectiveOperationException) {
        throw IOException("Privileged display metadata unavailable; reconnect Shizuku and retry.", e)
    }
}
