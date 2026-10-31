package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ShizukuWiringTest {
    @Test fun v3PeerSelectionHasNoPublicCodeFieldOrPerAttemptComparison() {
        val source = File(System.getProperty("senderSources"))
        val ui = File(source, "MainActivity.kt").readText()
        val session = File(source, "SenderSession.kt").readText()
        assertFalse(ui.contains("receiverCode")); assertFalse(ui.contains("receiverCompared"))
        assertFalse(ui.contains("putExtra(\"code\""))
        assertFalse(session.contains("STALE_CODE"))
        assertTrue(ui.contains("ShizukuSetup.shared"))
        assertTrue(session.contains("Wire.SESSION_REQUEST"))
        assertFalse(ReceiverTarget::class.java.declaredFields.any { it.name == "code" || it.name == "sessionCode" })
    }

    @Test fun providerUsesOfficialProtectedBinderAndPrimaryCaptureNeverCallsEmbeddedAdb() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(System.getProperty("senderManifest")))
        val namespace = "http://schemas.android.com/apk/res/android"
        val providers = manifest.getElementsByTagName("provider")
        val provider = (0 until providers.length).map { providers.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(namespace, "name") == "rikka.shizuku.ShizukuProvider" }
        assertEquals("android.permission.INTERACT_ACROSS_USERS_FULL", provider.getAttributeNS(namespace, "permission"))
        assertEquals("true", provider.getAttributeNS(namespace, "exported"))
        assertEquals("false", provider.getAttributeNS(namespace, "multiprocess"))
        val source = File(System.getProperty("senderSources"))
        val ui = File(source, "MainActivity.kt").readText()
        val session = File(source, "SenderSession.kt").readText()
        assertFalse(ui.contains("LocalAdb(")); assertFalse(ui.contains("prepareAdb("))
        assertFalse(ui.contains("AdbSetup.shared")); assertFalse(session.contains("LocalAdb("))
        assertTrue(ui.contains("Shizuku.requestPermission"))
        assertTrue(session.contains("ShizukuSetup.shared.bridge()"))
        assertFalse(ui.contains("input(\"pairCode\"")); assertFalse(ui.contains("input(\"pairPort\""))
    }

    @Test fun userServiceIsNonDaemonWithOwnerDeathAndFdRatherThanAppUidSocketAccess() {
        val source = File(System.getProperty("senderSources"))
        val client = File(source, "ShizukuClient.kt").readText()
        val service = File(source, "ShellCaptureService.kt").readText()
        assertTrue(client.contains(".daemon(false)"))
        assertTrue(client.contains("remote.attach(owner, allowRoot)"))
        assertTrue(service.contains("ShellCapturePolicy.requireUid(Os.getuid()"))
        assertTrue(service.contains("client.linkToDeath(ownerDeath"))
        assertTrue(service.contains("LocalSocketAddress.Namespace.ABSTRACT"))
        assertTrue(service.contains("ParcelFileDescriptor.dup(socket.fileDescriptor)"))
        assertFalse(client.contains("LocalSocket("))
        assertFalse(client.contains("Shizuku.newProcess")); assertFalse(service.contains("Shizuku.newProcess"))
    }
}
