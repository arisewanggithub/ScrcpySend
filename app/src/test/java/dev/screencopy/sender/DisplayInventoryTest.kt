package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class DisplayInventoryTest {
    private fun display(id: Int, name: String = "Panel", width: Int = 1080, height: Int = 2400,
                        rotation: Int = 0, type: Int = 1) = CaptureDisplay(id, name, width, height, rotation, type)
    class FakeInfo(@JvmField val name: String, @JvmField val logicalWidth: Int,
                   @JvmField val logicalHeight: Int, @JvmField val rotation: Int, @JvmField val type: Int)
    class FakeManager {
        fun getDisplayIds() = intArrayOf(0, 7, 42)
        fun getDisplayInfo(id: Int): FakeInfo? = when (id) {
            0 -> FakeInfo("Phone panel", 1080, 2400, 0, 1)
            7 -> FakeInfo("HDMI monitor", 1920, 1080, 0, 2)
            42 -> FakeInfo("Presentation", 800, 600, 1, 5)
            else -> null
        }
    }
    @Test fun productionDiscoveryReadsRealIdsNamesTypesDimensionsAndRotationWithoutContiguousAssumption() {
        val inventory = DisplayInventoryDiscovery.read(FakeManager())
        assertEquals(listOf(0, 7, 42), inventory.displays.map { it.id })
        assertEquals("HDMI monitor", inventory.displays[1].name)
        assertEquals(1920, inventory.displays[1].width)
        assertEquals(600, inventory.displays[2].naturalWidth)
        assertEquals(800, inventory.displays[2].naturalHeight)
    }
    @Test fun structuredInventoryRoundTripsAndRefreshReplacesOnlyInternalDisplayMetadata() {
        val inventory = DisplayInventoryDiscovery.read(FakeManager())
        val report = "--video-codec=h264\n--display-id=0 (100x200)\n" + inventory.report()
        assertEquals(inventory.displays, DisplayInventory.fromReport(report).displays)
        val fresh = DisplayInventory(listOf(display(17, "Fresh")))
        val updated = fresh.updateReport(report)
        assertTrue(updated.contains("--video-codec=h264"))
        assertEquals(listOf(17), DisplayInventory.fromReport(updated).displays.map { it.id })
    }
    @Test fun pinnedScrcpyListingParsesActualIdsButDoesNotInventNamesOrNaturalRotation() {
        val inventory = DisplayInventory.fromReport("List of displays:\n --display-id=7 (1920x1080)\n --display-id=42 (800x600)")
        assertEquals(listOf(7, 42), inventory.displays.map { it.id })
        assertNull(inventory.displays[0].rotation)
        assertEquals("", inventory.displays[0].name)
        assertTrue(inventory.choices(7).first().toString().contains("1920x1080"))
        val crop = Settings().apply { displayId = 7; cropWidth = 100; cropHeight = 100 }
        assertThrows(IOException::class.java) { inventory.validate(crop) }
    }
    @Test fun friendlyLabelsShowMainExternalVirtualAndSecondaryIdsWithoutParsingLabelsAsIds() {
        val inventory = DisplayInventoryDiscovery.read(FakeManager())
        val choices = inventory.choices(7)
        assertTrue(choices[0].toString().startsWith("Main screen - Phone panel"))
        assertTrue(choices[1].toString().contains("External screen - HDMI monitor"))
        assertTrue(choices[2].toString().contains("Virtual display - Presentation"))
        assertEquals(7, choices.single { it.toString().contains("ID 7") }.id)
    }
    @Test fun absentZeroIsNotInventedAndMissingSavedIdStaysExplicitlyInvalid() {
        val inventory = DisplayInventory(listOf(display(7), display(42)))
        val choices = inventory.choices(0)
        assertNull(choices.first().display)
        assertEquals(0, choices.first().id)
        assertTrue(choices.none { it.display != null && it.toString().startsWith("Main screen") })
        assertThrows(IOException::class.java) { inventory.validate(Settings()) }
        val chosen = Settings().apply { displayId = 42 }
        inventory.validate(chosen)
        assertEquals(42, Settings.from(chosen.request()).displayId)
        assertTrue(chosen.request().getJSONObject("video").get("displayId") is Int)
    }
    @Test fun savedDisplayRemovalAndReappearanceNeverChangeTheSelectedId() {
        val before = DisplayInventory(listOf(display(0), display(7)))
        val settings = Settings().apply { displayId = 7 }
        before.validate(settings)
        val after = DisplayInventory(listOf(display(0)))
        assertNull(after.choices(settings.displayId).first().display)
        assertEquals(7, settings.displayId)
        assertThrows(IOException::class.java) { after.validate(settings) }
        before.validate(settings)
        assertEquals(7, settings.displayId)
    }
    @Test fun emptyUnavailableMalformedDuplicateAndUnknownSizeCannotBecomeFakeMainScreen() {
        val empty = DisplayInventory.fromReport("")
        assertNull(empty.choices(0).single().display)
        assertThrows(IOException::class.java) { empty.requireAvailable() }
        assertThrows(IOException::class.java) { DisplayInventory.fromReport("--display-id=0 (size unknown)") }
        assertThrows(IOException::class.java) { DisplayInventory.fromReport(empty.report() + "\n" + empty.report()) }
        assertThrows(IllegalArgumentException::class.java) { DisplayInventory(listOf(display(7), display(7))) }
        assertThrows(IllegalArgumentException::class.java) { display(-1) }
        assertThrows(IOException::class.java) { DisplayInventoryDiscovery.read(Any()) }
    }
    @Test fun cropUsesSelectedPrivilegedNaturalBoundsAcrossRotationAndFreshSizeChanges() {
        val inventory = DisplayInventory(listOf(display(7, width = 2400, height = 1080, rotation = 1)))
        val settings = Settings().apply { displayId = 7; cropWidth = 1080; cropHeight = 2400 }
        settings.validate(); inventory.validate(settings)
        settings.cropX = 1
        assertThrows(IllegalArgumentException::class.java) { inventory.validate(settings) }
        settings.cropX = 0; settings.cropWidth = 1000; settings.cropHeight = 1000
        val changed = DisplayInventory(listOf(display(7, width = 800, height = 600)))
        assertThrows(IllegalArgumentException::class.java) { changed.validate(settings) }
        assertEquals(7, settings.displayId)
    }
    @Test fun singleActualMainDisplayNeedsNoNumericChoiceAndNoFallbackForMissingSavedSelection() {
        val inventory = DisplayInventory(listOf(display(0)))
        assertEquals(1, inventory.choices(0).size)
        assertEquals(2, inventory.choices(99).size)
        assertNull(inventory.choices(99).first().display)
    }
    @Test fun settingsEditorAndFreshProofAndStartupUseTheSameAuthoritativeInventory() {
        val path = Path.of(System.getProperty("senderSources"))
        val editor = Files.readString(path.resolve("CaptureSettingsEditor.kt"))
        assertTrue(editor.contains("spinner(\"displayId\", \"Screen to share\""))
        assertFalse(editor.contains("number(\"displayId\""))
        assertTrue(editor.contains("displayId = selectedDisplayId()"))
        assertTrue(editor.contains("activity.runOnUiThread"))
        assertTrue(editor.contains("dialog !== current"))
        assertTrue(editor.contains("displays.validate(this)"))
        assertTrue(editor.contains("The selected settings apply to the next cast."))
        val setup = Files.readString(path.resolve("ShizukuSetup.kt"))
        assertTrue(setup.contains("displayWorker.execute"))
        assertTrue(setup.contains("owned.remote().displayInventory()"))
        assertTrue(setup.contains("DisplayInventory.fromReport(text).requireAvailable()"))
        val backend = Files.readString(path.resolve("CaptureBackend.kt"))
        assertTrue(backend.contains("HardwareEncoders.fromReport(capabilities).listedBy(capabilities).resolve(settings)"))
        assertTrue(backend.contains("DisplayInventory.fromReport(capabilities).validate(resolved)"))
        assertTrue(backend.contains("bridge.validateVideo(checkNotNull(scid), resolved)"))
        assertTrue(backend.contains("return resolved"))
        assertTrue(Files.readString(path.resolve("ShellCaptureService.kt")).contains("DisplayInventoryDiscovery.read().validate(config)"))
    }
    @Test fun automaticAndExplicitEncoderResolutionPreserveAuthoritativeDisplayCropAndGeometry() {
        val inventory = DisplayInventory(listOf(display(7, width = 2400, height = 1080, rotation = 1)))
        val encoders = HardwareEncoders(listOf(EncoderMetadata("video.fixture", "video.fixture",
            listOf("video/avc"), true, true, false)))
        for (selected in listOf("", "video.fixture")) {
            val settings = Settings().apply {
                audio = false; videoEncoder = selected; displayId = 7
                cropWidth = 1000; cropHeight = 1000; cropX = 20; cropY = 228
                orientation = "90"
            }
            val before = settings.request().toString()
            val resolved = encoders.resolve(settings)
            assertEquals("video.fixture", resolved.videoEncoder)
            assertEquals(7, resolved.displayId)
            assertEquals(20, resolved.cropX); assertEquals(228, resolved.cropY)
            assertEquals(1000, resolved.cropWidth); assertEquals(1000, resolved.cropHeight)
            assertEquals("90", resolved.orientation)
            inventory.validate(resolved)
            assertEquals(ScrcpyVideoSizing.calculate(settings, inventory),
                ScrcpyVideoSizing.calculate(resolved, inventory))
            assertEquals(before, settings.request().toString())
            val removed = DisplayInventory(listOf(display(0)))
            assertThrows(IOException::class.java) { removed.validate(resolved) }
            val resized = DisplayInventory(listOf(display(7, width = 800, height = 600)))
            assertThrows(IllegalArgumentException::class.java) { resized.validate(resolved) }
            assertEquals(7, resolved.displayId)
        }
    }
    @Test fun allVideoSelectionsRemainAvailableWithoutStabilityOrDevelopmentWarnings() {
        for (codec in listOf("h264", "h265")) {
            val settings = Settings().apply { videoCodec = codec }
            assertEquals(codec, Settings.from(settings.request()).videoCodec)
        }
        val editor = Files.readString(Path.of(System.getProperty("senderSources"), "CaptureSettingsEditor.kt"))
        assertFalse(editor.contains("reported phone freeze"))
        assertFalse(editor.contains("not verified fixed"))
        assertFalse(editor.contains("unstable"))
        assertFalse(editor.contains("H.264 is recommended"))
        assertFalse(editor.contains("CaptureSettingsHints"))
        assertTrue(editor.contains("Bitrates.validate"))
        assertTrue(editor.contains("displays.validate"))
    }
}
