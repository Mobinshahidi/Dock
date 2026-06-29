package com.nousresearch.dock.dream

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.service.dreams.DreamService
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.constraintlayout.widget.ConstraintLayout
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
 * Phase 4: Integrates WidgetHostManager for live widgets.
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
    private lateinit var widgetRail: LinearLayout
    private lateinit var clockContainer: LinearLayout
    private lateinit var clockDisplay: android.widget.TextClock
    private lateinit var dateDisplay: android.widget.TextClock

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

        // --- Inflate main layout (always landscape) ---
        // The system DreamActivity may host dreams with a fixed orientation
        // that doesn't match the physical device.  We force the resource
        // qualifier via createConfigurationContext so the landscape layout
        // always loads — desk clocks / photo frames look best in landscape.
        val config = Configuration(resources.configuration).apply {
            orientation = Configuration.ORIENTATION_LANDSCAPE
        }
        setContentView(
            LayoutInflater.from(createConfigurationContext(config))
                .inflate(R.layout.dream_dock, null)
        )
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
        clockContainer = findViewById(R.id.clock_container)
        clockDisplay = findViewById(R.id.clock_display)
        dateDisplay = findViewById(R.id.date_display)

        widgetHostManager = WidgetHostManager.getInstance(this)
        widgetHostManager.init(widgetRail)

        applyBackgroundColor()
        applyBrightnessOverride()
        loadModuleStates()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        // Read toggles at launch (settings changes take effect next launch)
        loadModuleStates()
        applyClockPosition()
        applyClockCustomization()
        startSlideshowIfEnabled()
        widgetHostManager.start()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        slideshowManager.stop()
        widgetHostManager.stop()
    }

    // ------------------------------------------------------------------
    // Clock customization
    // ------------------------------------------------------------------

    private fun applyClockPosition() {
        val position = prefs.getString(
            getString(R.string.pref_key_clock_position), "left"
        ) ?: "left"
        val params = clockContainer.layoutParams as? ConstraintLayout.LayoutParams
        when (position) {
            "left" -> {
                params?.horizontalBias = 0.15f
                clockContainer.gravity = android.view.Gravity.START
            }
            "center" -> {
                params?.horizontalBias = 0.5f
                clockContainer.gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            "right" -> {
                params?.horizontalBias = 0.85f
                clockContainer.gravity = android.view.Gravity.END
            }
        }
        if (params != null) clockContainer.layoutParams = params
    }

    private fun applyClockCustomization() {
        val clockPercent = prefs.getInt(getString(R.string.pref_key_clock_font_size), 100)
        val datePercent = prefs.getInt(getString(R.string.pref_key_date_font_size), 100)
        val colorHex = prefs.getString(getString(R.string.pref_key_clock_color), "#c3c2b7") ?: "#c3c2b7"
        val fontOption = prefs.getString(getString(R.string.pref_key_clock_font), "default") ?: "default"

        val baseClockSize = resources.getDimension(R.dimen.clock_text_size)
        val baseDateSize = resources.getDimension(R.dimen.date_text_size)
        clockDisplay.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseClockSize * clockPercent / 100f)
        dateDisplay.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseDateSize * datePercent / 100f)

        try {
            val textColor = Color.parseColor(colorHex)
            clockDisplay.setTextColor(textColor)
            dateDisplay.setTextColor(textColor)
        } catch (e: Exception) {}

        val typeface = when (fontOption) {
            "serif" -> Typeface.SERIF
            "monospace" -> Typeface.MONOSPACE
            "sans-serif-light" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            "sans-serif-thin" -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
            "custom" -> {
                val fontFile = prefs.getString(getString(R.string.pref_key_clock_font_file), null)
                if (fontFile != null) try { Typeface.createFromFile(fontFile) } catch (e: Exception) { Typeface.DEFAULT }
                else Typeface.DEFAULT
            }
            else -> Typeface.DEFAULT
        }
        clockDisplay.typeface = typeface
        dateDisplay.typeface = typeface
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