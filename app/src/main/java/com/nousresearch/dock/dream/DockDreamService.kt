package com.nousresearch.dock.dream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.BatteryManager
import android.service.dreams.DreamService
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.preference.PreferenceManager
import com.nousresearch.dock.R
import com.nousresearch.dock.slideshow.PhotoSlideshowManager
import com.nousresearch.dock.widget.WidgetHostManager
import java.util.Calendar
import kotlin.math.sqrt

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

    // Clock views
    private lateinit var clockContainer: LinearLayout
    private lateinit var clockDisplay: AnimatedClockView
    private lateinit var dateDisplay: android.widget.TextClock
    private lateinit var batteryStatus: TextView

    // Battery receiver
    private var batteryReceiver: BroadcastReceiver? = null

    // Shake / accelerometer
    private var sensorManager: SensorManager? = null
    private var lastShakeMs = 0L

    private val shakeListener = object : SensorEventListener {
        private val SHAKE_THRESHOLD = 12f
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            val magnitude = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH
            if (magnitude > SHAKE_THRESHOLD) {
                lastShakeMs = System.currentTimeMillis()
            }
            // Smooth time-based decay: full impact for 100ms, then linear
            // fade to zero over ~1.9s.
            val elapsed = System.currentTimeMillis() - lastShakeMs
            val decay = when {
                elapsed > 2000 -> 0f
                elapsed > 100  -> 1f - (elapsed - 100) / 1900f
                else           -> 1f
            }
            clockDisplay.shakeOffsetX = decay * (x / 20f)
            clockDisplay.shakeOffsetY = decay * (y / 20f)
            if (decay > 0.01f) clockDisplay.postInvalidate()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // Managers (lazy — Service context not valid during construction)
    private lateinit var slideshowManager: PhotoSlideshowManager
    private lateinit var widgetHostManager: WidgetHostManager

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        setInteractive(false)
        setFullscreen(true)
        setScreenBright(false)
        applySystemUiFlags()

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        setupContentView()
    }

    /**
     * Called when the device orientation changes while the dream is
     * running (e.g. user rotates the phone).  Tears down the view tree
     * and rebuilds it against the correct layout-land / layout resource.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        // The system can deliver this before onAttachedToWindow() has ever
        // run (initial dream-window launch).  At that point there is no view
        // tree or managers to rebuild, and windowManager may still be null.
        if (!::widgetHostManager.isInitialized) return

        widgetHostManager.stop()
        slideshowManager.stop()
        clockDisplay.stop()

        // newConfig is the system's authoritative orientation for this
        // genuine rotation event — no need to re-derive via windowManager.
        setupContentView(newConfig.orientation)
        loadModuleStates()
        applyClockPosition()
        applyClockCustomization()
        clockDisplay.start()
        registerBatteryReceiver()
        startSlideshowIfEnabled()
        widgetHostManager.start()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        loadModuleStates()
        applyClockPosition()
        applyClockCustomization()
        registerSensor()
        clockDisplay.start()
        registerBatteryReceiver()
        startSlideshowIfEnabled()
        widgetHostManager.start()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        clockDisplay.stop()
        unregisterSensor()
        unregisterBatteryReceiver()
        slideshowManager.stop()
        widgetHostManager.stop()
    }

    /** Hide system bars for an immersive dream experience. */
    private fun applySystemUiFlags() {
        window?.decorView?.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    /**
     * Detects the physical display rotation directly.  The system
     * DreamActivity wrapper can report a stale/fixed orientation
     * that doesn't match how the device is actually held.
     */
    private fun currentPhysicalOrientation(): Int {
        val display = (getSystemService(WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
            ?: return resources.configuration.orientation
        return when (display.rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> Configuration.ORIENTATION_LANDSCAPE
            else -> Configuration.ORIENTATION_PORTRAIT
        }
    }

    /** Inflates the correct layout based on physical rotation and sets up all views. */
    private fun setupContentView(forceOrientation: Int? = null) {
        val actualOrientation = forceOrientation ?: currentPhysicalOrientation()
        val view = if (resources.configuration.orientation != actualOrientation) {
            val config = Configuration(resources.configuration).apply {
                orientation = actualOrientation
            }
            LayoutInflater.from(createConfigurationContext(config))
                .inflate(R.layout.dream_dock, null)
        } else {
            LayoutInflater.from(this).inflate(R.layout.dream_dock, null)
        }
        setContentView(view)

        rootLayout = findViewById(R.id.dream_root)
        slideshowFront = findViewById(R.id.slideshow_front)
        slideshowBack = findViewById(R.id.slideshow_back)
        scrimOverlay = findViewById(R.id.scrim_overlay)
        widgetRail = findViewById(R.id.widget_rail)
        clockContainer = findViewById(R.id.clock_container)
        clockDisplay = findViewById(R.id.clock_display)
        dateDisplay = findViewById(R.id.date_display)
        batteryStatus = findViewById(R.id.battery_status)

        slideshowManager = PhotoSlideshowManager.getInstance(this)
        slideshowManager.init(slideshowFront, slideshowBack, scrimOverlay)

        widgetHostManager = WidgetHostManager.getInstance(this)
        widgetHostManager.init(widgetRail, actualOrientation == Configuration.ORIENTATION_LANDSCAPE)

        applyBackgroundColor()
        applyBrightnessOverride()
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
        val colorHex = prefs.getString(getString(R.string.pref_key_clock_color), "#c3c2b7") ?: "#c3c2b7"
        val fontOption = prefs.getString(getString(R.string.pref_key_clock_font), "default") ?: "default"
        val animEnabled = prefs.getBoolean(getString(R.string.pref_key_transition_animation), true)

        val baseClockSize = resources.getDimension(R.dimen.clock_text_size)
        clockDisplay.clockSize = baseClockSize * clockPercent / 100f

        // Responsive: cap clockSize so it never overflows the clockContainer.
        // Post a one-shot measurement after layout pass.
        clockDisplay.post {
            val availW = clockContainer.width -
                clockContainer.paddingLeft - clockContainer.paddingRight
            if (availW > 0) {
                clockDisplay.clockSize = clockDisplay.clockSize.coerceAtMost(
                    clockDisplay.computeFittingTextSize(availW)
                )
            }
        }

        try {
            val textColor = Color.parseColor(colorHex)
            clockDisplay.clockColor = textColor
        } catch (e: Exception) {}

        clockDisplay.clockTypeface = when (fontOption) {
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
        clockDisplay.animEnabled = animEnabled

        // Dim bright styles (neon/gradient) and slow idle motion when the
        // screen is darkened, so they cooperate with OLED / night-dim mode.
        val oled = prefs.getBoolean(getString(R.string.pref_key_oled_mode), false)
        clockDisplay.dimmed = oled || isNightDimActive()

        // Selected visual style. Set after dimmed so the idle animation that
        // (re)starts with the style already reflects the dimmed intensity.
        val style = prefs.getString(getString(R.string.pref_key_clock_style), "default") ?: "default"
        clockDisplay.clockStyle = when (style) {
            "bubbly" -> AnimatedClockView.ClockStyle.BUBBLY
            "neon" -> AnimatedClockView.ClockStyle.NEON
            "mono" -> AnimatedClockView.ClockStyle.MONO
            "gradient" -> AnimatedClockView.ClockStyle.GRADIENT
            "outline" -> AnimatedClockView.ClockStyle.OUTLINE
            else -> AnimatedClockView.ClockStyle.DEFAULT
        }
    }

    /**
     * Whether the user's night-dim window is currently active. Handles windows
     * that wrap past midnight (e.g. 22:00 → 07:00). The night-dim toggle and
     * start/end hour prefs are shared with SettingsActivity.
     */
    private fun isNightDimActive(): Boolean {
        if (!prefs.getBoolean(getString(R.string.pref_key_night_dim), true)) return false
        val start = prefs.getString(getString(R.string.pref_key_night_dim_start), "22")?.toIntOrNull() ?: 22
        val end = prefs.getString(getString(R.string.pref_key_night_dim_end), "7")?.toIntOrNull() ?: 7
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (start <= end) hour in start until end else (hour >= start || hour < end)
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
        val showDate = prefs.getBoolean(
            getString(R.string.pref_key_show_date), true
        )
        val batteryEnabled = prefs.getBoolean(
            getString(R.string.pref_key_battery_enabled), true
        )

        // Date visibility
        dateDisplay.visibility = if (showDate) View.VISIBLE else View.GONE

        // Battery visibility
        batteryStatus.visibility = if (batteryEnabled) View.VISIBLE else View.GONE
        if (batteryEnabled) {
            val batteryPct = prefs.getInt(getString(R.string.pref_key_battery_font_size), 100)
            batteryStatus.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
                12f * batteryPct / 100f)
        }

        // Slideshow
        slideshowManager.setEnabled(slideshowEnabled)
        if (slideshowEnabled) {
            loadPersistedPhotoUris()
        }

        // Widgets
        widgetHostManager.setEnabled(widgetsEnabled)
    }

    // ------------------------------------------------------------------
    // Battery receiver
    // ------------------------------------------------------------------

    private fun registerBatteryReceiver() {
        unregisterBatteryReceiver()
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                if (pct >= 0) {
                    batteryStatus.text = "$pct%"
                }
            }
        }
        registerReceiver(batteryReceiver, filter)
    }

    private fun unregisterBatteryReceiver() {
        batteryReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
            batteryReceiver = null
        }
    }

    // ------------------------------------------------------------------
    // Shake / accelerometer sensor
    // ------------------------------------------------------------------

    private fun registerSensor() {
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { accel ->
            sensorManager?.registerListener(shakeListener, accel, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun unregisterSensor() {
        sensorManager?.also {
            try { it.unregisterListener(shakeListener) } catch (_: Exception) {}
            sensorManager = null
        }
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