package dev.screencopy.sender

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Local model and unsent PendingIntents only: no live service, Shizuku, socket or capture. */
@RunWith(AndroidJUnit4::class)
class SizeNotificationDeviceTest {
    private class Fixture(codec: String = "h264") {
        val model = ReceiverCasting { 1L }
        val state = CastingServiceState(model)
        val owner: ReceiverCasting.ServiceLease
        init {
            model.updateDiscovery(listOf(Discovery.Receiver("PRIVATE_PEER_LABEL", "192.0.2.1", 27184, "available", 1)))
            val request = model.reserve(ReceiverKey("192.0.2.1", 27184),
                Settings().apply { videoCodec = codec; maxSize = 1200; cropWidth = 640; cropHeight = 480 },
                applyRuntimeOverride = true)
            owner = checkNotNull(state.adopt(request.token))
            state.progress(owner, "Streaming")
        }
    }

    @Test fun sizeIntentIsExplicitVersionedImmutableServiceActionAndNeverOpensSender() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val f = Fixture()
        val snapshot = f.model.snapshot()
        val token = snapshot.active!!.request.token
        val intent = SizeNotificationAction.intent(context, token, snapshot.size.version, true)
        assertEquals(CastService::class.java.name, intent.component!!.className)
        assertTrue(SizeNotificationAction.valid(intent, token, snapshot.size.version, true))
        assertFalse(SizeNotificationAction.valid(Intent(intent).setData(null), token, snapshot.size.version, true))
        assertFalse(SizeNotificationAction.valid(intent, token, snapshot.size.version + 1, true))
        assertFalse(SizeNotificationAction.valid(intent, token, snapshot.size.version, false))
        assertFalse(SizeNotificationAction.valid(Intent(intent).setAction(
            "dev.screencopy.sender.CONFIRM_H265_SIZE_OVERRIDE"), token, snapshot.size.version, true))
        val notification = CastingNotification.create(context, snapshot)
        val action = notification.actions.single { it.title.toString() == "Full screen" }
        val pending = PendingIntent.getService(context, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val newer = PendingIntent.getService(context, 0,
            SizeNotificationAction.intent(context, token, snapshot.size.version + 1, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            assertEquals(pending, action.actionIntent)
            assertNotEquals(pending, newer)
            assertTrue(action.actionIntent.isService)
            if (Build.VERSION.SDK_INT >= 31) assertTrue(action.actionIntent.isImmutable)
        } finally { pending.cancel(); newer.cancel() }
    }

    @Test fun notificationShowsAppliedGeometryAndH265UsesSameOneTapActionWithoutPrivatePeerDetails() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val f = Fixture()
        val initial = CastingNotification.create(context, f.model.snapshot())
        assertTrue(initial.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Saved size/crop"))
        assertTrue(initial.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("crop 640 × 480"))
        assertFalse(initial.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("PRIVATE_PEER_LABEL"))
        assertFalse(initial.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("192.0.2.1"))
        val snapshot = f.model.snapshot()
        assertEquals(ReceiverCasting.SizeAction.Restart, f.model.requestSizeSwitch(
            snapshot.active!!.request.token, snapshot.size.version, true))
        val applying = CastingNotification.create(context, f.model.snapshot())
        assertEquals(listOf("Stop"), applying.actions.map { it.title.toString() })
        assertTrue(applying.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Applying size..."))
        assertTrue(applying.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Saved size/crop"))
        val next = checkNotNull(f.state.finishSizeSwitch(f.owner, true))
        val owner = checkNotNull(f.state.adopt(next.token))
        val reconnecting = CastingNotification.create(context, f.model.snapshot())
        assertEquals(listOf("Stop"), reconnecting.actions.map { it.title.toString() })
        assertTrue(reconnecting.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
            .contains("crop 640 × 480"))
        f.state.progress(owner, "Streaming")
        val applied = CastingNotification.create(context, f.model.snapshot())
        assertEquals(listOf("Stop", "Saved size/crop"), applied.actions.map { it.title.toString() })
        assertTrue(applied.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString().contains("Full screen"))
        val h265 = Fixture("h265")
        val h265Snapshot = h265.model.snapshot()
        val h265Initial = CastingNotification.create(context, h265Snapshot)
        assertEquals(listOf("Stop", "Full screen"), h265Initial.actions.map { it.title.toString() })
        val h265Text = h265Initial.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertFalse(h265Text.contains("Confirm"))
        assertFalse(h265Text.contains("warning", ignoreCase = true))
        assertFalse(h265Text.contains("kernel panic"))
        assertEquals(ReceiverCasting.SizeAction.Restart, h265.model.requestSizeSwitch(
            h265Snapshot.active!!.request.token, h265Snapshot.size.version, true))
        assertEquals(ReceiverCasting.SizeAction.Ignored, h265.model.requestSizeSwitch(
            h265Snapshot.active.request.token, h265Snapshot.size.version, true))
        val h265Applying = CastingNotification.create(context, h265.model.snapshot())
        assertEquals(listOf("Stop"), h265Applying.actions.map { it.title.toString() })
        for (notification in listOf(initial, applying, reconnecting, applied, h265Initial, h265Applying))
            notification.actions.forEach { it.actionIntent.cancel() }
    }
}
