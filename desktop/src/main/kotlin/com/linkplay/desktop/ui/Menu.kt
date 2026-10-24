package com.linkplay.desktop.ui

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JViewport
import javax.swing.KeyStroke
import javax.swing.Scrollable
import kotlin.math.max

/** One line of the menu. */
class MenuRow(
    val title: String,
    val detail: String? = null, // one muted line under the title
    val value: String? = null, // muted, right-aligned ("On")
    val enabled: Boolean = true,
    val reason: String? = null, // shown instead of [detail] while disabled
    val destructive: Boolean = false,
    val checked: Boolean = false,
    val chevron: Boolean = false,
    val thumb: BufferedImage? = null, // 96x64 preview; [hasThumb] without an image draws an empty frame
    val hasThumb: Boolean = false,
    val onClick: () -> Unit = {},
)

class MenuSection(val label: String?, val rows: List<MenuRow> = emptyList(), val view: JComponent? = null)

/** A tappable row of a menu: title, detail, value, tick, chevron or a thumbnail. */
class MenuRowView(private val row: MenuRow) : Comp(), Flow {
    private var hover = false
    private var pressed = false
    private var viaMouse = false

    init {
        isOpaque = false
        isFocusable = row.enabled
        cursor = if (row.enabled) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        getAccessibleContext().accessibleName = row.title + (row.detail?.let { ". $it" } ?: "")
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; pressed = false; repaint() }
            override fun mousePressed(e: MouseEvent) { if (row.enabled) { pressed = true; viaMouse = true; requestFocusInWindow(); repaint() } }
            override fun mouseReleased(e: MouseEvent) {
                val was = pressed
                pressed = false
                repaint()
                if (was && row.enabled && contains(e.point)) row.onClick()
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> { if (row.enabled) row.onClick(); e.consume() }
                    KeyEvent.VK_DOWN -> { transferFocus(); e.consume() }
                    KeyEvent.VK_UP -> { transferFocusBackward(); e.consume() }
                }
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                scrollRectToVisible(Rectangle(0, -4, width, height + 8))
                repaint()
            }
            override fun focusLost(e: FocusEvent) { viaMouse = false; repaint() }
        })
    }

    override fun heightFor(width: Int) = if (row.hasThumb) 74 else if (row.detail != null || (!row.enabled && row.reason != null)) 54 else 44

    override fun getPreferredSize() = Dimension(300, heightFor(300))

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.quality()
        val w = width.toFloat()
        val h = height.toFloat()
        if (pressed) Glass.paint(g, 0f, 0f, w, h, 12f, Pal.accentFill, 0.92f, 0.18f, 0.22f, 0f, false)
        else if (hover && row.enabled) {
            g.color = Pal.alpha(Pal.white, 0.07f)
            g.fillRound(0f, 0f, w, h, 12f)
        }
        val a = if (row.enabled) 1f else 0.4f
        var x = 14f
        if (row.hasThumb) {
            val tw = 78f
            val th = 52f
            val ty = (h - th) / 2f
            g.color = Pal.alpha(Pal.white, 0.10f)
            g.fillRound(x, ty, tw, th, 6f)
            row.thumb?.let {
                val old = g.clip
                g.clip(RoundRectangle2D.Float(x, ty, tw, th, 12f, 12f))
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.drawImage(it, x.toInt(), ty.toInt(), tw.toInt(), th.toInt(), null)
                g.clip = old
            }
            x += tw + 14f
        }
        var right = w - 14f
        if (row.chevron) {
            Icons.chevron(g, right - 4f, h / 2f, 11f, Pal.alpha(Pal.muted, a), 1)
            right -= 18f
        }
        if (row.checked) {
            Icons.check(g, right - 8f, h / 2f, 13f, Pal.accentText, 2.4f)
            right -= 26f
        }
        row.value?.let {
            val f = Fonts.regular(13.5f)
            val vw = Fonts.width(f, it)
            g.drawText(it, right - vw, (h - 17f) / 2f, f, Pal.alpha(Pal.muted, a))
            right -= vw + 10f
        }
        val titleColor = if (row.destructive) Pal.bad else Pal.ink
        val sub = if (!row.enabled && row.reason != null) row.reason else row.detail
        val titleFont = Fonts.regular(15f)
        val titleText = TextBlock.ellipsize(row.title, titleFont, right - x)
        if (sub != null) {
            val subFont = Fonts.regular(12.5f)
            g.drawText(titleText, x, h / 2f - 19f, titleFont, Pal.alpha(titleColor, a))
            g.drawText(TextBlock.ellipsize(sub, subFont, right - x), x, h / 2f + 1f, subFont, Pal.alpha(Pal.muted, a))
        } else {
            g.drawText(titleText, x, (h - 18f) / 2f, titleFont, Pal.alpha(titleColor, a))
        }
        if (isFocusOwner && !viaMouse) {
            g.stroke = BasicStroke(2f)
            g.color = Pal.alpha(Pal.accentText, 0.9f)
            g.draw(RoundRectangle2D.Float(1.5f, 1.5f, w - 3f, h - 3f, 22f, 22f))
        }
        g.dispose()
    }
}

/**
 * The right-hand panel: a title, a Close button, a back arrow on sub-pages and sections of rows.
 * Pages replace each other inside the same panel ([show] again), so there is no flicker.
 */
class MenuPanel(private val onClose: () -> Unit) : JPanel(BorderLayout()) {
    private val title = TextBlock("", Fonts.bold(18f), Pal.ink)
    private val back = IconButton({ g, cx, cy, s, c -> Icons.chevron(g, cx, cy, s * 0.8f, c, -1) }, "Back")
    private val close = GlassButton("Close", BtnKind.TEXT) { onClose() }
    private val body = VBox(2, Insets(4, 8, 14, 8))
    private val scroll = JScrollPane(Holder(), JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER)
    private var onBack: (() -> Unit)? = null

    /** The page's way back (null on the first page): Escape and the back arrow use it. */
    val canGoBack get() = onBack != null

    fun cancel() {
        val b = onBack
        if (b != null) b() else onClose()
    }

    init {
        isOpaque = false
        border = javax.swing.BorderFactory.createEmptyBorder(SHADOW, SHADOW, SHADOW, SHADOW)
        back.box = 36
        back.onClick = { onBack?.invoke() }
        val head = JPanel(BorderLayout(6, 0))
        head.isOpaque = false
        head.border = javax.swing.BorderFactory.createEmptyBorder(14, 12, 6, 10)
        val west = JPanel(BorderLayout(6, 0))
        west.isOpaque = false
        west.add(back, BorderLayout.WEST)
        west.add(title, BorderLayout.CENTER)
        head.add(west, BorderLayout.CENTER)
        head.add(close, BorderLayout.EAST)
        add(head, BorderLayout.NORTH)
        scroll.isOpaque = false
        scroll.border = null
        scroll.viewport.isOpaque = false
        scroll.viewport.scrollMode = JViewport.SIMPLE_SCROLL_MODE
        scroll.verticalScrollBar.ui = ThinScrollBarUI()
        scroll.verticalScrollBar.unitIncrement = 24
        scroll.verticalScrollBar.isOpaque = false
        add(scroll, BorderLayout.CENTER)
        addMouseListener(object : MouseAdapter() {})
        registerKeyboardAction({ if (canGoBack) cancel() }, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), WHEN_IN_FOCUSED_WINDOW)
    }

    /** The first row (or control) of the page, to give the keyboard focus. */
    var firstFocus: JComponent? = null
        private set

    fun show(pageTitle: String, sections: List<MenuSection>, back: (() -> Unit)? = null) {
        title.text = pageTitle
        onBack = back
        this.back.isVisible = back != null
        body.removeAll()
        firstFocus = null
        var first = true
        for (s in sections) {
            s.label?.let { body.put(TextBlock(it.uppercase(), Fonts.medium(11.5f), Pal.faint), if (first) 6 else 16).border = javax.swing.BorderFactory.createEmptyBorder(0, 14, 0, 0) }
            s.view?.let { body.put(it, if (s.label == null) 4 else 6) }
            for (r in s.rows) {
                val v = MenuRowView(r)
                body.put(v, if (s.label != null && r === s.rows.first()) 4 else 2)
                if (firstFocus == null && r.enabled) firstFocus = v
            }
            first = false
        }
        scroll.viewport.viewPosition = java.awt.Point(0, 0)
        revalidate()
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        Glass.paint(g as Graphics2D, SHADOW.toFloat(), SHADOW.toFloat(), (width - 2 * SHADOW).toFloat(), (height - 2 * SHADOW).toFloat(),
            22f, Pal.panel, 0.94f, 0.18f, 0.24f, 26f)
    }

    private inner class Holder : JPanel(null), Scrollable {
        init {
            isOpaque = false
            add(body)
        }

        override fun doLayout() {
            body.setBounds(0, 0, width, heightOf(body, width))
        }

        override fun getPreferredSize(): Dimension {
            val vw = (parent as? JViewport)?.width ?: width
            return Dimension(vw, heightOf(body, max(1, vw)))
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = 24
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = max(40, r.height - 50)
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }
}
