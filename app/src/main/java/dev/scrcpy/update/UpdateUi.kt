package dev.scrcpy.update

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import dev.screencopy.sender.SenderUi

class UpdateUi internal constructor(
    private val activity: Activity,
    private val controller: UpdateController,
    private val casting: () -> Boolean,
    private val openBrowser: (Intent) -> Unit
) {
    constructor(activity: Activity, controller: UpdateController, casting: () -> Boolean) :
        this(activity, controller, casting, { activity.startActivity(it) })

    private val style = SenderUi(activity)
    private var check: Button? = null
    private var update: Button? = null
    private var status: android.widget.TextView? = null
    private var action: Button? = null
    private var later: Button? = null
    private var disclosure: Button? = null
    private var details: LinearLayout? = null
    private var currentVersion: android.widget.TextView? = null
    private var latestVersion: android.widget.TextView? = null
    private var latestRow: LinearLayout? = null
    private var dialogStatus: android.widget.TextView? = null
    private var dialog: AlertDialog? = null
    private var visible = false
    private var reopen = false
    private var checkedOnLaunch = false
    private var automaticPending = false
    private var automaticDialog = false
    private var expanded = false
    private var localNotice: String? = null
    private var previousFocusId = View.NO_ID
    val installedVersion get() = controller.installedName

    fun mount(parent: LinearLayout, checkId: Int = View.NO_ID, updateId: Int = View.NO_ID,
              previousFocusId: Int = View.NO_ID) {
        this.previousFocusId = previousFocusId
        check = button(parent, "Check for updates", ::checkForUpdates).apply {
            if (checkId != View.NO_ID) id = checkId
        }
        status = style.description(parent, controller.status)
        update = button(parent, "Update available — details", { show() }).apply {
            if (updateId != View.NO_ID) id = updateId
        }
        wireHomeFocus()
    }

    private fun wireHomeFocus() {
        val entry = check ?: return
        val more = update ?: return
        entry.nextFocusUpId = previousFocusId
        entry.nextFocusLeftId = previousFocusId
        entry.nextFocusDownId = if (more.visibility == View.VISIBLE) more.id else View.NO_ID
        entry.nextFocusRightId = entry.nextFocusDownId
        more.nextFocusUpId = entry.id
        more.nextFocusLeftId = entry.id
    }

    fun start() {
        visible = true
        controller.finishUpdateRequested = false
        controller.attach { render() }
        if (!checkedOnLaunch) {
            checkedOnLaunch = true
            automaticPending = true
            controller.foregroundCheck()
            render()
        }
    }

    fun stop() {
        visible = false
        controller.finishUpdateRequested = false
        if (activity.isChangingConfigurations) controller.detachForRecreation() else controller.detach()
        if (automaticDialog) automaticPending = true
        dialog?.dismiss()
        dialog = null
        automaticDialog = false
    }

    fun saveState() = Bundle().apply {
        putBoolean("reopen", dialog != null && !automaticDialog)
        putBoolean("launch_check_started", checkedOnLaunch)
        putBoolean("automatic_prompt_pending", automaticPending || automaticDialog)
        putBoolean("details_expanded", expanded)
    }

    fun restoreState(state: Bundle?) {
        if (state == null) return
        reopen = state.getBoolean("reopen")
        checkedOnLaunch = state.getBoolean("launch_check_started")
        automaticPending = state.getBoolean("automatic_prompt_pending")
        expanded = state.getBoolean("details_expanded")
    }

    fun resume() {
        controller.refreshInstalledVersion()
        if (reopen && visible && !casting()) {
            reopen = false
            show()
        }
        render()
    }

    fun returnFocusTo(viewId: Int) { previousFocusId = viewId }
    fun focusCheck() {
        (check?.takeIf { it.isShown } ?: activity.findViewById<View>(previousFocusId))?.requestFocus()
    }
    fun refresh() = render()
    fun checkForUpdates() {
        if (!visible || activity.isFinishing || activity.isDestroyed) return
        show()
        controller.check()
    }

    private fun show(automatic: Boolean = false) {
        if (!visible || activity.isFinishing || activity.isDestroyed || dialog != null || casting()) return
        automaticPending = false
        automaticDialog = automatic
        localNotice = null
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(style.dp(20), style.dp(12), style.dp(20), style.dp(20))
        }
        currentVersion = versionRow(body, "Current version").second
        val latest = versionRow(body, "Available version")
        latestRow = latest.first
        latestVersion = latest.second
        dialogStatus = style.label(body, "").apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setPadding(0, style.dp(16), 0, style.dp(8))
        }
        style.description(body, "Download the APK in your browser, then open the downloaded file to install it.")
        disclosure = button(body, "Show details", { setExpanded(!expanded) })
        details = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }.also(body::addView)
        style.description(checkNotNull(details),
            "Downloads come from the official stable GitHub release. Android checks the signing certificate when installing. " +
                "Browser downloads are not checksum-verified by this app. Production releases cannot update a debug-key installation.")
        setExpanded(expanded)
        action = button(body, "Download APK in browser", ::downloadInBrowser)
        later = button(body, "Later", ::dismiss)
        val scroll = ScrollView(activity).apply {
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            addView(body)
        }
        val current = AlertDialog.Builder(activity).setTitle("App updates").setView(scroll).create()
        dialog = current
        current.setOnCancelListener { if (dialog === current) dismiss() }
        current.setOnDismissListener { if (dialog === current) dialog = null }
        current.show()
        render()
        val initial = if (action?.isEnabled == true) action else later
        initial?.requestFocus()
        initial?.post {
            if (dialog === current && current.isShowing && !initial.isInTouchMode)
                (if (action?.isEnabled == true) action else later)?.requestFocus()
        }
    }

    private fun downloadInBrowser() {
        if (!visible || activity.isFinishing || activity.isDestroyed || casting()) {
            localNotice = "Stop casting before opening the browser. The app will not stop your cast."
            render()
            return
        }
        try {
            val url = controller.browserDownloadUrl()
            openBrowser(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
            dismiss()
        } catch (error: ActivityNotFoundException) {
            browserFailure(error, "No browser can open this download. Install or enable a browser, then try again.")
        } catch (error: SecurityException) {
            browserFailure(error, "Android blocked opening the browser. Check your device restrictions and try again.")
        } catch (error: IllegalStateException) {
            browserFailure(error, error.message ?: "Download link unavailable. Check for updates again.")
        } catch (error: IllegalArgumentException) {
            browserFailure(error, "Download link is invalid. Check for updates again.")
        }
    }

    private fun browserFailure(error: Exception, message: String) {
        Log.e("ScrcpyAppUpdate", "Browser download launch failed", error)
        localNotice = message
        render()
    }

    private fun dismiss() {
        controller.finishUpdateRequested = false
        controller.cancel()
        dialog?.dismiss()
        dialog = null
        automaticPending = false
        automaticDialog = false
        reopen = false
        expanded = false
        if (update?.isShown == true) update?.requestFocus() else focusCheck()
    }

    private fun render() {
        if (!visible) return
        status?.text = controller.status
        update?.visibility = if (controller.available()) View.VISIBLE else View.GONE
        wireHomeFocus()
        if (dialog != null && casting()) {
            if (automaticDialog) automaticPending = true else reopen = true
            dialog?.dismiss()
            dialog = null
            automaticDialog = false
        }
        if (dialog == null && automaticPending && controller.checkSucceeded &&
            controller.available() && !controller.busy && !casting()) show(true)
        if (dialog == null) return
        currentVersion?.text = "${controller.installedName} (${controller.installedCode})"
        latestRow?.visibility = if (controller.release == null) View.GONE else View.VISIBLE
        latestVersion?.text = controller.release?.let { "${it.versionName} (${it.versionCode})" }.orEmpty()
        dialogStatus?.text = localNotice ?: if (controller.checkSucceeded && !controller.available() && !controller.busy)
            "Installed version is current." else controller.status
        action?.isEnabled = controller.available() && !controller.busy
        val primary = action ?: return
        val secondary = later ?: return
        val more = disclosure ?: return
        primary.nextFocusDownId = secondary.id
        primary.nextFocusUpId = more.id
        secondary.nextFocusUpId = if (primary.isEnabled) primary.id else more.id
        more.nextFocusDownId = if (primary.isEnabled) primary.id else secondary.id
        more.nextFocusUpId = secondary.id
        if (controller.busy) secondary.requestFocus()
    }

    private fun versionRow(parent: LinearLayout, label: String): Pair<LinearLayout, android.widget.TextView> {
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        parent.addView(row)
        style.description(row, label).layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        val value = style.label(row, "").apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            gravity = android.view.Gravity.END
        }
        return row to value
    }
    private fun setExpanded(value: Boolean) {
        expanded = value
        details?.visibility = if (value) View.VISIBLE else View.GONE
        disclosure?.text = if (value) "Hide details" else "Show details"
        disclosure?.stateDescription = if (value) "Expanded" else "Collapsed"
    }
    private fun button(parent: LinearLayout, label: String, click: () -> Unit) = Button(activity).apply {
        id = View.generateViewId()
        text = label
        isAllCaps = false
        isFocusable = true
        isFocusableInTouchMode = false
        minimumHeight = style.dp(52)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = style.dp(8) }
        parent.addView(this)
        setOnClickListener { click() }
    }
    internal fun dialogViewForTests(): View? = dialog?.window?.decorView
}
