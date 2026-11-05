package dev.screencopy.sender

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var ui: SenderUi
    private lateinit var updates: dev.scrcpy.update.UpdateUi
    private lateinit var root: LinearLayout
    private lateinit var receiverRows: LinearLayout
    private lateinit var empty: TextView
    private lateinit var info: TextView
    private lateinit var statusPanel: LinearLayout
    private lateinit var settingsButton: Button
    private lateinit var installedVersion: TextView
    private lateinit var retryDiscovery: Button
    private lateinit var ignoreSizeAndCrop: CheckBox
    private lateinit var appliedSettings: TextView
    private lateinit var appliedSettingsHeading: TextView
    private lateinit var sizeNotice: TextView
    private lateinit var settings: CaptureSettingsStore
    private lateinit var editor: CaptureSettingsEditor
    private lateinit var rowActions: ReceiverRowActions
    private val casting = ReceiverCasting.shared
    private var readiness: ProjectionInventory.Snapshot? = null
    private var pendingConsent: Long? = null
    private var renderingSize = false
    private var visible = false
    private var discovery: Discovery? = null
    private var discoveryEpoch = 0L
    private var reopenSettings = false
    private var shownError = ""
    private val listBinding by lazy {
        ReceiverListBinding(casting, { readiness?.ready == true }, { runOnUiThread(it) }) { current, rows ->
            if (visible) {
                if (current.error.isNotEmpty() && current.error != shownError) {
                    shownError = current.error; show(current.error)
                }
                render(current, rows)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = SenderUi(this)
        pendingConsent = savedInstanceState?.getLong("pending_consent", -1)?.takeIf {
            it > 0 && casting.snapshot().active?.request?.token == it
        }
        val preferences = getSharedPreferences("settings", MODE_PRIVATE)
        if (!preferences.edit().remove("host").remove("port").remove("selectedReceiver").remove("endpoints").commit())
            throw java.io.IOException("Cannot remove obsolete receiver preferences")
        settings = CaptureSettingsStore(preferences)
        rowActions = ReceiverRowActions(casting, { requireReady() }, { settings.readForEditing() },
            ::requestCapture, { request ->
                if (pendingConsent == request.token) {
                    pendingConsent = null
                    casting.complete(request.token)
                } else startService(Intent(this, CastService::class.java).setAction("stop").putExtra("request", request.token))
            }, applyRuntimeOverride = true)
        editor = CaptureSettingsEditor(this, settings, ::requireReady, { CastService.active }, ::show,
            { result -> try { result(Result.success(ProjectionInventory.displays(this))) }
                catch (e: Exception) { result(Result.failure(e)) } },
            { refreshAppliedSettings() }, { updates.checkForUpdates() })
        editor.restore(savedInstanceState?.getBundle("settings_draft"))
        reopenSettings = savedInstanceState?.getBundle("settings_draft")?.getBoolean("open") == true
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = ui.dp(16); setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply {
            id = R.id.home_scroll; isFillViewport = true; addView(root)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }
        }
        setContentView(scroll)
        window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(heading)
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ui.heading(brand, getString(R.string.app_name)).textSize = 26f
        installedVersion = text(brand, "").apply { id = R.id.home_installed_version }
        heading.addView(brand, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        settingsButton = button(heading, "Settings") {
            if (readiness?.ready == true) editor.show()
            else AlertDialog.Builder(this).setTitle("Settings")
                .setItems(arrayOf("Check for updates")) { _, _ -> updates.checkForUpdates() }.show()
        }.apply { id = R.id.capture_settings }
        updates = dev.scrcpy.update.UpdateUi(this,
            dev.scrcpy.update.UpdateController.get(this, "ScrcpySend", "ScrcpySend.apk"),
            { CastService.active })
        updates.restoreState(savedInstanceState?.getBundle("app_update_ui"))
        refreshVersion()
        statusPanel = card(R.id.home_status).also(root::addView)
        val statusHeading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusPanel.addView(statusHeading)
        ui.heading(statusHeading, "Status").layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        button(statusHeading, "Details") {
            AlertDialog.Builder(this).setTitle("Session details").setMessage(info.text).setPositiveButton("OK", null).show()
        }.id = R.id.home_status_details
        info = ui.body(statusPanel, "").apply {
            id = R.id.home_status_message; maxLines = 3; ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        statusPanel.visibility = View.GONE
        if (!CastService.active) try { PlaybackMute.create(this).restore() }
        catch (e: Exception) { show("Could not restore phone audio: ${safe(e)}") }
        val receivers = card(R.id.receiver_step).also(root::addView)
        ui.heading(receivers, "Receivers")
        text(receivers, "Trusted family LAN only: no encryption or authentication.")
        empty = text(receivers, "No receivers found—open Receiver on the same Wi-Fi")
        retryDiscovery = button(receivers, "Retry") { restartDiscovery() }.apply { id = R.id.retry_receiver_discovery }
        receiverRows = LinearLayout(this).apply { id = R.id.receiver_rows; orientation = LinearLayout.VERTICAL }
        receivers.addView(receiverRows)
        val capturePanel = card(R.id.home_capture_settings).also(root::addView)
        appliedSettingsHeading = ui.heading(capturePanel, "").apply { id = R.id.home_applied_settings_heading }
        appliedSettings = ui.body(capturePanel, "").apply { id = R.id.home_applied_settings }
        ignoreSizeAndCrop = CheckBox(this).apply {
            id = R.id.home_ignore_size_crop; text = "Use full screen"; textSize = 16f
            setTextColor(ui.primaryTextColor); setTypeface(typeface, Typeface.BOLD)
            minimumHeight = ui.dp(48); isSaveEnabled = false
            setOnCheckedChangeListener { _, checked ->
                if (!renderingSize) {
                    val current = casting.snapshot()
                    try {
                        if (current.active == null) casting.setNextSizeOverride(checked)
                        else if (current.size.enabled) startService(SizeNotificationAction.intent(
                            this@MainActivity, current.active.request.token, current.size.version, checked))
                    } catch (e: Exception) { show("Size change unavailable: ${safe(e)}") }
                    refreshAppliedSettings()
                }
            }
        }
        capturePanel.addView(ignoreSizeAndCrop)
        text(capturePanel, "Ignores saved size/crop without overwriting them. Switching while casting reuses the current Android sharing session.")
        sizeNotice = text(capturePanel, "").apply { id = R.id.home_size_notice }
        text(capturePanel, "Requested settings, not measured output. Automatic encoders are chosen before capture.")
        if (casting.snapshot().active == null) SenderFailureStore.read(this)?.let(::show)
        refreshInventory()
    }

    private fun requireReady(): ProjectionInventory.Snapshot {
        val result = ProjectionInventory.read(this)
        readiness = result
        return result
    }
    private fun refreshInventory() {
        try { readiness = ProjectionInventory.read(this) }
        catch (e: Exception) { readiness = null; show("Capture capabilities unavailable: ${safe(e)}") }
        render()
    }

    private fun requestCapture(request: ReceiverCasting.Request) {
        check(pendingConsent == null) { "A screen-sharing request is already pending" }
        val selected = Settings.from(Wire.parseJson(request.settings.toByteArray()))
        ProjectionInventory.validate(this, selected, natural = null)
        pendingConsent = request.token
        try {
            if (selected.audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION)
            else requestProjection()
        } catch (e: Exception) { pendingConsent = null; throw e }
    }
    private fun requestProjection() {
        val token = pendingConsent ?: return
        if (casting.snapshot().active?.request?.token != token) { pendingConsent = null; return }
        val manager = getSystemService(MediaProjectionManager::class.java)
        try {
            val intent = if (Build.VERSION.SDK_INT >= 34)
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            else manager.createScreenCaptureIntent()
            startActivityForResult(intent, SCREEN_PERMISSION)
        } catch (e: Exception) {
            pendingConsent = null
            casting.complete(token, "Could not request screen sharing: ${safe(e)}")
            throw e
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                try { requestProjection() } catch (e: Exception) { show("Could not request screen sharing: ${safe(e)}") }
            }
            else {
                pendingConsent?.let { casting.complete(it, "Audio permission denied. Allow audio recording or disable audio in Settings before Play.") }
                pendingConsent = null
            }
        }
    }
    @Deprecated("Activity result API is retained for this framework Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != SCREEN_PERMISSION) return
        val token = pendingConsent ?: return
        pendingConsent = null
        val active = casting.snapshot().active
        if (active?.request?.token != token || active.phase == ReceiverCasting.Phase.STOPPING) return
        if (resultCode != RESULT_OK || data == null) {
            casting.complete(token, "Screen sharing was cancelled. Press Play to try again."); return
        }
        try {
            startForegroundService(Intent(this, CastService::class.java).setAction("start")
                .putExtra("request", token).putExtra("projection", data))
        } catch (e: Exception) { casting.complete(token, "Could not start sharing: ${safe(e)}") }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
    }

    private fun render(current: ReceiverCasting.Snapshot = casting.snapshot(),
                       rows: List<ReceiverCasting.Row> = casting.rows(readiness?.ready == true)) {
        if (!::receiverRows.isInitialized) return
        val owned = current.active
        val effects = ReceiverMainScreen.effects(readiness?.ready == true, visible, owned != null)
        settingsButton.isEnabled = owned == null
        refreshAppliedSettings(current)
        window.setFlags(if (effects.keepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        updates.refresh(); refreshVersion()
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        retryDiscovery.visibility = if (rows.isEmpty() || discovery == null) View.VISIBLE else View.GONE
        receiverRows.removeAllViews()
        rows.forEach { row ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)); background = surface()
            }
            val description = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            ui.label(description, row.target.name)
            text(description, row.target.host)
            if (row.detail.isNotEmpty()) ui.body(description, row.detail)
            line.addView(description, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            button(line, row.label) {
                rowActions.press(ReceiverKey.of(row.target), row.requestToken, row.revision)
            }.apply { isEnabled = row.enabled; contentDescription = "${row.label}: ${row.target.name}, ${row.target.host}" }
            receiverRows.addView(line)
        }
        editor.updateAvailability()
        if (owned != null) reopenSettings = false
        if (reopenSettings && effects.settingsEnabled) { reopenSettings = false; editor.show() }
        updateDiscovery()
    }
    private fun updateDiscovery() {
        if (!visible || readiness?.ready != true || discovery != null) return
        val epoch = ++discoveryEpoch
        try {
            discovery = Discovery(this, { items -> runOnUiThread {
                if (visible && epoch == discoveryEpoch) casting.updateDiscovery(items)
            } }, { message -> runOnUiThread { if (visible && epoch == discoveryEpoch) show(message) } })
        } catch (e: Exception) { show("Discovery unavailable: ${safe(e)}. Retry on the same Wi-Fi.") }
    }
    private fun restartDiscovery() {
        refreshInventory()
        discoveryEpoch++; discovery?.close(); discovery = null
        casting.updateDiscovery(emptyList()); updateDiscovery()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingConsent?.let { outState.putLong("pending_consent", it) }
        outState.putBundle("app_update_ui", updates.saveState())
        outState.putBundle("settings_draft", editor.save())
        super.onSaveInstanceState(outState)
    }
    override fun onStart() {
        super.onStart(); visible = true; updates.start(); listBinding.attach(); refreshInventory()
    }
    override fun onResume() { super.onResume(); updates.resume(); refreshVersion(); refreshAppliedSettings() }
    override fun onStop() {
        updates.stop(); visible = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        listBinding.detach(); discoveryEpoch++; discovery?.close(); discovery = null
        casting.updateDiscovery(emptyList())
        if (!isChangingConfigurations) { editor.dismiss(); reopenSettings = false }
        super.onStop()
    }
    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations) pendingConsent?.let { casting.complete(it, "Screen-sharing request cancelled") }
        editor.dismiss(); super.onDestroy()
    }
    private fun refreshVersion() { installedVersion.text = getString(R.string.installed_version, updates.installedVersion) }
    private fun surface() = GradientDrawable().apply {
        setColor(ui.surfaceColor); cornerRadius = ui.dp(12).toFloat(); setStroke(ui.dp(1), ui.outlineColor)
    }
    private fun card(viewId: Int) = LinearLayout(this).apply {
        id = viewId; orientation = LinearLayout.VERTICAL; isSaveEnabled = false; background = surface()
        setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.dp(16) }
    }
    private fun text(parent: LinearLayout, value: String) = ui.description(parent, value)
    private fun button(parent: LinearLayout, label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; minimumHeight = ui.dp(48)
        setOnClickListener { try { action() } catch (e: Exception) { show(safe(e)) } }; parent.addView(this)
    }
    private fun safe(error: Exception): String = if (error is org.json.JSONException)
        "Invalid saved capture settings. Open Settings and correct/reset them, then Apply."
        else error.message ?: "Operation failed"
    private fun refreshAppliedSettings(current: ReceiverCasting.Snapshot = casting.snapshot()) {
        if (!::appliedSettings.isInitialized) return
        val active = current.active
        renderingSize = true
        ignoreSizeAndCrop.isChecked = current.size.requested; ignoreSizeAndCrop.isEnabled = current.size.enabled
        renderingSize = false
        sizeNotice.text = current.size.message
        sizeNotice.visibility = if (sizeNotice.text.isEmpty()) View.GONE else View.VISIBLE
        appliedSettingsHeading.text = if (active == null) "Applied settings — next cast" else "Active cast request"
        appliedSettings.text = try {
            val effective = if (active == null) HomeCaptureSettings.effective(settings.readForEditing(), current.size.requested)
                else Settings.from(Wire.parseJson(active.appliedRequest.settings.toByteArray()))
            HomeCaptureSettings.summary(effective, if (active == null) current.size.requested else current.size.applied)
        } catch (e: Exception) { "Unavailable: ${safe(e)}" }
    }
    private fun show(message: String) {
        info.text = message; statusPanel.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
    }
    companion object {
        private const val AUDIO_PERMISSION = 30
        private const val SCREEN_PERMISSION = 31
    }
}
