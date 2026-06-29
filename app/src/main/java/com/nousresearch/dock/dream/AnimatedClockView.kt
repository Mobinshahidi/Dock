package com.nousresearch.dock.dream

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class AnimatedClockView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class ClockStyle {
        DEFAULT, BUBBLY, NEON, GRADIENT, OUTLINE
    }

    var clockStyle: ClockStyle = ClockStyle.DEFAULT
        set(value) {
            field = value
            invalidate()
        }

    var clockColor: Int = Color.parseColor("#c3c2b7")
        set(value) {
            field = value
            paint.color = value
            invalidate()
        }

    var clockSize: Float = 120f
        set(value) {
            field = value
            paint.textSize = value
            invalidate()
        }

    var clockTypeface: Typeface? = null
        set(value) {
            field = value
            paint.typeface = value ?: Typeface.DEFAULT
            invalidate()
        }

    var animEnabled: Boolean = true

    private val density = resources.displayMetrics.density

    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = clockColor
        textSize = clockSize
        textAlign = Paint.Align.CENTER
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var displayText = ""
    private var prevText = ""

    // Animation state
    private var animFraction = 1f
    private var animator: ValueAnimator? = null

    // Preferred width per label fetch
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // Updates every second
    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            val now = Calendar.getInstance()
            val newText = timeFormat.format(now.time)
            if (newText != displayText) {
                prevText = displayText
                displayText = newText
                if (animEnabled) animateChange()
            }
            invalidate()
            // Sync to the next second boundary
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

    fun start() {
        // Immediate first tick
        tickRunnable.run()
    }

    fun stop() {
        handler.removeCallbacks(tickRunnable)
        animator?.cancel()
    }

    // --- Measurement ---

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val text = "00:00"
        val textWidth = paint.measureText(text)
        val textHeight = paint.fontMetrics.let { it.bottom - it.top }
        val bubblePad = if (clockStyle == ClockStyle.BUBBLY) (12 * density) else 0f
        val w = (textWidth + bubblePad * 2).toInt() + paddingLeft + paddingRight
        val h = (textHeight + bubblePad * 2).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(w, widthMeasureSpec),
            resolveSize(h, heightMeasureSpec)
        )
    }

    // --- Drawing ---

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (displayText.isEmpty()) return

        val cx = width / 2f
        val cy = height / 2f

        // Adjust paint for this frame
        paint.color = clockColor
        paint.textSize = clockSize
        paint.typeface = clockTypeface ?: Typeface.DEFAULT

        when (clockStyle) {
            ClockStyle.DEFAULT -> drawDefault(canvas, cx, cy)
            ClockStyle.BUBBLY -> drawBubbly(canvas, cx, cy)
            ClockStyle.NEON -> drawNeon(canvas, cx, cy)
            ClockStyle.GRADIENT -> drawGradient(canvas, cx, cy)
            ClockStyle.OUTLINE -> drawOutline(canvas, cx, cy)
        }
    }

    // --- Style renderers ---

    private fun drawDefault(canvas: Canvas, cx: Float, cy: Float) {
        val oldAlpha = paint.alpha
        val textY = cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f

        if (animFraction < 1f) {
            // Old text slides up + fades out
            paint.alpha = (oldAlpha * (1f - animFraction)).coerceIn(0, 255)
            canvas.drawText(prevText, cx, textY - (1f - animFraction) * 40f, paint)
            // New text slides up from below + fades in
            paint.alpha = (oldAlpha * animFraction).coerceIn(0, 255)
            canvas.drawText(displayText, cx, textY + (1f - animFraction) * 40f, paint)
            paint.alpha = oldAlpha
        } else {
            canvas.drawText(displayText, cx, textY, paint)
        }
    }

    private fun drawBubbly(canvas: Canvas, cx: Float, cy: Float) {
        val chars = displayText.toCharArray()
        val charWidths = FloatArray(chars.size)
        paint.getTextWidths(displayText, charWidths)
        val totalWidth = charWidths.sum()
        val bubbleH = paint.textSize * 1.2f
        val corner = bubbleH * 0.35f
        val hPad = paint.textSize * 0.2f
        val startX = cx - totalWidth / 2f

        val textY = cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        val bubbleTop = cy - bubbleH / 2f

        var x = startX
        for (i in chars.indices) {
            val cw = charWidths[i]
            val bw = cw + hPad * 2

            bgPaint.color = clockColor
            bgPaint.alpha = 25
            val rect = RectF(x - hPad, bubbleTop, x + cw + hPad, bubbleTop + bubbleH)
            canvas.drawRoundRect(rect, corner, corner, bgPaint)

            bgPaint.color = clockColor
            bgPaint.alpha = 60
            bgPaint.style = Paint.Style.STROKE
            bgPaint.strokeWidth = 1.5f * density
            canvas.drawRoundRect(rect, corner, corner, bgPaint)
            bgPaint.style = Paint.Style.FILL

            // Digit
            val digitText = String(charArrayOf(chars[i]))
            val oldAlpha = paint.alpha

            if (animFraction < 1f && i < 2 && prevText.length > i) {
                val prevChar = prevText[i]
                if (prevChar != chars[i]) {
                    paint.alpha = (oldAlpha * (1f - animFraction)).coerceIn(0, 255)
                    canvas.drawText(String(charArrayOf(prevChar)), x + cw / 2f, textY - (1f - animFraction) * 30f, paint)
                    paint.alpha = (oldAlpha * animFraction).coerceIn(0, 255)
                    canvas.drawText(digitText, x + cw / 2f, textY + (1f - animFraction) * 30f, paint)
                    paint.alpha = oldAlpha
                } else {
                    canvas.drawText(digitText, x + cw / 2f, textY, paint)
                }
            } else {
                canvas.drawText(digitText, x + cw / 2f, textY, paint)
            }
            x += cw + hPad * 2
        }
    }

    private fun drawNeon(canvas: Canvas, cx: Float, cy: Float) {
        val textY = cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        val glowColor = clockColor

        paint.alpha = 80
        paint.setShadowLayer(paint.textSize * 0.3f, 0f, 0f, glowColor)
        drawTextWithAnim(canvas, cx, textY)
        paint.setShadowLayer(paint.textSize * 0.15f, 0f, 0f, Color.WHITE)
        paint.alpha = 120
        drawTextWithAnim(canvas, cx, textY)
        paint.clearShadowLayer()
        paint.alpha = 255
        drawTextWithAnim(canvas, cx, textY)
    }

    private fun drawGradient(canvas: Canvas, cx: Float, cy: Float) {
        val textY = cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        val shader = android.graphics.LinearGradient(
            0f, cy - paint.textSize / 2f, 0f, cy + paint.textSize / 2f,
            Color.WHITE, clockColor, android.graphics.Shader.TileMode.CLAMP
        )
        paint.shader = shader
        drawTextWithAnim(canvas, cx, textY)
        paint.shader = null
    }

    private fun drawOutline(canvas: Canvas, cx: Float, cy: Float) {
        val textY = cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * density
        paint.color = clockColor
        drawTextWithAnim(canvas, cx, textY)
        paint.style = Paint.Style.FILL
    }

    // --- Shared animation helper ---

    private fun drawTextWithAnim(canvas: Canvas, cx: Float, textY: Float) {
        val oldAlpha = paint.alpha
        val oldShader = paint.shader
        val oldStyle = paint.style
        val oldStroke = paint.strokeWidth

        if (animFraction < 1f) {
            paint.alpha = (oldAlpha * (1f - animFraction)).coerceIn(0, 255)
            canvas.drawText(prevText, cx, textY - (1f - animFraction) * 40f, paint)
            paint.alpha = (oldAlpha * animFraction).coerceIn(0, 255)
            canvas.drawText(displayText, cx, textY + (1f - animFraction) * 40f, paint)
            paint.alpha = oldAlpha
        } else {
            canvas.drawText(displayText, cx, textY, paint)
        }

        paint.shader = oldShader
        paint.style = oldStyle
        paint.strokeWidth = oldStroke
    }
}
