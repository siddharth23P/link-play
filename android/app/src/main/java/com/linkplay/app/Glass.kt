package com.linkplay.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Glass helpers: recipes (drawables), the frosted backdrop, the one dialog, and the motion / contrast switches. */
object Glass {
    /** Settings > High contrast text: opaque surfaces, no blur, stronger borders. */
    @Volatile var highContrast = false

    fun refreshHighContrast(ctx: Context): Boolean {
        highContrast = try { Settings.Secure.getInt(ctx.contentResolver, "high_text_contrast_enabled", 0) == 1 } catch (_: Throwable) { false }
        return highContrast
    }

    /** False when the system's animator duration scale is 0 (no motion). */
    fun motionOn(ctx: Context): Boolean =
        try { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f } catch (_: Throwable) { true }

    val tint = Color.rgb(0x14, 0x12, 0x1C)
    val deep = Color.rgb(0x0E, 0x0C, 0x16)
    val indigo = Color.rgb(0x24, 0x16, 0x4F)
    val accent = Color.rgb(0x6A, 0x4B, 0xF0)

    /** Glass/Regular: menu panel, dialogs, cards. */
    fun regular(dp: Float, radiusDp: Float = 24f, fill: Float = 0.8f, shadow: Boolean = false): Drawable =
        GlassDrawable(dp, radiusDp, tint, fill, 0.16f, 0.22f, if (shadow) 28f else 0f, topGradient = true)

    /** Glass/Control: buttons (60% fill); pressed = accent @ 92%. */
    fun control(dp: Float, radiusDp: Float = 14f): Drawable = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), GlassDrawable(dp, radiusDp, tint, 0.35f, 0.10f, 0.12f, 0f, false))
        addState(intArrayOf(android.R.attr.state_pressed), GlassDrawable(dp, radiusDp, accent, 0.92f, 0.18f, 0.22f, 0f, false))
        addState(intArrayOf(), GlassDrawable(dp, radiusDp, tint, 0.60f, 0.18f, 0.22f, 0f, false))
    }

    /** The accent primary button. */
    fun primary(dp: Float, radiusDp: Float = 14f): Drawable = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), GlassDrawable(dp, radiusDp, accent, 0.35f, 0.10f, 0.10f, 0f, false))
        addState(intArrayOf(android.R.attr.state_pressed), GlassDrawable(dp, radiusDp, accent, 1f, 0.45f, 0.45f, 0f, false))
        addState(intArrayOf(), GlassDrawable(dp, radiusDp, accent, 1f, 0.22f, 0.30f, 0f, false))
    }

    /** A recessed well (the code box): glass-tint at 50%, hairline only. */
    fun well(dp: Float, radiusDp: Float = 14f): Drawable = GlassDrawable(dp, radiusDp, tint, 0.5f, 0.10f, 0f, 0f, false)

    /** Alpha over a colour. */
    fun alpha(c: Int, a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    // ---------------------------------------------------------------- frames

    /** The game's current frame as a 240x160 bitmap, or null. */
    fun currentFrame(): Bitmap? {
        val px = ShortArray(Native.WIDTH * Native.HEIGHT)
        val ok = try { Native.copyFrame(px) } catch (_: Throwable) { false }
        return if (ok) frameBitmap(px) else null
    }

    fun frameBitmap(px: ShortArray): Bitmap {
        val argb = IntArray(px.size) {
            val c = px[it].toInt()
            val r = (c shr 11) and 31; val g = (c shr 5) and 63; val b = c and 31
            Color.rgb(r * 255 / 31, g * 255 / 63, b * 255 / 31)
        }
        return Bitmap.createBitmap(argb, Native.WIDTH, Native.HEIGHT, Bitmap.Config.ARGB_8888)
    }

    fun lastFrameFile(ctx: Context, sha1: String): File = File(File(ctx.filesDir, "states/$sha1").apply { mkdirs() }, "last-frame.png")

    fun saveLastFrame(ctx: Context, sha1: String, frame: Bitmap) {
        Thread {
            try {
                val f = lastFrameFile(ctx, sha1)
                val tmp = File(f.parentFile, f.name + ".tmp")
                java.io.FileOutputStream(tmp).use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
                tmp.renameTo(f)
            } catch (_: Throwable) {}
        }.apply { isDaemon = true }.start()
    }

    fun loadLastFrame(ctx: Context, sha1: String): Bitmap? = try {
        lastFrameFile(ctx, sha1).takeIf { it.isFile }?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }
    } catch (_: Throwable) { null }

    /** 60x40 box-blurred copy of [src] (3 passes, radius 2): the frosted layer. */
    fun frost(src: Bitmap): Bitmap {
        val w = 60; val h = 40
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        var a = IntArray(w * h)
        small.getPixels(a, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        var b = IntArray(w * h)
        repeat(3) {
            boxPass(a, b, w, h, true); boxPass(b, a, w, h, false)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    private fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, horizontal: Boolean, r: Int = 2) {
        val n = 2 * r + 1
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        for (l in 0 until lines) {
            for (i in 0 until len) {
                var sr = 0; var sg = 0; var sb = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val p = if (horizontal) src[l * w + j] else src[j * w + l]
                    sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255
                }
                val o = Color.rgb(sr / n, sg / n, sb / n)
                if (horizontal) dst[l * w + i] = o else dst[i * w + l] = o
            }
        }
    }

    // ---------------------------------------------------------------- motion

    /** Panel entrance: 180 ms fade + scale 0.98 to 1 (nothing when animations are off). */
    fun enter(v: View) {
        if (!motionOn(v.context)) return
        v.alpha = 0f; v.scaleX = 0.98f; v.scaleY = 0.98f
        v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
    }

    fun dp(ctx: Context) = ctx.resources.displayMetrics.density

    /**
     * A dialog whose window really covers the whole screen. The default dialog theme is a floating window
     * the system sizes with margins and keeps clear of the camera cutout; on a punch-hole phone that window
     * could be resized again and again while the bars and cutout insets settle (the panel "vibrated").
     * This one is a non-floating translucent window that extends under the cutout and the system bars.
     */
    fun fullScreenDialog(a: android.app.Activity): android.app.Dialog {
        val d = android.app.Dialog(a, android.R.style.Theme_Translucent_NoTitleBar_Fullscreen)
        val w = d.window!!
        w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        w.addFlags(android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        w.attributes = w.attributes.apply {
            layoutInDisplayCutoutMode = if (android.os.Build.VERSION.SDK_INT >= 30)
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false)
        // Over the game the activity is full screen: keep the bars hidden while the dialog has focus too,
        // or the navigation bar pops up over the frosted backdrop.
        d.setOnShowListener { keepImmersive(a, d) }
        return d
    }

    private fun keepImmersive(a: android.app.Activity, d: android.app.Dialog) {
        val w = d.window ?: return
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val shown = a.window?.decorView?.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.navigationBars()) ?: true
            if (!shown) w.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = a.window?.decorView?.systemUiVisibility ?: 0
        }
    }

    /**
     * The camera cutout's safe insets, read once from the activity's window (stable while a dialog is open),
     * so a panel's margins are set once instead of following every insets pass.
     */
    fun cutoutInsets(a: android.app.Activity): android.graphics.Rect {
        val c = a.window?.decorView?.rootWindowInsets?.displayCutout
            ?: return android.graphics.Rect()
        return android.graphics.Rect(c.safeInsetLeft, c.safeInsetTop, c.safeInsetRight, c.safeInsetBottom)
    }
}

/** One glass surface: fill, hairline, top highlight (1 dp line + gradient), optional drop shadow outside the bounds. */
class GlassDrawable(
    private val dp: Float,
    private val radiusDp: Float,
    private val base: Int,
    private val fillAlpha: Float,
    private val hairlineAlpha: Float,
    private val highlightAlpha: Float,
    private val shadowDp: Float,
    private val topGradient: Boolean,
) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp }
    private val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp }
    private val grad = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var alpha = 255

    override fun onBoundsChange(b: android.graphics.Rect) {
        val top = b.top.toFloat()
        hi.shader = LinearGradient(0f, top, 0f, top + minOf(b.height().toFloat(), 28 * dp),
            Glass.alpha(Color.WHITE, highlightAlpha), 0x00FFFFFF, Shader.TileMode.CLAMP)
        grad.shader = LinearGradient(0f, top, 0f, top + 24 * dp, Glass.alpha(Color.WHITE, 0.06f), 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun draw(c: Canvas) {
        val hc = Glass.highContrast
        val b = bounds
        val r = radiusDp * dp
        val k = alpha / 255f
        if (shadowDp > 0f && !hc) {
            val layers = 7
            val spread = shadowDp * dp / 2
            for (i in layers downTo 1) {
                val g = spread * i / layers
                rect.set(b.left - g, b.top - g + 10 * dp * 0.6f, b.right + g, b.bottom + g + 10 * dp)
                shade.color = Glass.alpha(Color.BLACK, 0.35f / layers * 0.9f * k)
                c.drawRoundRect(rect, r + g, r + g, shade)
            }
        }
        rect.set(b.left + dp / 2, b.top + dp / 2, b.right - dp / 2, b.bottom - dp / 2)
        fill.color = Glass.alpha(base, if (hc) 1f else fillAlpha * k)
        c.drawRoundRect(rect, r, r, fill)
        if (!hc && topGradient) c.drawRoundRect(rect, r, r, grad)
        border.color = Glass.alpha(Color.WHITE, (if (hc) 0.40f else hairlineAlpha) * k)
        c.drawRoundRect(rect, r, r, border)
        if (!hc && highlightAlpha > 0f) c.drawRoundRect(rect, r, r, hi)
    }

    override fun setAlpha(a: Int) { alpha = a; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The content layer behind UI: a blurred, saturated, dimmed game frame, or a quiet gradient when there is none. */
class FrostedBackdrop(ctx: Context) : View(ctx) {
    var scrim = 0.55f
        set(v) { field = v; invalidate() }
    private var frosted: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.3f) })
    }
    private val bg = Paint()
    private val dst = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    /** [src]: any game frame (it is blurred here); null for the gradient. */
    fun setSource(src: Bitmap?) { setFrosted(if (src == null || Glass.highContrast) null else Glass.frost(src)) }

    /** An already blurred bitmap (see [Glass.frost]). */
    fun setFrosted(b: Bitmap?) { frosted = if (Glass.highContrast) null else b; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Glass.indigo, Glass.deep, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val f = frosted
        val w = width.toFloat(); val h = height.toFloat()
        if (f == null) {
            if (Glass.highContrast) c.drawColor(Glass.deep) else c.drawRect(0f, 0f, w, h, bg)
            return
        }
        val s = maxOf(w / f.width, h / f.height)
        val dw = f.width * s; val dh = f.height * s
        dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
        c.drawBitmap(f, null, dst, paint)
        c.drawColor(Glass.alpha(Color.BLACK, scrim))
    }
}

/**
 * The one dialog of the app (replaces every AlertDialog): Glass/Regular card over a frosted backdrop of the current
 * frame. Cancel is a glass control; the primary action is the accent fill (or a danger-coloured label).
 */
class GlassDialog(private val a: Activity, private val backdrop: Bitmap? = null) {
    private val dp = a.resources.displayMetrics.density
    private val dialog = Glass.fullScreenDialog(a)
    private var title: String? = null
    private var message: CharSequence? = null
    private var body: View? = null
    private class Btn(val label: String, val kind: Int, val dismiss: Boolean, val onClick: () -> Unit)
    private val buttons = mutableListOf<Btn>()
    private val views = mutableListOf<TextView>()

    fun title(t: String) = apply { title = t }
    fun message(m: CharSequence?) = apply { message = m }
    fun view(v: View) = apply { body = v }
    /** The preferred action: accent fill, or (destructive) a danger label on glass. */
    fun primary(label: String, destructive: Boolean = false, dismiss: Boolean = true, onClick: () -> Unit = {}) =
        apply { buttons += Btn(label, if (destructive) DANGER else PRIMARY, dismiss, onClick) }
    /** The safe choice (Cancel, OK…): glass control. */
    fun secondary(label: String = "Cancel", onClick: () -> Unit = {}) = apply { buttons += Btn(label, SECONDARY, true, onClick) }
    /** A third, quiet choice: accent text. */
    fun neutral(label: String, onClick: () -> Unit) = apply { buttons += Btn(label, NEUTRAL, true, onClick) }

    val isShowing get() = dialog.isShowing
    fun dismiss() { if (dialog.isShowing) try { dialog.dismiss() } catch (_: Exception) {} }
    fun setPrimaryEnabled(on: Boolean) { views.firstOrNull { it.tag == PRIMARY || it.tag == DANGER }?.isEnabled = on }

    fun show(): GlassDialog {
        if (a.isFinishing || a.isDestroyed) return this
        val w = dialog.window!!
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val root = FrameLayout(a).apply { clipChildren = false }
        val back = FrostedBackdrop(a).apply { scrim = 0.45f; setSource(backdrop) }
        root.addView(back, FrameLayout.LayoutParams(-1, -1))

        val card = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.regular(dp, 24f, 0.8f, shadow = true)
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
            isClickable = true
        }
        title?.let { t ->
            card.addView(TextView(a).apply {
                text = t; textSize = 20f; setTextColor(Pal.text)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setPadding(0, 0, 0, (8 * dp).toInt())
                isAccessibilityHeading = true
            })
        }
        val content = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        message?.let { m ->
            content.addView(TextView(a).apply {
                text = m; textSize = 16f; setTextColor(Pal.muted); setLineSpacing(0f, 1.1f)
                setPadding(0, 0, 0, (8 * dp).toInt())
            })
        }
        body?.let { content.addView(it) }
        val sv = object : ScrollView(a) {
            override fun onMeasure(wSpec: Int, hSpec: Int) = super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec((a.resources.displayMetrics.heightPixels * 0.55f).toInt(), MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; addView(content) }
        card.addView(sv, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Buttons: side by side for one or two, stacked for more.
        val stacked = buttons.size > 2
        val bar = LinearLayout(a).apply { orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        val ordered = if (stacked) buttons.sortedBy { if (it.kind == PRIMARY || it.kind == DANGER) 0 else if (it.kind == NEUTRAL) 1 else 2 } else buttons
        for ((i, b) in ordered.withIndex()) {
            val tv = TextView(a).apply {
                text = b.label; textSize = 16f; gravity = Gravity.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                minHeight = (52 * dp).toInt()
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                isClickable = true; isFocusable = true
                tag = b.kind
                when (b.kind) {
                    PRIMARY -> { background = Glass.primary(dp); setTextColor(Color.WHITE) }
                    DANGER -> { background = Glass.control(dp); setTextColor(Pal.bad) }
                    SECONDARY -> { background = Glass.control(dp); setTextColor(Pal.text) }
                    else -> { setTextColor(Pal.accentText) }
                }
                setOnClickListener { if (b.dismiss) dismiss(); b.onClick() }
            }
            views += tv
            val lp = if (stacked) LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() }
                     else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i < ordered.size - 1) marginEnd = (12 * dp).toInt() }
            bar.addView(tv, lp)
        }
        if (buttons.isNotEmpty()) card.addView(bar, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (16 * dp).toInt() })

        val maxW = minOf((400 * dp).toInt(), a.resources.displayMetrics.widthPixels - (48 * dp).toInt())
        root.addView(card, FrameLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
            val nw = minOf((400 * dp).toInt(), (r - l) - (48 * dp).toInt())
            val lp = card.layoutParams
            if ((r - l) > 0 && lp.width != nw) { lp.width = nw; card.layoutParams = lp }
        }
        root.setOnClickListener { dismiss() }
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        Glass.enter(card)
        return this
    }

    companion object {
        private const val PRIMARY = 1
        private const val DANGER = 2
        private const val SECONDARY = 3
        private const val NEUTRAL = 4
    }
}
