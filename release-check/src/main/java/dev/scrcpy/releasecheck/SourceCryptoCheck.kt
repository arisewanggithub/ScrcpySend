package dev.scrcpy.releasecheck

import android.content.Context
import android.os.IBinder
import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Provider
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class SourceCryptoCheck {
    @Test fun actualShizukuBinderConstructorLoadsWithoutAttachingOrDestroying() {
        val binder = TargetApp().type("dev.screencopy.sender.ShellCaptureService").getConstructor().newInstance() as IBinder
        assertEquals("dev.screencopy.sender.IShizukuCapture", binder.interfaceDescriptor)
    }

    @Test fun actualLocalAdbIdentityIsGeneratedAndVerifiedInHarnessStorageOnly() {
        val app = TargetApp()
        val alias = "release-check-${UUID.randomUUID()}"
        val preferences = "$alias-prefs"
        val secrets = app.type("dev.screencopy.sender.Secrets")
        val identityType = app.type("dev.screencopy.sender.LocalAdb")
        val store = secrets.getConstructor(Context::class.java, String::class.java, String::class.java)
        val identity = identityType.getConstructor(secrets)
        try {
            val instance = identity.newInstance(store.newInstance(app.harness, alias, preferences))
            try {
                val certificate = identityType.getMethod("getCertificate").invokeTarget(instance) as X509Certificate
                val key = identityType.getMethod("getPrivateKey").invokeTarget(instance) as PrivateKey
                certificate.checkValidity(); certificate.verify(certificate.publicKey)
                val challenge = "isolated-release-check".toByteArray()
                val signature = Signature.getInstance("SHA256withRSA").run {
                    initSign(key); update(challenge); sign()
                }
                assertTrue(Signature.getInstance("SHA256withRSA").run {
                    initVerify(certificate.publicKey); update(challenge); verify(signature)
                })
                val restored = identity.newInstance(store.newInstance(app.harness, alias, preferences))
                try {
                    val restoredCertificate = identityType.getMethod("getCertificate").invokeTarget(restored) as X509Certificate
                    assertArrayEquals(certificate.encoded, restoredCertificate.encoded)
                } finally { (restored as Closeable).close() }
            } finally { (instance as Closeable).close() }
        } finally {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            app.harness.deleteSharedPreferences(preferences)
        }
    }

    @Test fun actualSpake2NativeRegistrationCompletesSyntheticExchange() {
        val type = TargetApp().type("io.github.muntashirakon.crypto.spake2.Spake2Context")
        val allocate = type.getDeclaredMethod("allocNewContext", Int::class.javaPrimitiveType,
            ByteArray::class.java, ByteArray::class.java).apply { isAccessible = true }
        val generate = type.getDeclaredMethod("generateMessage", Long::class.javaPrimitiveType,
            ByteArray::class.java).apply { isAccessible = true }
        val process = type.getDeclaredMethod("processMessage", Long::class.javaPrimitiveType,
            ByteArray::class.java).apply { isAccessible = true }
        val destroy = type.getDeclaredMethod("destroy", Long::class.javaPrimitiveType).apply { isAccessible = true }
        val password = "synthetic-test-vector".toByteArray()
        val aliceName = "release-check-alice".toByteArray()
        val bobName = "release-check-bob".toByteArray()
        val alice = allocate.invokeTarget(null, 0, aliceName, bobName) as Long
        assertNotEquals(0L, alice)
        try {
            val bob = allocate.invokeTarget(null, 1, bobName, aliceName) as Long
            assertNotEquals(0L, bob)
            try {
                val aliceMessage = generate.invokeTarget(null, alice, password) as ByteArray
                val bobMessage = generate.invokeTarget(null, bob, password) as ByteArray
                val aliceKey = process.invokeTarget(null, alice, bobMessage) as ByteArray
                val bobKey = process.invokeTarget(null, bob, aliceMessage) as ByteArray
                assertTrue(aliceKey.isNotEmpty() && aliceKey.contentEquals(bobKey))
                aliceKey.fill(0); bobKey.fill(0)
            } finally { destroy.invokeTarget(null, bob) }
        } finally { destroy.invokeTarget(null, alice); password.fill(0) }
    }

    @Test fun actualConscryptProviderNativeFactoryAndExporterApiLoad() {
        val app = TargetApp()
        val provider = app.type("org.conscrypt.OpenSSLProvider").getConstructor().newInstance() as Provider
        val conscrypt = app.type("org.conscrypt.Conscrypt")
        assertEquals(true, conscrypt.getMethod("isAvailable").invokeTarget(null))
        SSLContext.getInstance("TLSv1.3", provider).init(null, null, null)
        assertNotNull(conscrypt.getMethod("exportKeyingMaterial", SSLSocket::class.java,
            String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType))
    }
}
