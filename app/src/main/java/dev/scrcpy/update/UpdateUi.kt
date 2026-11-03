package dev.scrcpy.update

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class UpdateUi internal constructor(
    private val activity: Activity,
    private val controller: UpdateController,
    private val casting: () -> Boolean,
    private val installs: InstallActions
) {
    companion object { const val INSTALL_REQUEST = 7312 }
    internal interface InstallActions {
        fun allowed(): Boolean
        fun openSettings()
        fun install(intent: Intent, requestCode: Int)
    }
    constructor(activity: Activity, controller: UpdateController, casting: () -> Boolean) :
        this(activity, controller, casting, object : InstallActions {
            override fun allowed(): Boolean = Build.VERSION.SDK_INT < 26 || activity.packageManager.canRequestPackageInstalls()
            override fun openSettings() {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
            }
            @Suppress("DEPRECATION")
            override fun install(intent: Intent, requestCode: Int) { activity.startActivityForResult(intent, requestCode) }
        })

    private var check: Button? = null
    private var update: Button? = null
    private var action: Button? = null
    private var cancel: Button? = null
    private var status: TextView? = null
    private var currentVersion: TextView? = null
    private var availableVersion: TextView? = null
    private var availableVersionRow: LinearLayout? = null
    private var dialogStatus: TextView? = null
    private var progress: TextView? = null
    private var details: LinearLayout? = null
    private var detailsToggle: Button? = null
    private var detailsExpanded = false
    private var dialog: AlertDialog? = null
    private var visible = false
    private var awaitingPermission = false
    private var installing = false
    private var reopen = false
    private var localNotice: String? = null
    private var previousFocusId = View.NO_ID
    private var launchCheckStarted = false
    private var automaticPromptPending = false
    private var automaticDialog = false
    val installedVersion: String get() = controller.installedName

    fun mount(parent: LinearLayout, checkId: Int = View.NO_ID, updateId: Int = View.NO_ID,
        previousFocusId: Int = View.NO_ID) {
        this.previousFocusId = previousFocusId
        check = button(parent, "Check for updates", click = ::checkForUpdates).apply {
            id = if (checkId == View.NO_ID) View.generateViewId() else checkId
        }
        status = text(parent, controller.status).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.DKGRAY)
        }
        update = button(parent, "Update available — details") { show() }.apply {
            id = if (updateId == View.NO_ID) View.generateViewId() else updateId
            visibility = if (controller.available()) View.VISIBLE else View.GONE
        }
        wireHomeFocus()
    }
    private fun wireHomeFocus() {
        if (previousFocusId == View.NO_ID) return
        val checkButton = check ?: return
        val updateButton = update ?: return
        checkButton.nextFocusUpId = previousFocusId
        checkButton.nextFocusLeftId = previousFocusId
        val next = if (updateButton.visibility == View.VISIBLE) updateButton.id else View.NO_ID
        checkButton.nextFocusDownId = next
        checkButton.nextFocusRightId = next
        updateButton.nextFocusUpId = checkButton.id
        updateButton.nextFocusLeftId = checkButton.id
    }
    fun start() {
        visible = true
        installing = controller.installPending()
        if (!launchCheckStarted) controller.finishUpdateRequested = false
        controller.attach { render() }
        if (!launchCheckStarted) {
            launchCheckStarted = true
            automaticPromptPending = true
            controller.foregroundCheck()
            render()
        }
        if (awaitingPermission) {
            awaitingPermission = false
            val continueInstall = controller.finishUpdateRequested && allowed() && !casting()
            controller.finishUpdateRequested = false
            if (!casting()) show() else automaticPromptPending = true
            localNotice = if (allowed()) "Install permission enabled. Choose Install update."
                else "Install permission is still disabled. Choose Allow installs and enable this app, then return."
            if (continueInstall) act()
            render()
        }
    }
    fun saveState(): Bundle = Bundle().apply {
        putBoolean("awaiting_permission", awaitingPermission)
        putBoolean("reopen", dialog != null && !automaticDialog || controller.installPending())
        putBoolean("launch_check_started", launchCheckStarted)
        putBoolean("automatic_prompt_pending", automaticPromptPending || automaticDialog)
        putBoolean("details_expanded", detailsExpanded)
    }
    fun restoreState(state: Bundle?) {
        if (state == null) return
        awaitingPermission = state.getBoolean("awaiting_permission")
        reopen = state.getBoolean("reopen")
        launchCheckStarted = state.getBoolean("launch_check_started")
        automaticPromptPending = state.getBoolean("automatic_prompt_pending")
        detailsExpanded = state.getBoolean("details_expanded")
        installing = controller.installPending()
    }
    fun resume() {
        val returning = controller.installPending()
        if (returning) controller.reconcileInstall(null)
        installing = controller.installPending()
        val actionable = controller.available() || controller.installNeedsRetry()
        if ((returning || reopen) && visible && !casting() && actionable) {
            reopen = false
            show()
        }
        if (!actionable) { reopen = false; if (returning) close() }
        render()
    }
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != INSTALL_REQUEST) return false
        if (controller.installTargetVersion() > 0) {
            controller.reconcileInstall(resultCode)
            installing = false
            reopen = true
            if (visible && !casting() && (controller.available() || controller.installNeedsRetry())) { reopen = false; show() }
            else if (!controller.available() && !controller.installNeedsRetry()) { reopen = false; close() }
            render()
        }
        return true
    }
    fun stop() {
        visible = false
        val continuePermission = awaitingPermission && controller.finishUpdateRequested && !activity.isFinishing
        if (!activity.isChangingConfigurations) controller.finishUpdateRequested = false
        if (activity.isChangingConfigurations) controller.detachForRecreation() else controller.detach()
        if (continuePermission) controller.finishUpdateRequested = true
        if (automaticDialog) automaticPromptPending = true
        dialog?.dismiss()
        dialog = null
        automaticDialog = false
    }
    fun returnFocusTo(viewId: Int) { previousFocusId = viewId }
    fun focusCheck() {
        val entry = check?.takeIf { it.isShown } ?:
            if (previousFocusId != View.NO_ID) activity.findViewById<View>(previousFocusId) else null
        entry?.requestFocus()
    }
    fun checkForUpdates() {
        if (!visible || activity.isFinishing || activity.isDestroyed) return
        show()
        controller.check()
    }
    fun refresh() { render() }
    private fun allowed(): Boolean = installs.allowed()
    private fun show(automatic: Boolean = false) {
        if (!visible || activity.isFinishing || activity.isDestroyed || dialog != null) return
        automaticPromptPending = false
        automaticDialog = automatic
        localNotice = null
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(20)
            setPadding(pad, pad, pad, pad)
        }
        currentVersion = versionRow(body, "Current version").second
        val latest = versionRow(body, "Available version")
        availableVersionRow = latest.first
        availableVersion = latest.second
        dialogStatus = text(body, "", 18f, true).apply {
            setPadding(0, dp(16), 0, dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        progress = text(body, "", 14f).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        text(body, "Updates are verified before Android's installer opens.", 13f)
        detailsToggle = button(body, "Show details") { setDetailsExpanded(!detailsExpanded) }
        details = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }.also(body::addView)
        detail("Source", "Stable GitHub release. Wire protocol 3.")
        detail("Verification", "Before installation, checks verify the APK checksum, application ID, version and installed signing key.")
        detail("Installation", "Android may ask for install confirmation. No browser or notification is required.")
        setDetailsExpanded(detailsExpanded)
        val actionButton = button(body, "Download and install update", primary = true, click = ::act).also { action = it }
        val cancelButton = button(body, "Later", click = ::later).also { cancel = it }
        val scroll = ScrollView(activity).apply {
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isFocusable = false
            addView(body)
        }
        val current = AlertDialog.Builder(activity).setTitle("App updates").setView(scroll).create()
        dialog = current
        current.setOnCancelListener {
            if (dialog === current) later()
        }
        current.setOnDismissListener { if (dialog === current) dialog = null }
        current.show()
        render()
        val initialFocus = if (!controller.busy && controller.available()) actionButton else cancelButton
        initialFocus.requestFocus()
        initialFocus.post {
            if (dialog === current && current.isShowing && !initialFocus.isInTouchMode)
                (if (actionButton.isEnabled) actionButton else cancelButton).requestFocus()
        }
    }
    private fun later() {
        controller.finishUpdateRequested = false
        controller.cancel()
        close()
    }
    private fun close() {
        dialog?.dismiss()
        dialog = null
        automaticDialog = false
        detailsExpanded = false
        val updateButton = update
        if (updateButton?.visibility == View.VISIBLE) updateButton.requestFocus() else focusCheck()
    }
    internal fun dialogViewForTests(): View? = dialog?.window?.decorView
    private fun render() {
        if (!visible) return
        val message = controller.displayStatus()
        status?.text = message
        update?.visibility = if (controller.available()) View.VISIBLE else View.GONE
        wireHomeFocus()
        if (automaticDialog && casting()) {
            automaticPromptPending = true
            automaticDialog = false
            dialog?.dismiss()
            dialog = null
        }
        if (dialog == null && automaticPromptPending && controller.checkSucceeded &&
            controller.available() && !controller.busy && !controller.installPending() &&
            !controller.installNeedsRetry() && !casting()) show(automatic = true)
        if (controller.finishUpdateRequested && !controller.busy && !awaitingPermission) {
            controller.finishUpdateRequested = false
            if (controller.downloadReady()) act()
        }
        if (dialog == null) return
        currentVersion?.text = "${controller.installedName} (${controller.installedCode})"
        availableVersionRow?.visibility = if (controller.release == null) View.GONE else View.VISIBLE
        availableVersion?.text = controller.release?.let { "${it.versionName} (${it.versionCode})" }.orEmpty()
        dialogStatus?.text = localNotice ?: message
        val downloadProgress = if (controller.busy && controller.downloaded > 0)
            "Downloaded ${controller.downloaded / 1024} KiB" +
                (if (controller.total > 0) " of ${controller.total / 1024} KiB" else "")
            else ""
        progress?.text = downloadProgress
        progress?.visibility = if (downloadProgress.isEmpty()) View.GONE else View.VISIBLE
        action?.text = if (controller.downloadReady()) {
            if (allowed()) {
                if (controller.installNeedsRetry()) "Retry installation" else "Install update"
            } else "Allow installs"
        } else "Download and install update"
        action?.isEnabled = controller.available() && !controller.busy && !installing
        wireDialogFocus()
        if (controller.busy && cancel?.hasFocus() == false) cancel?.requestFocus()
    }
    private fun act() {
        localNotice = null
        if (installing || controller.installPending()) return
        if (!controller.downloadReady()) {
            controller.finishUpdateRequested = true
            controller.download()
            if (!controller.busy) controller.finishUpdateRequested = false
            return
        }
        if (casting()) {
            localNotice = "Stop casting before installing. This updater will not stop an active cast."
            render()
            return
        }
        controller.verifyForInstall {
            if (!visible) return@verifyForInstall
            if (casting()) {
                localNotice = "Stop casting before installing. The verified download is retained."
                render()
                return@verifyForInstall
            }
            if (!allowed()) {
                try {
                    awaitingPermission = true
                    controller.finishUpdateRequested = true
                    installs.openSettings()
                } catch (_: ActivityNotFoundException) {
                    missingSettings()
                } catch (_: SecurityException) {
                    missingSettings()
                }
                return@verifyForInstall
            }
            val uri = Uri.parse("content://${activity.packageName}.updates/verified.apk")
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true)
            intent.clipData = ClipData.newRawUri("Verified app update", uri)
            try {
                controller.beginInstall()
                if (casting()) {
                    controller.installerUnavailable()
                    localNotice = "Stop casting before installing. The verified download is retained."
                    render()
                    return@verifyForInstall
                }
                installing = true
                installs.install(intent, INSTALL_REQUEST)
                close()
            } catch (_: ActivityNotFoundException) {
                missingInstaller()
            } catch (_: SecurityException) {
                missingInstaller()
            } catch (_: IllegalStateException) {
                installing = false
                localNotice = "Cannot save the installation attempt. Retry when app storage is available."
                render()
            }
        }
    }
    private fun missingSettings() {
        awaitingPermission = false
        controller.finishUpdateRequested = false
        localNotice = "This device has no app-scoped install settings. Open Android Settings, find this app's Install unknown apps permission, then return."
        render()
    }
    private fun missingInstaller() {
        installing = false
        controller.installerUnavailable()
        localNotice = "No Android package installer is available. Ask your device administrator to enable an installer; the verified download is retained."
        render()
    }
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    private fun versionRow(parent: LinearLayout, label: String): Pair<LinearLayout, TextView> {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        parent.addView(row)
        text(row, label, 13f).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, dp(8), 0)
        }
        val value = text(row, "", 16f, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            gravity = Gravity.END
        }
        return row to value
    }
    private fun detail(title: String, description: String) {
        val parent = checkNotNull(details)
        text(parent, title, 14f, true).apply {
            setPadding(0, dp(12), 0, dp(4))
            isAccessibilityHeading = true
        }
        text(parent, description, 13f)
    }
    private fun setDetailsExpanded(expanded: Boolean) {
        detailsExpanded = expanded
        details?.visibility = if (expanded) View.VISIBLE else View.GONE
        detailsToggle?.text = if (expanded) "Hide details" else "Show details"
        detailsToggle?.stateDescription = if (expanded) "Expanded" else "Collapsed"
    }
    private fun wireDialogFocus() {
        val primary = action ?: return
        val later = cancel ?: return
        val disclosure = detailsToggle ?: return
        val availableAction = if (primary.isEnabled) primary else disclosure
        primary.nextFocusDownId = later.id
        primary.nextFocusUpId = disclosure.id
        primary.nextFocusLeftId = later.id
        primary.nextFocusRightId = later.id
        later.nextFocusUpId = availableAction.id
        later.nextFocusDownId = disclosure.id
        later.nextFocusLeftId = availableAction.id
        later.nextFocusRightId = availableAction.id
        disclosure.nextFocusUpId = later.id
        disclosure.nextFocusDownId = if (primary.isEnabled) primary.id else later.id
        disclosure.nextFocusLeftId = later.id
        disclosure.nextFocusRightId = if (primary.isEnabled) primary.id else later.id
    }
    private fun text(parent: LinearLayout, value: String, size: Float = 14f, emphasized: Boolean = false): TextView = TextView(activity).apply {
        text = value
        setTextColor(if (emphasized) Color.BLACK else Color.DKGRAY)
        textSize = size
        if (emphasized) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
        parent.addView(this)
    }
    private fun button(parent: LinearLayout, label: String, primary: Boolean = false, click: () -> Unit): Button = Button(activity).apply {
        id = View.generateViewId()
        text = label
        isAllCaps = false
        textSize = 16f
        minimumHeight = dp(52)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf(android.R.attr.state_focused),
                intArrayOf(android.R.attr.state_pressed), intArrayOf()),
            intArrayOf(Color.DKGRAY, Color.BLACK, Color.BLACK, if (primary) Color.WHITE else Color.BLACK)
        ))
        isFocusable = true
        // Touch taps click immediately; D-pad input still uses normal focus.
        isFocusableInTouchMode = false
        background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(Color.LTGRAY, Color.DKGRAY, 1))
            addState(intArrayOf(android.R.attr.state_focused), shape(Color.rgb(255, 221, 112), Color.BLACK, 4))
            addState(intArrayOf(android.R.attr.state_pressed), shape(Color.rgb(255, 221, 112), Color.BLACK, 4))
            addState(intArrayOf(), shape(if (primary) Color.rgb(0, 105, 92) else Color.LTGRAY, Color.DKGRAY, 1))
        }
        parent.addView(this, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        setOnClickListener { click() }
    }
    private fun shape(fill: Int, stroke: Int, width: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        setStroke(width, stroke)
        cornerRadius = dp(6).toFloat()
    }
}
