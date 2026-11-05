package dev.screencopy.sender

import android.content.Context
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito

class SessionSizePreflightTest {
    private fun session() = SenderSession(Mockito.mock(Context::class.java), Settings().apply { audio = false },
        ReceiverTarget("127.0.0.1", 27184), {}, {}, {})

    @Test fun sizeSwitchReusesTheActiveCaptureAndPreservesItsModeAndValidationFailure() {
        val sender = session()
        val capture = Mockito.mock(ProjectionCapture::class.java)
        SenderSession::class.java.getDeclaredField("backend").apply { isAccessible = true }.set(sender, capture)
        val requested = Settings().apply { maxSize = 0; audio = false }
        val resolved = Settings.from(requested.request())
        Mockito.`when`(capture.validate(requested)).thenReturn(resolved)
        assertSame(resolved, sender.validateSizeSwitch(requested))
        Mockito.verify(capture).validate(requested)
        val failure = IOException("UNSUPPORTED_CONFIG: negotiated geometry is unsupported")
        Mockito.doAnswer { throw failure }.`when`(capture).validate(requested)
        assertSame(failure, assertThrows(IOException::class.java) { sender.validateSizeSwitch(requested) })
    }

    @Test fun sizeSwitchBeforeNegotiationFailsInsteadOfAssumingLegacyMode() {
        val error = assertThrows(IOException::class.java) { session().validateSizeSwitch(Settings()) }
        assertTrue(error.message!!.contains("not negotiated"))
    }
}
