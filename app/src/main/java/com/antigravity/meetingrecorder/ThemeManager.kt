package com.antigravity.meetingrecorder

import android.content.Context

object ThemeManager {

    const val PREF_NAME = "pravah_prefs"
    const val KEY_DARK  = "dark_mode"

    /** Returns true if dark mode is currently active (default = true). */
    fun isDark(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DARK, true)

    /** Persists the preference. The calling Activity must recreate() itself. */
    fun setDark(context: Context, dark: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DARK, dark).apply()
    }

    // ── Drawable res helpers (night-qualifier drawables are preferred; these
    //    serve as an explicit fallback for code that can't rely on the qualifier) ──

    fun drawableScreen(dark: Boolean)     = if (dark) R.drawable.bg_screen_gradient  else R.drawable.bg_screen_gradient_light
    fun drawableHeader(dark: Boolean)     = if (dark) R.drawable.bg_gradient_header  else R.drawable.bg_gradient_header_light
    fun drawableCard(dark: Boolean)       = if (dark) R.drawable.bg_card_premium     else R.drawable.bg_card_premium_light
    fun drawableBanner(dark: Boolean)     = if (dark) R.drawable.bg_email_banner     else R.drawable.bg_email_banner_light
    fun drawableStatusIdle(dark: Boolean) = if (dark) R.drawable.bg_status_idle      else R.drawable.bg_status_idle_light
    fun drawableChip(dark: Boolean)       = if (dark) R.drawable.bg_chip_selector    else R.drawable.bg_chip_selector_light
}
