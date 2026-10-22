package com.linkplay.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.Settings
import android.view.View

/**
 * The app's one signature: two player chips joined by a cable line. The line is dashed and grey
 * while connecting and a solid accent line once linked (one 300 ms cross-fade; instant when the
 * system's animator duration scale is 0).
 */
class CableView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private var left = ""
    private var right: String? = null
    private var linked = false
    private var progress = 0f // 0 = connecting, 1 = linked
    private var animator: ValueAnimator? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.tonal }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * dp }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val rect = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    /** [rightLabel] null: nobody on the other end yet. */
    fun set(leftLabel: String, rightLabel: String?, isLinked: Boolean) {
        val changed = leftLabel != left || rightLabel != right
        left = leftLabel
        right = rightLabel
        contentDescription = "$leftLabel, ${rightLabel ?: "waiting for a player"}, " + if (isLinked) "linked" else "not linked yet"
        if (isLinked != linked) {
            linked = isLinked
            animator?.cancel()
            val target = if (isLinked) 1f else 0f
            val scale = try { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) } catch (e: Exception) { 1f }
            if (scale == 0f || !isAttachedToWindow) {
                progress = target
                invalidate()
            } else {
                animator = ValueAnimator.ofFloat(progress, target).apply {
                    duration = 300
                    addUpdateListener { progress = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        } else if (changed) invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(resolveSize(0, w), (56 * dp).toInt())
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val h = 40 * dp
        val top = (height - h) / 2
        val pad = 14 * dp
        val maxW = width * 0.45f
        val left = android.text.TextUtils.ellipsize(left, android.text.TextPaint(text), maxW - pad * 2, android.text.TextUtils.TruncateAt.END).toString()
        val lw = text.measureText(left) + pad * 2
        val rl = android.text.TextUtils.ellipsize(right ?: "P2 waiting…", android.text.TextPaint(text), maxW - pad * 2, android.text.TextUtils.TruncateAt.END).toString()
        val rw = text.measureText(rl) + pad * 2
        val l = RectF(0f, top, lw, top + h)
        val r = RectF(width - rw, top, width.toFloat(), top + h)
        val midY = height / 2f
        val gap = 6 * dp
        // The cable, behind nothing: it runs between the chips.
        if (r.left - l.right > 2 * gap) {
            line.strokeWidth = 2.5f * dp
            line.pathEffect = DashPathEffect(floatArrayOf(6 * dp, 6 * dp), 0f)
            line.color = Pal.muted
            line.alpha = ((1f - progress) * 255).toInt()
            if (line.alpha > 0) c.drawLine(l.right + gap, midY, r.left - gap, midY, line)
            line.pathEffect = null
            line.color = Pal.accent
            line.alpha = (progress * 255).toInt()
            if (line.alpha > 0) c.drawLine(l.right + gap, midY, r.left - gap, midY, line)
        }
        chip(c, l, left, true)
        chip(c, r, rl, right != null)
    }

    private fun chip(c: Canvas, r: RectF, label: String, present: Boolean) {
        fill.color = Glass.alpha(Glass.tint, if (Glass.highContrast) 1f else 0.6f)
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
        stroke.color = if (present && progress > 0.5f) Pal.accent else Glass.alpha(Color.WHITE, if (present) 0.45f else 0.22f)
        stroke.strokeWidth = (if (present && progress > 0.5f) 1.5f else 1f) * dp
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, stroke)
        text.color = if (present) Pal.text else Pal.muted
        val y = r.centerY() - (text.descent() + text.ascent()) / 2
        c.drawText(label, r.left + 14 * dp, y, text)
    }
}
