package dev.screencopy.sender

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.widget.LinearLayout
import android.widget.TextView

internal class SenderUi(private val activity: Activity) {
    val primaryTextColor: ColorStateList
    val secondaryTextColor: ColorStateList
    val surfaceColor: Int
    val outlineColor: Int

    init {
        val colors = activity.obtainStyledAttributes(R.styleable.SenderUiTheme)
        try {
            primaryTextColor = checkNotNull(colors.getColorStateList(R.styleable.SenderUiTheme_android_textColorPrimary))
            secondaryTextColor = checkNotNull(colors.getColorStateList(R.styleable.SenderUiTheme_android_textColorSecondary))
            surfaceColor = checkNotNull(colors.getColorStateList(R.styleable.SenderUiTheme_android_colorBackgroundFloating)).defaultColor
        } finally { colors.recycle() }
        val secondary = secondaryTextColor.defaultColor
        outlineColor = Color.argb(40, Color.red(secondary), Color.green(secondary), Color.blue(secondary))
    }

    fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()

    fun description(parent: LinearLayout, value: String) = TextView(activity).apply {
        text = value
        textSize = 13f
        setTextColor(secondaryTextColor)
        setPadding(0, dp(4), 0, dp(4))
        parent.addView(this)
    }

    fun body(parent: LinearLayout, value: String) = description(parent, value).apply {
        textSize = 14f
        setTextColor(primaryTextColor)
    }

    fun label(parent: LinearLayout, value: String) = description(parent, value).apply {
        textSize = 16f
        setTextColor(primaryTextColor)
        setTypeface(typeface, Typeface.BOLD)
    }

    fun heading(parent: LinearLayout, value: String) = label(parent, value).apply {
        textSize = 20f
        isAccessibilityHeading = true
    }
}
