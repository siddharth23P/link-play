package com.linkplay.app

import android.app.Activity
import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The app's palette (design rules: accent for non-text, accentFill for filled buttons, accentText for text on dark). */
object Pal {
    val bg = Color.rgb(14, 12, 22) // #0E0C16
    val panel = Color.rgb(20, 18, 28) // glass-tint #14121C
    val text = Color.rgb(244, 242, 250) // ink #F4F2FA
    val muted = Color.rgb(208, 203, 226) // ink-2 #D0CBE2
    val accent = Color.rgb(124, 92, 255) // #7C5CFF: shapes, glows
    val accentFill = Color.rgb(106, 75, 240) // #6A4BF0: white on it is 5.4:1
    val accentText = Color.rgb(183, 168, 255) // accent-ink #B7A8FF
    val tonal = Color.argb(60, 255, 255, 255) // pressed / chip fill on glass
    val ok = Color.rgb(74, 214, 166) // signal-ok #4AD6A6
    val bad = Color.rgb(255, 154, 168) // signal-danger #FF9AA8
}

/** One tappable line of a menu. */
class MenuRow(
    val title: String,
    val detail: String? = null, // one muted line under the title
    val value: String? = null, // muted, right-aligned ("On")
    val enabled: Boolean = true,
    val reason: String? = null, // shown instead of [detail] while disabled
    val destructive: Boolean = false,
    val checked: Boolean = false,
    val chevron: Boolean = false,
    val thumb: Bitmap? = null, // 96x64 dp preview; [hasThumb] without a bitmap draws an empty frame
    val hasThumb: Boolean = false,
    val onClick: () -> Unit = {},
)

class MenuSection(val label: String?, val rows: List<MenuRow> = emptyList(), val view: View? = null)

/**
 * A sectioned menu panel: a right-side panel (360 dp) in landscape, a bottom panel in portrait.
 * Pages replace each other in the same dialog ([show] again), so going into a sub-menu doesn't flicker.
 */
class MenuPanel(private val a: Activity, private val dim: Float = 0.5f, private val scene: (() -> Bitmap?)? = null) {
    private val frosted = dim >= 0.5f
    private var backdrop: FrostedBackdrop? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastFrame = -2L
    private val refresh = object : Runnable {
        override fun run() {
            if (!dialog.isShowing) return
            val n = try { Native.frameCount() } catch (_: Throwable) { -1L }
            if (n != lastFrame || n < 0) { lastFrame = n; scene?.invoke()?.let { backdrop?.setSource(it) } }
            handler.postDelayed(this, 250)
        }
    }
    private val dp = a.resources.displayMetrics.density
    private val landscape = a.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val dialog = Glass.fullScreenDialog(a)
    private val title = TextView(a)
    private val back = TextView(a)
    private val list = LinearLayout(a)
    private var inited = false
    private var lastHeading: String? = null
    private var scroll: ScrollView? = null
    private var panelView: View? = null

    fun dismiss() { handler.removeCallbacks(refresh); if (dialog.isShowing) try { dialog.dismiss() } catch (_: Exception) {} }

    /** Shows (or switches to) a page. [onBack] non-null: a back arrow returns to the parent page. */
    fun show(heading: String, sections: List<MenuSection>, onBack: (() -> Unit)? = null) {
        if (!inited) init()
        title.text = heading
        back.visibility = if (onBack != null) View.VISIBLE else View.GONE
        back.setOnClickListener { onBack?.invoke() }
        back.contentDescription = "Back"
        list.removeAllViews()
        for ((i, s) in sections.withIndex()) {
            if (s.label != null) list.addView(TextView(a).apply {
                text = s.label
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(Pal.muted)
                setPadding((16 * dp).toInt(), (if (i == 0) 8 else 20).let { (it * dp).toInt() }, (16 * dp).toInt(), (4 * dp).toInt())
                isAllCaps = false
            })
            s.view?.let { list.addView(it) }
            for (r in s.rows) list.addView(rowView(r))
        }
        if (heading != lastHeading) scroll?.scrollTo(0, 0)
        lastHeading = heading
        if (!dialog.isShowing) {
            dialog.show()
            panelView?.let { Glass.enter(it) }
            if (frosted && scene != null) { handler.postDelayed(refresh, 250) }
        }
    }

    private fun init() {
        inited = true
        dialog.setCanceledOnTouchOutside(true)
        val w = dialog.window!!
        if (!frosted) {
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(dim)
        }

        val panel = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.regular(dp, 24f, 0.8f, shadow = true)
            isClickable = true // swallow taps so they don't reach the scrim
        }
        val header = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), 0)
        }
        back.apply {
            text = "‹"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Pal.text)
        }
        header.addView(back, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))
        title.apply {
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Pal.text)
            isAccessibilityHeading = true
            setPadding((12 * dp).toInt(), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f).apply { gravity = Gravity.CENTER_VERTICAL })
        title.gravity = Gravity.CENTER_VERTICAL
        val close = TextView(a).apply {
            text = "Close"
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(Pal.accentText)
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            setOnClickListener { dismiss() }
        }
        header.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (48 * dp).toInt()))
        panel.addView(header)

        list.orientation = LinearLayout.VERTICAL
        val maxH = (a.resources.displayMetrics.heightPixels * 0.78f).toInt()
        val sv = object : ScrollView(a) {
            override fun onMeasure(wSpec: Int, hSpec: Int) {
                if (landscape) super.onMeasure(wSpec, hSpec)
                else super.onMeasure(wSpec, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST))
            }
        }.apply { isVerticalScrollBarEnabled = false; setPadding(0, 0, 0, (16 * dp).toInt()); clipToPadding = false }
        sv.addView(list)
        scroll = sv
        panel.addView(sv, if (landscape) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                          else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val root = FrameLayout(a).apply { clipChildren = false }
        if (frosted) {
            backdrop = FrostedBackdrop(a).apply { scrim = 0.45f; scene?.invoke()?.let { setSource(it) } }
            root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        }
        root.setOnClickListener { dismiss() }
        val m = (12 * dp).toInt()
        val lp = if (landscape) {
            FrameLayout.LayoutParams(minOf((360 * dp).toInt(), a.resources.displayMetrics.widthPixels - 2 * m), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END)
        } else {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        lp.setMargins(m, m, m, m)
        root.addView(panel, lp)
        panelView = panel
        // Keep the panel clear of the camera cutout: margins from the activity's cutout, set once. (Following every
        // insets pass of the dialog window made the panel jump while the bars and cutout insets settled.)
        val cut = Glass.cutoutInsets(a)
        lp.setMargins(m + (if (landscape) 0 else cut.left), m + cut.top, m + cut.right, m + cut.bottom)
        panel.layoutParams = lp
        dialog.setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 30) w.insetsController?.hide(WindowInsets.Type.systemBars())
    }

    private fun rowView(r: MenuRow): View {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (52 * dp).toInt()
            setPadding((16 * dp).toInt(), (if (r.hasThumb) 8 else 6).let { (it * dp).toInt() }, (16 * dp).toInt(), (if (r.hasThumb) 8 else 6).let { (it * dp).toInt() })
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), Glass.control(dp, 14f))
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            isEnabled = r.enabled
            if (r.enabled) setOnClickListener { r.onClick() }
            else isClickable = false
        }
        if (r.hasThumb) {
            val iv = ImageView(a).apply {
                scaleType = ImageView.ScaleType.FIT_XY
                setBackgroundColor(Glass.alpha(Color.WHITE, 0.10f))
                r.thumb?.let { setImageBitmap(it) }
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, 6 * dp)
                }
                clipToOutline = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            row.addView(iv, LinearLayout.LayoutParams((96 * dp).toInt(), (64 * dp).toInt()).apply { marginEnd = (14 * dp).toInt() })
        }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(a).apply {
            text = r.title
            textSize = 16f
            setTextColor(if (r.destructive) Pal.bad else Pal.text)
            alpha = if (r.enabled) 1f else 0.4f
        })
        if (!r.enabled) row.stateDescription = "Unavailable"
        val sub = if (!r.enabled && r.reason != null) r.reason else r.detail
        if (sub != null) col.addView(TextView(a).apply {
            text = sub
            textSize = 13f
            setTextColor(Pal.muted)
        })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (r.value != null) row.addView(TextView(a).apply {
            text = r.value
            textSize = 14f
            setTextColor(Pal.muted)
            setPadding((8 * dp).toInt(), 0, 0, 0)
        })
        if (r.checked) row.addView(TextView(a).apply {
            text = "✓"
            textSize = 20f
            setTextColor(Pal.accentText)
            setPadding((8 * dp).toInt(), 0, 0, 0)
            contentDescription = "Selected"
        })
        if (r.chevron) row.addView(TextView(a).apply {
            text = "›"
            textSize = 22f
            setTextColor(Pal.muted)
            setPadding((8 * dp).toInt(), 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        return row
    }
}

/**
 * A confirmation on glass: Cancel is the safe glass control; the action is the accent fill, or a danger-coloured
 * label when [destructive].
 */
fun Activity.confirmDialog(title: String, message: String?, action: String, destructive: Boolean = false,
                           neutral: Pair<String, () -> Unit>? = null, backdrop: Bitmap? = null, onConfirm: () -> Unit) {
    val d = GlassDialog(this, backdrop).title(title).message(message)
    neutral?.let { (label, fn) -> d.neutral(label, fn) }
    d.secondary("Cancel").primary(action, destructive = destructive) { onConfirm() }.show()
}
