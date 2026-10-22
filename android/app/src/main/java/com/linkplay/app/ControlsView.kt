package com.linkplay.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Touch controls drawn over the game: floating thumbstick, A/B, L/R, Start,
 * Select, Back. Taps on the game picture pick menu items; drags up/down step
 * through lists, sideways flip pages (bag pockets, summary pages, PC boxes).
 * Native code (touchnav.c) turns those into key presses.
 */
@SuppressLint("ViewConstructor")
class ControlsView(context: Context, private val keys: KeyMixer) : View(context) {
    private val dp = resources.displayMetrics.density

    /** Where the game picture is on screen (set by the activity after layout). */
    var gameRect = RectF()
        set(v) { field = v; layoutControls(); invalidate() }

    /**
     * Taps and drags on the game picture drive menus (TouchNav). Off until every screen's
     * mapping is done: a stray tap must never press A on the map. The buttons and stick work as always.
     */
    var pictureGestures = false

    /** false = floating thumbstick, true = D-pad cross (8 directions). */
    var dpadMode = false
        set(v) { if (field != v) { field = v; releaseStick(); layoutControls(); invalidate() } }

    /** Display cutout safe insets in px; landscape uses left/right so panels clear the camera hole. */
    var safeInsets = Rect()
        set(v) { field = Rect(v); layoutControls(); invalidate() }

    /** Extra bottom inset in px (system gesture area); landscape keeps controls >= 16 dp above the edge or this, whichever is larger. */
    var bottomGestureInset = 0
        set(v) { if (field != v) { field = v; layoutControls(); invalidate() } }

    /** Light tick on each press; players can turn it off. */
    var hapticsEnabled = true

    /** Opaque fallback for "High contrast text": no translucency, borders at 40% white. */
    var highContrast = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Overall control opacity: 0.6, 0.8 or 1.0. */
    var controlsOpacity = 1.0f
        set(v) { val c = v.coerceIn(0.3f, 1f); if (field != c) { field = c; invalidate() } }

    /** Label under the glyph while fast-forward is on ("2×" / "4×"). */
    var fastForwardLabel = "2×"
        set(v) { if (field != v) { field = v; invalidate() } }

    /** Called when the player taps fast-forward while it is unavailable (show a toast). */
    var onUnavailableTap: (() -> Unit)? = null

    /** Called with true/false when fast-forward should start/stop. */
    var onFastForward: ((Boolean) -> Unit)? = null

    /** Toggled fast-forward state (drawn lit). Set by the activity or by double-tap. */
    var fastForwardOn = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** False while linked: the button is greyed and ignores touches. */
    var fastForwardEnabled = true
        set(v) {
            if (field != v) {
                field = v
                if (!v) { ffHeld = false; ffPointer = -1 }
                invalidate()
            }
        }

    private val ffRect = RectF()
    private var ffHeld = false
    private var ffPointer = -1
    private var ffLastUp = 0L

    private class Button(val key: Int, val label: String, val color: Int, val round: Boolean) {
        val rect = RectF()
        var pressed = false
    }

    private val buttons = listOf(
        Button(Native.A, "A", Color.rgb(0xD6, 0x45, 0x5D), true),
        Button(Native.B, "B", Color.rgb(0x4A, 0x7B, 0xD6), true),
        Button(Native.L, "L", PILL, false),
        Button(Native.R, "R", PILL, false),
        Button(Native.SELECT, "SELECT", PILL, false),
        Button(Native.START, "START", PILL, false),
        Button(Native.B, "BACK", PILL, false),
    )

    private val stickZone = RectF()
    private var stickRestX = 0f
    private var stickRestY = 0f
    private var stickX = 0f
    private var stickY = 0f
    private var knobDx = 0f
    private var knobDy = 0f
    private var stickPointer = -1
    private var stickDir = 0
    private val stickRadius get() = 48 * dp

    private class Gesture(val x: Float, val y: Float, val t: Long) {
        var lastX = x
        var lastY = y
        var axis = 0 // 0 = not dragging yet (a tap), else AXIS_X / AXIS_Y
    }
    private val buttonPointers = SparseArray<Button?>()
    private val gestures = SparseArray<Gesture>()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * dp }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6 * dp }
    private val glowRect = RectF()

    /** Sets a paint colour with the personal opacity applied to its alpha. */
    private fun Paint.set(argb: Int): Paint {
        color = argb
        alpha = (Color.alpha(argb) * controlsOpacity).toInt()
        return this
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    /** Glass/Control surface: tinted fill, 1 dp hairline, 1 dp top highlight. */
    private fun glass(canvas: Canvas, r: RectF, rad: Float, base: Int, fillFrac: Float, ghost: Float = 1f) {
        val fa = if (highContrast) 255f else 255f * fillFrac
        canvas.drawRoundRect(r, rad, rad, fill.set(withAlpha(base, fa * ghost)))
        glowRect.set(r); glowRect.inset(0.5f * dp, 0.5f * dp)
        stroke.strokeWidth = 1 * dp
        val hair = (255 * (if (highContrast) 0.40f else 0.18f) * ghost).toInt()
        canvas.drawRoundRect(glowRect, rad - 0.5f * dp, rad - 0.5f * dp, stroke.set(Color.argb(hair, 255, 255, 255)))
        if (!highContrast) {
            val inset = rad * 0.6f
            stroke.set(Color.argb((255 * 0.22f * ghost).toInt(), 255, 255, 255))
            canvas.drawLine(r.left + inset, r.top + 1.5f * dp, r.right - inset, r.top + 1.5f * dp, stroke)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = layoutControls()

    private fun layoutControls() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val (a, b, l, r, select, start, back) = buttons
        val bottomInset = maxOf(16 * dp, bottomGestureInset.toFloat())
        val pw = 88 * dp
        val ph = 48 * dp
        val gap = 12 * dp
        if (w > h) {
            // Landscape: stick | game | buttons, inside the side panels (the activity
            // keeps them SIDE_PANEL_DP wide). Status text and the ⋯ menu sit on top.
            val left = maxOf(gameRect.left, SIDE_PANEL_DP * dp)
            val right = maxOf(w - gameRect.right, SIDE_PANEL_DP * dp)
            val inL = safeInsets.left.toFloat().coerceAtMost(left)
            val inR = safeInsets.right.toFloat().coerceAtMost(right)
            // Panel content lives between the cutout inset and the game picture.
            val lx = (inL + left) / 2
            val rx = w - (right + inR) / 2
            val rowTop = 72 * dp
            val pad = 6 * dp
            val lw = minOf(76 * dp, left - inL - 2 * pad).coerceAtLeast(48 * dp)
            l.rect.set(lx - lw / 2, rowTop, lx + lw / 2, rowTop + ph)
            val cRight = w - inR - pad
            val cLeft = w - right + pad
            val ffS = 48 * dp
            val rw = minOf(56 * dp, cRight - cLeft - ffS - gap).coerceAtLeast(44 * dp)
            r.rect.set(cRight - rw, rowTop, cRight, rowTop + ph)
            ffRect.set(r.rect.left - gap - ffS, rowTop, r.rect.left - gap, rowTop + ffS)
            // Bottom pills: Start (right), Select with Back above it (left).
            val pillY = h - bottomInset - ph
            val pillX = { cx: Float, lo: Float, hi: Float -> (cx - pw / 2).coerceIn(lo, maxOf(lo, hi - pw)) }
            val lpx = pillX(lx, inL + pad, left - pad)
            val rpx = pillX(rx, w - right + pad, w - inR - pad)
            pill(select, lpx, pillY)
            pill(back, lpx, pillY - ph - gap)
            pill(start, rpx, pillY)
            stickZone.set(inL, rowTop + ph + gap, left, back.rect.top - gap)
            stickRestX = lx
            stickRestY = (stickZone.top + stickZone.bottom) / 2
            val round = 34 * dp
            val ay = maxOf(h * 0.42f, rowTop + ph + gap + round + 2 * dp)
            val by = ay + 72 * dp
            val acx = (rx + 22 * dp).coerceIn(cLeft + round, cRight - round)
            val bcx = (rx - 24 * dp).coerceIn(cLeft + round, cRight - round)
            a.rect.set(acx - round, ay - round, acx + round, ay + round)
            b.rect.set(bcx - round, by - round, bcx + round, by + round)
        } else {
            val round = 36 * dp
            // Portrait: game on top, controls below.
            val top = gameRect.bottom + gap
            val inset = maxOf(16 * dp, safeInsets.left.toFloat(), safeInsets.right.toFloat())
            l.rect.set(inset, top, inset + 76 * dp, top + ph)
            r.rect.set(w - inset - 76 * dp, top, w - inset, top + ph)
            ffRect.set(r.rect.left - gap - 48 * dp, top, r.rect.left - gap, top + 48 * dp)
            val pillsY = top + ph + gap
            pill(select, w / 2 - pw / 2 - pw - gap, pillsY)
            pill(start, w / 2 - pw / 2, pillsY)
            pill(back, w / 2 + pw / 2 + gap, pillsY)
            val areaTop = pillsY + ph + gap
            val areaMid = ((areaTop + h) / 2).coerceAtMost(h - bottomInset - round - 32 * dp)
            stickZone.set(0f, areaTop, w / 2, h)
            stickRestX = w * 0.26f
            stickRestY = (areaTop + h) / 2
            a.rect.set(w - 24 * dp - 2 * round, areaMid - 64 * dp - round, w - 24 * dp, areaMid - 64 * dp + round)
            b.rect.set(w - 104 * dp - 2 * round, areaMid - round, w - 104 * dp, areaMid + round)
        }
        if (stickPointer < 0) {
            stickX = stickRestX; stickY = stickRestY
        }
    }

    private fun pill(button: Button, x: Float, y: Float) = button.rect.set(x, y, x + 88 * dp, y + 48 * dp)

    companion object {
        /** Width of each landscape side panel (stick left, buttons right). */
        const val SIDE_PANEL_DP = 128f
        private val PILL = Color.rgb(0x14, 0x12, 0x1C) // glass-tint
        private val INK = Color.rgb(0xF4, 0xF2, 0xFA)
        private val ACCENT_C = Color.rgb(0x6A, 0x4B, 0xF0)
        private const val AXIS_X = 1
        private const val AXIS_Y = 2
    }

    private operator fun <T> List<T>.component6() = this[5]
    private operator fun <T> List<T>.component7() = this[6]

    private fun buttonAt(x: Float, y: Float): Button? {
        val slop = 10 * dp
        return buttons.firstOrNull { RectF(it.rect).apply { inset(-slop, -slop) }.contains(x, y) }
    }

    private fun haptic() { if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
    private fun tick() { if (hapticsEnabled) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val before = visualSig()
        handleTouch(e)
        if (visualSig() != before) invalidate()
        return true
    }

    private fun visualSig(): Int {
        var h = 17
        h = h * 31 + stickDir
        h = h * 31 + (if (stickPointer >= 0) 1 else 0)
        h = h * 31 + java.lang.Float.floatToIntBits(knobDx)
        h = h * 31 + java.lang.Float.floatToIntBits(knobDy)
        h = h * 31 + (if (ffHeld) 1 else 0)
        for (b in buttons) h = h * 31 + (if (b.pressed) 1 else 0)
        return h
    }

    private fun ffHit(x: Float, y: Float) = RectF(ffRect).apply { inset(-10 * dp, -10 * dp) }.contains(x, y)

    private fun ffDown(id: Int) {
        ffPointer = id
        haptic()
        val now = System.currentTimeMillis()
        if (fastForwardOn) {
            // Any tap while toggled on switches it off.
            fastForwardOn = false
            ffHeld = false
            ffLastUp = 0L
            onFastForward?.invoke(false)
            ffConsumed = true
        } else if (now - ffLastUp < 350) {
            // Double-tap: toggle on and keep going after release.
            fastForwardOn = true
            ffHeld = true
            ffLastUp = 0L
            onFastForward?.invoke(true)
            ffConsumed = true
        } else {
            ffHeld = true
            ffConsumed = false
            onFastForward?.invoke(true)
        }
    }

    private var ffConsumed = false

    private fun ffUp() {
        ffPointer = -1
        val wasHeld = ffHeld
        ffHeld = false
        if (!ffConsumed) ffLastUp = System.currentTimeMillis()
        if (wasHeld && !fastForwardOn) onFastForward?.invoke(false)
        invalidate()
    }

    private fun handleTouch(e: MotionEvent) {
        val i = e.actionIndex
        val id = e.getPointerId(i)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val x = e.getX(i)
                val y = e.getY(i)
                val btn = buttonAt(x, y)
                when {
                    !fastForwardEnabled && ffHit(x, y) -> onUnavailableTap?.invoke()
                    fastForwardEnabled && ffPointer < 0 && ffHit(x, y) -> ffDown(id)
                    btn != null -> { buttonPointers.put(id, btn); haptic(); updateButtons() }
                    stickPointer < 0 && stickZone.contains(x, y) && dpadMode -> {
                        stickPointer = id
                        movePad(x, y)
                    }
                    stickPointer < 0 && stickZone.contains(x, y) -> {
                        stickPointer = id
                        val rr = 60 * dp
                        val lox = stickZone.left + rr; val loy = stickZone.top + rr
                        stickX = x.coerceIn(lox, maxOf(lox, stickZone.right - rr))
                        stickY = y.coerceIn(loy, maxOf(loy, stickZone.bottom - rr))
                        knobDx = 0f; knobDy = 0f
                    }
                    pictureGestures && gameRect.contains(x, y) -> gestures.put(id, Gesture(x, y, System.currentTimeMillis()))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                for (p in 0 until e.pointerCount) {
                    val pid = e.getPointerId(p)
                    val x = e.getX(p)
                    val y = e.getY(p)
                    if (pid == stickPointer) { if (dpadMode) movePad(x, y) else moveStick(x, y) }
                    if (buttonPointers.indexOfKey(pid) >= 0) {
                        val now = buttonAt(x, y)
                        if (now != null && now !== buttonPointers[pid]) haptic()
                        buttonPointers.put(pid, now)
                        updateButtons()
                    }
                    gestures[pid]?.let { g -> dragGame(g, x, y) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (id == stickPointer) releaseStick()
                if (id == ffPointer) ffUp()
                if (buttonPointers.indexOfKey(id) >= 0) { buttonPointers.remove(id); updateButtons() }
                gestures[id]?.let { g ->
                    gestures.remove(id)
                    val x = e.getX(i)
                    val y = e.getY(i)
                    val quick = System.currentTimeMillis() - g.t < 600
                    if (e.actionMasked != MotionEvent.ACTION_CANCEL && g.axis == 0 && quick && hypot(x - g.x, y - g.y) < 14 * dp) {
                        val gx = ((x - gameRect.left) / gameRect.width() * Native.WIDTH).toInt()
                        val gy = ((y - gameRect.top) / gameRect.height() * Native.HEIGHT).toInt()
                        haptic()
                        Native.touchTap(gx.coerceIn(0, Native.WIDTH - 1), gy.coerceIn(0, Native.HEIGHT - 1))
                    }
                }
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    buttonPointers.clear(); gestures.clear(); releaseStick(); updateButtons(); if (ffPointer >= 0) ffUp()
                }
            }
        }
    }

    private fun dragGame(g: Gesture, x: Float, y: Float) {
        if (g.axis == 0) {
            // The first clear movement picks the axis for the whole drag.
            val ax = abs(x - g.x)
            val ay = abs(y - g.y)
            if (maxOf(ax, ay) > 14 * dp) g.axis = if (ax > ay) AXIS_X else AXIS_Y
        }
        if (g.axis == AXIS_Y) {
            val step = 24 * dp
            val dy = y - g.lastY
            if (abs(dy) >= step) {
                val steps = (abs(dy) / step).toInt()
                Native.touchSwipe(if (dy > 0) Native.SWIPE_DOWN else Native.SWIPE_UP, steps)
                g.lastY += (if (dy > 0) 1 else -1) * steps * step
                tick()
            }
        } else if (g.axis == AXIS_X) {
            val step = 40 * dp // pages are bigger jumps than list rows
            val dx = x - g.lastX
            if (abs(dx) >= step) {
                val steps = (abs(dx) / step).toInt()
                Native.touchSwipe(if (dx > 0) Native.SWIPE_RIGHT else Native.SWIPE_LEFT, steps)
                g.lastX += (if (dx > 0) 1 else -1) * steps * step
                tick()
            }
        }
    }

    private fun moveStick(x: Float, y: Float) {
        var dx = x - stickX
        var dy = y - stickY
        val dist = hypot(dx, dy)
        if (dist > stickRadius) { dx *= stickRadius / dist; dy *= stickRadius / dist }
        knobDx = dx; knobDy = dy
        if (dist < 14 * dp) return setStickDir(0)
        // 4-way with hysteresis: keep the direction until the thumb is clearly elsewhere.
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble()))
        val centers = mapOf(Native.RIGHT to 0.0, Native.DOWN to 90.0, Native.LEFT to 180.0, Native.UP to -90.0)
        fun off(a: Double, c: Double) = abs(((a - c + 540) % 360) - 180)
        if (stickDir != 0 && off(angle, centers.getValue(stickDir)) < 55) return
        setStickDir(centers.minByOrNull { off(angle, it.value) }!!.key)
    }

    private fun movePad(x: Float, y: Float) {
        val dx = x - stickRestX
        val dy = y - stickRestY
        var dir = 0
        if (hypot(dx, dy) >= 12 * dp) {
            val ax = abs(dx)
            val ay = abs(dy)
            val big = maxOf(ax, ay)
            // Diagonal when the minor axis is a clear fraction of the major one.
            val horiz = ax >= 0.45f * big
            val vert = ay >= 0.45f * big
            if (horiz) dir = dir or (if (dx > 0) Native.RIGHT else Native.LEFT)
            if (vert) dir = dir or (if (dy > 0) Native.DOWN else Native.UP)
        }
        setStickDir(dir)
    }

    private fun setStickDir(dir: Int) {
        if (dir == stickDir) return
        stickDir = dir
        if (dir != 0) haptic()
        updateButtons()
    }

    private fun releaseStick() {
        stickPointer = -1
        stickX = stickRestX; stickY = stickRestY
        knobDx = 0f; knobDy = 0f
        setStickDir(0)
    }

    private fun updateButtons() {
        var k = stickDir
        for (b in buttons) b.pressed = false
        for (n in 0 until buttonPointers.size()) buttonPointers.valueAt(n)?.let { it.pressed = true; k = k or it.key }
        keys.touch = k
    }

    override fun onDraw(canvas: Canvas) {
        if (dpadMode) drawPad(canvas) else drawStick(canvas)
        drawFastForward(canvas)
        drawButtons(canvas)
    }

    private fun drawPad(canvas: Canvas) {
        val cx = stickRestX
        val cy = stickRestY
        val arm = minOf(62 * dp, (stickZone.width() - 12 * dp) / 2)
        val half = 20 * dp
        fill.set(Color.argb(if (highContrast) 255 else 56, 0x14, 0x12, 0x1C))
        canvas.drawCircle(cx, cy, arm + 6 * dp, fill)
        glowRect.set(cx - arm, cy - half, cx + arm, cy + half)
        glass(canvas, glowRect, 10 * dp, PILL, 0.60f)
        val v = RectF(cx - half, cy - arm, cx + half, cy + arm)
        glass(canvas, v, 10 * dp, PILL, 0.60f)
        fill.set(Color.argb(if (highContrast) 255 else 56, 0x14, 0x12, 0x1C))
        canvas.drawRoundRect(cx - half + 1 * dp, cy - half + 1 * dp, cx + half - 1 * dp, cy + half - 1 * dp, 8 * dp, 8 * dp, fill)
        stroke.strokeWidth = 1 * dp
        stroke.set(if (stickPointer >= 0) Color.argb(178, 0x6A, 0x4B, 0xF0) else Color.argb(if (highContrast) 102 else 46, 255, 255, 255))
        canvas.drawCircle(cx, cy, arm + 6 * dp, stroke)
        fill.set(Color.argb(235, 0x6A, 0x4B, 0xF0))
        val seg = arm - half
        if (stickDir and Native.UP != 0) canvas.drawRoundRect(cx - half, cy - arm, cx + half, cy - half + 4 * dp, 10 * dp, 10 * dp, fill)
        if (stickDir and Native.DOWN != 0) canvas.drawRoundRect(cx - half, cy + half - 4 * dp, cx + half, cy + arm, 10 * dp, 10 * dp, fill)
        if (stickDir and Native.LEFT != 0) canvas.drawRoundRect(cx - arm, cy - half, cx - half + 4 * dp, cy + half, 10 * dp, 10 * dp, fill)
        if (stickDir and Native.RIGHT != 0) canvas.drawRoundRect(cx + half - 4 * dp, cy - half, cx + arm, cy + half, 10 * dp, 10 * dp, fill)
        // Arrow heads
        fill.set(Color.argb(235, 0xF4, 0xF2, 0xFA))
        val t = 7 * dp
        val m = half + seg / 2
        arrow(canvas, cx, cy - m, 0, t)
        arrow(canvas, cx, cy + m, 1, t)
        arrow(canvas, cx - m, cy, 2, t)
        arrow(canvas, cx + m, cy, 3, t)
    }

    private val arrowPath = Path()
    private fun arrow(canvas: Canvas, x: Float, y: Float, d: Int, t: Float) {
        arrowPath.rewind()
        when (d) {
            0 -> { arrowPath.moveTo(x, y - t); arrowPath.lineTo(x - t, y + t); arrowPath.lineTo(x + t, y + t) }
            1 -> { arrowPath.moveTo(x, y + t); arrowPath.lineTo(x - t, y - t); arrowPath.lineTo(x + t, y - t) }
            2 -> { arrowPath.moveTo(x - t, y); arrowPath.lineTo(x + t, y - t); arrowPath.lineTo(x + t, y + t) }
            else -> { arrowPath.moveTo(x + t, y); arrowPath.lineTo(x - t, y - t); arrowPath.lineTo(x - t, y + t) }
        }
        arrowPath.close()
        canvas.drawPath(arrowPath, fill)
    }

    private fun drawFastForward(canvas: Canvas) {
        val on = fastForwardEnabled && (fastForwardOn || ffHeld)
        val r = 16 * dp
        val ghost = if (fastForwardEnabled) 1f else 0.4f
        if (on) {
            glowRect.set(ffRect); glowRect.inset(-3 * dp, -3 * dp)
            canvas.drawRoundRect(glowRect, r + 3 * dp, r + 3 * dp, glow.set(Color.argb((255 * 0.35f).toInt(), 0x6A, 0x4B, 0xF0)))
            canvas.drawRoundRect(ffRect, r, r, fill.set(Color.argb(235, 0x6A, 0x4B, 0xF0)))
        } else {
            glass(canvas, ffRect, r, PILL, 0.60f, ghost)
        }
        fill.set(Color.argb((255 * ghost).toInt(), 0xF4, 0xF2, 0xFA))
        val cx = ffRect.centerX()
        val cy = ffRect.centerY() - (if (on) 5 * dp else 0f)
        val t = if (on) 5.5f * dp else 7 * dp
        arrowPath.rewind()
        for (off in floatArrayOf(-t, t)) {
            val x = cx + off
            arrowPath.moveTo(x - t, cy - t); arrowPath.lineTo(x + t, cy); arrowPath.lineTo(x - t, cy + t); arrowPath.close()
        }
        canvas.drawPath(arrowPath, fill)
        if (on) {
            text.set(Color.WHITE)
            text.textSize = 13 * dp
            canvas.drawText(fastForwardLabel, cx, ffRect.bottom - 7 * dp, text)
        }
    }

    private fun withAlpha(c: Int, a: Float) = Color.argb(a.toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun drawStick(canvas: Canvas) {
        val active = stickPointer >= 0
        canvas.drawCircle(stickX, stickY, 60 * dp, fill.set(Color.argb(if (highContrast) 255 else 56, 0x14, 0x12, 0x1C)))
        stroke.strokeWidth = 1 * dp
        stroke.set(if (active) Color.argb(178, 0x6A, 0x4B, 0xF0) else Color.argb(if (highContrast) 102 else 46, 255, 255, 255))
        canvas.drawCircle(stickX, stickY, 60 * dp, stroke)
        val kx = stickX + knobDx
        val ky = stickY + knobDy
        if (active) {
            canvas.drawCircle(kx, ky, 31 * dp, glow.set(Color.argb((255 * 0.35f).toInt(), 0x6A, 0x4B, 0xF0)))
            canvas.drawCircle(kx, ky, 28 * dp, fill.set(Color.argb(235, 0x6A, 0x4B, 0xF0)))
        } else {
            glowRect.set(kx - 28 * dp, ky - 28 * dp, kx + 28 * dp, ky + 28 * dp)
            glass(canvas, glowRect, 28 * dp, PILL, 0.60f)
        }
    }

    private fun drawButtons(canvas: Canvas) {
        for (b in buttons) {
            val rad = if (b.round) b.rect.width() / 2 else 18 * dp
            if (b.pressed) {
                glowRect.set(b.rect); glowRect.inset(-3 * dp, -3 * dp)
                val gr = rad + 3 * dp
                canvas.drawRoundRect(glowRect, gr, gr, glow.set(Color.argb((255 * 0.35f).toInt(), 0x6A, 0x4B, 0xF0)))
                canvas.drawRoundRect(b.rect, rad, rad, fill.set(Color.argb(if (highContrast) 255 else 235, 0x6A, 0x4B, 0xF0)))
            } else {
                glass(canvas, b.rect, rad, b.color, if (b.round) 0.85f else 0.60f)
            }
            text.textSize = if (b.round) 24 * dp else (if (b.label.length > 2) 13 else 18) * dp
            text.set(if (b.round || b.pressed) Color.WHITE else INK)
            canvas.drawText(b.label, b.rect.centerX(), b.rect.centerY() - (text.descent() + text.ascent()) / 2, text)
        }
    }
}
