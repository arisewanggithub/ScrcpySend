package dev.screencopy.sender

import android.content.Intent
import android.os.IBinder
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
import java.security.Provider
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

@RunWith(AndroidJUnit4::class)
class ReleaseRuntimeSmokeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun shizukuUserServiceConstructorLoadsAsBinderWithoutStartingCapture() {
        val type = instrumentation.targetContext.classLoader.loadClass("dev.screencopy.sender.ShellCaptureService")
        val instance = type.getConstructor().newInstance()
        assertTrue(instance is IBinder)
        assertEquals("dev.screencopy.sender.IShizukuCapture", (instance as IBinder).interfaceDescriptor)
        // No attach/deploy/destroy: destroy terminates the UserService process.
    }

    @Test fun localAdbCreatesVerifiableIdentityWithoutConnecting() {
        val context = instrumentation.targetContext
        val alias = "release-smoke-${UUID.randomUUID()}"
        val preferences = "$alias-prefs"
        try {
            LocalAdb(Secrets(context, alias, preferences)).use { identity ->
                val certificate = identity.getCertificate() as X509Certificate
                certificate.checkValidity()
                certificate.verify(certificate.publicKey)
                val challenge = "isolated-release-smoke".toByteArray(Charsets.UTF_8)
                val signature = Signature.getInstance("SHA256withRSA").run {
                    initSign(identity.getPrivateKey()); update(challenge); sign()
                }
                assertTrue(Signature.getInstance("SHA256withRSA").run {
                    initVerify(certificate.publicKey); update(challenge); verify(signature)
                })
                LocalAdb(Secrets(context, alias, preferences)).use { restored ->
                    assertArrayEquals(certificate.encoded, restored.getCertificate().encoded)
                }
            }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            context.deleteSharedPreferences(preferences)
        }
    }

    @Test fun spake2ExchangeUsesBundledNativeRegistration() {
        val type = instrumentation.targetContext.classLoader.loadClass("io.github.muntashirakon.crypto.spake2.Spake2Context")
        val allocate = type.getDeclaredMethod("allocNewContext", Int::class.javaPrimitiveType,
            ByteArray::class.java, ByteArray::class.java).apply { isAccessible = true }
        val generate = type.getDeclaredMethod("generateMessage", Long::class.javaPrimitiveType,
            ByteArray::class.java).apply { isAccessible = true }
        val process = type.getDeclaredMethod("processMessage", Long::class.javaPrimitiveType,
            ByteArray::class.java).apply { isAccessible = true }
        val destroy = type.getDeclaredMethod("destroy", Long::class.javaPrimitiveType).apply { isAccessible = true }
        val aliceName = "release-smoke-alice".toByteArray()
        val bobName = "release-smoke-bob".toByteArray()
        val password = "synthetic-test-vector".toByteArray()
        // Pinned SPAKE2 roles are Alice=0/Bob=1. Invoke the actual kept native
        // boundary rather than assuming optimized Java convenience APIs survive.
        val alice = allocate.invoke(null, 0, aliceName, bobName) as Long
        assertNotEquals(0L, alice)
        try {
            val bob = allocate.invoke(null, 1, bobName, aliceName) as Long
            assertNotEquals(0L, bob)
            try {
                val aliceMessage = generate.invoke(null, alice, password) as ByteArray
                val bobMessage = generate.invoke(null, bob, password) as ByteArray
                val aliceKey = process.invoke(null, alice, bobMessage) as ByteArray
                val bobKey = process.invoke(null, bob, aliceMessage) as ByteArray
                assertTrue(aliceKey.isNotEmpty())
                assertTrue(aliceKey.contentEquals(bobKey))
                aliceKey.fill(0); bobKey.fill(0)
            } finally { destroy.invoke(null, bob) }
        } finally { destroy.invoke(null, alice); password.fill(0) }
    }

    @Test fun libadbConscryptReflectionLoadsNativeProviderAndExporterApi() {
        val loader = instrumentation.targetContext.classLoader
        val provider = loader.loadClass("org.conscrypt.OpenSSLProvider").getConstructor().newInstance() as Provider
        val conscrypt = loader.loadClass("org.conscrypt.Conscrypt")
        assertEquals(true, conscrypt.getMethod("isAvailable").invoke(null))
        SSLContext.getInstance("TLSv1.3", provider).init(null, null, null)
        assertNotNull(conscrypt.getMethod("exportKeyingMaterial", SSLSocket::class.java,
            String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType))
    }

    @Test fun optimizedActivityLaunchesWithRequiredResourcesWithoutSelectingPlay() {
        val activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                assertEquals("ScrcpySend", activity.getString(R.string.app_name))
                assertNotNull(activity.findViewById<View>(R.id.home_applied_settings))
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            instrumentation.waitForIdleSync()
        }
    }
}
