package com.nousresearch.dock.dream

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.service.dreams.DreamService
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.nousresearch.dock.R
import com.nousresearch.dock.slideshow.PhotoSlideshowManager

/**
 * Dock dream service — the charging screensaver.
 *
 * Phase 1: Shows a large clock + date on the custom dark background.
 * Phase 2: Reads slideshow_enabled and widgets_enabled toggles from
 *          SharedPreferences and shows/hides the respective modules.
 * Phase 3: Integrates PhotoSlideshowManager for photo background with crossfade.
 *
 * The system handles auto-launch when charging (user selects Dock
 * in Settings → Display → Screen saver → While charging).
 */
class DockDreamService : DreamService() {

    private lateinit var prefs: SharedPreferences
    private lateinit var rootLayout: ConstraintLayout

    // Slideshow views
    private lateinit var slideshowFront: ImageView
    private lateinit var slideshowBack: ImageView
    private lateinit var scrimOverlay: View

    // Slideshow manager
    private val slideshowManager = PhotoSlideshowManager.getInstance(this)

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

        // --- Slideshow views ---
        slideshowFront = findViewById(R.id.slideshow_front)
        slideshowBack = findViewById(R.id.slideshow_back)
        scrimOverlay = findViewById(R.id.scrim_overlay)

        // Initialize slideshow manager with views
        slideshowManager.init(slideshowFront, slideshowBack, scrimOverlay)

        applyBackgroundColor()
        applyBrightnessOverride()
        loadModuleStates()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        // Read toggles at launch (settings changes take effect next launch)
        loadModuleStates()
        startSlideshowIfEnabled()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        slideshowManager.stop()
        // Phase 4: release widget host
    }

    // ------------------------------------------------------------------
    // Module state management
    // ------------------------------------------------------------------

    private fun loadModuleStates() {
        val slideshowEnabled = prefs.getBoolean(
            getString(R.string.pref_key_slideshow_enabled), true
        )
        val widgetsEnabled = prefs.getBoolean(
            getString(R.string.pref_key_widgets_enabled), true
        )

        // Slideshow
        slideshowManager.setEnabled(slideshowEnabled)
        if (slideshowEnabled) {
            loadPersistedPhotoUris()
        }

        // TODO (Phase 4): manage widget rail visibility + clock re-centering
    }

    private fun startSlideshowIfEnabled() {
        if (slideshowManager.isEnabled()) {
            slideshowManager.start()
        }
    }

    // ------------------------------------------------------------------
    // Slideshow persistence
    // ------------------------------------------------------------------

    /**
     * Load persisted photo URIs from SharedPreferences.
     * Stored as pipe-separated URI strings.
     */
    private fun loadPersistedPhotoUris() {
        val uriString = prefs.getString("slideshow_photo_uris", "") ?: ""
        if (uriString.isNotEmpty()) {
            val uris = uriString.split("|").map { Uri.parse(it) }
            if (uris.isNotEmpty()) {
                slideshowManager.setPhotoUris(uris)
            }
        }
    }

    /** Persist photo URIs (called from SettingsActivity after picker). */
    internal fun persistPhotoUris(uris: List<Uri>) {
        val uriString = uris.map { it.toString() }.joinToString("|")
        prefs.edit().putString("slideshow_photo_uris", uriString).apply()
        // Reload in slideshow manager
        slideshowManager.setPhotoUris(uris)
        if (slideshowManager.isEnabled()) {
            slideshowManager.start()
        }
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