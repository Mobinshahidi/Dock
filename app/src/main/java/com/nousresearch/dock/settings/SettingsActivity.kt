package com.nousresearch.dock.settings

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat
import com.nousresearch.dock.R

/**
 * Dock settings activity.
 *
 * Phase 2: Two toggle switches (slideshow_enabled, widgets_enabled)
 *          wired to SharedPreferences via PreferenceFragmentCompat.
 *          Uses the same 3-color palette — no default Material colors.
 *
 * The dream service reads these toggles on each launch.
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

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.settings_preferences, rootKey)

            // Phase 3 TODO: wire up photo picker click handler
            // Phase 4 TODO: wire up widget picker click handler
        }
    }
}