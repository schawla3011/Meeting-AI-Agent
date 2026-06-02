package com.antigravity.meetingrecorder

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat

object ThemeManager {

    const val PREF_NAME  = "pravah_prefs"
    const val KEY_DARK   = "dark_mode"

    /** Returns true if dark mode is currently enabled (default = true). */
    fun isDark(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_DARK, true)
    }

    /** Persists the preference. The calling Activity must recreate() itself. */
    fun setDark(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK, dark).apply()
    }

    // ── Colour helpers ────────────────────────────────────────────────────────

    fun colorBackground(context: Context, dark: Boolean)   = if (dark) col(context, R.color.background_dark)      else col(context, R.color.bg_light)
    fun colorSurface(context: Context, dark: Boolean)      = if (dark) col(context, R.color.surface_dark)         else col(context, R.color.surface_light)
    fun colorCard(context: Context, dark: Boolean)         = if (dark) col(context, R.color.surface_card)         else col(context, R.color.card_light)
    fun colorTextPrimary(context: Context, dark: Boolean)  = if (dark) col(context, R.color.text_primary)         else col(context, R.color.text_primary_light)
    fun colorTextSecondary(context: Context, dark: Boolean)= if (dark) col(context, R.color.text_secondary)       else col(context, R.color.text_secondary_light)
    fun colorDivider(context: Context, dark: Boolean)      = if (dark) col(context, R.color.divider)              else col(context, R.color.divider_light)
    fun colorOwnerChipBg(context: Context, dark: Boolean)  = if (dark) col(context, R.color.owner_chip_bg)        else col(context, R.color.owner_chip_bg_light)
    fun colorOwnerChipText(context: Context, dark: Boolean)= if (dark) col(context, R.color.owner_chip_text)      else col(context, R.color.owner_chip_text_light)

    // ── Drawable res helpers ──────────────────────────────────────────────────

    fun drawableScreen(dark: Boolean)     = if (dark) R.drawable.bg_screen_gradient      else R.drawable.bg_screen_gradient_light
    fun drawableHeader(dark: Boolean)     = if (dark) R.drawable.bg_gradient_header      else R.drawable.bg_gradient_header_light
    fun drawableCard(dark: Boolean)       = if (dark) R.drawable.bg_card_premium         else R.drawable.bg_card_premium_light
    fun drawableBanner(dark: Boolean)     = if (dark) R.drawable.bg_email_banner         else R.drawable.bg_email_banner_light
    fun drawableStatusIdle(dark: Boolean) = if (dark) R.drawable.bg_status_idle          else R.drawable.bg_status_idle_light
    fun drawableChip(dark: Boolean)       = if (dark) R.drawable.bg_chip_selector        else R.drawable.bg_chip_selector_light

    /** History icon colour – always the secondary brand colour on dark, deep purple on light */
    fun colorIconHistory(context: Context, dark: Boolean) =
        if (dark) col(context, R.color.text_secondary) else col(context, R.color.icon_light)

    /** Settings gear colour */
    fun colorIconSettings(context: Context, dark: Boolean) =
        if (dark) col(context, R.color.text_secondary) else col(context, R.color.icon_light)

    private fun col(context: Context, id: Int) = ContextCompat.getColor(context, id)
}
