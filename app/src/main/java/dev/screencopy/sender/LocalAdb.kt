package dev.screencopy.sender

import android.os.Build
import android.util.Base64
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

class LocalAdb(store: Secrets) : AbsAdbConnectionManager() {
    private val identityKey: PrivateKey
    private val identityCertificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT); setHostAddress("127.0.0.1")
        setTimeout(10, TimeUnit.SECONDS); setThrowOnUnauthorised(true)
        val identity = store.getOrCreate("adbIdentity") {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
            val name = X500Name("CN=ScreenCopy local ADB")
            val provider = BouncyCastleProvider()
            val builder = JcaX509v3CertificateBuilder(name, BigInteger(128, SecureRandom()),
                Date(System.currentTimeMillis() - 86400000L), Date(System.currentTimeMillis() + 10L * 365 * 86400000),
                name, pair.public)
            val signer = JcaContentSignerBuilder("SHA256withRSA").setProvider(provider).build(pair.private)
            val cert = JcaX509CertificateConverter().setProvider(provider).getCertificate(builder.build(signer))
            Wire.obj("key" to Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP),
                "cert" to Base64.encodeToString(cert.encoded, Base64.NO_WRAP)).toString()
        }
        val saved = Wire.parseJson(identity.toByteArray(Charsets.UTF_8))
        identityKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.decode(saved.getString("key"), Base64.NO_WRAP)))
        identityCertificate = CertificateFactory.getInstance("X.509").generateCertificate(
            ByteArrayInputStream(Base64.decode(saved.getString("cert"), Base64.NO_WRAP)))
    }
    public override fun getPrivateKey(): PrivateKey = identityKey
    public override fun getCertificate(): Certificate = identityCertificate
    override fun getDeviceName() = "ScreenCopySender"
    fun connectLocal(port: Int) {
        Settings.range(port, 1, 65535, "ADB connection port")
        if (!connect("127.0.0.1", port)) throw IOException("ADB unavailable: enable wireless debugging and pair this app")
        if (adbConnection?.isTls != true) {
            disconnect()
            throw IOException("Wireless-debugging TLS port required, not legacy TCP ADB (5555)")
        }
    }
    fun pairLocal(port: Int, code: String) {
        Settings.range(port, 1, 65535, "ADB pairing port")
        Settings.require(code.matches(Regex("[0-9]{6}")), "ADB pairing code must have six digits")
        if (!pair("127.0.0.1", port, code)) throw IOException("Wireless ADB pairing failed")
    }
    fun shell(command: String, maxBytes: Int): String = openStream("shell:$command").use { stream ->
        val output = ByteArrayOutputStream()
        val input = stream.openInputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) break
            if (output.size() + count > maxBytes) throw IOException("ADB command output budget exceeded")
            output.write(buffer, 0, count)
        }
        output.toString(Charsets.UTF_8.name())
    }
    fun push(data: ByteArray, path: String) = openStream("sync:").use { stream ->
        val out = stream.openOutputStream()
        val name = "$path,33204".toByteArray(Charsets.UTF_8)
        out.write(sync("SEND", name.size)); out.write(name)
        var offset = 0
        while (offset < data.size) {
            val count = minOf(65536, data.size - offset)
            out.write(sync("DATA", count)); out.write(data, offset, count); offset += count
        }
        out.write(sync("DONE", (System.currentTimeMillis() / 1000).toInt())); out.flush()
        val result = ByteArray(8).also { DataInputStream(stream.openInputStream()).readFully(it) }
        val status = String(result, 0, 4, Charsets.US_ASCII)
        val length = ByteBuffer.wrap(result, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (status != "OKAY" || length != 0) throw IOException("Backend deployment failed (ADB sync $status)")
    }
    private fun sync(command: String, value: Int) =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(command.toByteArray(Charsets.US_ASCII)).putInt(value).array()

    override fun close() = disconnect()
}
