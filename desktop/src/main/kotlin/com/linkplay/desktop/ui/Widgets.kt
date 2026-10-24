package com.linkplay.desktop.ui

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JScrollBar
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.plaf.basic.BasicScrollBarUI
import kotlin.math.max
import kotlin.math.min

enum class BtnKind { PRIMARY, CONTROL, DANGER, TEXT }

/** A button: the accent one (one per screen), a glass one, a danger one (red label) or a quiet text one. */
class GlassButton(text: String, val kind: BtnKind = BtnKind.CONTROL, var onClick: () -> Unit = {}) : Comp() {
    var text: String = text
        set(v) { field = v; revalidate(); repaint(); getAccessibleContext().accessibleName = v }

    private var hover = false
    private var pressed = false
    private var viaMouse = false // focus came from a click: no ring then

    init {
        isOpaque = false
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        getAccessibleContext().accessibleName = text
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; pressed = false; repaint() }
            override fun mousePressed(e: MouseEvent) {
                if (!isEnabled || e.button != MouseEvent.BUTTON1) return
                pressed = true
                viaMouse = true
                requestFocusInWindow()
                repaint()
            }
            override fun mouseReleased(e: MouseEvent) {
                val was = pressed
                pressed = false
                repaint()
                if (was && isEnabled && contains(e.point)) fire()
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (isEnabled && (e.keyCode == KeyEvent.VK_SPACE || e.keyCode == KeyEvent.VK_ENTER)) { pressed = true; repaint(); e.consume() }
            }
            override fun keyReleased(e: KeyEvent) {
                if (pressed && (e.keyCode == KeyEvent.VK_SPACE || e.keyCode == KeyEvent.VK_ENTER)) { pressed = false; repaint(); fire(); e.consume() }
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { repaint() }
            override fun focusLost(e: FocusEvent) { viaMouse = false; pressed = false; repaint() }
        })
    }

    private fun fire() = onClick()

    private fun font() = Fonts.medium(14.5f)

    override fun setEnabled(b: Boolean) {
        super.setEnabled(b)
        cursor = if (b) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val h = if (kind == BtnKind.TEXT) 34 else 44
        val w = Math.ceil(Fonts.width(font(), text).toDouble()).toInt() + 40
        return Dimension(max(w, if (kind == BtnKind.TEXT) 0 else 96), h)
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.quality()
        val w = width.toFloat()
        val h = height.toFloat()
        val on = isEnabled
        when (kind) {
            BtnKind.PRIMARY -> {
                val base = when {
                    pressed -> Pal.mix(Pal.accentFill, Color.BLACK, 0.12f)
                    hover -> Pal.mix(Pal.accentFill, Color.WHITE, 0.10f)
                    else -> Pal.accentFill
                }
                Glass.paint(g, 0f, 0f, w, h, 12f, base, if (on) 1f else 0.35f, if (pressed) 0.45f else if (on) 0.22f else 0.10f,
                    if (pressed) 0.45f else if (on) 0.30f else 0.10f, 0f, false)
            }
            BtnKind.CONTROL, BtnKind.DANGER -> when {
                !on -> Glass.paint(g, 0f, 0f, w, h, 12f, Pal.panel, 0.35f, 0.10f, 0.12f, 0f, false)
                pressed -> Glass.paint(g, 0f, 0f, w, h, 12f, Pal.accentFill, 0.92f, 0.18f, 0.22f, 0f, false)
                hover -> Glass.paint(g, 0f, 0f, w, h, 12f, Pal.panel, 0.82f, 0.30f, 0.26f, 0f, false)
                else -> Glass.paint(g, 0f, 0f, w, h, 12f, Pal.panel, 0.60f, 0.18f, 0.22f, 0f, false)
            }
            BtnKind.TEXT -> if (on && (hover || pressed)) {
                g.color = Pal.alpha(Pal.white, if (pressed) 0.16f else 0.08f)
                g.fillRound(0f, 0f, w, h, 10f)
            }
        }
        val label = when (kind) {
            BtnKind.PRIMARY -> Pal.white
            BtnKind.CONTROL -> Pal.ink
            BtnKind.DANGER -> Pal.bad
            BtnKind.TEXT -> Pal.accentText
        }
        g.font = font()
        g.color = if (on) label else Pal.alpha(label, 0.45f)
        val lm = g.font.getLineMetrics(text, Fonts.frc)
        val tw = Fonts.width(g.font, text)
        val shown = if (tw > w - 16) TextBlock.ellipsize(text, g.font, w - 16) else text
        g.drawString(shown, (w - Fonts.width(g.font, shown)) / 2f, (h - (lm.ascent + lm.descent)) / 2f + lm.ascent)
        if (isFocusOwner && !viaMouse) {
            g.stroke = BasicStroke(2f)
            g.color = Pal.alpha(Pal.accentText, 0.9f)
            g.draw(RoundRectangle2D.Float(1.5f, 1.5f, w - 3f, h - 3f, 22f, 22f))
        }
        g.dispose()
    }
}

/** A square glass button with a drawn icon (the menu's three dots, a back arrow, a close cross). */
class IconButton(private val icon: (Graphics2D, Float, Float, Float, Color) -> Unit, label: String, var onClick: () -> Unit = {}) : Comp() {
    private var hover = false
    private var pressed = false
    private var viaMouse = false
    var box = 40

    init {
        isOpaque = false
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        getAccessibleContext().accessibleName = label
        toolTipText = null
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; pressed = false; repaint() }
            override fun mousePressed(e: MouseEvent) { pressed = true; viaMouse = true; requestFocusInWindow(); repaint() }
            override fun mouseReleased(e: MouseEvent) {
                val was = pressed
                pressed = false
                repaint()
                if (was && contains(e.point)) onClick()
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_SPACE || e.keyCode == KeyEvent.VK_ENTER) { onClick(); e.consume() }
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { repaint() }
            override fun focusLost(e: FocusEvent) { viaMouse = false; repaint() }
        })
    }

    override fun getPreferredSize() = Dimension(box, box)

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.quality()
        val w = width.toFloat()
        val h = height.toFloat()
        if (pressed) Glass.paint(g, 0f, 0f, w, h, 12f, Pal.accentFill, 0.92f, 0.18f, 0.22f, 0f, false)
        else Glass.paint(g, 0f, 0f, w, h, 12f, Pal.panel, if (hover) 0.82f else 0.62f, if (hover) 0.30f else 0.18f, 0.22f, 0f, false)
        icon(g, w / 2f, h / 2f, 18f, Pal.ink)
        if (isFocusOwner && !viaMouse) {
            g.stroke = BasicStroke(2f)
            g.color = Pal.alpha(Pal.accentText, 0.9f)
            g.draw(RoundRectangle2D.Float(1.5f, 1.5f, w - 3f, h - 3f, 22f, 22f))
        }
        g.dispose()
    }
}

/** A Host / Join tile: an icon, a title and a caption on glass, accent when pressed. */
class Tile(
    private val icon: (Graphics2D, Float, Float, Float, Color) -> Unit,
    title: String,
    caption: String,
    var onClick: () -> Unit = {},
) : Comp() {
    var title = title
        set(v) { field = v; repaint() }
    var caption = caption
        set(v) { field = v; repaint() }

    private var hover = false
    private var pressed = false
    private var viaMouse = false

    init {
        isOpaque = false
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        getAccessibleContext().accessibleName = "$title. $caption"
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; pressed = false; repaint() }
            override fun mousePressed(e: MouseEvent) {
                if (!isEnabled) return
                pressed = true; viaMouse = true; requestFocusInWindow(); repaint()
            }
            override fun mouseReleased(e: MouseEvent) {
                val was = pressed
                pressed = false
                repaint()
                if (was && isEnabled && contains(e.point)) onClick()
            }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                if (isEnabled && (e.keyCode == KeyEvent.VK_SPACE || e.keyCode == KeyEvent.VK_ENTER)) { onClick(); e.consume() }
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { repaint() }
            override fun focusLost(e: FocusEvent) { viaMouse = false; repaint() }
        })
    }

    override fun getPreferredSize() = Dimension(160, 110)

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.quality()
        val w = width.toFloat()
        val h = height.toFloat()
        val on = isEnabled
        when {
            !on -> Glass.paint(g, 0f, 0f, w, h, 18f, Pal.panel, 0.40f, 0.10f, 0.12f)
            pressed -> Glass.paint(g, 0f, 0f, w, h, 18f, Pal.accentFill, 0.92f, 0.18f, 0.22f)
            hover -> Glass.paint(g, 0f, 0f, w, h, 18f, Pal.mix(Pal.panel, Pal.accentFill, 0.18f), 0.85f, 0.28f, 0.26f)
            else -> Glass.paint(g, 0f, 0f, w, h, 18f, Pal.panel, 0.72f, 0.16f, 0.22f)
        }
        val a = if (on) 1f else 0.45f
        icon(g, 16f + 11f, 16f + 12f, 22f, Pal.alpha(Pal.accentText, a))
        g.drawText(title, 16f, 50f, Fonts.bold(16f), Pal.alpha(Pal.ink, a))
        g.drawText(caption, 16f, 74f, Fonts.regular(13f), Pal.alpha(Pal.muted, a))
        if (isFocusOwner && !viaMouse) {
            g.stroke = BasicStroke(2f)
            g.color = Pal.alpha(Pal.accentText, 0.9f)
            g.draw(RoundRectangle2D.Float(1.5f, 1.5f, w - 3f, h - 3f, 34f, 34f))
        }
        g.dispose()
    }
}

/** A glass card holding a column. */
open class Card(
    private val radius: Float = 18f,
    pad: Insets = Insets(20, 20, 20, 20),
    gap: Int = 12,
    private val fill: Float = 0.72f,
) : VBox(gap, pad) {
    override fun paintComponent(g: Graphics) {
        Glass.paint(g as Graphics2D, 0f, 0f, width.toFloat(), height.toFloat(), radius, Pal.panel, fill, 0.16f, 0.22f)
    }
}

/** A tick box with wrapped text beside it. */
class CheckRow(text: String, checked: Boolean, private val onChange: (Boolean) -> Unit) : JPanel(null), Flow {
    var checked: Boolean = checked
        set(v) { field = v; repaint() }

    private val label = TextBlock(text, Fonts.regular(14f), Pal.ink)
    private var hover = false
    private var viaMouse = false

    init {
        isOpaque = false
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        getAccessibleContext().accessibleName = text
        add(label)
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            override fun mousePressed(e: MouseEvent) { viaMouse = true; requestFocusInWindow() }
            override fun mouseClicked(e: MouseEvent) { toggle() }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_SPACE) { toggle(); e.consume() }
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { repaint() }
            override fun focusLost(e: FocusEvent) { viaMouse = false; repaint() }
        })
    }

    private fun toggle() {
        checked = !checked
        onChange(checked)
    }

    override fun heightFor(width: Int): Int = max(24, label.heightFor(max(1, width - 34))) + 4

    override fun doLayout() {
        val lw = max(1, width - 34)
        val lh = label.heightFor(lw)
        label.setBounds(34, (height - lh) / 2, lw, lh)
    }

    override fun getPreferredSize() = Dimension(300, heightFor(if (width > 0) width else 300))

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.quality()
        val y = (height - 20) / 2f
        if (checked) {
            g.color = Pal.accentFill
            g.fillRound(1f, y, 20f, 20f, 6f)
            Icons.check(g, 11f, y + 10f, 10f, Pal.white, 2f)
        } else {
            g.color = Pal.alpha(Pal.white, if (hover) 0.10f else 0.04f)
            g.fillRound(1f, y, 20f, 20f, 6f)
            g.color = Pal.alpha(Pal.white, 0.45f)
            g.stroke = BasicStroke(1.2f)
            g.draw(RoundRectangle2D.Float(1.6f, y + 0.6f, 18.8f, 18.8f, 12f, 12f))
        }
        if (isFocusOwner && !viaMouse) {
            g.stroke = BasicStroke(2f)
            g.color = Pal.alpha(Pal.accentText, 0.9f)
            g.draw(RoundRectangle2D.Float(-1f, y - 2f, 26f, 24f, 14f, 14f))
        }
        g.dispose()
    }
}

/** A one-line text field with no frame of its own (it sits in a card), and a hint while empty. */
class StyledField(private val hint: String) : JTextField() {
    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(0, 2, 0, 2)
        font = Fonts.regular(15f)
        foreground = Pal.ink
        caretColor = Pal.ink
        selectionColor = Pal.accentFill
        selectedTextColor = Pal.white
        disabledTextColor = Pal.faint
        getAccessibleContext().accessibleName = hint
    }

    override fun getPreferredSize() = Dimension(160, 40)

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (text.isEmpty()) {
            val g2 = g.create() as Graphics2D
            g2.quality()
            g2.font = font
            g2.color = Pal.faint
            val ins = insets
            val lm = font.getLineMetrics(hint, Fonts.frc)
            g2.drawString(hint, ins.left.toFloat(), (height - (lm.ascent + lm.descent)) / 2f + lm.ascent)
            g2.dispose()
        }
    }
}

/** The "Name" row of the lobby: a label, the field, a pencil. */
class NameRow(val field: StyledField) : JPanel(BorderLayout(12, 0)), Flow {
    init {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(0, 18, 0, 16)
        val label = JPanel(java.awt.GridBagLayout())
        label.isOpaque = false
        label.add(TextBlock("Name", Fonts.medium(14f), Pal.muted).also { it.preferredSize = Dimension(44, 20) })
        add(label, BorderLayout.WEST)
        add(field, BorderLayout.CENTER)
        add(object : JComponent() {
            override fun getPreferredSize() = Dimension(18, 18)
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.quality()
                Icons.pencil(g2, width / 2f, height / 2f, 15f, Pal.faint)
                g2.dispose()
            }
        }, BorderLayout.EAST)
    }

    override fun heightFor(width: Int) = 56
    override fun getPreferredSize() = Dimension(300, 56)

    override fun paintComponent(g: Graphics) {
        Glass.paint(g as Graphics2D, 0f, 0f, width.toFloat(), height.toFloat(), 18f, Pal.panel, 0.72f, 0.16f, 0.22f)
    }
}

/** A selectable, wrapping box for an invite or reply code (or, while there is none yet, a note saying why). */
class CodeBox : JPanel(null), Flow {
    val area = JTextArea()
    private val padding = 14

    init {
        isOpaque = false
        area.isEditable = false
        area.lineWrap = true
        area.wrapStyleWord = false
        area.isOpaque = false
        area.border = null
        area.font = Fonts.mono(14f)
        area.foreground = Pal.ink
        area.selectionColor = Pal.accentFill
        area.selectedTextColor = Pal.white
        area.caret.isVisible = false
        area.cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        area.getAccessibleContext().accessibleName = "Code"
        add(area)
        // One click selects all of it: nobody needs half a code.
        area.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) { if (area.font.family != Fonts.regular(14f).family) area.selectAll() }
        })
    }

    private var isCode = true

    /** Shows [text] as a code ([ready]) or as a quiet note. */
    fun show(text: String, ready: Boolean) {
        if (area.text == text && isCode == ready) return
        isCode = ready
        area.font = if (ready) Fonts.mono(14f) else Fonts.regular(14f)
        area.foreground = if (ready) Pal.ink else Pal.muted
        area.text = text
        area.caretPosition = 0
        revalidate()
        repaint()
    }

    override fun heightFor(width: Int): Int {
        val w = max(40, width - 2 * padding)
        area.setSize(w, Short.MAX_VALUE.toInt())
        return max(56, area.preferredSize.height + 2 * padding)
    }

    override fun doLayout() {
        area.setBounds(padding, padding, max(1, width - 2 * padding), max(1, height - 2 * padding))
    }

    override fun getPreferredSize() = Dimension(300, heightFor(if (width > 0) width else 300))

    override fun paintComponent(g: Graphics) {
        Glass.paint(g as Graphics2D, 0f, 0f, width.toFloat(), height.toFloat(), 14f, Pal.panel, 0.55f, 0.12f, 0f, 0f, false)
    }
}

/** A thumb-only scroll bar that floats over the content. */
class ThinScrollBarUI : BasicScrollBarUI() {
    private fun none(): JButton = JButton().apply { preferredSize = Dimension(0, 0); isOpaque = false; border = null }

    override fun createDecreaseButton(orientation: Int) = none()
    override fun createIncreaseButton(orientation: Int) = none()
    override fun configureScrollBarColors() {}
    override fun paintTrack(g: Graphics, c: JComponent, r: java.awt.Rectangle) {}
    override fun getPreferredSize(c: JComponent) = Dimension(12, 12)

    override fun paintThumb(g0: Graphics, c: JComponent, r: java.awt.Rectangle) {
        if (r.isEmpty || !scrollbar.isEnabled) return
        val g = g0.create() as Graphics2D
        g.quality()
        g.color = Pal.alpha(Pal.white, if (isThumbRollover || isDragging) 0.38f else 0.22f)
        g.fillRound(r.x + 3f, r.y + 2f, r.width - 6f, r.height - 4f, (r.width - 6f) / 2f)
        g.dispose()
    }
}

/** A horizontal slider, 0..100. */
class SliderBar(initial: Int, private val onChange: (Int) -> Unit) : Comp() {
    var value: Int = initial
        set(v) { field = v.coerceIn(0, 100); repaint() }

    private var viaMouse = false

    init {
        isOpaque = false
        isFocusable = true
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        getAccessibleContext().accessibleName = "Volume"
        val set = { e: MouseEvent ->
            val v = Math.round((e.x - PAD) / max(1f, (width - 2 * PAD).toFloat()) * 100f).coerceIn(0, 100)
            if (v != value) { value = v; onChange(v) }
        }
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { viaMouse = true; requestFocusInWindow(); set(e) }
        })
        addMouseMotionListener(object : MouseAdapter() {
            override fun mouseDragged(e: MouseEvent) { set(e) }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val step = when (e.keyCode) {
                    KeyEvent.VK_LEFT, KeyEvent.VK_DOWN -> -5
                    KeyEvent.VK_RIGHT, KeyEvent.VK_UP -> 5
                    else -> return
                }
                value += step
                onChange(value)
                e.consume()
            }
        })
        addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { repaint() }
            override fun focusLost(e: FocusEvent) { viaMouse = false; repaint() }
        })
    }

    override fun getPreferredSize() = Dimension(200, 36)

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.quality()
        val cy = height / 2f
        val x0 = PAD.toFloat()
        val x1 = (width - PAD).toFloat()
        g.color = Pal.alpha(Pal.white, 0.16f)
        g.fillRound(x0, cy - 3f, x1 - x0, 6f, 3f)
        val fx = x0 + (x1 - x0) * value / 100f
        g.color = Pal.accent
        g.fillRound(x0, cy - 3f, fx - x0, 6f, 3f)
        g.color = Color(0, 0, 0, 70)
        g.fill(Ellipse2D.Float(fx - 10f, cy - 8f, 20f, 20f))
        g.color = Pal.accentText
        g.fill(Ellipse2D.Float(fx - 9f, cy - 9f, 18f, 18f))
        if (isFocusOwner && !viaMouse) {
            g.stroke = BasicStroke(2f)
            g.color = Pal.alpha(Pal.accentText, 0.9f)
            g.draw(Ellipse2D.Float(fx - 13f, cy - 13f, 26f, 26f))
        }
        g.dispose()
    }

    private companion object { const val PAD = 14 }
}

/** The app's signature: two player chips joined by a cable line, dashed while connecting, solid once linked. */
class CableView : Comp() {
    private var left = ""
    private var right: String? = null
    private var linked = false
    private var progress = 0f // 0 = connecting, 1 = linked
    private var timer: Timer? = null
    private var fromP = 0f
    private var startMs = 0L

    init {
        isOpaque = false
        getAccessibleContext().accessibleName = "Link"
    }

    /** [rightLabel] null: nobody on the other end yet. */
    fun set(leftLabel: String, rightLabel: String?, isLinked: Boolean, animate: Boolean = true) {
        val changed = leftLabel != left || rightLabel != right
        left = leftLabel
        right = rightLabel
        getAccessibleContext().accessibleDescription = "$leftLabel, ${rightLabel ?: "waiting for a player"}, " + if (isLinked) "linked" else "not linked yet"
        if (isLinked != linked) {
            linked = isLinked
            timer?.stop()
            val target = if (isLinked) 1f else 0f
            if (!animate || !isShowing) {
                progress = target
                repaint()
            } else {
                fromP = progress
                startMs = System.currentTimeMillis()
                timer = Timer(15) {
                    val t = ((System.currentTimeMillis() - startMs) / 300f).coerceIn(0f, 1f)
                    progress = fromP + (target - fromP) * t
                    repaint()
                    if (t >= 1f) timer?.stop()
                }.also { it.start() }
            }
        } else if (changed) repaint()
    }

    override fun removeNotify() {
        timer?.stop()
        super.removeNotify()
    }

    override fun getPreferredSize() = Dimension(300, 60)

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.quality()
        val font = Fonts.medium(14.5f)
        val h = 40f
        val top = (height - h) / 2f
        val pad = 16f
        val maxW = width * 0.45f
        val l = TextBlock.ellipsize(left, font, maxW - pad * 2)
        val lw = Fonts.width(font, l) + pad * 2
        val rt = TextBlock.ellipsize(right ?: "P2 waiting…", font, maxW - pad * 2)
        val rw = Fonts.width(font, rt) + pad * 2
        val lx = 0f
        val rx = width - rw
        val midY = height / 2f
        val gap = 6f
        if (rx - (lx + lw) > 2 * gap) {
            g.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, floatArrayOf(6f, 7f), 0f)
            g.color = Pal.alpha(Pal.muted, (1f - progress) * 0.9f)
            if (progress < 1f) g.draw(Line2D.Float(lx + lw + gap, midY, rx - gap, midY))
            g.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.color = Pal.alpha(Pal.accent, progress)
            if (progress > 0f) g.draw(Line2D.Float(lx + lw + gap, midY, rx - gap, midY))
        }
        chip(g, lx, top, lw, h, l, true, font)
        chip(g, rx, top, rw, h, rt, right != null, font)
        g.dispose()
    }

    private fun chip(g: Graphics2D, x: Float, y: Float, w: Float, h: Float, label: String, present: Boolean, font: java.awt.Font) {
        g.color = Pal.alpha(Pal.panel, 0.7f)
        g.fillRound(x, y, w, h, h / 2)
        val live = present && progress > 0.5f
        g.color = if (live) Pal.accent else Pal.alpha(Pal.white, if (present) 0.45f else 0.22f)
        g.stroke = BasicStroke(if (live) 1.5f else 1f)
        g.draw(RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1f, h - 1f, h, h))
        g.font = font
        g.color = if (present) Pal.ink else Pal.muted
        val lm = font.getLineMetrics(label, Fonts.frc)
        g.drawString(label, x + 16f, y + (h - (lm.ascent + lm.descent)) / 2f + lm.ascent)
    }
}

/** The small "ROM name ✓" pill at the top of the lobby. */
class RomChip : JComponent() {
    var text = ""
        set(v) { field = v; revalidate(); repaint() }

    private val font = Fonts.medium(13.5f)

    init { isOpaque = false }

    private fun shown() = TextBlock.ellipsize(text, font, 200f)

    override fun getPreferredSize(): Dimension {
        val w = Math.ceil(Fonts.width(font, shown()).toDouble()).toInt() + 14 + 34
        return Dimension(w, 32)
    }

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.quality()
        Glass.paint(g, 0f, 0f, width.toFloat(), height.toFloat(), 16f, Pal.panel, 0.72f, 0.16f, 0.22f)
        val s = shown()
        g.font = font
        g.color = Pal.ink
        val lm = font.getLineMetrics(s, Fonts.frc)
        g.drawString(s, 14f, (height - (lm.ascent + lm.descent)) / 2f + lm.ascent)
        Icons.check(g, width - 19f, height / 2f, 10f, Pal.accentText, 2f)
        g.dispose()
    }
}

/** A short message at the bottom of the window that fades by itself. Never takes the mouse or the keyboard. */
class ToastLayer : JComponent() {
    private var text = ""
    private var shownAt = 0L
    private var lengthMs = 3000
    private var alpha = 0f
    private val timer = Timer(30) { tick() }

    /** Extra room kept under the toast (the window's own bottom bar, if any). */
    var bottom = 28

    init { isOpaque = false }

    override fun contains(x: Int, y: Int) = false

    fun show(message: String, long: Boolean = false) {
        text = message
        shownAt = System.currentTimeMillis()
        lengthMs = if (long) 5200 else 2800
        alpha = 0.01f
        if (!timer.isRunning) timer.start()
        repaint()
    }

    private fun tick() {
        val age = System.currentTimeMillis() - shownAt
        alpha = when {
            age < 160 -> age / 160f
            age > lengthMs -> 1f - (age - lengthMs) / 320f
            else -> 1f
        }
        if (age > lengthMs + 320) {
            alpha = 0f
            text = ""
            timer.stop()
        }
        repaint()
    }

    /** For snapshots: shows the toast fully, with no timer. */
    fun showStatic(message: String) {
        timer.stop()
        text = message
        alpha = 1f
        repaint()
    }

    override fun paintComponent(g0: Graphics) {
        if (text.isEmpty() || alpha <= 0f) return
        val g = g0.create() as Graphics2D
        g.quality()
        val font = Fonts.regular(14f)
        val maxW = min(620, width - 64)
        val lines = TextBlock.wrap(text, font, maxW - 40)
        val pitch = 20
        val tw = lines.maxOf { Fonts.width(font, it) }
        val w = Math.ceil(tw.toDouble()).toInt() + 40
        val h = lines.size * pitch + 22
        val x = (width - w) / 2f
        val y = height - h - bottom.toFloat() - (1f - alpha) * -8f
        g.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, alpha.coerceIn(0f, 1f))
        Glass.paint(g, x, y, w.toFloat(), h.toFloat(), 16f, Pal.panel, 0.94f, 0.22f, 0.26f, 18f)
        g.font = font
        g.color = Pal.ink
        val lm = font.getLineMetrics("Ag", Fonts.frc)
        for ((i, line) in lines.withIndex()) {
            g.drawString(line, (width - Fonts.width(font, line)) / 2f, y + 11 + i * pitch + (pitch - (lm.ascent + lm.descent)) / 2f + lm.ascent)
        }
        g.dispose()
    }
}

/** A centred, wrapped line, e.g. the status under a card. */
fun caption(text: String, size: Float = 13.5f, color: Color = Pal.muted, align: Int = SwingConstants.LEFT) =
    TextBlock(text, Fonts.regular(size), color, align = align)

fun heading(text: String) = TextBlock(text, Fonts.medium(13.5f), Pal.muted)
