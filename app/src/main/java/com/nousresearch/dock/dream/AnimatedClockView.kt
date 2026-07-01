package com.nousresearch.dock.dream

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.sin

/**
 * Custom-drawn HH:mm clock with per-second ticking, a 400ms time-change
 * animation, and a set of visual "clock styles" (StandBy-inspired).
 *
 * Idle animations (breathing pulse, neon hue cycle, gradient sweep) are
 * driven by [ValueAnimator]/[ObjectAnimator] — never a self-scheduling
 * invalidate() loop — and are throttled to ~30fps to stay power-friendly
 * for a screensaver that runs for hours while charging.
 *
 * Lifecycle: every animator started here is cancelled in [stop] AND in
 * [onDetachedFromWindow], so nothing leaks when the dream tears the view
 * tree down on rotation or when dreaming stops.  Callers MUST call [stop]
 * before discarding/replacing the view; the detach hook is a safety net.
 */
class AnimatedClockView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class ClockStyle {
        DEFAULT, BUBBLY, NEON, MONO, GRADIENT, OUTLINE
    }

    var clockStyle: ClockStyle = ClockStyle.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            invalidateGlowCache()
            // Switching style swaps which idle animation should run.
            restartIdleAnimationIfRunning()
            requestLayout() // padding/glow extents differ per style
            invalidate()
        }

    /**
     * When true (OLED / night-dim active) bright effects are toned down and
     * idle motion slowed, so the styles cooperate with a darkened screen
     * instead of fighting it.
     */
    var dimmed: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            restartIdleAnimationIfRunning()
            invalidate()
        }

    var clockColor: Int = Color.parseColor("#c3c2b7")
        set(value) {
            field = value
            paint.color = value
            invalidateGlowCache()
            invalidate()
        }

    var clockSize: Float = 120f
        set(value) {
            field = value
            paint.textSize = value
            invalidateGlowCache()
            requestLayout()
            invalidate()
        }

    var clockTypeface: Typeface? = null
        set(value) {
            field = value
            invalidateGlowCache()
            invalidate()
        }

    var animEnabled: Boolean = true

    /** Applied by DockDreamService from accelerometer data — per-digit wobble offset. */
    var shakeOffsetX: Float = 0f
    var shakeOffsetY: Float = 0f

    /**
     * Measure "HH:mm" at various sizes and return the largest that fits
     * within [availableWidth] pixels.  Called from DockDreamService after
     * the layout pass so [clockContainer] dimensions are known.
     */
    fun computeFittingTextSize(availableWidth: Int): Float {
        val text = if (displayText.isNotEmpty()) displayText else "88:88"
        var lo = 8f
        var hi = clockSize
        while (hi - lo > 1f) {
            val mid = (lo + hi) / 2f
            paint.textSize = mid
            if (paint.measureText(text) <= availableWidth) lo = mid else hi = mid
        }
        paint.textSize = clockSize // restore
        return lo
    }

    private val density = resources.displayMetrics.density

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = clockColor
        textSize = clockSize
        textAlign = Paint.Align.CENTER
    }

    /** Paint used to composite the cached neon glow bitmap (tinted per frame). */
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var displayText = ""
    private var prevText = ""

    // Time-change animation state
    private var animFraction = 1f
    private var animator: ValueAnimator? = null

    // Idle animation state
    private var isRunning = false
    private val idleAnimators = mutableListOf<ValueAnimator>()
    private var bubblyAnimators: List<ObjectAnimator> = emptyList()
    private var neonHue = 0f
    private var gradientPhase = 0f
    private val gradientMatrix = Matrix()

    // Neon glow cache (regenerated only when the rendered glyphs change — ~1/min)
    private var glowBitmap: Bitmap? = null
    private var glowCacheKey: String? = null

    // 30fps redraw throttle for the idle ValueAnimators
    private var lastIdleInvalidateMs = 0L

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            val now = Calendar.getInstance()
            val newText = timeFormat.format(now.time)
            if (newText != displayText) {
                prevText = displayText
                displayText = newText
                invalidateGlowCache()
                if (animEnabled) animateChange()
            }
            invalidate()
            // Re-sync to the next whole-second boundary
            val delay = 1000L - System.currentTimeMillis() % 1000L
            handler.postDelayed(this, delay)
        }
    }

    private fun animateChange() {
        animFraction = 0f
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 400L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                animFraction = it.animatedFraction
                invalidate()
            }
            start()
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    fun start() {
        isRunning = true
        tickRunnable.run() // immediate first tick
        startIdleAnimation()
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(tickRunnable)
        animator?.cancel()
        stopIdleAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Safety net: never let animators outlive the view (rotation teardown).
        stop()
        glowBitmap?.recycle()
        glowBitmap = null
        glowCacheKey = null
    }

    // ------------------------------------------------------------------
    // Idle animations
    // ------------------------------------------------------------------

    private fun restartIdleAnimationIfRunning() {
        if (isRunning) startIdleAnimation()
    }

    private fun startIdleAnimation() {
        stopIdleAnimation()
        if (!isRunning) return
        when (clockStyle) {
            ClockStyle.BUBBLY -> startBubblyPulse()
            ClockStyle.NEON -> startNeonHueCycle()
            ClockStyle.GRADIENT -> startGradientSweep()
            else -> { /* Classic / Mono / Outline are intentionally still */ }
        }
    }

    private fun stopIdleAnimation() {
        idleAnimators.forEach { it.cancel() }
        idleAnimators.clear()
        bubblyAnimators.forEach { it.cancel() }
        bubblyAnimators = emptyList()
        // Reset any compositor transform the pulse left behind.
        scaleX = 1f
        scaleY = 1f
    }

    /**
     * Gentle breathing pulse.  Animates the view's scale on the compositor
     * (no onDraw / invalidate), which is the cheapest possible idle effect.
     */
    private fun startBubblyPulse() {
        val peak = if (dimmed) 1.01f else 1.02f
        val sx = ObjectAnimator.ofFloat(this, SCALE_X, 1f, peak)
        val sy = ObjectAnimator.ofFloat(this, SCALE_Y, 1f, peak)
        for (a in listOf(sx, sy)) {
            a.duration = 3500L
            a.repeatMode = ObjectAnimator.REVERSE
            a.repeatCount = ObjectAnimator.INFINITE
            a.interpolator = AccelerateDecelerateInterpolator()
            a.start()
        }
        bubblyAnimators = listOf(sx, sy)
    }

    /** Slow hue rotation across the neon glow color (~9s, ~16s when dimmed). */
    private fun startNeonHueCycle() {
        val anim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = if (dimmed) 16000L else 9000L
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                neonHue = it.animatedValue as Float
                throttledIdleInvalidate()
            }
            start()
        }
        idleAnimators.add(anim)
    }

    /** Looping gradient sweep across the digits (~4s, ~7s when dimmed). */
    private fun startGradientSweep() {
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (dimmed) 7000L else 4000L
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                gradientPhase = it.animatedValue as Float
                throttledIdleInvalidate()
            }
            start()
        }
        idleAnimators.add(anim)
    }

    /** Cap idle-driven redraws at ~30fps regardless of display refresh rate. */
    private fun throttledIdleInvalidate() {
        val now = System.currentTimeMillis()
        if (now - lastIdleInvalidateMs >= 33L) {
            lastIdleInvalidateMs = now
            invalidate()
        }
    }

    // ------------------------------------------------------------------
    // Measurement
    // ------------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        paint.typeface = effectiveTypeface()
        paint.textSize = clockSize
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        val textWidth = paint.measureText("00:00")
        val textHeight = paint.fontMetrics.let { it.bottom - it.top }
        // Extra room so glow / rounded strokes / shadow are not clipped.
        val extra = when (clockStyle) {
            ClockStyle.NEON -> clockSize * 0.5f
            ClockStyle.BUBBLY -> clockSize * 0.2f
            ClockStyle.GRADIENT -> clockSize * 0.1f
            ClockStyle.OUTLINE -> 3f * density
            else -> 0f
        }
        val w = (textWidth + extra * 2).toInt() + paddingLeft + paddingRight
        val h = (textHeight + extra * 2).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec)
        )
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (displayText.isEmpty()) return

        val cx = width / 2f
        val cy = height / 2f

        // Reset shared paint state every frame so styles never bleed into each other.
        paint.color = clockColor
        paint.textSize = clockSize
        paint.typeface = effectiveTypeface()
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        paint.strokeJoin = Paint.Join.MITER
        paint.strokeCap = Paint.Cap.BUTT
        paint.shader = null
        paint.maskFilter = null
        paint.clearShadowLayer()
        paint.alpha = 255

        val textY = cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f

        when (clockStyle) {
            ClockStyle.DEFAULT -> drawPlain(canvas, cx, textY)
            ClockStyle.MONO -> drawPlain(canvas, cx, textY)
            ClockStyle.BUBBLY -> drawBubbly(canvas, cx, textY)
            ClockStyle.NEON -> drawNeon(canvas, cx, textY)
            ClockStyle.GRADIENT -> drawGradient(canvas, cx, cy, textY)
            ClockStyle.OUTLINE -> drawOutline(canvas, cx, textY)
        }
    }

    private fun drawPlain(canvas: Canvas, cx: Float, textY: Float) {
        drawTextWithAnim(canvas, cx, textY)
    }

    private fun drawBubbly(canvas: Canvas, cx: Float, textY: Float) {
        val chars = displayText.toCharArray()
        val charWidths = FloatArray(chars.size)
        paint.getTextWidths(displayText, charWidths)
        val totalWidth = charWidths.sum()
        val capH = paint.textSize * 1.35f
        val corner = capH * 0.45f
        val hPad = paint.textSize * 0.2f
        val startX = cx - totalWidth / 2f
        val capTop = height / 2f - capH / 2f
        val capsulePaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Each digit drawn inside its own rounded capsule. The capsule uses a
        // very subtle gradient fill (barely lighter than the background) and a
        // thin border. When shake is active, each digit independently wavers.
        var x = startX
        for (i in chars.indices) {
            val cw = charWidths[i]

            // Shake per-digit using a sine offset derived from shakeOffset
            val wobble = if (shakeOffsetX != 0f || shakeOffsetY != 0f) {
                val phase = i * 1.2f
                val inAng = sin((System.currentTimeMillis() * 0.008).toFloat() + phase)
                val outAng = sin((System.currentTimeMillis() * 0.006).toFloat() + phase)
                Pair(shakeOffsetX * inAng * 4f, shakeOffsetY * outAng * 4f)
            } else Pair(0f, 0f)

            val rx = x + wobble.first
            val ry = capTop + wobble.second

            // Capsule background
            capsulePaint.color = clockColor
            capsulePaint.alpha = 18
            val rect = RectF(rx - hPad, ry, rx + cw + hPad, ry + capH)
            canvas.drawRoundRect(rect, corner, corner, capsulePaint)

            // Capsule border
            capsulePaint.alpha = 50
            capsulePaint.style = Paint.Style.STROKE
            capsulePaint.strokeWidth = 1.2f * density
            canvas.drawRoundRect(rect, corner, corner, capsulePaint)
            capsulePaint.style = Paint.Style.FILL

            // Digit
            val digit = String(charArrayOf(chars[i]))
            if (animFraction < 1f && i < 2 && prevText.length > i && prevText[i] != chars[i]) {
                val oldAlpha = paint.alpha
                paint.alpha = (oldAlpha * (1f - animFraction)).coerceIn(0f, 255f).toInt()
                canvas.drawText(String(charArrayOf(prevText[i])), x + cw / 2f + wobble.first,
                    textY + wobble.second - (1f - animFraction) * 30f, paint)
                paint.alpha = (oldAlpha * animFraction).coerceIn(0f, 255f).toInt()
                canvas.drawText(digit, x + cw / 2f + wobble.first,
                    textY + wobble.second + (1f - animFraction) * 30f, paint)
                paint.alpha = oldAlpha
            } else {
                canvas.drawText(digit, x + cw / 2f + wobble.first,
                    textY + wobble.second, paint)
            }
            x += cw + hPad * 2
        }
    }

    private fun drawNeon(canvas: Canvas, cx: Float, textY: Float) {
        ensureGlowBitmap(textY)
        val glow = glowBitmap
        if (glow != null) {
            val sv = if (dimmed) 0.55f else 1f
            val glowColor = Color.HSVToColor(floatArrayOf(neonHue, 1f, sv))
            glowPaint.colorFilter = PorterDuffColorFilter(glowColor, PorterDuff.Mode.SRC_IN)
            glowPaint.alpha = if (dimmed) 90 else 200
            canvas.drawBitmap(glow, 0f, 0f, glowPaint)
        }
        // Crisp digits on top of the halo.
        paint.color = clockColor
        drawTextWithAnim(canvas, cx, textY)
    }

    private fun drawGradient(canvas: Canvas, cx: Float, cy: Float, textY: Float) {
        val band = clockSize * 2f
        val highlight = if (dimmed) lighten(clockColor, 0.3f) else Color.WHITE
        val shader = LinearGradient(
            0f, 0f, band, 0f,
            intArrayOf(clockColor, highlight, clockColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.MIRROR
        )
        gradientMatrix.reset()
        gradientMatrix.setTranslate(gradientPhase * band, 0f)
        shader.setLocalMatrix(gradientMatrix)
        paint.shader = shader
        drawTextWithAnim(canvas, cx, textY)
        paint.shader = null
    }

    private fun drawOutline(canvas: Canvas, cx: Float, textY: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        drawTextWithAnim(canvas, cx, textY)
        paint.style = Paint.Style.FILL
    }

    /**
     * Draws [displayText] with the active paint, layering the brief
     * slide/fade time-change animation when one is in progress.
     */
    private fun drawTextWithAnim(canvas: Canvas, cx: Float, textY: Float) {
        val oldAlpha = paint.alpha
        val shakeX: Float
        val shakeY: Float
        if (shakeOffsetX != 0f || shakeOffsetY != 0f) {
            val t = (System.currentTimeMillis() * 0.01).toFloat()
            shakeX = shakeOffsetX * sin(t) * 3f
            shakeY = shakeOffsetY * sin(t * 0.7f) * 3f
        } else {
            shakeX = 0f
            shakeY = 0f
        }
        val drawCx = cx + shakeX
        val drawTextY = textY + shakeY

        if (animFraction < 1f && prevText.isNotEmpty()) {
            val slide = (1f - animFraction) * 40f
            paint.alpha = (oldAlpha * (1f - animFraction)).coerceIn(0f, 255f).toInt()
            canvas.drawText(prevText, drawCx, drawTextY - slide, paint)
            paint.alpha = (oldAlpha * animFraction).coerceIn(0f, 255f).toInt()
            canvas.drawText(displayText, drawCx, drawTextY + slide, paint)
            paint.alpha = oldAlpha
        } else {
            canvas.drawText(displayText, drawCx, drawTextY, paint)
        }
    }

    // ------------------------------------------------------------------
    // Neon glow cache
    // ------------------------------------------------------------------

    private fun invalidateGlowCache() {
        glowCacheKey = null
    }

    /**
     * Renders a blurred white silhouette of the current time into an offscreen
     * bitmap (heavy [BlurMaskFilter] work done once per glyph change, ~1/min).
     * The halo is recolored cheaply per frame via a color filter, so the hue
     * cycle costs almost nothing.  Chosen over [android.graphics.RenderEffect]
     * because RenderEffect blurs the whole view (crisp digits included), while
     * we want the blur strictly behind sharp text; this also works below API 31.
     */
    private fun ensureGlowBitmap(textY: Float) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        val key = "$displayText|$clockSize|$w|$h|${effectiveTypeface()}"
        if (key == glowCacheKey && glowBitmap != null) return

        glowBitmap?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val gp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = clockSize
            typeface = effectiveTypeface()
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            maskFilter = BlurMaskFilter(clockSize * 0.22f, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawText(displayText, w / 2f, textY, gp)
        glowBitmap = bmp
        glowCacheKey = key
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Some styles dictate their own font family regardless of the user pick. */
    private fun effectiveTypeface(): Typeface {
        val base = clockTypeface ?: Typeface.DEFAULT
        return when (clockStyle) {
            ClockStyle.NEON -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
            ClockStyle.MONO -> Typeface.MONOSPACE
            ClockStyle.GRADIENT -> Typeface.create(base, Typeface.BOLD)
            ClockStyle.BUBBLY -> Typeface.create(base, Typeface.BOLD)
            else -> base
        }
    }

    private fun lighten(color: Int, amount: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
