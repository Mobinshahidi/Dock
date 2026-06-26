package com.nousresearch.dock.dream

import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.service.dreams.DreamService
import android.view.View
import android.view.WindowManager
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.nousresearch.dock.R

/**
 * Dock dream service — the charging screensaver.
 *
 * Phase 1: Shows a large clock + date on the custom dark background.
 * Phase 2: Reads slideshow_enabled and widgets_enabled toggles from
 *          SharedPreferences and shows/hides the respective modules.
 *          Currently both modules are TODO stubs.
 *
 * The system handles auto-launch when charging (user selects Dock
 * in Settings → Display → Screen saver → While charging).
 */
class DockDreamService : DreamService() {

    private lateinit var prefs: SharedPreferences
    private lateinit var rootLayout: ConstraintLayout

    // Phase 3 TODO: PhotoSlideshowManager
    // Phase 4 TODO: WidgetHostManager

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // --- DreamService config ---
        setInteractive(false)        // touch dismisses
        setFullscreen(true)          // hide system bars
        setScreenBright(false)       // respect brightness override
        isWindowless = false

        // Hide decor
        window?.decorView?.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        // --- Load prefs ---
        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // --- Inflate main layout ---
        setContentView(R.layout.dream_dock)
        rootLayout = findViewById(R.id.dream_root)

        applyBackgroundColor()
        applyBrightnessOverride()
        loadModuleStates()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        // Read toggles at launch (settings changes take effect next launch)
        loadModuleStates()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        // Phase 3: pause slideshow
        // Phase 4: release widget host
    }

    // ------------------------------------------------------------------
    // Phase 2 — Toggle-aware module loading
    // ------------------------------------------------------------------

    private fun loadModuleStates() {
        val slideshowEnabled = prefs.getBoolean(
            getString(R.string.pref_key_slideshow_enabled), true
        )
        val widgetsEnabled = prefs.getBoolean(
            getString(R.string.pref_key_widgets_enabled), true
        )

        // TODO (Phase 3): manage slideshow visibility
        // TODO (Phase 4): manage widget rail visibility + clock re-centering

        // For Phase 1 + 2 we just acknowledge the toggles.
        // In Phases 3–4 these will control actual view visibility
        // and layout constraint adjustments via ConstraintSet.
    }

    // ------------------------------------------------------------------
    // Display helpers
    // ------------------------------------------------------------------

    private fun applyBackgroundColor() {
        val useOled = prefs.getBoolean(getString(R.string.pref_key_oled_mode), false)
        val color = if (useOled) Color.BLACK else getColor(R.color.bg_dark)
        rootLayout.setBackgroundColor(color)
    }

    private fun applyBrightnessOverride() {
        val brightness = prefs.getFloat(getString(R.string.pref_key_brightness), -1f)
        if (brightness in 0f..1f) {
            val lp = window?.attributes
            lp?.screenBrightness = brightness
            window?.attributes = lp
        }
    }
}