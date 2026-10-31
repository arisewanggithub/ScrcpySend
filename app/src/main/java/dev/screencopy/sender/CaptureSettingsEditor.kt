package dev.screencopy.sender

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Rect
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.widget.AdapterView
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Button

internal class CaptureSettingsEditor(
    private val activity: Activity, private val store: CaptureSettingsStore,
    private val requireReady: () -> ShizukuSetup.Snapshot, private val active: () -> Boolean,
    private val report: (String) -> Unit,
    private val refreshDisplays: (((Result<DisplayInventory>) -> Unit) -> Unit)? = null,
    private val onApplied: () -> Unit = {}
) {
    private var dialog: AlertDialog? = null
    private var pendingDraft: Bundle? = null
    private val fields = linkedMapOf<String, EditText>()
    private val choices = linkedMapOf<String, Spinner>()
    private lateinit var form: LinearLayout
    private lateinit var audio: CheckBox
    private lateinit var notice: TextView
    private lateinit var scroll: ScrollView
    private lateinit var audioRateInfo: TextView
    private var audioRate = 192_000
    private var rateChoices = emptyList<Bitrates.AudioChoice>()
    private var rateContext = ""
    private var bitrateRanges = CodecBitrates()
    private var hardware = HardwareEncoders(emptyList())
    private val encoderInfo = linkedMapOf<String, TextView>()
    private data class EncoderChoice(val name: String, val available: Boolean = true, val hardwareOnly: Boolean = false) {
        override fun toString() = when {
            !available && name.isEmpty() -> "No ${if (hardwareOnly) "hardware" else "compatible"} encoder — automatic unavailable"
            !available -> "Unavailable saved selection: $name — choose ${if (hardwareOnly) "hardware" else "compatible encoder"} or automatic"
            name.isEmpty() -> if (hardwareOnly) "Automatic (hardware only)" else "Automatic (all compatible encoders)"
            else -> name
        }
    }
    private var displays = DisplayInventory(emptyList())
    private lateinit var displayInfo: TextView
    private lateinit var refreshDisplayButton: Button
    private var displayRefreshing = false
    private var displayError: String? = null
    private var displayRequest = 0L
    private var loading = false
    val showing get() = dialog?.isShowing == true

    fun show(): AlertDialog {
        check(!active()) { "Stop sharing and wait for cleanup before opening Settings." }
        if (showing) return checkNotNull(dialog)
        if (dialog != null) dismiss()
        val proof = requireReady()
        bitrateRanges = CodecBitrates.fromReport(proof.capabilities)
        hardware = HardwareEncoders.fromReport(proof.capabilities).listedBy(proof.capabilities)
        displays = DisplayInventory.fromReport(proof.capabilities)
        displayRefreshing = false; displayError = null
        form = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        fields.clear(); choices.clear(); encoderInfo.clear()
        notice = TextView(activity).apply {
            id = R.id.capture_settings_notice
            setPadding(24, 16, 24, 8)
            maxLines = 5
            ellipsize = TextUtils.TruncateAt.END
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            text = "Changes are saved only with Apply. Active encoding is never changed live."
        }
        spinner("mediaTransportMode", "Media transport (experimental modes require matching trial APKs)",
            listOf("Custom TCP", "SRT (fMP4)"))
        text("SRT: H.264/H.265/AV1 + Opus/AAC-LC or audio off. H.264/Opus verified; HEVC rotation/AAC/AV1 validation pending. PCM excluded; no codec/FPS/bitrate fallback. H.264/H.265 support in-band rotation; AV1/audio config changes require a new session.")
        spinner("videoCodec", "Video codec", listOf("h264", "h265", "av1"))
        spinner("videoEncoder", "Video encoder (hardware-accelerated only)", listOf(""))
        encoderInfo["video"] = text("")
        number("videoBitrateMbps", "Video bitrate (Mbps; decimal, e.g. 8 or 2.5)")
        text("Encoder bitrate limits are checked on Apply and again before capture.")
        number("maxSize", "Maximum size (0 = unlimited)")
        number("maxFps", "Maximum FPS")
        spinner("displayId", "Screen to share", emptyList())
        displayInfo = text("")
        refreshDisplayButton = Button(activity).apply {
            id = R.id.refresh_capture_displays
            text = "Refresh screens"
            setOnClickListener { requestDisplayRefresh() }
            form.addView(this)
        }
        spinner("orientation", "Capture orientation", listOf("0", "90", "180", "270", "@0", "@90", "@180", "@270"))
        spinner("receiverOrientation", "Receiver orientation", listOf("Automatic", "Landscape", "Portrait"))
        text("Receiver presentation only; does not rotate the capture.")
        text("Set width, height, X and Y to 0 for the whole display; otherwise width and height must be positive. Offsets are measured from the top-left in the display's default orientation. Cropping happens before maximum-size scaling. Empty X/Y become 0 on leaving the field or Apply.")
        number("cropWidth", "Crop width (pixels)"); number("cropHeight", "Crop height (pixels)")
        number("cropX", "Crop X (pixels)"); number("cropY", "Crop Y (pixels)")
        field("videoOptions", "Typed numeric video codec options", false)
        audio = CheckBox(activity).apply { text = "Include device audio" }; form.addView(audio)
        spinner("audioCodec", "Audio codec", listOf("opus", "aac", "raw"))
        spinner("audioEncoder", "Audio encoder (hardware or software)", listOf(""))
        encoderInfo["audio"] = text("")
        spinner("audioBitrate", "Audio bitrate (kbps)", emptyList())
        audioRateInfo = text("")
        spinner("audioSource", "Audio source", listOf("output", "playback", "mic"))
        field("audioOptions", "Typed numeric audio codec options", false)
        form.addView(android.widget.Button(activity).apply {
            text = "Available displays and encoders"
            setOnClickListener {
                try { AlertDialog.Builder(activity).setTitle("Pinned backend capabilities")
                    .setMessage(requireReady().capabilities).setPositiveButton("OK", null).show() }
                catch (e: Exception) { notice.text = e.message ?: "Capabilities unavailable" }
            }
        })
        text("The selected settings apply to the next cast. No silent codec/audio fallback. Output audio may suppress local playback. Android 11 must be unlocked during startup; protected content may be blank.")
        choices.getValue("videoCodec").onItemSelectedListener = selected {
            if (!loading) encoders("video")
        }
        choices.getValue("videoEncoder").onItemSelectedListener = selected {
            if (!loading) updateEncoderInfo("video")
        }
        choices.getValue("audioCodec").onItemSelectedListener = selected {
            if (!loading) { encoders("audio"); refreshAudioRates(); rawControls() }
        }
        choices.getValue("audioEncoder").onItemSelectedListener = selected {
            if (!loading) { updateEncoderInfo("audio"); refreshAudioRates() }
        }
        choices.getValue("displayId").onItemSelectedListener = selected { if (!loading) updateDisplayInfo() }
        choices.getValue("audioBitrate").onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!loading && parent?.selectedItemPosition == position &&
                    parent.selectedItem === rateChoices.getOrNull(position))
                    rateChoices[position].bps?.let { audioRate = it; updateRateInfo() }
            }
        }
        load(Settings())
        try {
            val saved = store.readForEditing()
            load(saved)
            saved.validate()
        }
        catch (e: Exception) { notice.text = "Saved settings are invalid/unavailable: ${e.message}. Correct them or Reset, then explicitly Apply." }
        pendingDraft?.let(::restoreFields)
        pendingDraft = null
        scroll = ScrollView(activity).apply { id = R.id.capture_settings_scroll; addView(form) }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(notice)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val created = AlertDialog.Builder(activity).setTitle("Capture settings")
            .setView(content)
            .setPositiveButton("Apply", null).setNegativeButton("Close") { _, _ -> dismiss() }
            .setNeutralButton("Reset defaults", null).create()
        dialog = created
        created.setOnDismissListener {
            if (dialog === created) { pendingDraft = draft(); dialog = null }
        }
        created.show()
        created.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (dialog !== created || !created.isShowing) return@setOnClickListener
            try {
                check(!active()) { "Stop sharing from the receiver row or notification before applying settings." }
                check(!displayRefreshing) { "Refreshing screens. Wait for completion or retry after a refresh failure." }
                fields.values.forEach { it.error = null }
                CropEditorInput.Field.entries.filter { it.offset }.forEach(::commitOffset)
                requireReady()
                store.write(read())
                onApplied()
                report("Capture settings saved.")
                dismiss()
            } catch (e: Exception) { showFailure(e) }
        }
        created.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            if (dialog !== created || !created.isShowing) return@setOnClickListener
            if (active()) notice.text = "Stop sharing before resetting settings."
            else if (!displayRefreshing) {
                fields.values.forEach { it.error = null }
                load(Settings()); notice.text = "Defaults selected. Apply to save them."
            }
        }
        updateAvailability()
        if (refreshDisplays != null) requestDisplayRefresh()
        return created
    }
    fun updateAvailability() {
        val current = dialog ?: return
        val editable = !active()
        current.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = editable && !displayRefreshing
        current.getButton(AlertDialog.BUTTON_NEUTRAL)?.isEnabled = editable && !displayRefreshing
        refreshDisplayButton.isEnabled = editable && !displayRefreshing && refreshDisplays != null
        if (!editable) dismiss()
    }
    fun dismiss() {
        val current = dialog ?: return
        displayRequest++
        pendingDraft = draft(); dialog = null
        current.dismiss()
    }
    fun save(): Bundle = draft().apply { putBoolean("open", showing) }
    fun restore(saved: Bundle?) { pendingDraft = saved }
    private fun draft(): Bundle {
        if (!::form.isInitialized) return pendingDraft?.let(::Bundle) ?: Bundle()
        selectedAudioRate()?.let { audioRate = it }
        return Bundle().apply {
            fields.forEach { (key, value) -> putString(key, value.text.toString()) }
            choices.keys.forEach { key -> putString(key, choice(key)) }
            putString("displayId", selectedDisplayId().toString())
            putString("audioBitrateBps", audioRate.toString())
            putBoolean("audio", audio.isChecked)
        }
    }
    private fun restoreFields(saved: Bundle) {
        loading = true
        try {
            saved.getString("mediaTransportMode")?.let { label ->
                val supported = listOf("Custom TCP", "SRT (fMP4)")
                val available = label in supported
                val unavailable = "Unavailable saved transport — choose explicitly"
                setItems(choices.getValue("mediaTransportMode"),
                    if (available) supported else listOf(unavailable) + supported)
                select("mediaTransportMode", if (available) label else unavailable)
                if (!available) notice.text = "Saved media transport is unavailable. Choose Custom TCP or SRT and explicitly Apply."
            }
            for (key in listOf("videoCodec", "audioCodec", "orientation", "audioSource"))
                saved.getString(key)?.let { select(key, it) }
            saved.getString("receiverOrientation")?.let { select("receiverOrientation", it) }
            encoders("video", saved.getString("videoEncoder")); encoders("audio", saved.getString("audioEncoder"))
            fields.forEach { (key, value) -> saved.getString(key)?.let(value::setText) }
            val savedDisplay = saved.getString("displayId")
            setDisplays(if (savedDisplay == null) Settings().displayId else
                savedDisplay.toIntOrNull() ?: throw IllegalArgumentException("Saved display ID is invalid; choose a screen explicitly."))
            if (!saved.containsKey("videoBitrateMbps"))
                saved.getString("videoBitrate")?.let { fields.getValue("videoBitrateMbps").setText(Bitrates.videoMbps(it.toInt())) }
            (saved.getString("audioBitrateBps") ?: saved.getString("audioBitrate"))?.let { audioRate = it.toInt() }
            audio.isChecked = saved.getBoolean("audio", audio.isChecked)
            refreshAudioRates(force = true)
            rawControls()
        } finally { loading = false }
    }
    private fun load(s: Settings) {
        restoreFields(Bundle().apply {
            putString("mediaTransportMode", when (s.mediaTransportMode) {
                "srt" -> "SRT (fMP4)"
                "custom" -> "Custom TCP"
                else -> "Unavailable saved transport — choose explicitly"
            })
            putString("videoCodec", s.videoCodec); putString("videoEncoder", s.videoEncoder)
            putString("orientation", s.orientation); putString("audioCodec", s.audioCodec)
            putString("receiverOrientation", receiverOrientationLabel(s.receiverOrientation))
            putString("audioEncoder", s.audioEncoder); putString("audioSource", s.audioSource); putBoolean("audio", s.audio)
            putString("videoBitrateMbps", Bitrates.videoMbps(s.videoBitrate))
            putString("audioBitrateBps", s.audioBitrate.toString())
            mapOf("maxSize" to s.maxSize, "maxFps" to s.maxFps,
                "displayId" to s.displayId, "cropWidth" to s.cropWidth, "cropHeight" to s.cropHeight,
                "cropX" to s.cropX, "cropY" to s.cropY).forEach { (key, value) -> putString(key, value.toString()) }
            putString("videoOptions", s.videoOptions); putString("audioOptions", s.audioOptions)
        })
    }
    private fun read(): Settings = Settings().apply {
        mediaTransportMode = when (choice("mediaTransportMode")) {
            "SRT (fMP4)" -> "srt"
            "Custom TCP" -> "custom"
            else -> throw IllegalArgumentException("Choose Custom TCP or SRT explicitly before applying.")
        }
        videoCodec = choice("videoCodec"); videoEncoder = choice("videoEncoder"); orientation = choice("orientation")
        receiverOrientation = when (choice("receiverOrientation")) {
            "Landscape" -> "landscape"; "Portrait" -> "portrait"; else -> "auto"
        }
        videoBitrate = Bitrates.videoBps(fields.getValue("videoBitrateMbps").text.toString())
        maxSize = n("maxSize"); maxFps = n("maxFps"); displayId = selectedDisplayId()
        cropWidth = crop(CropEditorInput.Field.WIDTH); cropHeight = crop(CropEditorInput.Field.HEIGHT)
        cropX = crop(CropEditorInput.Field.X); cropY = crop(CropEditorInput.Field.Y)
        videoOptions = fields.getValue("videoOptions").text.toString().trim()
        audio = this@CaptureSettingsEditor.audio.isChecked
        audioCodec = choice("audioCodec"); audioEncoder = choice("audioEncoder"); audioSource = choice("audioSource")
        audioBitrate = selectedAudioRate() ?: audioRate
        audioOptions = fields.getValue("audioOptions").text.toString().trim()
        if (audioCodec == "raw") { audioEncoder = ""; audioOptions = "" }
        validate()
        val proof = requireReady().capabilities
        val resolved = HardwareEncoders.fromReport(proof).listedBy(proof).resolve(this)
        Bitrates.validate(resolved, CodecBitrates.fromReport(proof))
        displays.validate(this)
        DisplayInventory.fromReport(requireReady().capabilities).validate(this)
    }
    private fun text(value: String) = TextView(activity).apply { text = value; form.addView(this) }
    private fun number(key: String, label: String) = field(key, label, true)
    private fun field(key: String, label: String, numeric: Boolean) {
        text(label)
        fields[key] = EditText(activity).apply {
            isSaveEnabled = false; setSingleLine(true)
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            if (key == "videoBitrateMbps") {
                id = R.id.video_bitrate
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            }
            id = when (key) {
                "cropWidth" -> R.id.crop_width; "cropHeight" -> R.id.crop_height
                "cropX" -> R.id.crop_x; "cropY" -> R.id.crop_y
                else -> id
            }
            CropEditorInput.Field.entries.singleOrNull { it.key == key && it.offset }?.let { cropField ->
                setOnFocusChangeListener { view, focused ->
                    if (!focused && fields[key] === view) commitOffset(cropField)
                }
            }
            form.addView(this)
        }
    }
    private fun spinner(key: String, label: String, items: List<String>) {
        text(label)
        choices[key] = Spinner(activity).also {
            it.background = it.background.mutate().also(::boundSpinnerRipples)
            it.id = when (key) { "audioCodec" -> R.id.audio_codec; "audioEncoder" -> R.id.audio_encoder
                "audioBitrate" -> R.id.audio_bitrate; "displayId" -> R.id.capture_display
                "videoCodec" -> R.id.video_codec; "receiverOrientation" -> R.id.receiver_orientation
                "mediaTransportMode" -> R.id.media_transport
                else -> View.NO_ID }
            setItems(it, items); form.addView(it)
        }
    }
    private fun boundSpinnerRipples(background: Drawable) {
        when (background) {
            is RippleDrawable -> if (background.numberOfLayers == 0) {
                // Material's unbounded ripple projects the entire spinner background (including its
                // caret) past ScrollView clipping. An opaque mask bounds it without changing the art.
                val mask = ColorDrawable(Color.WHITE)
                background.setId(background.addLayer(mask), android.R.id.mask)
                // Update RippleDrawable's cached mask as well as the layer ID.
                background.setDrawableByLayerId(android.R.id.mask, mask)
            }
            is LayerDrawable -> for (index in 0 until background.numberOfLayers)
                boundSpinnerRipples(background.getDrawable(index))
        }
    }
    private fun <T> setItems(view: Spinner, values: List<T>) {
        view.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, values).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }
    private fun choice(key: String): String = when (val item = choices.getValue(key).selectedItem) {
        is EncoderChoice -> item.name
        else -> item?.toString().orEmpty()
    }
    private fun select(key: String, value: String) {
        val view = choices.getValue(key)
        val index = (0 until view.count).firstOrNull {
            when (val item = view.getItemAtPosition(it)) {
                is EncoderChoice -> item.name == value
                else -> item == value
            }
        }
            ?: throw IllegalArgumentException("Saved $key is unavailable: $value")
        view.setSelection(index)
    }
    private fun encoders(track: String, requested: String? = null) {
        val view = choices.getValue("${track}Encoder")
        val selected = requested ?: choice("${track}Encoder")
        val names = hardware.names(choice("${track}Codec"))
        val hardwareOnly = HardwareEncoderPolicy.requiresHardware(choice("${track}Codec"))
        val values = (listOf(EncoderChoice("", names.isNotEmpty(), hardwareOnly)) + names.map { EncoderChoice(it) } +
            if (selected.isNotEmpty() && selected !in names) listOf(EncoderChoice(selected, false, hardwareOnly)) else emptyList())
        view.adapter = object : ArrayAdapter<EncoderChoice>(activity, android.R.layout.simple_spinner_item, values) {
            override fun areAllItemsEnabled() = values.all { it.available }
            override fun isEnabled(position: Int) = values[position].available
        }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        select("${track}Encoder", selected)
        updateEncoderInfo(track)
    }
    private fun updateEncoderInfo(track: String) {
        val codec = choice("${track}Codec")
        val selected = choice("${track}Encoder")
        val hardwareOnly = HardwareEncoderPolicy.requiresHardware(codec)
        encoderInfo.getValue(track).text = when {
            codec == "raw" -> "PCM uses the raw audio path, not an encoder."
            hardware.names(codec).isEmpty() -> "No ${if (hardwareOnly) "hardware" else "compatible"} $codec encoder. Choose another codec" +
                if (track == "audio") " or explicitly disable audio. No automatic codec fallback." else " explicitly. No software fallback."
            selected.isNotEmpty() && selected !in hardware.names(codec) ->
                if (hardwareOnly) "Saved encoder is unavailable or not hardware-accelerated. Choose hardware or automatic explicitly."
                else "Saved encoder is unavailable or incompatible. Choose a compatible encoder or automatic explicitly."
            selected.isEmpty() -> "Automatic will use ${hardware.resolve(codec, "")}; settings remain automatic."
            else -> "Selected ${if (hardwareOnly) "hardware " else ""}encoder: $selected"
        }
    }
    private fun effectiveAudioEncoder(): String {
        val selected = choice("audioEncoder")
        return if (selected.isEmpty()) hardware.names(choice("audioCodec")).firstOrNull().orEmpty() else selected
    }
    private fun rawControls() {
        val raw = choice("audioCodec") == "raw"
        choices.getValue("audioEncoder").isEnabled = !raw
        choices.getValue("audioBitrate").isEnabled = !raw; fields.getValue("audioOptions").isEnabled = !raw
    }
    private fun ranges() = bitrateRanges
    private fun selectedDisplayId() = (choices.getValue("displayId").selectedItem as? DisplayInventory.Choice)?.id
        ?: throw IllegalStateException("Select an available capture screen.")
    private fun setDisplays(selected: Int) {
        val view = choices.getValue("displayId")
        val items = displays.choices(selected)
        setItems(view, items)
        view.setSelection(items.indexOfFirst { it.id == selected })
        view.isEnabled = items.size > 1 && !displayRefreshing
        updateDisplayInfo()
    }

    private fun updateDisplayInfo() {
        val selected = choices.getValue("displayId").selectedItem as? DisplayInventory.Choice
        val display = selected?.display
        displayInfo.text = when {
            displayRefreshing -> "Refreshing actual screens through Shizuku..."
            displayError != null -> displayError
            display == null -> "Selected screen is unavailable. Refresh screens and choose one explicitly; no default is substituted."
            display.rotation == null -> "Dimensions from the verified backend. Refresh screens for natural crop bounds."
            else -> "Crop uses natural display bounds ${display.naturalWidth}x${display.naturalHeight}. Screen IDs are saved internally."
        }
    }
    private fun requestDisplayRefresh() {
        val query = refreshDisplays ?: return
        val current = dialog ?: return
        if (active()) { updateAvailability(); return }
        if (displayRefreshing) return
        displayRefreshing = true; displayError = null
        val request = ++displayRequest
        val generation = try { requireReady().generation } catch (e: Exception) {
            displayRefreshing = false; showFailure(e); updateAvailability(); return
        }
        notice.text = "Refreshing screens through Shizuku. Apply will be available when this finishes."
        choices.getValue("displayId").isEnabled = false
        updateDisplayInfo(); updateAvailability()
        fun finish(result: Result<DisplayInventory>) {
            activity.runOnUiThread {
                if (dialog !== current || !current.isShowing || request != displayRequest || !displayRefreshing)
                    return@runOnUiThread
                if (active()) { updateAvailability(); return@runOnUiThread }
                val selected = selectedDisplayId()
                displayRefreshing = false
                val checked = try {
                    check(requireReady().generation == generation) { "Shizuku changed. Reconnect and refresh screens." }
                    result
                } catch (e: Exception) { Result.failure(e) }
                checked.fold({ inventory ->
                    displays = inventory
                    displayError = if (inventory.displays.isEmpty()) "No capture screens available. Refresh or reconnect Shizuku." else null
                }, { error ->
                    displays = DisplayInventory(emptyList())
                    displayError = "Screen refresh failed: ${error.message ?: error.javaClass.simpleName}. Retry or reconnect Shizuku."
                })
                setDisplays(selected); updateAvailability()
                notice.text = displayError ?: "Screens refreshed. Review the settings, then Apply to save."
            }
        }
        try { query(::finish) } catch (e: Exception) { finish(Result.failure(e)) }
    }
    private fun selectedAudioRate() = (choices["audioBitrate"]?.selectedItem as? Bitrates.AudioChoice)?.bps
    private fun refreshAudioRates(force: Boolean = false) {
        val codec = choice("audioCodec")
        val encoder = effectiveAudioEncoder()
        val ranges = ranges()
        val context = "$codec|$encoder|${ranges.report()}"
        if (!force && rateContext == context) return
        rateContext = context
        rateChoices = Bitrates.audioChoices(codec, encoder, audioRate, ranges)
        val view = choices.getValue("audioBitrate")
        setItems(view, rateChoices)
        val index = if (codec == "raw") 0 else rateChoices.indexOfFirst { it.bps == audioRate }
        check(index >= 0) { "Current audio bitrate must be retained for explicit selection" }
        view.setSelection(index)
        updateRateInfo()
    }
    private fun updateRateInfo() {
        val codec = choice("audioCodec")
        audioRateInfo.text = if (codec == "raw") "PCM bitrate is not selectable; sample rate x channels x bits/sample determines the actual rate."
            else if (hardware.names(codec).isEmpty()) "No compatible $codec encoder. Choose another codec or explicitly disable audio."
            else ranges().describe(codec, effectiveAudioEncoder()) + if (rateChoices.any { it.bps == audioRate && !it.valid })
                " Existing bitrate is invalid for this selection. Choose a rate explicitly before enabling this codec." else ""
    }
    private fun n(key: String) = fields.getValue(key).text.toString().toIntOrNull() ?: throw IllegalArgumentException("$key requires an integer")
    private fun crop(field: CropEditorInput.Field) = CropEditorInput.parse(field, fields.getValue(field.key).text.toString())
    private fun commitOffset(field: CropEditorInput.Field) {
        val view = fields.getValue(field.key)
        val text = view.text.toString()
        val committed = CropEditorInput.committedText(field, text)
        if (committed != text) view.setText(committed)
    }
    private fun receiverOrientationLabel(value: String) = when (value) {
        "auto" -> "Automatic"; "landscape" -> "Landscape"; "portrait" -> "Portrait"
        else -> throw IllegalArgumentException("Saved receiver orientation is unavailable: $value")
    }
    private fun showFailure(error: Exception) {
        val message = error.message ?: "Settings could not be applied"
        notice.text = "Not saved: $message"
        val key = when {
            error is CropEditorInput.Invalid -> error.field.key
            error is VideoGeometryException -> if (error.reason == VideoGeometryReason.FRAME_RATE) "maxFps" else "maxSize"
            message.contains("crop", ignoreCase = true) -> {
                CropEditorInput.Field.entries.forEach { fields.getValue(it.key).error = message }
                "cropY"
            }
            message.contains("bitrate", ignoreCase = true) && !message.contains("audio", ignoreCase = true) -> "videoBitrateMbps"
            message.contains("Maximum size", ignoreCase = true) || message.contains("maxSize") -> "maxSize"
            message.contains("Maximum FPS", ignoreCase = true) || message.contains("maxFps") -> "maxFps"
            else -> null
        }
        key?.let { fields.getValue(it) }?.let { view ->
            view.error = message
            view.requestFocus()
            val current = dialog
            view.post {
                if (dialog === current && current?.isShowing == true) {
                    val bounds = Rect()
                    view.getDrawingRect(bounds)
                    view.requestRectangleOnScreen(bounds, true)
                }
            }
        }
    }
    private fun selected(action: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action()
    }
}
