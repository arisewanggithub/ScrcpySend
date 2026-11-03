package dev.scrcpy.update

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class AppUpdateRemoteUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: UpdateTestActivity
    private lateinit var backend: FakeBackend
    private lateinit var ui: UpdateUi
    private val opened = mutableListOf<Intent>()
    private var casting = false
    private var browserError: RuntimeException? = null
    private val url = "https://github.com/arisewanggithub/ScrcpySend/releases/download/v9.0.0/ScrcpySend.apk"
    private inner class FakeBackend : UpdateController(activity, "ScrcpySend", "ScrcpySend.apk", "update_test_browser_ui") {
        private var observer: Observer? = null
        var checks = 0
        var refreshed = 0
        init {
            release = ReleasePolicy.Manifest(activity.packageName, "Fixture", "ScrcpySend.apk",
                "a".repeat(64), "a".repeat(64), installedCode.toInt() + 1)
            status = "Update available: Fixture"
        }
        override fun attach(value: Observer) { observer = value; emit() }
        override fun detach() { observer = null }
        override fun foregroundCheck() { check() }
        override fun check() { checks++; checkSucceeded = true; emit() }
        override fun cancel() { busy = false }
        override fun browserDownloadUrl(): String {
            check(available())
            return url
        }
        override fun download() { fail("Browser flow must not download inside the app") }
        override fun verifyForInstall(ready: () -> Unit) { fail("Browser flow must not launch an installer") }
        override fun refreshInstalledVersion(): Boolean { refreshed++; return true }
        fun emit() { observer?.changed() }
    }
    @Before fun setup() {
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, UpdateTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as UpdateTestActivity
        instrumentation.runOnMainSync {
            backend = FakeBackend()
            ui = newUi()
            ui.mount(activity.content)
            // Tests open manually unless they explicitly test an automatic prompt.
            ui.restoreState(android.os.Bundle().apply { putBoolean("launch_check_started", true) })
            ui.start()
        }
        await("host window") { windowReady(activity.window.decorView) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
        instrumentation.runOnMainSync { ui.focusCheck() }
        await("check focus") {
            windowReady(activity.window.decorView) &&
                button(activity.content, "Check").let { it.hasFocus() && targetReady(it) }
        }
    }
    private fun newUi() = UpdateUi(activity, backend, { casting }, { intent ->
        browserError?.let { throw it }
        opened.add(intent)
    })
    @After fun cleanup() {
        instrumentation.runOnMainSync {
            ui.stop()
            activity.finish()
        }
    }
    private fun button(root: View?, prefix: String): Button {
        fun find(view: View?): Button? {
            if (view is Button && view.text.startsWith(prefix)) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
            return null
        }
        return checkNotNull(find(root)) { "Missing $prefix" }
    }
    private fun text(root: View?, needle: String): TextView? {
        if (root is TextView && root.text.contains(needle)) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) text(root.getChildAt(index), needle)?.let { return it }
        return null
    }
    private fun await(label: String, condition: () -> Boolean) {
        val main = Handler(Looper.getMainLooper())
        val done = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        val deadline = SystemClock.uptimeMillis() + 5000
        val poll = object : Runnable {
            override fun run() {
                try {
                    if (condition()) done.countDown()
                    else if (SystemClock.uptimeMillis() >= deadline) {
                        error.set(AssertionError("Timed out: $label")); done.countDown()
                    } else main.postDelayed(this, 16)
                } catch (failure: Throwable) { error.set(failure); done.countDown() }
            }
        }
        main.post(poll)
        try {
            assertTrue(done.await(6, TimeUnit.SECONDS))
            error.get()?.let { throw it }
        } finally { main.removeCallbacks(poll) }
    }
    private fun open() {
        instrumentation.runOnMainSync { ui.checkForUpdates() }
        awaitDialogFocus("Download")
    }
    private fun windowReady(root: View) = root.isAttachedToWindow && root.hasWindowFocus()
    private fun targetReady(target: View): Boolean {
        val bounds = Rect()
        return target.isShown && target.isEnabled && target.getGlobalVisibleRect(bounds) && !bounds.isEmpty
    }
    private fun awaitDialogFocus(prefix: String) {
        await("$prefix dialog focus") {
            ui.dialogViewForTests()?.let { root ->
                windowReady(root) && button(root, prefix).let { it.hasFocus() && targetReady(it) }
            } == true
        }
    }
    private fun key(code: Int) {
        await("window and focused target before key $code") {
            val root = ui.dialogViewForTests() ?: activity.window.decorView
            windowReady(root) && root.findFocus()?.let { it is Button && targetReady(it) } == true
        }
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
    }
    private fun tap(prefix: String) {
        val bounds = Rect()
        await("window and visible target before tap $prefix") {
            val root = ui.dialogViewForTests() ?: activity.window.decorView
            windowReady(root) && targetReady(button(root, prefix))
        }
        instrumentation.runOnMainSync {
            val root = ui.dialogViewForTests() ?: activity.window.decorView
            assertTrue(windowReady(root))
            val target = button(root, prefix)
            assertTrue(targetReady(target))
            assertFalse(target.isFocusableInTouchMode)
            assertTrue(target.getGlobalVisibleRect(bounds))
            val offset = IntArray(2)
            target.rootView.getLocationOnScreen(offset)
            bounds.offset(offset[0], offset[1])
        }
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
            SystemClock.sleep(30)
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun remoteConfirmOpensOfficialBrowserDownload() {
        open(); key(KeyEvent.KEYCODE_DPAD_CENTER)
        await("browser request") { opened.size == 1 }
        assertEquals(Intent.ACTION_VIEW, opened.single().action)
        assertEquals(url, opened.single().data.toString())
        assertTrue(opened.single().categories.contains(Intent.CATEGORY_BROWSABLE))
    }
    @Test fun oneTouchTapOpensBrowser() {
        open(); tap("Download")
        assertEquals(1, opened.size)
        assertNull(ui.dialogViewForTests())
    }
    @Test fun laterDoesNotOpenBrowser() {
        open(); key(KeyEvent.KEYCODE_DPAD_DOWN)
        awaitDialogFocus("Later")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals(0, opened.size)
        assertNull(ui.dialogViewForTests())
    }
    @Test fun backDismissesWithoutExitingActivity() {
        open(); key(KeyEvent.KEYCODE_BACK)
        assertNull(ui.dialogViewForTests())
        assertFalse(activity.isFinishing)
        assertEquals(0, opened.size)
    }
    @Test fun detailsDoNotDownload() {
        open(); key(KeyEvent.KEYCODE_DPAD_UP)
        awaitDialogFocus("Show details")
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertTrue(button(ui.dialogViewForTests(), "Hide details").isShown)
        assertEquals(0, opened.size)
    }
    @Test fun missingBrowserShowsActionableError() {
        browserError = ActivityNotFoundException()
        open(); tap("Download")
        await("missing browser error") { text(ui.dialogViewForTests(), "No browser") != null }
        assertEquals(0, opened.size)
    }
    @Test fun deniedBrowserShowsActionableError() {
        browserError = SecurityException()
        open(); tap("Download")
        await("browser restriction") { text(ui.dialogViewForTests(), "Android blocked") != null }
        assertEquals(0, opened.size)
    }
    @Test fun browserRetryRequiresExplicitTap() {
        browserError = ActivityNotFoundException()
        open(); tap("Download")
        browserError = null
        assertEquals(0, opened.size)
        tap("Download")
        assertEquals(1, opened.size)
    }
    @Test fun activeCastDoesNotOpenDialogOrBrowser() {
        casting = true
        instrumentation.runOnMainSync { ui.checkForUpdates() }
        assertNull(ui.dialogViewForTests())
        assertEquals(0, opened.size)
    }
    @Test fun castStartingDismissesExistingDialog() {
        open()
        instrumentation.runOnMainSync { casting = true; ui.refresh() }
        assertNull(ui.dialogViewForTests())
        assertEquals(0, opened.size)
    }
    @Test fun restorationDoesNotLaunchBrowserAutomatically() {
        open()
        instrumentation.runOnMainSync {
            val saved = ui.saveState()
            ui.stop()
            activity.content.removeAllViews()
            ui = newUi()
            ui.restoreState(saved); ui.mount(activity.content); ui.start(); ui.resume()
        }
        awaitDialogFocus("Download")
        assertEquals(0, opened.size)
    }
    @Test fun automaticPromptOccursOnlyAfterSuccessfulNewReleaseCheck() {
        instrumentation.runOnMainSync {
            ui.stop(); ui = newUi(); ui.start()
        }
        awaitDialogFocus("Download")
        tap("Later")
        instrumentation.runOnMainSync { ui.resume(); ui.refresh() }
        assertNull(ui.dialogViewForTests())
        assertEquals(0, opened.size)
    }
    @Test fun installedVersionRefreshesOnBrowserReturn() {
        open(); tap("Download")
        instrumentation.runOnMainSync { ui.resume() }
        assertTrue(backend.refreshed > 0)
        assertEquals(1, opened.size)
    }
    @Test fun versionRowsAndHonestVerificationNoticeAreVisible() {
        open()
        instrumentation.runOnMainSync {
            assertNotNull(text(ui.dialogViewForTests(), "Current version"))
            assertNotNull(text(ui.dialogViewForTests(), "Available version"))
            button(ui.dialogViewForTests(), "Show details").performClick()
            assertTrue(checkNotNull(text(ui.dialogViewForTests(), "not checksum-verified")).isShown)
            assertNotNull(text(ui.dialogViewForTests(), "debug-key"))
        }
    }
}
