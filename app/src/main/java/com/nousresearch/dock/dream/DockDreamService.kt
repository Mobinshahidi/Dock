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
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.nousresearch.dock.R
import com.nousresearch.dock.slideshow.PhotoSlideshowManager
import com.nousresearch.dock.widget.WidgetHostManager

/**
 * Dock dream service — the charging screensaver.
 *
 * Phase 1: Shows a large clock + date on the custom dark background.
 * Phase 2: Reads slideshow_enabled and widgets_enabled toggles from
 *          SharedPreferences and shows/hides the respective modules.
 * Phase 3: Integrates PhotoSlideshowManager for photo background with crossfade.
 * Phase 4: Integrates WidgetHostManager with ConstraintSet layout swap.
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

    // Widget views
    private lateinit var widgetRail: FrameLayout
    private lateinit var clockDisplay: android.widget.TextClock
    private lateinit var dateDisplay: android.widget.TextClock

    // ConstraintSets for layout swap
    private val widgetsEnabledConstraintSet = ConstraintSet()
    private val widgetsDisabledConstraintSet = ConstraintSet()

    // Managers (lazy — Service context not valid during construction)
    private lateinit var slideshowManager: PhotoSlideshowManager
    private lateinit var widgetHostManager: WidgetHostManager

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // --- DreamService config ---
        setInteractive(false)        // touch dismisses
        setFullscreen(true)          // hide system bars
        setScreenBright(false)       // respect brightness override

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

        // Initialize managers (context is valid now)
        slideshowManager = PhotoSlideshowManager.getInstance(this)
        slideshowManager.init(slideshowFront, slideshowBack, scrimOverlay)

        // --- Widget views ---
        widgetRail = findViewById(R.id.widget_rail)
        clockDisplay = findViewById(R.id.clock_display)
        dateDisplay = findViewById(R.id.date_display)

        widgetHostManager = WidgetHostManager.getInstance(this)
        widgetHostManager.init(widgetRail)

        // Build constraint sets for layout swap (widgets enabled/disabled)
        buildConstraintSets()

        applyBackgroundColor()
        applyBrightnessOverride()
        loadModuleStates()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        // Read toggles at launch (settings changes take effect next launch)
        loadModuleStates()
        startSlideshowIfEnabled()
        widgetHostManager.start()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        slideshowManager.stop()
        widgetHostManager.stop()
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

        // Widgets
        widgetHostManager.setEnabled(widgetsEnabled)
        applyWidgetLayout(widgetsEnabled)
    }

    private fun startSlideshowIfEnabled() {
        if (slideshowManager.isEnabled()) {
            slideshowManager.start()
        }
    }

    private fun applyWidgetLayout(widgetsEnabled: Boolean) {
        val constraintSet = if (widgetsEnabled) {
            widgetsEnabledConstraintSet
        } else {
            widgetsDisabledConstraintSet
        }
        constraintSet.applyTo(rootLayout)
    }

    private fun buildConstraintSets() {
        // Clone current layout as baseline
        val baseSet = ConstraintSet()
        baseSet.clone(rootLayout)

        // Widgets ENABLED: clock constrained to start of parent and end of widget_rail
        widgetsEnabledConstraintSet.clone(rootLayout)
        // Clock: start to parent start, end to widget_rail start
        widgetsEnabledConstraintSet.connect(
            R.id.clock_display, ConstraintSet.START,
            R.id.dream_root, ConstraintSet.START, 0
        )
        widgetsEnabledConstraintSet.connect(
            R.id.clock_display, ConstraintSet.END,
            R.id.widget_rail, ConstraintSet.START, 0
        )
        widgetsEnabledConstraintSet.connect(
            R.id.date_display, ConstraintSet.START,
            R.id.dream_root, ConstraintSet.START, 0
        )
        widgetsEnabledConstraintSet.connect(
            R.id.date_display, ConstraintSet.END,
            R.id.widget_rail, ConstraintSet.START, 0
        )
        // Widget rail visible
        widgetsEnabledConstraintSet.setVisibility(R.id.widget_rail, View.VISIBLE)

        // Widgets DISABLED: clock centered (start to parent, end to parent)
        widgetsDisabledConstraintSet.clone(rootLayout)
        widgetsDisabledConstraintSet.connect(
            R.id.clock_display, ConstraintSet.START,
            R.id.dream_root, ConstraintSet.START, 0
        )
        widgetsDisabledConstraintSet.connect(
            R.id.clock_display, ConstraintSet.END,
            R.id.dream_root, ConstraintSet.END, 0
        )
        widgetsDisabledConstraintSet.connect(
            R.id.date_display, ConstraintSet.START,
            R.id.dream_root, ConstraintSet.START, 0
        )
        widgetsDisabledConstraintSet.connect(
            R.id.date_display, ConstraintSet.END,
            R.id.dream_root, ConstraintSet.END, 0
        )
        // Widget rail gone
        widgetsDisabledConstraintSet.setVisibility(R.id.widget_rail, View.GONE)
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