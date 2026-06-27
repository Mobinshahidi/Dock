package com.nousresearch.dock.widget

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.VisibleForTesting
import com.nousresearch.dock.R

/**
 * WidgetHostManager — manages AppWidgetHost with 1–3 slots.
 *
 * Responsibilities:
 * - Create and manage AppWidgetHost + AppWidgetHostView instances
 * - Launch widget picker (ACTION_APPWIDGET_PICK)
 * - Add/remove/reorder slots per user config (1-3 slots)
 * - Style each slot: rounded corners, @color/bg_surface background, no shadows
 * - When widgets_enabled=false: fully remove rail from layout, recenter/scale clock
 *   (uses ConstraintSet to swap between two layout states)
 * - Release widget host on dream stop to avoid leaks
 */
class WidgetHostManager private constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "WidgetHostManager"
        private const val HOST_ID = 0xD0CC // "Dock"
        private const val MAX_SLOTS = 3
        private const val PREFS_NAME = "dock_widget_prefs"
        private const val PREFS_KEY_SLOT_COUNT = "widget_slot_count"
        private const val PREFS_KEY_SLOT_PREFIX = "widget_slot_"
        private const val PREFS_KEY_ENABLED = "widgets_enabled"

        @Volatile private var INSTANCE: WidgetHostManager? = null

        fun getInstance(context: Context): WidgetHostManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: WidgetHostManager(context.applicationContext).also { INSTANCE = it }
            }

        @VisibleForTesting
        fun resetInstance() {
            INSTANCE = null
        }
    }

    // AppWidgetHost
    private val appWidgetHost = AppWidgetHost(context, HOST_ID)
    private val appWidgetManager: AppWidgetManager = AppWidgetManager.getInstance(context)

    // Views
    private var widgetRail: ViewGroup? = null
    private val slotViews = arrayOfNulls<FrameLayout>(MAX_SLOTS)
    private val hostViews = arrayOfNulls<AppWidgetHostView>(MAX_SLOTS)

    // State
    private var slotCount = 1
    private var isEnabled = true
    private var isStarted = false

    /** Initialize with the widget rail container from the dream layout. */
    fun init(widgetRail: ViewGroup) {
        this.widgetRail = widgetRail
        createSlotViews()
        loadPersistedState()
    }

    /** Start binding all widgets. Must be called after init(). */
    fun start() {
        if (!isEnabled || isStarted) return
        isStarted = true
        appWidgetHost.startListening()
        bindAllWidgets()
    }

    /** Stop and release all widget resources. */
    fun stop() {
        if (!isStarted) return
        isStarted = false
        unbindAllWidgets()
        appWidgetHost.stopListening()
    }

    /** Enable/disable widgets entirely. When disabled, views are hidden. */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        persistEnabled()
        if (enabled) {
            if (isStarted) start()
            showRail()
        } else {
            stop()
            hideRail()
        }
    }

    fun isEnabled(): Boolean = isEnabled

    /** Allocate a new widget ID for a slot (for picker launch). */
    fun allocateWidgetIdForSlot(slotIndex: Int): Int {
        if (slotIndex !in 0 until slotCount) return -1
        return appWidgetHost.allocateAppWidgetId()
    }

    /** Clean up a widget ID if picker was cancelled. */
    fun cleanupWidgetId(appWidgetId: Int) {
        appWidgetHost.deleteAppWidgetId(appWidgetId)
    }

    /** Set number of slots (1-3). Recreates slot views. */
    fun setSlotCount(count: Int) {
        slotCount = count.coerceIn(1, MAX_SLOTS)
        createSlotViews()
        persistSlotCount()
        if (isStarted) {
            unbindAllWidgets()
            bindAllWidgets()
        }
    }

    /** Launch widget picker for a specific slot. */
    fun pickWidgetForSlot(slotIndex: Int, onPicked: (Boolean) -> Unit) {
        if (slotIndex !in 0 until slotCount) {
            onPicked(false)
            return
        }
        val appWidgetId = appWidgetHost.allocateAppWidgetId()
        val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        // Need to start activity from a context that can start activities
        // This will be called from SettingsActivity which is an Activity
        try {
            context.startActivity(intent)
            // The result will come back via onActivityResult in SettingsActivity
            // We'll handle binding there
            onPicked(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch widget picker", e)
            appWidgetHost.deleteAppWidgetId(appWidgetId)
            onPicked(false)
        }
    }

    /** Bind widget result from picker (called from SettingsActivity onActivityResult). */
    fun bindWidgetResult(slotIndex: Int, appWidgetId: Int, resultData: Intent?) {
        if (slotIndex !in 0 until slotCount) return

        val provider = resultData?.getParcelableExtra<ComponentName>(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER)
        if (provider == null) {
            // User cancelled or error
            appWidgetHost.deleteAppWidgetId(appWidgetId)
            return
        }

        // Bind the widget
        try {
            appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider)
            val info = appWidgetManager.getAppWidgetProviderInfo(provider) ?: run {
                appWidgetHost.deleteAppWidgetId(appWidgetId)
                return
            }
            val hostView = appWidgetHost.createView(context, appWidgetId, info)
            hostView.layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setHostView(slotIndex, hostView)
            persistWidget(slotIndex, provider, appWidgetId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind widget", e)
            appWidgetHost.deleteAppWidgetId(appWidgetId)
        }
    }

    /** Remove widget from a slot. */
    fun removeWidget(slotIndex: Int) {
        if (slotIndex !in 0 until slotCount) return
        val hostView = hostViews[slotIndex]
        val appWidgetId = hostView?.appWidgetId
        if (appWidgetId != null) {
            appWidgetHost.deleteAppWidgetId(appWidgetId)
        }
        setHostView(slotIndex, null)
        clearPersistedWidget(slotIndex)
    }

    // ------------------------------------------------------------------
    // Private implementation
    // ------------------------------------------------------------------

    private fun createSlotViews() {
        widgetRail?.removeAllViews()
        val params = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        for (i in 0 until slotCount) {
            // Slot container: rounded, bg_surface background, no shadow
            val slotContainer = FrameLayout(context).apply {
                layoutParams = params
                setBackgroundResource(R.drawable.widget_slot_background)
                clipToOutline = true
                visibility = if (isEnabled) View.VISIBLE else View.GONE
            }
            widgetRail?.addView(slotContainer)
            slotViews[i] = slotContainer
        }
    }

    private fun bindAllWidgets() {
        for (i in 0 until slotCount) {
            bindPersistedWidget(i)
        }
    }

    private fun unbindAllWidgets() {
        for (i in 0 until slotCount) {
            val hostView = hostViews[i]
            val appWidgetId = hostView?.appWidgetId
            if (appWidgetId != null) {
                appWidgetHost.deleteAppWidgetId(appWidgetId)
            }
            setHostView(i, null)
        }
    }

    private fun bindPersistedWidget(slotIndex: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val flattened = prefs.getString("${PREFS_KEY_SLOT_PREFIX}${slotIndex}_provider", null)
        val appWidgetId = prefs.getInt("${PREFS_KEY_SLOT_PREFIX}${slotIndex}_id", -1)
        if (flattened != null && appWidgetId != -1) {
            val provider = ComponentName.unflattenFromString(flattened)
            if (provider != null && appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, provider)) {
                val info = appWidgetManager.getAppWidgetProviderInfo(provider) ?: run {
                    clearPersistedWidget(slotIndex)
                    return
                }
                val hostView = appWidgetHost.createView(context, appWidgetId, info)
                hostView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setHostView(slotIndex, hostView)
            } else {
                clearPersistedWidget(slotIndex)
            }
        }
    }

    private fun setHostView(slotIndex: Int, hostView: AppWidgetHostView?) {
        val container = slotViews[slotIndex] ?: return
        // Remove old
        container.removeAllViews()
        // Add new
        hostView?.let { container.addView(it) }
        hostViews[slotIndex] = hostView
    }

    private fun showRail() {
        widgetRail?.visibility = View.VISIBLE
        for (i in 0 until slotCount) {
            slotViews[i]?.visibility = View.VISIBLE
        }
    }

    private fun hideRail() {
        widgetRail?.visibility = View.GONE
        for (i in 0 until slotCount) {
            slotViews[i]?.visibility = View.GONE
        }
    }

    private fun loadPersistedState() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        slotCount = prefs.getInt(PREFS_KEY_SLOT_COUNT, 1).coerceIn(1, MAX_SLOTS)
        isEnabled = prefs.getBoolean(PREFS_KEY_ENABLED, true)
    }

    private fun persistSlotCount() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(PREFS_KEY_SLOT_COUNT, slotCount).apply()
    }

    private fun persistEnabled() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREFS_KEY_ENABLED, isEnabled).apply()
    }

    private fun persistWidget(slotIndex: Int, provider: ComponentName, appWidgetId: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("${PREFS_KEY_SLOT_PREFIX}${slotIndex}_provider", provider.flattenToString())
            .putInt("${PREFS_KEY_SLOT_PREFIX}${slotIndex}_id", appWidgetId)
            .apply()
    }

    private fun clearPersistedWidget(slotIndex: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove("${PREFS_KEY_SLOT_PREFIX}${slotIndex}_provider")
            .remove("${PREFS_KEY_SLOT_PREFIX}${slotIndex}_id")
            .apply()
    }
}