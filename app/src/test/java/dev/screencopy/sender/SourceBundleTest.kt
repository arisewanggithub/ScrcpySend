package dev.screencopy.sender

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.ZipFile

class SourceBundleTest {
    @Test fun allNativeCmakeInputsAndPinnedSubmoduleOriginAreBundled() {
        ZipFile(System.getProperty("pairingSources")).use { zip ->
            val root = "spake2-java-7615ddd680b990e14513ebb66eac4cb0dbf82464/"
            val origins = JSONObject(zip.getInputStream(checkNotNull(zip.getEntry(root + "SOURCE_ORIGINS.json"))).reader().use { it.readText() })
            assertEquals("0d15933e5ba3e662cb01245a7ac0dc9fca3eac31", origins.getJSONObject("native").getString("revision"))
            for (relative in listOf(
                "LICENSE", "android/src/main/cpp/CMakeLists.txt", "android/src/main/cpp/spake2_jni.cpp",
                "android/src/main/cpp/spake2-c/sha512.c", "android/src/main/cpp/spake2-c/spake2.c",
                "android/src/main/cpp/spake2-c/include/spake2/sha512.h", "android/src/main/cpp/spake2-c/include/spake2/spake2.h",
                "android/src/main/cpp/spake2-c/LICENSE"
            )) {
                val entry = zip.getEntry(root + relative)
                assertNotNull("Missing rebuild input: $relative", entry)
                assertTrue("Empty rebuild input: $relative", entry.size > 0)
            }
        }
    }
}
