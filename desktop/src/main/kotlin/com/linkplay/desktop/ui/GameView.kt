package com.linkplay.desktop.ui

import com.linkplay.desktop.Settings
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JComponent
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What the game screen needs from the app. */
interface GameHost {
    fun openMenu()
    fun statusText(): String
    fun fastForwardOn(): Boolean
    /** True while the chips should stay on past the usual three quiet seconds (a notice up, or waiting for a friend). */
    fun chipsHeld(): Boolean = false
}

/**
 * The picture. A new frame arrives from the emulator's frame pump (any thread): it is copied into
 * one of three images and a repaint is asked for, at most one at a time. Painting happens only
 * then (or when the window changes), never on a clock. The surround is the picture itself, blurred.
 *
 * Filters, all done here in device pixels so a 150% Windows screen or a Retina Mac stays crisp:
 *  - Pixel: nearest neighbour, at the biggest whole-number size that fits (smaller than that only if the window is smaller than the game);
 *  - Sharp: nearest up to the biggest whole-number size, then smooth to the exact size (crisp edges, no uneven pixels);
 *  - Smooth: plain bilinear.
 */
class GameView(private val host: GameHost) : Comp() {
    private val images = Array(3) { BufferedImage(W, H, BufferedImage.TYPE_INT_RGB) }
    private var next = 0
    @Volatile private var latest: BufferedImage? = null
    @Volatile private var frameId = 0L
    private val pending = AtomicBoolean(false)

    private var pre: BufferedImage? = null // the nearest-neighbour prescale of the Sharp filter
    private var preFor = -1L
    private var preK = 0

    private var ambient: BufferedImage? = null
    private var ambientFor = -100L
    private val backdrop = BackdropCache()

    /** Window chrome kept clear: the title bar, and top and bottom bars for the chips (none in full screen). */
    var topInset = 0
    var fullScreen = false

    /** For snapshots: keep the chips fully visible. */
    var chipsAlwaysOn = false

    private var chipAlpha = 1f
    private var lastPoke = System.currentTimeMillis()
    private val fade = Timer(33) { stepFade() }
    private var menuHover = false

    init {
        isOpaque = true
        isFocusable = true
        focusTraversalKeysEnabled = false // Tab is fast forward here
        cursor = Cursor.getDefaultCursor()
        getAccessibleContext().accessibleName = "Game"
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                requestFocusInWindow()
                if (menuRect().contains(e.point)) host.openMenu()
                poke()
            }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                val over = menuRect().contains(e.point)
                if (over != menuHover) { menuHover = over; cursor = if (over) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor(); repaint() }
                poke()
            }
        })
    }

    override fun getPreferredSize() = Dimension(W * 3, H * 3 + 104)

    /** A new picture (240x160 ints, 0xFFRRGGBB). Called on the frame pump's thread; copies and returns. */
    fun offerFrame(rgb: IntArray) {
        val img = images[next]
        next = (next + 1) % images.size
        System.arraycopy(rgb, 0, (img.raster.dataBuffer as DataBufferInt).data, 0, W * H)
        latest = img
        frameId++
        if (isShowing && pending.compareAndSet(false, true)) {
            SwingUtilities.invokeLater {
                pending.set(false)
                if (isShowing) repaint()
            }
        }
    }

    fun clearFrame() {
        latest = null
        ambient = null
        ambientFor = -100L
        repaint()
    }

    val hasFrame get() = latest != null

    /** The colour of the picture's centre pixel (a check that the game is really drawing), or null before the first picture. */
    fun centerPixel(): Int? = latest?.let { (it.raster.dataBuffer as DataBufferInt).data[(H / 2) * W + W / 2] }

    private val hideTimer: Timer = Timer(3000) { if (host.chipsHeld()) hideTimer.restart() else fade.start() }.apply { isRepeats = false }

    /** Chips come back when the mouse moves or the text changes, and go after three quiet seconds. */
    fun poke() {
        lastPoke = System.currentTimeMillis()
        hideTimer.restart()
        if (chipAlpha < 1f && !fade.isRunning) fade.start()
    }

    private fun stepFade() {
        val show = chipsAlwaysOn || menuHover || host.chipsHeld() || System.currentTimeMillis() - lastPoke < 3000
        val want = if (show) 1f else 0f
        chipAlpha += (want - chipAlpha) * 0.35f
        if (kotlin.math.abs(want - chipAlpha) < 0.02f) chipAlpha = want
        if (chipAlpha == want) fade.stop()
        repaint()
    }

    private fun barTop() = if (fullScreen) 0 else 52
    private fun menuRect() = Rectangle2D.Float((width - 16 - 40).toFloat(), (topInset + 8).toFloat(), 40f, 40f)

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.quality()
        val w = width
        val h = height
        val img = latest
        if (img != null) {
            if (ambient == null || frameId - ambientFor >= 4) {
                ambient = Backdrop.ambientOf((img.raster.dataBuffer as DataBufferInt).data)
                ambientFor = frameId
            }
            backdrop.paint(g, w, h, ambient, 0.5f)
        } else {
            backdrop.paint(g, w, h, null, 0f)
        }
        if (img != null) drawPicture(g, img, w, h)
        drawChips(g, w, h)
        g.dispose()
    }

    private fun drawPicture(g0: Graphics2D, img: BufferedImage, w: Int, h: Int) {
        val t = g0.transform
        // The device scale as it is: 1.0 on a plain screen, 2.0 on Retina, 1.5 at Windows 150%, and the small
        // fraction when Overlay.capture() paints this view into its shrunken image for the frosted dialogs.
        val s = t.scaleX
        if (s <= 0.0) return
        val g = g0.create() as Graphics2D
        g.transform = AffineTransform(1.0, 0.0, 0.0, 1.0, t.translateX, t.translateY) // device pixels from here on
        val top = topInset + barTop()
        val ax = 0.0
        val ay = top * s
        val aw = w * s
        val ah = (h - top - barTop()) * s
        val fit = min(aw / W, ah / H)
        if (fit <= 0.0) { g.dispose(); return }
        val filter = Settings.filter
        val whole = floor(fit).toInt()
        val scale = if (filter == Settings.FILTER_PIXEL && whole >= 1) whole.toDouble() else fit
        val dw = (W * scale).roundToInt()
        val dh = (H * scale).roundToInt()
        val dx = (ax + (aw - dw) / 2).roundToInt()
        val dy = (ay + (ah - dh) / 2).roundToInt()
        when (filter) {
            Settings.FILTER_PIXEL -> {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                g.drawImage(img, dx, dy, dw, dh, null)
            }
            Settings.FILTER_SHARP -> {
                val k = min(6, max(1, whole))
                var p = pre
                if (p == null || preK != k || preFor != frameId) {
                    if (p == null || preK != k) p = BufferedImage(W * k, H * k, BufferedImage.TYPE_INT_RGB)
                    val pg = p.createGraphics()
                    pg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                    pg.drawImage(img, 0, 0, W * k, H * k, null)
                    pg.dispose()
                    pre = p
                    preK = k
                    preFor = frameId
                }
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(p, dx, dy, dw, dh, null)
            }
            else -> {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(img, dx, dy, dw, dh, null)
            }
        }
        // A hairline so the picture never melts into its own blur.
        g.color = Pal.alpha(Pal.white, 0.10f)
        g.drawRect(dx - 1, dy - 1, dw + 1, dh + 1)
        g.dispose()
    }

    private fun drawChips(g0: Graphics2D, w: Int, h: Int) {
        if (chipAlpha <= 0.01f) return
        val g = g0.create() as Graphics2D
        g.composite = java.awt.AlphaComposite.SrcOver.derive(chipAlpha.coerceIn(0f, 1f))
        val y = topInset + 8f
        val font = Fonts.regular(13f)
        val maxW = max(120f, w - 16f - 40f - 16f - 60f - 16f)
        val text = TextBlock.ellipsize(host.statusText(), font, maxW - 24f)
        val cw = Fonts.width(font, text) + 24f
        Glass.paint(g, 16f, y + 4f, cw, 32f, 12f, Pal.panel, 0.62f, 0.18f, 0.22f, 0f, false)
        g.drawText(text, 28f, y + 4f + (32f - 17f) / 2f, font, Pal.muted)
        val m = menuRect()
        if (menuHover) Glass.paint(g, m.x, m.y, 40f, 40f, 12f, Pal.accentFill, 0.7f, 0.22f, 0.22f, 0f, false)
        else Glass.paint(g, m.x, m.y, 40f, 40f, 12f, Pal.panel, 0.62f, 0.18f, 0.22f, 0f, false)
        Icons.dots(g, m.x + 20f, m.y + 20f, 22f, Pal.ink)
        if (host.fastForwardOn()) {
            val ff = "4×"
            val ffont = Fonts.bold(13f)
            val fw = Fonts.width(ffont, ff) + 40f
            val fx = m.x - 10f - fw
            Glass.paint(g, fx, m.y + 4f, fw, 32f, 12f, Pal.accentFill, 0.85f, 0.25f, 0.25f, 0f, false)
            Icons.fast(g, fx + 17f, m.y + 20f, 13f, Pal.white)
            g.drawText(ff, fx + 31f, m.y + 4f + (32f - 17f) / 2f, ffont, Pal.white)
        }
        g.dispose()
    }

    companion object {
        const val W = 240
        const val H = 160
    }
}
