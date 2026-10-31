package dev.screencopy.sender

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import java.io.IOException
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

class LocalAdbDiscoveryTest {
    private val local = InetAddress.getByName("192.0.2.10")
    private val remote = InetAddress.getByName("192.0.2.20")
    private fun service(name: String, kind: LocalAdbDiscovery.Kind, host: InetAddress? = local, port: Int = 37123): NsdServiceInfo {
        val result = Mockito.mock(NsdServiceInfo::class.java)
        Mockito.`when`(result.serviceName).thenReturn(name)
        Mockito.`when`(result.serviceType).thenReturn(kind.serviceType)
        Mockito.`when`(result.host).thenReturn(host)
        Mockito.`when`(result.port).thenReturn(port)
        return result
    }

    @Test fun connectionDiscoveryResolvesOnlyTheCurrentLocalTlsService() {
        val nsd = Mockito.mock(NsdManager::class.java)
        val kind = LocalAdbDiscovery.Kind.CONNECT
        val found = service("this-phone-new-port", kind, port = 45678)
        Mockito.doAnswer { invocation ->
            val listener = invocation.getArgument<NsdManager.DiscoveryListener>(2)
            listener.onDiscoveryStarted(kind.serviceType)
            listener.onServiceFound(found)
            null
        }.`when`(nsd).discoverServices(Mockito.anyString(), Mockito.anyInt(), Mockito.any())
        Mockito.doAnswer { invocation ->
            invocation.getArgument<NsdManager.ResolveListener>(1).onServiceResolved(found)
            null
        }.`when`(nsd).resolveService(Mockito.any(), Mockito.any())
        val endpoint = LocalAdbDiscovery(nsd, setOf(local), kind).discover(1000)
        assertEquals(45678, endpoint.port); assertEquals(local, endpoint.address)
        Mockito.verify(nsd).discoverServices(Mockito.eq("_adb-tls-connect._tcp."), Mockito.eq(NsdManager.PROTOCOL_DNS_SD), Mockito.any())
        Mockito.verify(nsd).stopServiceDiscovery(Mockito.any())
    }

    @Test fun otherPhonesAndLegacyServicesCannotSupplyLocalConnectionPort() {
        val nsd = Mockito.mock(NsdManager::class.java)
        val kind = LocalAdbDiscovery.Kind.CONNECT
        val elsewhere = service("other-phone", kind, remote)
        val here = service("this-phone", kind)
        val legacy = service("legacy", kind)
        Mockito.`when`(legacy.serviceType).thenReturn("_adb._tcp.")
        Mockito.doAnswer { invocation ->
            val listener = invocation.getArgument<NsdManager.DiscoveryListener>(2)
            listener.onDiscoveryStarted(kind.serviceType)
            listener.onServiceFound(legacy); listener.onServiceFound(elsewhere); listener.onServiceFound(here)
            null
        }.`when`(nsd).discoverServices(Mockito.anyString(), Mockito.anyInt(), Mockito.any())
        Mockito.doAnswer { invocation ->
            val value = invocation.getArgument<NsdServiceInfo>(0)
            invocation.getArgument<NsdManager.ResolveListener>(1).onServiceResolved(value)
            null
        }.`when`(nsd).resolveService(Mockito.any(), Mockito.any())
        val endpoint = LocalAdbDiscovery(nsd, setOf(local), kind).discover(1000)
        assertEquals(local, endpoint.address)
        Mockito.verify(nsd, Mockito.never()).resolveService(Mockito.eq(legacy), Mockito.any())
    }

    @Test fun missingAdvertisementsTimeOutAndStopInsteadOfClaimingPairingRequired() {
        val nsd = Mockito.mock(NsdManager::class.java)
        Mockito.doAnswer { invocation ->
            invocation.getArgument<NsdManager.DiscoveryListener>(2).onDiscoveryStarted("_adb-tls-connect._tcp.")
            null
        }.`when`(nsd).discoverServices(Mockito.anyString(), Mockito.anyInt(), Mockito.any())
        val error = assertThrows(SocketTimeoutException::class.java) {
            LocalAdbDiscovery(nsd, setOf(local), LocalAdbDiscovery.Kind.CONNECT).discover(20)
        }
        assertTrue(error.message.orEmpty().contains("manually"))
        assertEquals(AdbSetup.Failure.UNAVAILABLE, AdbSetup.failureKind(error))
        Mockito.verify(nsd).stopServiceDiscovery(Mockito.any())
    }

    @Test fun cancelledDiscoveryWakesWaiterAndStopsEvenIfStartCallbackArrivesLate() {
        val nsd = Mockito.mock(NsdManager::class.java)
        val subscribed = CountDownLatch(1)
        var listener: NsdManager.DiscoveryListener? = null
        Mockito.doAnswer { invocation ->
            listener = invocation.getArgument(2); subscribed.countDown(); null
        }.`when`(nsd).discoverServices(Mockito.anyString(), Mockito.anyInt(), Mockito.any())
        val search = LocalAdbDiscovery(nsd, setOf(local), LocalAdbDiscovery.Kind.CONNECT)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val result = executor.submit<LocalAdbDiscovery.Endpoint> { search.discover(6000) }
            assertTrue(subscribed.await(2, TimeUnit.SECONDS))
            search.close()
            val failure = assertThrows(ExecutionException::class.java) { result.get(1, TimeUnit.SECONDS) }
            assertTrue(failure.cause is IOException)
            checkNotNull(listener).onDiscoveryStarted("_adb-tls-connect._tcp.")
            Mockito.verify(nsd).stopServiceDiscovery(Mockito.any())
            search.close()
            Mockito.verify(nsd, Mockito.times(1)).stopServiceDiscovery(Mockito.any())
        } finally { search.close(); executor.shutdownNow() }
    }

    @Test fun failedNsdRegistrationReportsManualFallback() {
        val nsd = Mockito.mock(NsdManager::class.java)
        Mockito.doAnswer { invocation ->
            invocation.getArgument<NsdManager.DiscoveryListener>(2).onStartDiscoveryFailed("_adb-tls-connect._tcp.", 3)
            null
        }.`when`(nsd).discoverServices(Mockito.anyString(), Mockito.anyInt(), Mockito.any())
        val error = assertThrows(IOException::class.java) {
            LocalAdbDiscovery(nsd, setOf(local), LocalAdbDiscovery.Kind.CONNECT).discover(1000)
        }
        assertTrue(error.message.orEmpty().contains("manual"))
    }

    @Test fun pairingDiscoveryProvidesOnlyPortNotTheManualPairingCode() {
        val nsd = Mockito.mock(NsdManager::class.java)
        val kind = LocalAdbDiscovery.Kind.PAIRING
        val found = service("pairing-dialog", kind, port = 39123)
        Mockito.doAnswer { invocation ->
            val listener = invocation.getArgument<NsdManager.DiscoveryListener>(2)
            listener.onDiscoveryStarted(kind.serviceType); listener.onServiceFound(found); null
        }.`when`(nsd).discoverServices(Mockito.anyString(), Mockito.anyInt(), Mockito.any())
        Mockito.doAnswer { invocation ->
            invocation.getArgument<NsdManager.ResolveListener>(1).onServiceResolved(found); null
        }.`when`(nsd).resolveService(Mockito.any(), Mockito.any())
        val endpoint = LocalAdbDiscovery(nsd, setOf(local), kind).discover(1000)
        assertEquals(39123, endpoint.port)
        val pairing = PairingUiState().apply { port = endpoint.port.toString() }
        assertTrue(pairing.code.isEmpty()); assertFalse(pairing.currentDialogConfirmed)
        Mockito.verify(nsd).discoverServices(Mockito.eq("_adb-tls-pairing._tcp."), Mockito.anyInt(), Mockito.any())
    }

    @Test fun endpointFilterRejectsWildcardMulticastRemoteAndInvalidPorts() {
        assertTrue(LocalAdbDiscovery.isLocalEndpoint(local, 1, setOf(local)))
        assertTrue(LocalAdbDiscovery.isLocalEndpoint(local, 65535, setOf(local)))
        assertFalse(LocalAdbDiscovery.isLocalEndpoint(remote, 37123, setOf(local)))
        assertFalse(LocalAdbDiscovery.isLocalEndpoint(local, 0, setOf(local)))
        assertFalse(LocalAdbDiscovery.isLocalEndpoint(local, 65536, setOf(local)))
        val wildcard = InetAddress.getByName("0.0.0.0")
        val multicast = InetAddress.getByName("224.0.0.251")
        assertFalse(LocalAdbDiscovery.isLocalEndpoint(wildcard, 37123, setOf(wildcard)))
        assertFalse(LocalAdbDiscovery.isLocalEndpoint(multicast, 37123, setOf(multicast)))
        assertFalse(LocalAdbDiscovery.matches("_adb._tcp", LocalAdbDiscovery.Kind.CONNECT))
        assertTrue(LocalAdbDiscovery.matches("_adb-tls-connect._tcp", LocalAdbDiscovery.Kind.CONNECT))
    }

    @Test fun failedResolveCanContinueToAnotherLocalService() {
        val nsd = Mockito.mock(NsdManager::class.java)
        val kind = LocalAdbDiscovery.Kind.CONNECT
        val first = service("unresolved", kind)
        val second = service("local", kind, port = 45678)
        Mockito.doAnswer { invocation ->
            val listener = invocation.getArgument<NsdManager.DiscoveryListener>(2)
            listener.onDiscoveryStarted(kind.serviceType); listener.onServiceFound(first); listener.onServiceFound(second)
            null
        }.`when`(nsd).discoverServices(Mockito.anyString(), Mockito.anyInt(), Mockito.any())
        Mockito.doAnswer { invocation ->
            val value = invocation.getArgument<NsdServiceInfo>(0)
            val listener = invocation.getArgument<NsdManager.ResolveListener>(1)
            if (value === first) listener.onResolveFailed(value, 3) else listener.onServiceResolved(value)
            null
        }.`when`(nsd).resolveService(Mockito.any(), Mockito.any())
        assertEquals(45678, LocalAdbDiscovery(nsd, setOf(local), kind).discover(1000).port)
    }
}
