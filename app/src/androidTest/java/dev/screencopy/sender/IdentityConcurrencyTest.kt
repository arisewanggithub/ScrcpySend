package dev.screencopy.sender

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class IdentityConcurrencyTest {
    @Test fun concurrentFirstInitializationKeepsOneKeyAndIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "screencopy-test-${UUID.randomUUID()}"
        val preferences = "$alias-prefs"
        val executor = Executors.newFixedThreadPool(8)
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val creations = AtomicInteger()
        try {
            val futures = (1..8).map {
                executor.submit<Pair<Secrets, String>> {
                    ready.countDown()
                    check(start.await(3, TimeUnit.SECONDS))
                    val store = Secrets(context, alias, preferences)
                    store.getOrCreate("concurrent-fixture") { creations.incrementAndGet(); "shared-value" }
                    store to store.getOrCreate("local-adb-fixture") { UUID.randomUUID().toString() }
                }

            }
            assertTrue(ready.await(3, TimeUnit.SECONDS)); start.countDown()
            val stores = futures.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, creations.get()); assertEquals(1, stores.map { it.second }.toSet().size)
            stores.first().first.put("fixture", "encrypted-regression-value")
            for ((store, _) in stores) assertEquals("encrypted-regression-value", store.get("fixture"))
            assertEquals(stores.first().second, Secrets(context, alias, preferences).get("local-adb-fixture"))
        } finally {
            start.countDown(); executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            context.deleteSharedPreferences(preferences)
        }
    }

    @Test fun obsoleteScreenCopyTrustIsRemovedWithoutReplacingAdbIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val alias = "screencopy-migration-${UUID.randomUUID()}"
        val preferences = "$alias-prefs"
        try {
            val before = Secrets(context, alias, preferences)
            before.put("adbIdentity", "adb-regression-identity")
            before.put("receivers", "obsolete-peer-credential"); before.put("senderId", "obsolete-peer-id")
            val after = Secrets(context, alias, preferences)
            assertEquals("adb-regression-identity", after.get("adbIdentity"))
            assertNull(after.get("receivers")); assertNull(after.get("senderId"))
            assertFalse(context.getSharedPreferences(preferences, 0).contains("receivers"))
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            context.deleteSharedPreferences(preferences)
        }
    }
}
