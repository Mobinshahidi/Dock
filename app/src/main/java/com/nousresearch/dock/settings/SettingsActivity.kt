package com.nousresearch.dock.settings

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
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
                "manage_slot_1", "manage_slot_2", "manage_slot_3"
            )
            for (key in keys) {
                findPreference<Preference>(key)?.isEnabled = enabled
            }
        }

        private fun updateSlotManageVisibility(count: Int) {
            for (i in 1..3) {
                val pref = findPreference<Preference>("manage_slot_$i")
                pref?.isVisible = (i <= count)
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