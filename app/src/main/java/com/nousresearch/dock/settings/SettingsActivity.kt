package com.nousresearch.dock.settings

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.graphics.Color
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SwitchPreferenceCompat
import com.nousresearch.dock.R
import com.nousresearch.dock.slideshow.PhotoSlideshowManager
import com.nousresearch.dock.widget.WidgetHostManager

/**
 * Dock settings activity.
 *
 * Phase 2: Toggle switches (slideshow_enabled, widgets_enabled) wired to SharedPreferences.
 * Phase 3: Photo picker for slideshow using system picker (no broad storage permission).
 * Phase 4: Widget slot management - slot count + per-slot widget picker via AppWidgetHost.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportFragmentManager
            .beginTransaction()
            .replace(android.R.id.content, DockSettingsFragment())
            .commit()
    }

    class DockSettingsFragment : PreferenceFragmentCompat() {

        // Photo picker launcher (OpenMultipleDocuments for persistable permissions)
        private val pickPhotosLauncher =
            registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                if (uris.isNotEmpty()) {
                    persistPhotoUris(uris)
                    updatePickPhotosSummary(uris.size)
                }
            }

        // Widget picker launcher
        private var pendingWidgetSlot = -1
        private var pendingWidgetId = -1
        private var pendingWidgetProvider: ComponentName? = null

        private val pickWidgetLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && pendingWidgetSlot != -1) {
                    val manager = WidgetHostManager.getInstance(requireContext())
                    if (manager.bindAppWidget(pendingWidgetSlot, pendingWidgetId, result.data)) {
                        // Bound directly
                    } else {
                        // Need ACTION_REQUEST_BIND_APPWIDGET
                        pendingWidgetProvider = result.data?.getParcelableExtra<ComponentName>(
                            AppWidgetManager.EXTRA_APPWIDGET_PROVIDER
                        )
                        if (pendingWidgetProvider != null) {
                            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
                                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, pendingWidgetProvider as android.os.Parcelable)
                            bindWidgetLauncher.launch(intent)
                        } else {
                            manager.cleanupWidgetId(pendingWidgetId)
                            pendingWidgetSlot = -1
                            pendingWidgetId = -1
                        }
                    }
                } else if (pendingWidgetSlot != -1) {
                    WidgetHostManager.getInstance(requireContext()).cleanupWidgetId(pendingWidgetId)
                    pendingWidgetSlot = -1
                    pendingWidgetId = -1
                }
            }

        private val pickFontLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    try {
                        val context = requireContext()
                        val inputStream = context.contentResolver.openInputStream(uri)
                        val fontFile = java.io.File(context.filesDir, "custom_font.ttf")
                        inputStream?.use { input ->
                            fontFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        PreferenceManager.getDefaultSharedPreferences(context)
                            .edit()
                            .putString(getString(R.string.pref_key_clock_font), "custom")
                            .putString(getString(R.string.pref_key_clock_font_file), fontFile.absolutePath)
                            .apply()
                        val fontPref = findPreference<Preference>("clock_font_upload")
                        fontPref?.summary = "Custom font loaded"
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

        private val bindWidgetLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK && pendingWidgetSlot != -1) {
                    WidgetHostManager.getInstance(requireContext()).finalizeWidgetBinding(
                        pendingWidgetSlot, pendingWidgetId, pendingWidgetProvider
                    )
                } else if (pendingWidgetSlot != -1) {
                    WidgetHostManager.getInstance(requireContext()).cleanupWidgetId(pendingWidgetId)
                }
                pendingWidgetSlot = -1
                pendingWidgetId = -1
                pendingWidgetProvider = null
            }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings_preferences, rootKey)

            val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
            val context = requireContext()

            // --- Pick photos preference ---
            val pickPhotosPref = findPreference<Preference>("pick_photos")
            pickPhotosPref?.setOnPreferenceClickListener {
                launchPhotoPicker()
                true
            }
            updatePickPhotosSummaryFromPrefs(prefs)

            // --- Slideshow enabled toggle ---
            val slideshowEnabledPref = findPreference<SwitchPreferenceCompat>(getString(R.string.pref_key_slideshow_enabled))
            slideshowEnabledPref?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                pickPhotosPref?.isEnabled = enabled
                PhotoSlideshowManager.getInstance(context).setEnabled(enabled)
                true
            }
            pickPhotosPref?.isEnabled = prefs.getBoolean(getString(R.string.pref_key_slideshow_enabled), true)

            // --- Slideshow interval ---
            val intervalPref = findPreference<ListPreference>(getString(R.string.pref_key_slideshow_interval))
            intervalPref?.setOnPreferenceChangeListener { _, newValue ->
                val intervalMillis = (newValue as String).toLongOrNull() ?: 60000L
                PhotoSlideshowManager.getInstance(context).setInterval(intervalMillis)
                true
            }

            // ===== Widgets =====
            // Widgets enabled toggle
            val widgetsEnabledPref = findPreference<SwitchPreferenceCompat>(getString(R.string.pref_key_widgets_enabled))
            widgetsEnabledPref?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                updateWidgetPrefsVisibility(enabled)
                WidgetHostManager.getInstance(context).setEnabled(enabled)
                true
            }
            updateWidgetPrefsVisibility(prefs.getBoolean(getString(R.string.pref_key_widgets_enabled), true))

            // Widget slot count
            val slotCountPref = findPreference<ListPreference>(getString(R.string.pref_key_widget_slot_count))
            slotCountPref?.setOnPreferenceChangeListener { _, newValue ->
                val count = (newValue as String).toIntOrNull() ?: 1
                WidgetHostManager.getInstance(context).setSlotCount(count)
                updateSlotManageVisibility(count)
                true
            }
            val currentSlotCount = prefs.getString(getString(R.string.pref_key_widget_slot_count), "1")?.toIntOrNull() ?: 1
            updateSlotManageVisibility(currentSlotCount)

            // Per-slot widget pickers
            for (i in 1..3) {
                val key = "manage_slot_$i"
                findPreference<Preference>(key)?.setOnPreferenceClickListener {
                    launchWidgetPicker(i - 1)
                    true
                }
            }

            // Per-slot size
            for (i in 1..3) {
                val key = "slot_size_$i"
                findPreference<ListPreference>(key)?.setOnPreferenceChangeListener { _, _ ->
                    WidgetHostManager.getInstance(context).notifySlotSizeChanged()
                    true
                }
            }

            // Auto-start guide — open system dream settings
            findPreference<Preference>("auto_start_guide")?.setOnPreferenceClickListener {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        startActivity(Intent(Settings.ACTION_DREAM_SETTINGS))
                    }
                } catch (e: Exception) {
                    // Settings may not be reachable
                }
                true
            }

            // Clock color picker — full RGB + hex dialog
            findPreference<Preference>(getString(R.string.pref_key_clock_color))?.setOnPreferenceClickListener {
                val ctx = requireContext()
                val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
                val currentHex = prefs.getString(getString(R.string.pref_key_clock_color), "#c3c2b7") ?: "#c3c2b7"
                val currentColor = try { Color.parseColor(currentHex) } catch (e: Exception) { Color.parseColor("#c3c2b7") }

                val dp = ctx.resources.displayMetrics.density
                val padding = (16 * dp).toInt()

                // Root vertical layout
                val root = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(padding, padding, padding, 0)
                }

                // Color preview — solid rectangle
                val preview = View(ctx).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT, (80 * dp).toInt()
                    ).also { it.setMargins(0, 0, 0, padding) }
                    setBackgroundColor(currentColor)
                }
                root.addView(preview)

                // Helper to build RGB slider row
                fun addSlider(label: String, initial: Int, tag: String): android.widget.SeekBar {
                    val row = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).also { it.setMargins(0, 0, 0, (8 * dp).toInt()) }
                    }
                    val labelView = android.widget.TextView(ctx).apply {
                        text = label
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            (40 * dp).toInt(), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        textSize = 14f
                    }
                    row.addView(labelView)
                    val seekBar = android.widget.SeekBar(ctx).apply {
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        )
                        max = 255
                        progress = initial
                    }
                    row.addView(seekBar)
                    val valueView = android.widget.TextView(ctx).apply {
                        text = initial.toString()
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            (40 * dp).toInt(), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        )
                        gravity = android.view.Gravity.END
                        textSize = 14f
                    }
                    row.addView(valueView)
                    root.addView(row)
                    seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                            valueView.text = progress.toString()
                            val r = root.findViewWithTag<android.widget.SeekBar>("R")?.progress ?: 0
                            val g = root.findViewWithTag<android.widget.SeekBar>("G")?.progress ?: 0
                            val b = root.findViewWithTag<android.widget.SeekBar>("B")?.progress ?: 0
                            preview.setBackgroundColor(android.graphics.Color.rgb(r, g, b))
                        }
                        override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                        override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
                    })
                    seekBar.tag = tag
                    return seekBar
                }

                val r = Color.red(currentColor)
                val g = Color.green(currentColor)
                val b = Color.blue(currentColor)

                addSlider("R", r, "R")
                addSlider("G", g, "G")
                addSlider("B", b, "B")

                // Hex input row
                val hexRow = android.widget.LinearLayout(ctx).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { it.setMargins(0, 0, 0, 0) }
                }
                val hexLabel = android.widget.TextView(ctx).apply {
                    text = "#"
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (24 * dp).toInt(), android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                    textSize = 16f
                    gravity = android.view.Gravity.CENTER_VERTICAL
                }
                hexRow.addView(hexLabel)
                val hexInput = android.widget.EditText(ctx).apply {
                    setText(String.format("%06X", currentColor and 0xFFFFFF))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                    filters = arrayOf(android.text.InputFilter.LengthFilter(6))
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                }
                hexRow.addView(hexInput)
                root.addView(hexRow)

                val dialog = AlertDialog.Builder(ctx)
                    .setTitle("Clock color")
                    .setView(root)
                    .setPositiveButton("OK") { _, _ ->
                        val hex = try {
                            val rv = root.findViewWithTag<android.widget.SeekBar>("R")?.progress ?: 0
                            val gv = root.findViewWithTag<android.widget.SeekBar>("G")?.progress ?: 0
                            val bv = root.findViewWithTag<android.widget.SeekBar>("B")?.progress ?: 0
                            String.format("#%02X%02X%02X", rv, gv, bv)
                        } catch (e: Exception) { "#c3c2b7" }
                        prefs.edit().putString(getString(R.string.pref_key_clock_color), hex).apply()
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                dialog.show()
                true
            }

            // Font upload picker
            findPreference<Preference>("clock_font_upload")?.setOnPreferenceClickListener {
                pickFontLauncher.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/x-font-opentype"))
                true
            }
        }

        private fun launchWidgetPicker(slotIndex: Int) {
            val manager = WidgetHostManager.getInstance(requireContext())
            pendingWidgetId = manager.allocateWidgetIdForSlot(slotIndex)
            if (pendingWidgetId == -1) return

            pendingWidgetSlot = slotIndex
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, pendingWidgetId)
            pickWidgetLauncher.launch(intent)
        }

        private fun updateWidgetPrefsVisibility(enabled: Boolean) {
            val keys = listOf(
                getString(R.string.pref_key_widget_slot_count),
                "manage_slot_1", "slot_size_1",
                "manage_slot_2", "slot_size_2",
                "manage_slot_3", "slot_size_3"
            )
            for (key in keys) {
                findPreference<Preference>(key)?.isEnabled = enabled
            }
        }

        private fun updateSlotManageVisibility(count: Int) {
            for (i in 1..3) {
                val pref = findPreference<Preference>("manage_slot_$i")
                pref?.isVisible = (i <= count)
                val sizePref = findPreference<Preference>("slot_size_$i")
                sizePref?.isVisible = (i <= count)
            }
        }

        private fun launchPhotoPicker() {
            pickPhotosLauncher.launch(arrayOf("image/*"))
        }

        private fun persistPhotoUris(uris: List<Uri>) {
            val context = requireContext()
            val contentResolver: ContentResolver = context.contentResolver

            // Take persistable URI permissions
            for (uri in uris) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }

            // Store as pipe-separated string in SharedPreferences
            val uriString = uris.map { it.toString() }.joinToString("|")
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString("slideshow_photo_uris", uriString)
                .apply()

            // Notify slideshow manager
            PhotoSlideshowManager.getInstance(context).setPhotoUris(uris)
        }

        private fun updatePickPhotosSummary(count: Int) {
            val pickPhotosPref = findPreference<Preference>("pick_photos")
            pickPhotosPref?.summary = if (count > 0) {
                getString(R.string.slideshow_photos_selected, count)
            } else {
                getString(R.string.slideshow_no_photos)
            }
        }

        private fun updatePickPhotosSummaryFromPrefs(prefs: SharedPreferences) {
            val uriString = prefs.getString("slideshow_photo_uris", "") ?: ""
            val count = if (uriString.isNotEmpty()) uriString.split("|").size else 0
            updatePickPhotosSummary(count)
        }
    }
}