package com.nousresearch.dock.slideshow

import android.content.ContentResolver
import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.annotation.VisibleForTesting
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.nousresearch.dock.R

/**
 * PhotoSlideshowManager — manages crossfade slideshow for the dream background.
 *
 * Responsibilities:
 * - Load photos from persisted URIs using Glide
 * - Crossfade between two ImageViews (front/back) for smooth transitions
 * - Apply dark scrim overlay (40-50% black) for text legibility
 * - Respect slideshow_enabled toggle — skip all loading when disabled
 * - Configurable interval (30s / 1m / 5m)
 *
 * Usage:
 *   val manager = PhotoSlideshowManager.getInstance(context)
 *   manager.init(frontImageView, backImageView, scrimView)
 *   manager.setPhotoUris(uris)
 *   manager.start()
 *   manager.stop() // on dream stop
 */
class PhotoSlideshowManager private constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "PhotoSlideshowManager"
        @Volatile private var INSTANCE: PhotoSlideshowManager? = null
        fun getInstance(context: Context): PhotoSlideshowManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: PhotoSlideshowManager(context.applicationContext).also { INSTANCE = it }
            }
    }

    // View references
    private var frontImageView: ImageView? = null
    private var backImageView: ImageView? = null
    private var scrimView: View? = null

    // State
    private var photoUris: List<Uri> = emptyList()
    private var currentIndex = 0
    private var isFrontShowing = true
    private var isRunning = false
    private var isEnabled = true
    private var intervalMillis = 60000L // default 1 minute

    private val handler = Handler(Looper.getMainLooper())
    private val contentResolver: ContentResolver = context.contentResolver

    // Runnable for advancing slideshow
    private val advanceRunnable = Runnable { advanceSlideshow() }

    /**
     * Initialize with view references. Must be called before start().
     */
    fun init(
        front: ImageView,
        back: ImageView,
        scrim: View
    ) {
        frontImageView = front
        backImageView = back
        scrimView = scrim
        applyScrim()
    }

    /** Set the list of photo URIs to cycle through. */
    fun setPhotoUris(uris: List<Uri>) {
        photoUris = uris
        currentIndex = 0
    }

    /** Start the slideshow (load first photo, begin timer). */
    fun start() {
        if (!isEnabled || photoUris.isEmpty()) {
            hideViews()
            return
        }
        isRunning = true
        showViews()
        loadCurrentPhoto()
        scheduleNext()
    }

    /** Stop the slideshow and cancel pending transitions. */
    fun stop() {
        isRunning = false
        handler.removeCallbacks(advanceRunnable)
        // Keep views visible but stop cycling
    }

    /** Update the slideshow interval. Takes effect on next cycle. */
    fun setInterval(millis: Long) {
        intervalMillis = millis
        if (isRunning) {
            handler.removeCallbacks(advanceRunnable)
            scheduleNext()
        }
    }

    /** Enable/disable slideshow entirely. When disabled, views are hidden and no loading occurs. */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (enabled) {
            if (isRunning) start()
        } else {
            stop()
            hideViews()
        }
    }

    fun isEnabled(): Boolean = isEnabled

    // ------------------------------------------------------------------
    // Private implementation
    // ------------------------------------------------------------------

    private fun showViews() {
        frontImageView?.visibility = View.VISIBLE
        backImageView?.visibility = View.VISIBLE
        scrimView?.visibility = View.VISIBLE
    }

    private fun hideViews() {
        frontImageView?.visibility = View.GONE
        backImageView?.visibility = View.GONE
        scrimView?.visibility = View.GONE
    }

    private fun applyScrim() {
        scrimView?.setBackgroundColor(context.getColor(R.color.scrim))
    }

    private fun loadCurrentPhoto() {
        val uri = photoUris[currentIndex]
        val targetView = if (isFrontShowing) frontImageView else backImageView
        val otherView = if (isFrontShowing) backImageView else frontImageView

        // Load new photo into the hidden view
        Glide.with(context)
            .load(uri)
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade(500))
            .into(object : CustomTarget<Drawable>() {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    targetView?.setImageDrawable(resource)
                    // Crossfade: show new, hide old
                    crossfadeViews()
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })

        // Clear the other view after crossfade completes
        otherView?.setImageDrawable(null)
    }

    private fun crossfadeViews() {
        val fromView = if (isFrontShowing) frontImageView else backImageView
        val toView = if (isFrontShowing) backImageView else frontImageView

        fromView?.animate().alpha(0f).setDuration(500).start()
        toView?.alpha = 1f
        toView?.visibility = View.VISIBLE

        isFrontShowing = !isFrontShowing
    }

    private fun advanceSlideshow() {
        if (!isRunning || photoUris.isEmpty()) return
        currentIndex = (currentIndex + 1) % photoUris.size
        loadCurrentPhoto()
        scheduleNext()
    }

    private fun scheduleNext() {
        handler.postDelayed(advanceRunnable, intervalMillis)
    }

    @VisibleForTesting
    internal fun setContentResolverForTest(resolver: ContentResolver) {
        // Not used but kept for testability pattern
    }
}