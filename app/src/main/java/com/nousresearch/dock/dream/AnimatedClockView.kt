package com.nousresearch.dock.dream

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
import kotlin.math.exp
import kotlin.math.sin

/**
 * Custom-drawn clock with per-second ticking, a short time-change animation,
 * shake-reactive physics, and a set of visual "clock styles".
 *
 * Motion sources, all self-contained and lifecycle-safe:
 *  - Idle animations (neon hue cycle, gradient sweep) — [ValueAnimator], ~30fps
 *    throttled, INFINITE, cancelled in [stop]/[onDetachedFromWindow].
 *  - Shake — [onShake] runs a brief decaying-spring [ValueAnimator]; every style
 *    wobbles via [shakeOffset]. Transient (~1.1s) then settles, so it costs
 *    nothing at rest.
 *
 * Callers MUST call [stop] before discarding/replacing the view; the detach
 * hook is a safety net so animators never outlive the view on rotation.
 */
class AnimatedClockView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class ClockStyle {
        DEFAULT, NEON, MONO, GRADIENT, OUTLINE, BUBBLE
    }

    var clockStyle: ClockStyle = ClockStyle.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            invalidateGlowCache()
            restartIdleAnimationIfRunning()
            requestLayout()
            invalidate()
        }

    /**
     * When true (OLED / night-dim active) bright effects are toned down and
     * idle motion slowed, so the styles cooperate with a darkened screen.
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

    /** 24-hour (HH:mm) vs 12-hour (h:mm) time format. */
    var is24Hour: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            timeFormat = SimpleDateFormat(if (value) "HH:mm" else "h:mm", Locale.getDefault())
            displayText = timeFormat.format(Calendar.getInstance().time)
            invalidateGlowCache()
            requestLayout()
            invalidate()
        }

    /**
     * Measure the current time at various sizes and return the largest that
     * fits within [availableWidth]. Called by DockDreamService after layout so
     * the clock never overflows its container (orientation-responsive).
     */
    fun computeFittingTextSize(availableWidth: Int): Float {
        val text = if (displayText.isNotEmpty()) displayText else if (is24Hour) "88:88" else "8:88"
        // BUBBLE lays digits out wider (dot gap + heavy stroke); reserve margin.
        val target = if (clockStyle == ClockStyle.BUBBLE) availableWidth * 0.80f else availableWidth.toFloat()
        var lo = 8f
        var hi = clockSize
        while (hi - lo > 1f) {
            val mid = (lo + hi) / 2f
            paint.textSize = mid
            if (paint.measureText(text) <= target) lo = mid else hi = mid
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

    /** Paint for the BUBBLE style's colon dots. */
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var displayText = ""
    private var prevText = ""

    // Time-change animation state
    private var animFraction = 1f
    private var animator: ValueAnimator? = null

    // Idle animation state
    private var isRunning = false
    private val idleAnimators = mutableListOf<ValueAnimator>()
    private var neonHue = 0f
    private var gradientPhase = 0f
    private val gradientMatrix = Matrix()

    // Shake state (transient, user-triggered)
    private var shakeAnimator: ValueAnimator? = null
    private var shakeFraction = 1f // 1 = settled / no shake
    private var shakeDirX = 0f
    private var shakeDirY = 0f

    // Neon glow cache (regenerated only when the rendered glyphs change — ~1/min)
    private var glowBitmap: Bitmap? = null
    private var glowCacheKey: String? = null

    // 30fps redraw throttle for the idle ValueAnimators
    private var lastIdleInvalidateMs = 0L

    private var timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            val now = Calendar.getInstance()
            val newText = timeFormat.format(now.time)
            if (newText != displayText) {
                prevText = displayText
                displayText = newText
                invalidateGlowCache()
                // BUBBLE is intentionally static — no minute-change animation.
                if (animEnabled && clockStyle != ClockStyle.BUBBLE) animateChange()
            }
            invalidate()
            val delay = 1000L - System.currentTimeMillis() % 1000L
            handler.postDelayed(this, delay)
        }
    }

    private fun animateChange() {
        animFraction = 0f
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420L
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
        tickRunnable.run()
        startIdleAnimation()
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(tickRunnable)
        animator?.cancel()
        shakeAnimator?.cancel()
        shakeFraction = 1f
        stopIdleAnimation()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
        glowBitmap?.recycle()
        glowBitmap = null
        glowCacheKey = null
    }

    // ------------------------------------------------------------------
    // Shake physics
    // ------------------------------------------------------------------

    /**
     * Trigger a shake wobble in the given (normalized) direction. Runs a brief
     * decaying spring; repeated calls restart it so continuous shaking keeps the
     * clock jiggling. Cheap and self-terminating — nothing animates at rest.
     */
    fun onShake(dirX: Float, dirY: Float) {
        shakeDirX = dirX.coerceIn(-1f, 1f)
        shakeDirY = dirY.coerceIn(-1f, 1f)
        shakeAnimator?.cancel()
        shakeFraction = 0f
        shakeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100L
            interpolator = LinearInterpolator()
            addUpdateListener {
                shakeFraction = it.animatedFraction
                invalidate()
            }
            start()
        }
    }

    /** Current springy offset for an element at [phase], scaled by [amp] px. */
    private fun shakeOffset(phase: Float, amp: Float): Pair<Float, Float> {
        if (shakeFraction >= 1f) return 0f to 0f
        val decay = exp(-3.4f * shakeFraction)
        val osc = sin(shakeFraction * 26f + phase) * decay
        return Pair(shakeDirX * osc * amp, shakeDirY * osc * amp)
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
            ClockStyle.NEON -> startNeonHueCycle()
            ClockStyle.GRADIENT -> startGradientSweep()
            // BUBBLE / Classic / Mono / Outline are intentionally static.
            else -> {}
        }
    }

    private fun stopIdleAnimation() {
        idleAnimators.forEach { it.cancel() }
        idleAnimators.clear()
    }

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
        val extra = when (clockStyle) {
            ClockStyle.NEON -> clockSize * 0.5f
            ClockStyle.BUBBLE -> clockSize * 0.28f
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

        // Reset shared paint state every frame so styles never bleed together.
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
        paint.textAlign = Paint.Align.CENTER

        val textY = cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f

        when (clockStyle) {
            ClockStyle.DEFAULT -> drawPlain(canvas, cx, textY)
            ClockStyle.MONO -> drawPlain(canvas, cx, textY)
            ClockStyle.NEON -> drawNeon(canvas, cx, textY)
            ClockStyle.GRADIENT -> drawGradient(canvas, cx, cy, textY)
            ClockStyle.OUTLINE -> drawOutline(canvas, cx, textY)
            ClockStyle.BUBBLE -> drawBubble(canvas, cx, cy, textY)
        }
    }

    private fun drawPlain(canvas: Canvas, cx: Float, textY: Float) {
        drawTextWithAnim(canvas, cx, textY)
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
     * iOS StandBy-inspired style: very heavy, rounded numerals filled with a
     * vivid horizontal gradient (centered on the chosen clock color), with the
     * colon drawn as two soft dots. Static at rest; on shake, each element
     * (HH, mm, both dots) springs independently like bouncing bubbles.
     */
    private fun drawBubble(canvas: Canvas, cx: Float, cy: Float, textY: Float) {
        val parts = displayText.split(":")
        if (parts.size < 2) { drawPlain(canvas, cx, textY); return }
        val hh = parts[0]
        val mm = parts[1]

        // Fat rounded glyphs: bold face + a heavy round stroke fused to the fill.
        paint.style = Paint.Style.FILL_AND_STROKE
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = clockSize * 0.11f
        paint.textAlign = Paint.Align.LEFT

        val hhW = paint.measureText(hh)
        val mmW = paint.measureText(mm)
        val dotR = clockSize * 0.085f
        val gap = dotR * 3.4f
        val totalW = hhW + gap + mmW
        val startX = cx - totalW / 2f

        val (c1, c2) = bubbleGradientColors()
        paint.shader = LinearGradient(startX, 0f, startX + totalW, 0f, c1, c2, Shader.TileMode.CLAMP)

        val wHH = shakeOffset(0f, clockSize * 0.06f)
        canvas.drawText(hh, startX + wHH.first, textY + wHH.second, paint)
        val wMM = shakeOffset(2.4f, clockSize * 0.06f)
        canvas.drawText(mm, startX + hhW + gap + wMM.first, textY + wMM.second, paint)
        paint.shader = null

        // Colon dots bounce hardest.
        val dotCx = startX + hhW + gap / 2f
        dotPaint.color = bubbleDotColor()
        val wTop = shakeOffset(1.1f, clockSize * 0.11f)
        val wBot = shakeOffset(3.7f, clockSize * 0.11f)
        canvas.drawCircle(dotCx + wTop.first, cy - dotR * 1.5f + wTop.second, dotR, dotPaint)
        canvas.drawCircle(dotCx + wBot.first, cy + dotR * 1.5f + wBot.second, dotR, dotPaint)

        paint.style = Paint.Style.FILL
        paint.strokeWidth = 0f
        paint.textAlign = Paint.Align.CENTER
    }

    /**
     * Draws [displayText] with the active paint plus a whole-clock shake offset.
     * During a minute change, the old time fades out while the new one fades in
     * with a soft scale-up pop (cleaner than a slide).
     */
    private fun drawTextWithAnim(canvas: Canvas, cx: Float, textY: Float) {
        val (sxo, syo) = shakeOffset(0f, clockSize * 0.06f)
        val baseX = cx + sxo
        val baseY = textY + syo

        if (animEnabled && animFraction < 1f && prevText.isNotEmpty()) {
            val oldAlpha = paint.alpha
            // Outgoing: fade out (quadratic so it clears quickly).
            val outA = (1f - animFraction) * (1f - animFraction)
            paint.alpha = (oldAlpha * outA).coerceIn(0f, 255f).toInt()
            canvas.drawText(prevText, baseX, baseY, paint)
            // Incoming: fade + scale-up pop.
            val scale = 0.82f + 0.18f * animFraction
            val pivotY = baseY - clockSize * 0.33f
            canvas.save()
            canvas.scale(scale, scale, baseX, pivotY)
            paint.alpha = (oldAlpha * animFraction).coerceIn(0f, 255f).toInt()
            canvas.drawText(displayText, baseX, baseY, paint)
            canvas.restore()
            paint.alpha = oldAlpha
        } else {
            canvas.drawText(displayText, baseX, baseY, paint)
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
     * bitmap (heavy [BlurMaskFilter] work done once per glyph change, ~1/min);
     * recolored cheaply per frame via a color filter for the hue cycle. Chosen
     * over RenderEffect because RenderEffect blurs the whole view (crisp digits
     * included) — we want the blur strictly behind sharp text; also works < API 31.
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
            ClockStyle.BUBBLE -> Typeface.create(base, Typeface.BOLD)
            else -> base
        }
    }

    private fun bubbleGradientColors(): Pair<Int, Int> {
        val hsv = FloatArray(3)
        Color.colorToHSV(clockColor, hsv)
        val s = maxOf(hsv[1], 0.72f)
        val v = if (dimmed) minOf(hsv[2], 0.6f) else maxOf(hsv[2], 0.9f)
        val h = hsv[0]
        val c1 = Color.HSVToColor(floatArrayOf((h - 28f + 360f) % 360f, s, v))
        val c2 = Color.HSVToColor(floatArrayOf((h + 28f) % 360f, s, v))
        return Pair(c1, c2)
    }

    private fun bubbleDotColor(): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(clockColor, hsv)
        return Color.HSVToColor(floatArrayOf(hsv[0], hsv[1] * 0.18f, if (dimmed) 0.55f else 0.92f))
    }

    private fun lighten(color: Int, amount: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * amount).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * amount).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * amount).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }
}
