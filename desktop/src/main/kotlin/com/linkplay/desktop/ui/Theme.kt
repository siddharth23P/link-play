package com.linkplay.desktop.ui

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.FontRenderContext
import java.awt.geom.Area
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The look of the app, taken from the phone app (Glass.kt, GameMenu.kt's Pal): a dark, calm surface
 * with one violet accent. Everything is painted by hand, so there is no look-and-feel to fight.
 */
/** A plain component that can also describe itself to screen readers (JComponent alone has no accessible context). */
open class Comp : javax.swing.JComponent() {
    private var ctx: javax.accessibility.AccessibleContext? = null

    override fun getAccessibleContext(): javax.accessibility.AccessibleContext =
        ctx ?: (object : AccessibleJComponent() {}).also { ctx = it }
}

object Pal {
    val bg = Color(0x0E0C16)
    val panel = Color(0x14121C)
    val indigo = Color(0x24164F)
    val ink = Color(0xF4F2FA)
    val muted = Color(0xD0CBE2)
    /** Hints and captions: a step quieter than [muted], still above 7:1 on the panels. */
    val faint = Color(0xA9A2C4)
    val accent = Color(0x7C5CFF)
    val accentFill = Color(0x6A4BF0)
    val accentText = Color(0xB7A8FF)
    val ok = Color(0x4AD6A6)
    val bad = Color(0xFF9AA8)
    val white = Color.WHITE

    fun alpha(c: Color, a: Float): Color = Color(c.red, c.green, c.blue, (a.coerceIn(0f, 1f) * 255f + 0.5f).toInt())

    fun mix(a: Color, b: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        return Color(
            (a.red + (b.red - a.red) * k).toInt(),
            (a.green + (b.green - a.green) * k).toInt(),
            (a.blue + (b.blue - a.blue) * k).toInt(),
            (a.alpha + (b.alpha - a.alpha) * k).toInt(),
        )
    }
}

/** The system font where Java can reach it (SF on a Mac, Segoe UI on Windows), in the few weights it has. */
object Fonts {
    val mac = (System.getProperty("os.name") ?: "").lowercase(Locale.ROOT).contains("mac")
    val windows = (System.getProperty("os.name") ?: "").lowercase(Locale.ROOT).contains("windows")

    private val family: String = when {
        mac -> ".AppleSystemUIFont"
        windows -> "Segoe UI"
        else -> Font.SANS_SERIF
    }
    private val mediumFamily: String = if (windows) "Segoe UI Semibold" else family

    fun regular(size: Float): Font = Font(family, Font.PLAIN, 1).deriveFont(size)

    /** Medium weight: Windows has a semibold face; a Mac's system font only comes in regular and bold, so this is bold there. */
    fun medium(size: Float): Font = if (windows) Font(mediumFamily, Font.PLAIN, 1).deriveFont(size) else Font(family, Font.BOLD, 1).deriveFont(size)

    fun bold(size: Float): Font = Font(family, Font.BOLD, 1).deriveFont(size)

    fun mono(size: Float): Font = Font(Font.MONOSPACED, Font.PLAIN, 1).deriveFont(size)

    /** One shared context so layout (measuring) and painting agree on the width of a string. */
    val frc = FontRenderContext(null, true, true)

    fun width(font: Font, s: String): Float = if (s.isEmpty()) 0f else font.getStringBounds(s, frc).width.toFloat()

    fun lineHeight(font: Font): Int {
        val lm = font.getLineMetrics("Ag", frc)
        return Math.ceil((lm.ascent + lm.descent + lm.leading).toDouble()).toInt()
    }
}

fun Graphics2D.quality() {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
    setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
}

fun Graphics2D.fillRound(x: Float, y: Float, w: Float, h: Float, r: Float) {
    fill(RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2))
}

/** Draws [s] with its baseline [baseline] below [y] (the top of the line box). */
fun Graphics2D.drawText(s: String, x: Float, y: Float, font: Font, color: Color) {
    this.font = font
    this.color = color
    drawString(s, x, y + font.getLineMetrics("Ag", Fonts.frc).ascent)
}

/**
 * Glass: a rounded surface that lets the backdrop show through, a hairline edge and a soft highlight
 * along the top. The recipe (and its numbers) is the phone app's GlassDrawable.
 */
object Glass {
    fun paint(
        g0: Graphics2D, x: Float, y: Float, w: Float, h: Float, radius: Float,
        base: Color = Pal.panel, fill: Float = 0.72f, hairline: Float = 0.16f, highlight: Float = 0.22f,
        shadow: Float = 0f, topGradient: Boolean = true,
    ) {
        val g = g0.create() as Graphics2D
        try {
            g.quality()
            if (shadow > 0f) {
                // Stacked translucent rounded rects: a cheap soft shadow, a little lower than the surface.
                val steps = 7
                val spread = shadow / 2f
                for (i in steps downTo 1) {
                    val grow = spread * i / steps
                    g.color = Color(0, 0, 0, (255 * 0.35f / steps * 0.9f * 1.4f).toInt())
                    g.fillRound(x - grow, y - grow + shadow * 0.28f, w + grow * 2, h + grow * 2, radius + grow)
                }
            }
            val shape = RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1f, h - 1f, radius * 2, radius * 2)
            g.color = Pal.alpha(base, fill)
            g.fill(shape)
            if (topGradient) {
                val old = g.clip
                g.clip(shape)
                g.paint = GradientPaint(0f, y, Pal.alpha(Pal.white, 0.06f), 0f, y + 24f, Pal.alpha(Pal.white, 0f))
                g.fillRect(x.toInt(), y.toInt(), w.toInt() + 1, 25)
                g.clip = old
            }
            g.stroke = java.awt.BasicStroke(1f)
            g.color = Pal.alpha(Pal.white, hairline)
            g.draw(shape)
            if (highlight > 0f) {
                val hh = min(h, 28f)
                g.paint = GradientPaint(0f, y, Pal.alpha(Pal.white, highlight), 0f, y + hh, Pal.alpha(Pal.white, 0f))
                g.draw(RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1f, h - 1f, radius * 2, radius * 2))
            }
        } finally {
            g.dispose()
        }
    }
}

/**
 * What sits behind the screens: the last picture of the game, shrunk and blurred, with a dark scrim
 * (Glass.kt's FrostedBackdrop); before there is one, a violet-to-black wash.
 */
object Backdrop {
    /** A 60x40 blur of a 240x160 picture (3 box passes, radius 2, a little more colour), ready to be stretched. */
    fun ambientOf(frame: IntArray): BufferedImage {
        val w = 60
        val h = 40
        var px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            // Average the 4x4 block this small pixel stands for.
            var r = 0; var gr = 0; var b = 0
            for (dy in 0 until 4) for (dx in 0 until 4) {
                val c = frame[(y * 4 + dy) * 240 + x * 4 + dx]
                r += (c shr 16) and 0xFF; gr += (c shr 8) and 0xFF; b += c and 0xFF
            }
            px[y * w + x] = (r / 16 shl 16) or (gr / 16 shl 8) or (b / 16)
        }
        repeat(3) { px = boxPass(px, w, h, 2) }
        for (i in px.indices) { // saturation x1.3
            val c = px[i]
            val r = (c shr 16) and 0xFF; val gr = (c shr 8) and 0xFF; val b = c and 0xFF
            val l = 0.2126f * r + 0.7152f * gr + 0.0722f * b
            fun s(v: Int) = (l + (v - l) * 1.3f).toInt().coerceIn(0, 255)
            px[i] = (s(r) shl 16) or (s(gr) shl 8) or s(b)
        }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, w, h, px, 0, w)
        return img
    }

    private fun boxPass(src: IntArray, w: Int, h: Int, rad: Int): IntArray {
        fun pass(inp: IntArray, horizontal: Boolean): IntArray {
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                var r = 0; var g = 0; var b = 0; var n = 0
                for (d in -rad..rad) {
                    val sx = if (horizontal) (x + d).coerceIn(0, w - 1) else x
                    val sy = if (horizontal) y else (y + d).coerceIn(0, h - 1)
                    val c = inp[sy * w + sx]
                    r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
                }
                out[y * w + x] = (r / n shl 16) or (g / n shl 8) or (b / n)
            }
            return out
        }
        return pass(pass(src, true), false)
    }

    /** Paints the backdrop over (0, 0, w, h): [ambient] stretched to cover, then [scrim] of black. */
    fun paint(g0: Graphics2D, w: Int, h: Int, ambient: BufferedImage?, scrim: Float) {
        val g = g0.create() as Graphics2D
        try {
            if (ambient == null) {
                g.paint = GradientPaint(0f, 0f, Pal.indigo, 0f, h.toFloat(), Pal.bg)
                g.fillRect(0, 0, w, h)
                return
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            val s = max(w / ambient.width.toFloat(), h / ambient.height.toFloat())
            val dw = ambient.width * s
            val dh = ambient.height * s
            g.drawImage(ambient, ((w - dw) / 2).toInt(), ((h - dh) / 2).toInt(), dw.toInt() + 1, dh.toInt() + 1, null)
            g.color = Color(0, 0, 0, (scrim * 255).toInt())
            g.fillRect(0, 0, w, h)
        } finally {
            g.dispose()
        }
    }
}

/** A cache for the backdrop at one size, so a lobby screen does not scale a picture on every repaint. */
class BackdropCache {
    private var image: BufferedImage? = null
    private var key = ""

    fun paint(g: Graphics2D, w: Int, h: Int, ambient: BufferedImage?, scrim: Float) {
        val k = "$w x $h ${System.identityHashCode(ambient)} $scrim"
        val cached = image
        if (cached == null || k != key) {
            val img = BufferedImage(max(1, w), max(1, h), BufferedImage.TYPE_INT_RGB)
            val ig = img.createGraphics()
            Backdrop.paint(ig, w, h, ambient, scrim)
            ig.dispose()
            image = img
            key = k
        }
        g.drawImage(image, 0, 0, null)
    }
}

/** Drawn icons, so nothing depends on the system font having the glyph. All centred on (cx, cy). */
object Icons {
    private fun stroke(g: Graphics2D, w: Float) {
        g.stroke = java.awt.BasicStroke(w, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
    }

    fun chevron(g0: Graphics2D, cx: Float, cy: Float, size: Float, color: Color, dir: Int) {
        val g = g0.create() as Graphics2D
        g.quality(); g.color = color; stroke(g, 2f)
        val s = size / 2
        val p = java.awt.geom.Path2D.Float()
        p.moveTo(cx - dir * s * 0.5f, cy - s)
        p.lineTo(cx + dir * s * 0.5f, cy)
        p.lineTo(cx - dir * s * 0.5f, cy + s)
        g.draw(p); g.dispose()
    }

    fun check(g0: Graphics2D, cx: Float, cy: Float, size: Float, color: Color, width: Float = 2.2f) {
        val g = g0.create() as Graphics2D
        g.quality(); g.color = color; stroke(g, width)
        val s = size / 2
        val p = java.awt.geom.Path2D.Float()
        p.moveTo(cx - s, cy + s * 0.05f)
        p.lineTo(cx - s * 0.3f, cy + s * 0.7f)
        p.lineTo(cx + s, cy - s * 0.6f)
        g.draw(p); g.dispose()
    }

    fun dots(g0: Graphics2D, cx: Float, cy: Float, size: Float, color: Color) {
        val g = g0.create() as Graphics2D
        g.quality(); g.color = color
        val r = size / 9f
        for (i in -1..1) g.fill(java.awt.geom.Ellipse2D.Float(cx + i * size / 3.2f - r, cy - r, r * 2, r * 2))
        g.dispose()
    }

    /** Two arrows, one each way: "host a game". */
    fun swap(g0: Graphics2D, cx: Float, cy: Float, size: Float, color: Color) {
        val g = g0.create() as Graphics2D
        g.quality(); g.color = color; stroke(g, 2.2f)
        val s = size / 2
        val a = 0.32f * size
        g.draw(java.awt.geom.Line2D.Float(cx - s, cy - s * 0.38f, cx + s, cy - s * 0.38f))
        g.draw(java.awt.geom.Line2D.Float(cx + s - a * 0.6f, cy - s * 0.38f - a * 0.55f, cx + s, cy - s * 0.38f))
        g.draw(java.awt.geom.Line2D.Float(cx + s - a * 0.6f, cy - s * 0.38f + a * 0.55f, cx + s, cy - s * 0.38f))
        g.draw(java.awt.geom.Line2D.Float(cx + s, cy + s * 0.38f, cx - s, cy + s * 0.38f))
        g.draw(java.awt.geom.Line2D.Float(cx - s + a * 0.6f, cy + s * 0.38f - a * 0.55f, cx - s, cy + s * 0.38f))
        g.draw(java.awt.geom.Line2D.Float(cx - s + a * 0.6f, cy + s * 0.38f + a * 0.55f, cx - s, cy + s * 0.38f))
        g.dispose()
    }

    /** An arrow into a tray: "join a game". */
    fun download(g0: Graphics2D, cx: Float, cy: Float, size: Float, color: Color) {
        val g = g0.create() as Graphics2D
        g.quality(); g.color = color; stroke(g, 2.2f)
        val s = size / 2
        g.draw(java.awt.geom.Line2D.Float(cx, cy - s, cx, cy + s * 0.35f))
        g.draw(java.awt.geom.Line2D.Float(cx - s * 0.5f, cy - s * 0.15f, cx, cy + s * 0.4f))
        g.draw(java.awt.geom.Line2D.Float(cx + s * 0.5f, cy - s * 0.15f, cx, cy + s * 0.4f))
        val p = java.awt.geom.Path2D.Float()
        p.moveTo(cx - s, cy + s * 0.4f); p.lineTo(cx - s, cy + s); p.lineTo(cx + s, cy + s); p.lineTo(cx + s, cy + s * 0.4f)
        g.draw(p); g.dispose()
    }

    fun pencil(g0: Graphics2D, cx: Float, cy: Float, size: Float, color: Color) {
        val g = g0.create() as Graphics2D
        g.quality(); g.color = color; stroke(g, 1.8f)
        val s = size / 2
        g.draw(java.awt.geom.Line2D.Float(cx - s, cy + s, cx - s * 0.2f, cy + s * 0.8f))
        g.draw(java.awt.geom.Line2D.Float(cx - s, cy + s, cx - s * 0.8f, cy + s * 0.2f))
        g.draw(java.awt.geom.Line2D.Float(cx - s * 0.8f, cy + s * 0.2f, cx + s * 0.4f, cy - s * 0.95f))
        g.draw(java.awt.geom.Line2D.Float(cx - s * 0.2f, cy + s * 0.8f, cx + s * 0.95f, cy - s * 0.4f))
        g.draw(java.awt.geom.Line2D.Float(cx + s * 0.4f, cy - s * 0.95f, cx + s * 0.95f, cy - s * 0.4f))
        g.dispose()
    }

    /** A small play triangle and a cable: the app glyph for the title bar corner. */
    fun fast(g0: Graphics2D, cx: Float, cy: Float, size: Float, color: Color) {
        val g = g0.create() as Graphics2D
        g.quality(); g.color = color
        val s = size / 2
        for (k in 0..1) {
            val ox = cx - s * 0.1f + (k - 0.5f) * s
            val p = java.awt.geom.Path2D.Float()
            p.moveTo(ox - s * 0.45f, cy - s * 0.7f); p.lineTo(ox + s * 0.45f, cy); p.lineTo(ox - s * 0.45f, cy + s * 0.7f); p.closePath()
            g.fill(p)
        }
        g.dispose()
    }
}

/** A window-sized scrim helper: darkens [img] by [a] (used by the dialog backdrop). */
fun darken(g: Graphics2D, w: Int, h: Int, a: Float) {
    val old = g.composite
    g.composite = AlphaComposite.SrcOver
    g.color = Color(0, 0, 0, (a.coerceIn(0f, 1f) * 255).toInt())
    g.fillRect(0, 0, w, h)
    g.composite = old
}

/** Cuts a rounded corner out of [shape] drawing: clip helper for thumbnails. */
fun roundClip(x: Float, y: Float, w: Float, h: Float, r: Float): java.awt.Shape = Area(RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2))
