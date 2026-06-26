package com.nousresearch.dock.slideshow

import android.content.Context

/**
 * Phase 3 TODO: PhotoSlideshowManager
 *
 * Responsibilities:
 * - Accept list of photo URIs from system photo picker (ActivityResultContracts.PickMultipleVisualMedia)
 * - Manage crossfade animation between photos (configurable interval: 30s / 1m / 5m)
 * - Apply dark scrim overlay (40–50% #000000) for text legibility
 * - Pause/resume on dream start/stop to save battery
 * - Skip all logic entirely when slideshow_enabled=false (don't just hide — don't load/cache)
 *
 * Implementation notes:
 * - Use ImageView with TransitionDrawable or two ImageViews + crossfade animator
 * - Glide/Coil optional — keep dep count low; BitmapFactory + inSampleSize is fine for local URIs
 * - Store URIs as persisted permission-granted strings in SharedPreferences/DataStore
 * - Respect user's interval setting from preferences
 */
class PhotoSlideshowManager private constructor(
    private val context: Context
) {

    companion object {
        @Volatile private var INSTANCE: PhotoSlideshowManager? = null
        fun getInstance(context: Context): PhotoSlideshowManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PhotoSlideshowManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    // TODO: init(ImageView container, View scrimOverlay)
    // TODO: setPhotoUris(List<Uri>)
    // TODO: start()
    // TODO: stop()
    // TODO: setInterval(millis: Long)
    // TODO: isEnabled(): Boolean
}