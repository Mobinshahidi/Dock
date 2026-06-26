package com.nousresearch.dock.widget

import android.content.Context

/**
 * Phase 4 TODO: WidgetHostManager
 *
 * Responsibilities:
 * - Create and manage AppWidgetHost + AppWidgetHostView instances for 1–3 slots
 * - Launch widget picker (AppWidgetManager.ACTION_APPWIDGET_PICK or custom list)
 * - Add/remove/reorder slots per user config
 * - Style each slot: rounded corners, @color/bg_surface background, no shadows
 * - When widgets_enabled=false: fully remove rail from layout, recenter/scale clock
 *   (use ConstraintSet to swap between two layout states — animate if desired)
 * - Release widget host on dream stop to avoid leaks
 *
 * Implementation notes:
 * - AppWidgetHost requires unique host ID per app (static int)
 * - Each slot = AppWidgetHostView with allocated widget ID from host.allocateAppWidgetId()
 * - Bind with AppWidgetManager.bindAppWidgetId()
 * - On dream stop: host.deleteHost() + unbind all IDs
 * - Persist selected widget ComponentNames + IDs in SharedPreferences
 */
class WidgetHostManager private constructor(
    private val context: Context
) {

    companion object {
        private const val HOST_ID = 0xD0CC // "Dock"
        @Volatile private var INSTANCE: WidgetHostManager? = null
        fun getInstance(context: Context): WidgetHostManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: WidgetHostManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    // TODO: init(ViewGroup widgetRailContainer)
    // TODO: setSlotCount(count: Int) // 1–3
    // TODO: pickWidgetForSlot(slotIndex: Int)
    // TODO: loadPersistedWidgets()
    // TODO: start() // bind all widgets
    // TODO: stop() // unbind + deleteHost
    // TODO: isEnabled(): Boolean
}