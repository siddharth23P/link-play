package com.linkplay.desktop.ui

import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.Scrollable
import javax.swing.SwingConstants
import kotlin.math.max
import kotlin.math.min

/**
 * Layout that knows how tall something is at a given width: wrapped text needs that, and Swing's
 * own layouts cannot ask. Everything on the screens is a [Flow] or an ordinary component with a
 * fixed preferred height.
 */
interface Flow {
    fun heightFor(width: Int): Int
}

fun heightOf(c: Component, width: Int): Int = if (c is Flow) c.heightFor(width) else c.preferredSize.height

private fun gapOf(c: Component, default: Int): Int = ((c as? JComponent)?.getClientProperty("gap") as? Int) ?: default

private fun fixedWidth(c: Component): Int? = (c as? JComponent)?.getClientProperty("w") as? Int

/** Children stacked top to bottom, each as wide as the box. A child's `gap` client property overrides [gap] above it. */
open class VBox(private val gap: Int = 0, protected val pad: Insets = Insets(0, 0, 0, 0)) : JPanel(null), Flow {
    init { isOpaque = false }

    fun <T : JComponent> put(c: T, gapBefore: Int = -1): T {
        if (gapBefore >= 0) c.putClientProperty("gap", gapBefore)
        add(c)
        return c
    }

    override fun heightFor(width: Int): Int {
        val w = width - pad.left - pad.right
        var h = pad.top + pad.bottom
        var first = true
        for (c in components) {
            if (!c.isVisible) continue
            if (!first) h += gapOf(c, gap)
            h += heightOf(c, w)
            first = false
        }
        return h
    }

    override fun doLayout() {
        val w = width - pad.left - pad.right
        var y = pad.top
        var first = true
        for (c in components) {
            if (!c.isVisible) continue
            if (!first) y += gapOf(c, gap)
            val h = heightOf(c, w)
            c.setBounds(pad.left, y, w, h)
            y += h
            first = false
        }
    }

    override fun getPreferredSize(): Dimension {
        val w = (components.filter { it.isVisible }.maxOfOrNull { it.preferredSize.width } ?: 0) + pad.left + pad.right
        return Dimension(w, heightFor(if (width > 0) width else w))
    }

    override fun getMinimumSize(): Dimension = Dimension(0, 0)
}

/** Children side by side. By default they share the width equally; a child with a `w` client property keeps that width. */
class HBox(private val gap: Int = 12) : JPanel(null), Flow {
    init { isOpaque = false }

    fun <T : JComponent> put(c: T, fixedW: Int? = null): T {
        if (fixedW != null) c.putClientProperty("w", fixedW)
        add(c)
        return c
    }

    private fun widths(width: Int): IntArray {
        val kids = components.filter { it.isVisible }
        if (kids.isEmpty()) return IntArray(0)
        val fixed = kids.sumOf { fixedWidth(it) ?: 0 }
        val flexCount = kids.count { fixedWidth(it) == null }
        val room = width - fixed - gap * (kids.size - 1)
        val each = if (flexCount > 0) room / flexCount else 0
        return IntArray(kids.size) { fixedWidth(kids[it]) ?: each }
    }

    override fun heightFor(width: Int): Int {
        val kids = components.filter { it.isVisible }
        val ws = widths(width)
        var h = 0
        for ((i, c) in kids.withIndex()) h = max(h, heightOf(c, ws[i]))
        return h
    }

    override fun doLayout() {
        val kids = components.filter { it.isVisible }
        val ws = widths(width)
        var x = 0
        val h = height
        for ((i, c) in kids.withIndex()) {
            c.setBounds(x, 0, ws[i], h)
            x += ws[i] + gap
        }
    }

    override fun getPreferredSize(): Dimension {
        val kids = components.filter { it.isVisible }
        val w = kids.sumOf { fixedWidth(it) ?: it.preferredSize.width } + gap * max(0, kids.size - 1)
        return Dimension(w, heightFor(if (width > 0) width else w))
    }

    override fun getMinimumSize(): Dimension = Dimension(0, 0)
}

/**
 * Children left to right, each as wide as it asks (or its `w` client property), and on to the next line when the
 * row is full: a row of labels that must never be cut, whatever the font (Segoe UI is wider than SF) or the window.
 */
class WrapRow(private val gap: Int = 4, private val lineGap: Int = 0) : JPanel(null), Flow {
    init { isOpaque = false }

    fun <T : JComponent> put(c: T, fixedW: Int? = null): T {
        if (fixedW != null) c.putClientProperty("w", fixedW)
        add(c)
        return c
    }

    /** Where each visible child goes at [width]: its bounds, and the height all together. */
    private fun place(width: Int): Pair<List<Rectangle>, Int> {
        val out = ArrayList<Rectangle>()
        var x = 0
        var y = 0
        var line = 0
        for (c in components) {
            if (!c.isVisible) continue
            val w = min(max(1, width), fixedWidth(c) ?: c.preferredSize.width)
            val h = heightOf(c, w)
            if (x > 0 && x + w > width) {
                x = 0
                y += line + lineGap
                line = 0
            }
            out += Rectangle(x, y, w, h)
            x += w + gap
            line = max(line, h)
        }
        return out to (y + line)
    }

    override fun heightFor(width: Int): Int = place(width).second

    override fun doLayout() {
        val (rects, _) = place(width)
        var i = 0
        for (c in components) if (c.isVisible) c.bounds = rects[i++]
    }

    override fun getPreferredSize(): Dimension {
        val w = components.filter { it.isVisible }.sumOf { fixedWidth(it) ?: it.preferredSize.width } + gap * max(0, components.count { it.isVisible } - 1)
        return Dimension(w, heightFor(if (width > 0) width else w))
    }

    override fun getMinimumSize(): Dimension = Dimension(0, 0)
}

/** Two columns on a wide window, stacked on a narrow one. */
class Columns(
    private val left: JComponent,
    private val right: JComponent,
    private val gap: Int = 24,
    private val breakAt: Int = 800,
) : JPanel(null), Flow {
    init {
        isOpaque = false
        add(left)
        add(right)
    }

    private fun wide(width: Int) = width >= breakAt

    private fun colW(width: Int) = (width - gap) / 2

    override fun heightFor(width: Int): Int {
        if (wide(width)) {
            val cw = colW(width)
            return max(heightOf(left, cw), heightOf(right, cw))
        }
        return heightOf(left, width) + gap + heightOf(right, width)
    }

    override fun doLayout() {
        if (wide(width)) {
            val cw = colW(width)
            left.setBounds(0, 0, cw, heightOf(left, cw))
            right.setBounds(cw + gap, 0, width - cw - gap, heightOf(right, width - cw - gap))
        } else {
            val lh = heightOf(left, width)
            left.setBounds(0, 0, width, lh)
            right.setBounds(0, lh + gap, width, heightOf(right, width))
        }
    }

    override fun getPreferredSize(): Dimension = Dimension(breakAt, heightFor(if (width > 0) width else breakAt))
    override fun getMinimumSize(): Dimension = Dimension(0, 0)
}

/** A fixed amount of empty space. */
class Spacer(private val h: Int) : JComponent(), Flow {
    override fun heightFor(width: Int) = h
    override fun getPreferredSize() = Dimension(1, h)
}

/** Text that wraps to the width it is given. */
class TextBlock(
    text: String = "",
    font: Font = Fonts.regular(15f),
    color: Color = Pal.ink,
    private val maxLines: Int = Int.MAX_VALUE,
    /** Distance between lines as a multiple of the font size. */
    private val spacing: Float = 1.38f,
    private val align: Int = SwingConstants.LEFT,
) : JComponent(), Flow {
    var text: String = text
        set(v) {
            if (v == field) return
            field = v
            cacheW = -1
            revalidate()
            repaint()
        }

    var font2: Font = font
        set(v) {
            field = v
            cacheW = -1
            revalidate()
            repaint()
        }

    var color: Color = color
        set(v) {
            field = v
            repaint()
        }

    private var cacheW = -1
    private var cache: List<String> = emptyList()

    init { isOpaque = false }

    private var ctx: javax.accessibility.AccessibleContext? = null

    /** A screen reader reads the words: a JComponent alone has no accessible context, and so no name. */
    override fun getAccessibleContext(): javax.accessibility.AccessibleContext =
        ctx ?: (object : AccessibleJComponent() {
            override fun getAccessibleName(): String? = super.getAccessibleName() ?: text
            override fun getAccessibleRole(): javax.accessibility.AccessibleRole = javax.accessibility.AccessibleRole.LABEL
        }).also { ctx = it }

    private fun pitch() = max(1, Math.round(font2.size2D * spacing))

    private fun lines(w: Int): List<String> {
        if (w == cacheW) return cache
        cacheW = w
        cache = wrap(text, font2, w, maxLines)
        return cache
    }

    override fun heightFor(width: Int): Int = if (text.isEmpty()) 0 else lines(max(1, width)).size * pitch()

    override fun getPreferredSize(): Dimension {
        val widest = text.split('\n').maxOfOrNull { Fonts.width(font2, it) } ?: 0f
        val w = Math.ceil(widest.toDouble()).toInt() + 1
        return Dimension(w, heightFor(if (width > 0) min(width, w) else w))
    }

    override fun paintComponent(g0: Graphics) {
        if (text.isEmpty()) return
        val g = g0.create() as Graphics2D
        g.quality()
        g.font = font2
        g.color = if (isEnabled) color else Pal.alpha(color, 0.5f)
        val lm = font2.getLineMetrics("Ag", Fonts.frc)
        val p = pitch()
        val base = (p - (lm.ascent + lm.descent)) / 2f + lm.ascent
        for ((i, line) in lines(max(1, width)).withIndex()) {
            val lw = Fonts.width(font2, line)
            val x = when (align) {
                SwingConstants.CENTER -> (width - lw) / 2f
                SwingConstants.RIGHT -> width - lw
                else -> 0f
            }
            g.drawString(line, x, i * p + base)
        }
        g.dispose()
    }

    companion object {
        /** Breaks [text] into lines no wider than [w] (at spaces, or anywhere inside a word that is too long). */
        fun wrap(text: String, font: Font, w: Int, maxLines: Int = Int.MAX_VALUE): List<String> {
            val out = ArrayList<String>()
            for (para in text.split('\n')) {
                if (para.isEmpty()) { out += ""; continue }
                var line = ""
                for (word in para.split(' ')) {
                    val cand = if (line.isEmpty()) word else "$line $word"
                    if (Fonts.width(font, cand) <= w) { line = cand; continue }
                    if (line.isNotEmpty()) { out += line; line = "" }
                    var rest = word
                    while (rest.length > 1 && Fonts.width(font, rest) > w) {
                        var n = rest.length
                        while (n > 1 && Fonts.width(font, rest.substring(0, n)) > w) n--
                        out += rest.substring(0, n)
                        rest = rest.substring(n)
                    }
                    line = rest
                }
                out += line
            }
            if (out.size > maxLines) {
                val cut = ArrayList(out.subList(0, maxLines))
                var last = cut[maxLines - 1]
                while (last.isNotEmpty() && Fonts.width(font, "$last…") > w) last = last.dropLast(1)
                cut[maxLines - 1] = "$last…"
                return cut
            }
            return out
        }

        /** [s] cut with an ellipsis so it fits in [w]. */
        fun ellipsize(s: String, font: Font, w: Float): String {
            if (Fonts.width(font, s) <= w) return s
            var t = s
            while (t.isNotEmpty() && Fonts.width(font, "$t…") > w) t = t.dropLast(1)
            return "$t…"
        }
    }
}

/**
 * Hosts one lobby screen: the backdrop, and the content as a column of at most [maxWidth], centred and
 * scrolled when the window is too short for it.
 */
open class ScreenHost(
    private val content: JComponent,
    private val maxWidth: Int,
    private val padX: Int = 32,
    private val padY: Int = 28,
    private val centerV: Boolean = false,
) : JPanel(BorderLayout()) {
    private val backdrop = BackdropCache()
    private val page = Page()
    private val scroll = JScrollPane(page, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER)

    init {
        isOpaque = true
        scroll.isOpaque = false
        scroll.border = null
        scroll.viewport.isOpaque = false
        scroll.viewport.scrollMode = JViewport.SIMPLE_SCROLL_MODE
        scroll.verticalScrollBar.ui = ThinScrollBarUI()
        scroll.verticalScrollBar.unitIncrement = 28
        scroll.verticalScrollBar.isOpaque = false
        scroll.isWheelScrollingEnabled = true
        add(scroll, BorderLayout.CENTER)
    }

    /** Space kept free at the top (the title bar when the window's content runs under it). */
    var topInset = 0
        set(v) { field = v; page.revalidate() }

    fun scrollToTop() {
        scroll.verticalScrollBar.value = 0
    }

    override fun paintComponent(g: Graphics) {
        backdrop.paint(g as Graphics2D, width, height, Scene.ambient, 0.75f) // dark enough for the muted and faint captions over a bright last scene
    }

    private inner class Page : JPanel(null), Scrollable {
        init {
            isOpaque = false
            add(content)
        }

        private fun contentWidth(w: Int) = min(max(w, 320) - 2 * padX, maxWidth)

        override fun doLayout() {
            val cw = contentWidth(width)
            val ch = heightOf(content, cw)
            val x = (width - cw) / 2
            val y = if (centerV) max(padY + topInset, (height - ch) / 2) else padY + topInset
            content.setBounds(x, y, cw, ch)
        }

        override fun getPreferredSize(): Dimension {
            val vw = (parent as? JViewport)?.width ?: width
            return Dimension(vw, heightOf(content, contentWidth(vw)) + 2 * padY + topInset)
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 28
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = max(40, r.height - 60)
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight(): Boolean {
            val vp = parent as? JViewport ?: return false
            return vp.height >= preferredSize.height
        }
    }
}

/** The picture behind the lobby screens and dialogs: set when the game's last picture is known. */
object Scene {
    @Volatile var ambient: java.awt.image.BufferedImage? = null

    fun set(frame: IntArray?) {
        ambient = frame?.let { Backdrop.ambientOf(it) }
    }
}
