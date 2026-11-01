package dev.screencopy.sender

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {
    private lateinit var updates: dev.scrcpy.update.UpdateUi
    private lateinit var root: LinearLayout
    private lateinit var setupPanel: LinearLayout
    private lateinit var mainPanel: LinearLayout
    private lateinit var receiverRows: LinearLayout
    private lateinit var empty: TextView
    private lateinit var info: TextView
    private lateinit var connectionNotice: TextView
    private lateinit var setupInstructions: TextView
    private lateinit var connectButton: Button
    private lateinit var cancelConnection: Button
    private lateinit var permissionButton: Button
    private lateinit var openShizukuButton: Button
    private lateinit var installShizukuButton: Button
    private lateinit var helpButton: Button
    private lateinit var settingsButton: Button
    private lateinit var installedVersion: TextView
    private lateinit var retryDiscovery: Button
    private lateinit var rootConsent: CheckBox
    private lateinit var ignoreSizeAndCrop: CheckBox
    private lateinit var appliedSettings: TextView
    private lateinit var sizeNotice: TextView
    private var renderingSize = false
    private lateinit var autoConnect: ShizukuAutoConnect
    private lateinit var settings: CaptureSettingsStore
    private lateinit var editor: CaptureSettingsEditor
    private lateinit var rowActions: ReceiverRowActions
    private val setup = ShizukuSetup.shared
    private val casting = ReceiverCasting.shared
    private var readiness = setup.snapshot()
    private var availability = ShizukuState.STOPPED
    private var accessError: String? = null
    private var permissionTicket: Long? = null
    private var visible = false
    private var discovery: Discovery? = null
    private var discoveryEpoch = 0L
    private val discoveryTrace = DiscoveryLifecycleTrace { android.util.Log.i("ScreenCopySender", it) }
    private var reopenSettings = false
    private var shownError = ""
    private var shownRequest: Long? = null

    private val permissionResult = Shizuku.OnRequestPermissionResultListener { request, grant ->
        if (request == 4201) runOnUiThread {
            if (grant == PackageManager.PERMISSION_GRANTED) permissionTicket?.let { autoConnect.permissionGranted(it) }
            else if (permissionTicket?.let(autoConnect::permissionDenied) == true) {
                setup.accessUnavailable("Shizuku permission denied or revoked.")
                show("Allow ScrcpySend in Shizuku, then retry.")
            }
            reconcileShizuku()
        }
    }
    private val binderReceived = Shizuku.OnBinderReceivedListener { runOnUiThread { if (visible) reconcileShizuku() } }
    private val setupObserver: (ShizukuSetup.Snapshot) -> Unit = {
        runOnUiThread {
            if (visible) {
                readiness = setup.snapshot() // Never replay an obsolete queued proof.
                render()
                reconcileShizuku()
            }
        }
    }
    private val listBinding by lazy {
        ReceiverListBinding(casting, { readiness.ready }, { runOnUiThread(it) }) { current, rows ->
            if (visible) {
                val request = current.active?.request?.token
                if (request != null && request != shownRequest) {
                    shownRequest = request
                    shownError = ""
                    if (current.error.isEmpty()) info.text = ""
                }
                if (current.error.isNotEmpty() && current.error != shownError) { shownError = current.error; show(current.error) }
                render(current, rows)
                reconcileShizuku()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        autoConnect = lastNonConfigurationInstance as? ShizukuAutoConnect
            ?: ShizukuAutoConnect(savedInstanceState?.getBoolean("shizuku_auto_paused") ?: false)
        permissionTicket = autoConnect.pendingPermission
        val preferences = getSharedPreferences("settings", MODE_PRIVATE)
        if (!preferences.edit().remove("host").remove("port").remove("selectedReceiver").remove("endpoints").commit())
            throw java.io.IOException("Cannot remove obsolete receiver preferences")
        settings = CaptureSettingsStore(preferences)
        setup.onConnectionLost = CastService::stopForReadiness
        rowActions = ReceiverRowActions(casting, { setup.requireReady() },
            { settings.readForEditing() },
            { request ->
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 20)
                startForegroundService(Intent(this, CastService::class.java).setAction("start").putExtra("request", request.token))
            },
            { request -> startService(Intent(this, CastService::class.java).setAction("stop").putExtra("request", request.token)) },
            applyRuntimeOverride = true)
        editor = CaptureSettingsEditor(this, settings, setup::requireReady, { CastService.active }, ::show,
            setup::refreshDisplays, { refreshAppliedSettings() }, { updates.checkForUpdates() })
        editor.restore(savedInstanceState?.getBundle("settings_draft"))
        reopenSettings = savedInstanceState?.getBundle("settings_draft")?.getBoolean("open") == true
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        val appHeading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(appHeading)
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text(brand, getString(R.string.app_name)).textSize = 22f
        installedVersion = text(brand, "").apply { id = R.id.home_installed_version; textSize = 14f }
        appHeading.addView(brand,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        settingsButton = button(appHeading, "Settings") {
            if (readiness.ready) editor.show()
            else AlertDialog.Builder(this).setTitle("Settings")
                .setItems(arrayOf("Check for updates")) { _, _ -> updates.checkForUpdates() }.show()
        }.apply { id = R.id.capture_settings }
        updates = dev.scrcpy.update.UpdateUi(this,
            dev.scrcpy.update.UpdateController.get(this, "ScrcpySend", "ScrcpySend.apk"),
            { CastService.active || casting.snapshot().active != null })
        updates.restoreState(savedInstanceState?.getBundle("app_update_ui"))
        updates.returnFocusTo(R.id.capture_settings)
        refreshVersion()
        info = text(root, "")
        appliedSettings = text(root, "").apply { id = R.id.home_applied_settings }
        ignoreSizeAndCrop = CheckBox(this).apply {
            id = R.id.home_ignore_size_crop
            text = "Ignore size and crop (full screen)"
            isSaveEnabled = false
            isChecked = casting.snapshot().size.requested
            setOnCheckedChangeListener { _, checked ->
                if (!renderingSize) {
                    val current = casting.snapshot()
                    try {
                        if (current.active == null) casting.setNextSizeOverride(checked)
                        else if (current.size.enabled) {
                            val token = current.active.request.token
                            startService(SizeNotificationAction.intent(
                                this@MainActivity, token, current.size.version, checked))
                        }
                    } catch (e: Exception) { show("Size change unavailable: ${safe(e)}") }
                    refreshAppliedSettings()
                }
            }
        }
        root.addView(ignoreSizeAndCrop)
        sizeNotice = text(root, "")
        text(root, "Saved settings remain unchanged. While sharing, a size switch explicitly authorizes one clean Stop and new Play to the same receiver. Requests shown, not measured encoder output; automatic encoders resolve before capture.")
        if (casting.snapshot().active == null) SenderFailureStore.read(this)?.let(::show)
        setupPanel = panel(root, R.id.adb_step)
        text(setupPanel, "Shizuku").textSize = 20f
        setupInstructions = text(setupPanel, "Install/start Shizuku separately and allow ScrcpySend. Non-root users restart it after reboot. No pairing ports/codes are entered here.")
        connectionNotice = text(setupPanel, "")
        rootConsent = CheckBox(this).apply {
            text = "I explicitly allow root-mode Shizuku (not required; shell mode recommended)"
            isSaveEnabled = false
            setOnCheckedChangeListener { _, checked -> if (checked && visible) { autoConnect.userRetry(); reconcileShizuku() } }
        }
        setupPanel.addView(rootConsent)
        permissionButton = button(setupPanel, "Allow ScrcpySend in Shizuku") {
            check(Shizuku.pingBinder()) { "Start Shizuku first" }
            check(!Shizuku.isPreV11() && Shizuku.getVersion() >= 13) { "Update Shizuku to version 13+" }
            if (Shizuku.shouldShowRequestPermissionRationale()) show("Open Shizuku > Authorized applications and allow ScrcpySend.")
            else { permissionTicket = autoConnect.permissionRequested(); Shizuku.requestPermission(4201) }
        }.apply { id = R.id.shizuku_permission }
        connectButton = button(setupPanel, "Retry privileged capture") {
            check(!CastService.active) { "Wait for sharing cleanup" }
            autoConnect.userRetry(); reconcileShizuku()
        }.apply { id = R.id.shizuku_connect }
        cancelConnection = button(setupPanel, "Cancel") {
            autoConnect.pause(); setup.disconnect(); readiness = setup.snapshot(); render()
        }.apply { id = R.id.cancel_adb_setup }
        openShizukuButton = button(setupPanel, "Open Shizuku") {
            val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: throw IllegalStateException("Shizuku is not installed; use Official download.")
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        installShizukuButton = button(setupPanel, "Official Shizuku download") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/")))
        }
        helpButton = button(setupPanel, "Help") {
            AlertDialog.Builder(this).setTitle("Shizuku setup").setMessage(
                "Install official Shizuku, start it using its Wireless debugging guide or computer ADB, then grant ScrcpySend permission. Restart after reboot on non-root devices. Root is not required. This app never pairs, approves permissions or changes Developer options for you. Available authorization triggers an actual privileged backend check automatically.")
                .setPositiveButton("OK", null).show()
        }
        mainPanel = panel(root, R.id.receiver_step)
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mainPanel.addView(heading)
        heading.addView(TextView(this).apply { text = "Receivers"; textSize = 20f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        text(mainPanel, "Trusted family LAN only: no encryption or authentication.")
        empty = text(mainPanel, "No receivers found—open Receiver on the same Wi-Fi")
        retryDiscovery = button(mainPanel, "Retry") { restartDiscovery() }.apply { id = R.id.retry_receiver_discovery }
        receiverRows = panel(mainPanel, R.id.receiver_rows)
        render()
    }

    private fun reconcileShizuku() {
        if (!visible || !::receiverRows.isInitialized) return
        val proof = setup.snapshot()
        availability = try { setup.status(this, rootConsent.isChecked || proof.rootApproved) }
        catch (e: RuntimeException) {
            accessError = "Shizuku Binder check failed: ${e.message ?: e.javaClass.simpleName}. Open Shizuku and retry."
            if (proof.ready) setup.accessUnavailable(checkNotNull(accessError))
            render(); return
        }
        accessError = null
        if (proof.ready && availability != ShizukuState.CONNECTING) {
            setup.accessUnavailable("Shizuku permission, Binder or supported identity is unavailable.")
            return
        }
        readiness = setup.snapshot()
        when (autoConnect.decide(availability, readiness.ready, readiness.state == ShizukuState.CONNECTING,
            readiness.cleanupPending, CastService.active, Shizuku.getBinder())) {
            ShizukuAutoConnect.Action.CHECK -> {
                try { setup.connect(applicationContext, rootConsent.isChecked) }
                catch (e: Exception) { accessError = "Shizuku check failed: ${safe(e)}. Retry when setup is idle." }
            }
            ShizukuAutoConnect.Action.REUSE, ShizukuAutoConnect.Action.NONE -> Unit
        }
        readiness = setup.snapshot(); render()
    }
    private fun renderShizuku() {
        val available = availability == ShizukuState.CONNECTING
        val failed = readiness.state == ShizukuState.ERROR && !readiness.checking
        connectionNotice.text = when {
            accessError != null -> accessError
            readiness.cleanupPending -> "Finishing previous Shizuku cleanup..."
            readiness.checking -> "Connecting to Shizuku; verifying privileged capture..."
            autoConnect.paused && available -> "Automatic check paused. Tap Resume when ready."
            available && failed -> readiness.message
            else -> when (availability) {
                ShizukuState.MISSING -> "Install official Shizuku first."
                ShizukuState.STOPPED -> "Shizuku Binder unavailable. Open/start Shizuku, then return."
                ShizukuState.DENIED -> "Allow ScrcpySend's Shizuku permission."
                ShizukuState.ROOT_CONFIRMATION -> "Root-mode Shizuku requires explicit consent, or start Shizuku as shell."
                ShizukuState.ERROR -> "Unsupported Shizuku identity/version. Update to Shizuku 13+ and use shell mode."
                else -> "Preparing Shizuku capture check..."
            }
        }
        val progress = readiness.checking && accessError == null
        setupInstructions.visibility = if (progress || (available && !failed && !autoConnect.paused && accessError == null)) View.GONE else View.VISIBLE
        permissionButton.visibility = if (!progress && availability == ShizukuState.DENIED) View.VISIBLE else View.GONE
        rootConsent.visibility = if (!progress && (availability == ShizukuState.ROOT_CONFIRMATION || rootConsent.isChecked)) View.VISIBLE else View.GONE
        installShizukuButton.visibility = if (!progress && availability == ShizukuState.MISSING) View.VISIBLE else View.GONE
        openShizukuButton.visibility = if (!progress && availability != ShizukuState.MISSING &&
            (!available || failed || autoConnect.paused || accessError != null)) View.VISIBLE else View.GONE
        helpButton.visibility = if (!progress && (!available || failed || autoConnect.paused || accessError != null)) View.VISIBLE else View.GONE
        connectButton.visibility = if (!progress && available && (failed || autoConnect.paused || accessError != null)) View.VISIBLE else View.GONE
        connectButton.text = if (autoConnect.paused) "Resume privileged capture check" else "Retry privileged capture"
        cancelConnection.visibility = if (readiness.checking) View.VISIBLE else View.GONE
        permissionButton.isEnabled = !readiness.checking
        rootConsent.isEnabled = !readiness.checking
    }
    private fun render(current: ReceiverCasting.Snapshot = casting.snapshot(),
                       rows: List<ReceiverCasting.Row> = casting.rows(readiness.ready)) {
        if (!::receiverRows.isInitialized) return
        val owned = current.active
        val effects = ReceiverMainScreen.effects(readiness.ready, visible, owned != null)
        setupPanel.visibility = if (!readiness.ready && owned == null) View.VISIBLE else View.GONE
        mainPanel.visibility = if (ReceiverMainScreen.listVisible(readiness.ready, owned != null)) View.VISIBLE else View.GONE
        settingsButton.isEnabled = owned == null
        ignoreSizeAndCrop.isEnabled = current.size.enabled
        refreshAppliedSettings(current)
        window.setFlags(if (effects.keepScreenOn) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0,
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderShizuku()
        updates.refresh()
        refreshVersion()
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        retryDiscovery.visibility = if (readiness.ready && (rows.isEmpty() || discovery == null)) View.VISIBLE else View.GONE
        receiverRows.removeAllViews()
        rows.forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val description = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text(description, row.target.name)
            text(description, row.target.host)
            if (row.detail.isNotEmpty()) text(description, row.detail)
            line.addView(description, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            button(line, row.label) {
                rowActions.press(ReceiverKey.of(row.target), row.requestToken, row.revision)
            }.apply {
                isEnabled = row.enabled
                contentDescription = "${row.label}: ${row.target.name}, ${row.target.host}"
            }
            receiverRows.addView(line)
        }
        editor.updateAvailability()
        if (!readiness.ready && editor.showing) editor.dismiss()
        if (owned != null) reopenSettings = false
        if (reopenSettings && effects.settingsEnabled) {
            reopenSettings = false
            try { editor.show() } catch (e: Exception) { show(safe(e)) }
        }
        updateDiscovery()
    }
    private fun updateDiscovery() {
        traceDiscovery()
        if (!visible || !readiness.ready) {
            if (discovery != null) {
                discoveryEpoch++; discovery?.close(); discovery = null
                casting.updateDiscovery(emptyList())
                traceDiscovery()
            }
            return
        }
        if (discovery != null) return
        val epoch = ++discoveryEpoch
        try {
            discovery = Discovery(this, { items -> runOnUiThread {
                if (visible && readiness.ready && epoch == discoveryEpoch) casting.updateDiscovery(items)
            } }, { message -> runOnUiThread { if (visible && epoch == discoveryEpoch) show(message) } })
            traceDiscovery()
        } catch (e: Exception) {
            android.util.Log.e("ScreenCopySender", "event=DISCOVERY_START_FAILED exception=${e.javaClass.simpleName}")
            show("Discovery unavailable: ${safe(e)}. Retry on the same Wi-Fi.")
        }
    }
    private fun traceDiscovery() = discoveryTrace.state(
        visible, readiness.ready, readiness.state.name, readiness.cleanupPending, discovery != null)
    private fun restartDiscovery() {
        setup.requireReady()
        discoveryEpoch++; discovery?.close(); discovery = null
        casting.updateDiscovery(emptyList())
        updateDiscovery(); render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBundle("app_update_ui", updates.saveState())
        outState.putBoolean("shizuku_auto_paused", autoConnect.paused)
        outState.putBundle("settings_draft", editor.save())
        super.onSaveInstanceState(outState)
    }
    override fun onRetainNonConfigurationInstance(): Any = autoConnect
    override fun onStart() {
        super.onStart(); visible = true; autoConnect.enter()
        updates.start()
        Shizuku.addRequestPermissionResultListener(permissionResult)
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        setup.observe(setupObserver); listBinding.attach()
        render(); reconcileShizuku()
    }
    override fun onResume() { super.onResume(); updates.resume(); refreshVersion(); refreshAppliedSettings(); reconcileShizuku() }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (updates.onActivityResult(requestCode, resultCode, data)) { refreshVersion(); return }
        super.onActivityResult(requestCode, resultCode, data)
    }
    override fun onStop() {
        updates.stop()
        visible = false; autoConnect.leave(isChangingConfigurations)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        Shizuku.removeBinderReceivedListener(binderReceived)
        setup.removeObserver(setupObserver); listBinding.detach()
        discoveryEpoch++; discovery?.close(); discovery = null
        traceDiscovery()
        casting.updateDiscovery(emptyList())
        if (!isChangingConfigurations) { editor.dismiss(); reopenSettings = false }
        if (!isChangingConfigurations && !CastService.active) setup.disconnect()
        super.onStop()
    }
    override fun onDestroy() {
        if (isFinishing && !isChangingConfigurations)
            casting.snapshot().active?.let { casting.cancelSizeSwitch(it.request.token) }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        editor.dismiss(); super.onDestroy()
    }
    private fun refreshVersion() { installedVersion.text = getString(R.string.installed_version, updates.installedVersion) }
    private fun panel(parent: LinearLayout, viewId: Int) = LinearLayout(this).apply {
        id = viewId; orientation = LinearLayout.VERTICAL; isSaveEnabled = false; parent.addView(this)
    }
    private fun text(parent: LinearLayout, value: String) = TextView(this).apply { text = value; parent.addView(this) }
    private fun button(parent: LinearLayout, label: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false
        setOnClickListener {
            try { action() }
            catch (e: ActivityNotFoundException) { show("No application can open this action. Use https://shizuku.rikka.app/download/ or open Shizuku manually.") }
            catch (e: Exception) { show(safe(e)) }
        }
        parent.addView(this)
    }
    private fun safe(error: Exception): String = if (error is org.json.JSONException)
        "Invalid saved capture settings. Open Settings and correct/reset them, then Apply."
        else error.message ?: "Operation failed"
    private fun refreshAppliedSettings(current: ReceiverCasting.Snapshot = casting.snapshot()) {
        if (!::appliedSettings.isInitialized || !::ignoreSizeAndCrop.isInitialized) return
        val active = current.active
        renderingSize = true
        ignoreSizeAndCrop.isChecked = current.size.requested
        ignoreSizeAndCrop.isEnabled = current.size.enabled
        renderingSize = false
        if (::sizeNotice.isInitialized) {
            val manager = getSystemService(android.app.NotificationManager::class.java)
            val notificationsAvailable = manager.areNotificationsEnabled() &&
                manager.getNotificationChannel("casting")?.importance != android.app.NotificationManager.IMPORTANCE_NONE
            sizeNotice.text = listOfNotNull(
                current.size.message.takeIf { it.isNotEmpty() },
                if (!notificationsAvailable)
                    "Notification size action unavailable: notifications are disabled. You can use this home control; allow notifications through Android's normal app controls if wanted."
                else if (active != null && !current.size.enabled && !current.size.busy && current.size.message.isEmpty())
                    "Size switching becomes available when the cast is playing."
                else null).joinToString("\n")
        }
        val heading = if (active == null) "Applied settings — next cast" else "Active cast request"
        appliedSettings.text = try {
            val effective = if (active == null)
                HomeCaptureSettings.effective(settings.readForEditing(), current.size.requested)
            else Settings.from(Wire.parseJson(active.appliedRequest.settings.toByteArray(Charsets.UTF_8)))
            val fullScreen = if (active == null) current.size.requested
                else current.size.applied
            "$heading\n${HomeCaptureSettings.summary(effective, fullScreen)}"
        } catch (e: Exception) {
            "$heading\nUnavailable: ${safe(e)}"
        }
    }
    private fun show(message: String) { info.text = message }
}
