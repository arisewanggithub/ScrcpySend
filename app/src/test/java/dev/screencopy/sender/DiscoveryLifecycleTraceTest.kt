package dev.screencopy.sender

import org.junit.Assert.*
import org.junit.Test

class DiscoveryLifecycleTraceTest {
    @Test fun stoppedActivityAndReadinessGateAreDistinctFromListening() {
        val events = mutableListOf<String>()
        val trace = DiscoveryLifecycleTrace(events::add)
        trace.state(false, true, "READY", false, false)
        trace.state(true, false, "CONNECTING", true, false)
        trace.state(true, true, "READY", false, true)
        assertEquals(3, events.size)
        assertTrue(events[0].contains("visible=false"))
        assertTrue(events[1].contains("ready=false setup=CONNECTING cleanup=true listening=false"))
        assertTrue(events[2].contains("ready=true setup=READY cleanup=false listening=true"))
    }

    @Test fun quietRenderingDoesNotSpamButListenerLossPublishes() {
        val events = mutableListOf<String>()
        val trace = DiscoveryLifecycleTrace(events::add)
        repeat(10) { trace.state(true, true, "READY", false, true) }
        trace.state(true, true, "READY", false, false)
        assertEquals(2, events.size)
        assertTrue(events.last().endsWith("listening=false"))
    }
}
