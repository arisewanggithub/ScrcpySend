package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test

class ProjectionTransformTest {
    @Test fun asymmetricNaturalCropUsesAndroidPhysicalToLogicalCoordinatesForEveryRotation() {
        val settings = Settings().apply {
            cropX = 100; cropY = 200; cropWidth = 300; cropHeight = 400
        }
        // Android RotationUtils physical-to-logical results, ordered natural TL, TR, BR, BL.
        val corners = listOf(
            listOf(100 to 200, 400 to 200, 400 to 600, 100 to 600),
            listOf(200 to 980, 200 to 680, 600 to 680, 600 to 980),
            listOf(980 to 2200, 680 to 2200, 680 to 1800, 980 to 1800),
            listOf(2200 to 100, 2200 to 400, 1800 to 400, 1800 to 100)
        )
        for (rotation in 0..3) {
            val source = if (rotation % 2 == 0) CaptureDisplay(0, "Panel", 1080, 2400, rotation, 1)
                else CaptureDisplay(0, "Panel", 2400, 1080, rotation, 1)
            for (locked in listOf(false, true)) for (degrees in listOf(0, 90, 180, 270)) {
                settings.orientation = (if (locked) "@" else "") + degrees
                val fixed = ProjectionTransform.coordinates(settings, source, natural = true)
                val expectedNatural = listOf(3, 2, 0, 1).flatMap { index ->
                    val (x, y) = corners[rotation][index]
                    listOf(x.toFloat() / source.width, 1f - y.toFloat() / source.height)
                }.toFloatArray()
                assertArrayEquals("Natural crop at rotation $rotation", expectedNatural, fixed.first, 0.00001f)
                assertEquals(0.75f, fixed.second, 0.00001f)

                val logical = corners[rotation]
                val minX = logical.minOf { it.first }; val maxX = logical.maxOf { it.first }
                val minY = logical.minOf { it.second }; val maxY = logical.maxOf { it.second }
                val upright = listOf(minX to minY, maxX to minY, maxX to maxY, minX to maxY)
                val clockwise = if (locked) (rotation + degrees / 90) % 4 else degrees / 90
                val expected = listOf(3, 2, 0, 1).flatMap { index ->
                    val (x, y) = upright[(index - clockwise + 4) % 4]
                    listOf(x.toFloat() / source.width, 1f - y.toFloat() / source.height)
                }.toFloatArray()
                assertArrayEquals("Legacy crop rotation=$rotation orientation=${settings.orientation}",
                    expected, ProjectionTransform.coordinates(settings, source).first, 0.00001f)
            }
        }
    }

    @Test fun portraitToLandscapeSwapsEncodedCanvasAndKeepsTheNaturalCropValid() {
        val settings = Settings().apply {
            cropWidth = 1080; cropHeight = 1920; cropY = 210
            maxSize = 1920; orientation = "0"
        }
        val portrait = CaptureDisplay(0, "Panel", 1080, 2340, 0, 1)
        val landscape = CaptureDisplay(0, "Panel", 2340, 1080, 1, 1)
        val back = CaptureDisplay(0, "Panel", 1080, 2340, 2, 1)
        val dimensions = listOf(portrait, landscape, back).map { source ->
            DisplayInventory(listOf(source)).validate(settings)
            val geometry = ScrcpyVideoSizing.calculate(settings, DisplayInventory(listOf(source)))
            val plan = ProjectionTransform.coordinates(settings, source)
            assertEquals(geometry.output.width.toFloat() / geometry.output.height, plan.second, 0.00001f)
            geometry.output
        }
        assertEquals(listOf(VideoSize(1080, 1920), VideoSize(1920, 1080), VideoSize(1080, 1920)), dimensions)
        assertEquals(210, settings.cropY)
        settings.orientation = "@0"
        assertEquals(VideoSize(1080, 1920), ScrcpyVideoSizing.calculate(settings, DisplayInventory(listOf(landscape))).output)
    }
    @Test fun fullScreenUsesUnmodifiedTextureCornersAndNaturalAspect() {
        val plan = ProjectionTransform.coordinates(Settings(), CaptureDisplay(0, "Panel", 1080, 2400, 0, 1))
        assertArrayEquals(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), plan.first, 0.00001f)
        assertEquals(1080f / 2400, plan.second, 0.00001f)
    }
    @Test fun naturalCropAndQuarterTurnsRemainBoundedWithoutCpuPixelCopies() {
        for (rotation in 0..3) for (locked in listOf(false, true)) for (degrees in listOf(0, 90, 180, 270)) {
            val settings = Settings().apply {
                cropWidth = 540; cropHeight = 1200; cropX = 270; cropY = 600
                orientation = (if (locked) "@" else "") + degrees
            }
            val panel = if (rotation % 2 == 0) CaptureDisplay(0, "Panel", 1080, 2400, rotation, 1)
                else CaptureDisplay(0, "Panel", 2400, 1080, rotation, 1)
            val plan = ProjectionTransform.coordinates(settings, panel)
            assertEquals(8, plan.first.size)
            assertTrue(plan.first.all { it in 0.24999f..0.75001f })
            val turns = if (locked) degrees / 90 else (degrees / 90 - rotation + 4) % 4
            assertEquals(if (turns % 2 == 0) 540f / 1200 else 1200f / 540, plan.second, 0.00001f)
        }
    }
}
