package com.nousresearch.dock.settings

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceViewHolder
import com.nousresearch.dock.R
import com.nousresearch.dock.dream.AnimatedClockView
import java.util.Calendar

/**
 * A non-interactive Settings row that renders a small live [AnimatedClockView]
 * in the currently-selected clock style, so the user sees the effect (including
 * idle animation) without leaving Settings.
 *
 * The hosted clock owns ticking + animators; [stopPreview] (called from the
 * fragment's onPause) cancels them so nothing leaks while Settings is
 * backgrounded. [refresh] re-reads prefs when a relevant preference changes.
 */
class ClockStylePreviewPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    private var clockView: AnimatedClockView? = null
    private var running = false

    init {
        layoutResource = R.layout.preference_clock_preview
        isSelectable = false
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        // Release any previously-bound instance (view recycling) before adopting
        // the new one, so we never leave an orphaned ticking clock behind.
        clockView?.stop()
        clockView = holder.itemView.findViewById(R.id.preview_clock)
        applyStyle()
        if (running) clockView?.start()
    }

    fun startPreview() {
        running = true
        applyStyle()
        clockView?.start()
    }

    fun stopPreview() {
        running = false
        clockView?.stop()
    }

    fun refresh() {
        applyStyle()
        if (running) clockView?.start()
    }

    private fun applyStyle() {
        val v = clockView ?: return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)

        val colorHex = prefs.getString(context.getString(R.string.pref_key_clock_color), "#c3c2b7") ?: "#c3c2b7"
        try { v.clockColor = Color.parseColor(colorHex) } catch (_: Exception) {}

        val fontOption = prefs.getString(context.getString(R.string.pref_key_clock_font), "default") ?: "default"
        v.clockTypeface = when (fontOption) {
            "serif" -> Typeface.SERIF
            "monospace" -> Typeface.MONOSPACE
            "sans-serif-light" -> Typeface.create("sans-serif-light", Typeface.NORMAL)
            "sans-serif-thin" -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
            "custom" -> {
                val f = prefs.getString(context.getString(R.string.pref_key_clock_font_file), null)
                if (f != null) try { Typeface.createFromFile(f) } catch (_: Exception) { Typeface.DEFAULT }
                else Typeface.DEFAULT
            }
            else -> Typeface.DEFAULT
        }

        // Fixed compact size for the preview row (the global font-size % is for
        // the real dream clock, out of scope here).
        v.clockSize = 56f * context.resources.displayMetrics.density

        val oled = prefs.getBoolean(context.getString(R.string.pref_key_oled_mode), false)
        v.dimmed = oled || isNightDimActive(prefs)

        v.is24Hour = prefs.getBoolean(context.getString(R.string.pref_key_clock_24h), true)

        val style = prefs.getString(context.getString(R.string.pref_key_clock_style), "default") ?: "default"
        v.clockStyle = when (style) {
            "bubble" -> AnimatedClockView.ClockStyle.BUBBLE
            "neon" -> AnimatedClockView.ClockStyle.NEON
            "mono" -> AnimatedClockView.ClockStyle.MONO
            "gradient" -> AnimatedClockView.ClockStyle.GRADIENT
            "outline" -> AnimatedClockView.ClockStyle.OUTLINE
            else -> AnimatedClockView.ClockStyle.DEFAULT
        }
    }

    private fun isNightDimActive(prefs: SharedPreferences): Boolean {
        if (!prefs.getBoolean(context.getString(R.string.pref_key_night_dim), true)) return false
        val start = prefs.getString(context.getString(R.string.pref_key_night_dim_start), "22")?.toIntOrNull() ?: 22
        val end = prefs.getString(context.getString(R.string.pref_key_night_dim_end), "7")?.toIntOrNull() ?: 7
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return if (start <= end) hour in start until end else (hour >= start || hour < end)
    }
}
