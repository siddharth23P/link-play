package com.linkplay.desktop.ui

import com.linkplay.desktop.Button as Pad
import com.linkplay.desktop.Settings
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.KeyboardFocusManager
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.max
import kotlin.math.min

/** The system clipboard, read only when the user asks (a click on Paste) and never trusted to be there. */
object Clip {
    fun text(): String? = try {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    } catch (e: Exception) {
        null
    }

    fun copy(s: String): Boolean = try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(s), null)
        true
    } catch (e: Exception) {
        false
    }
}

/** What the overlay shows: a card in the middle (dialogs), or a panel on the right (the game menu). */
class Modal(
    val content: JComponent,
    val side: Boolean = false,
    /** Visible width of the card or panel. */
    val width: Int = 420,
    /** Blur what is behind (dialogs), or only dim it (the menu, so the picture stays in view). */
    val frosted: Boolean = true,
    val scrim: Float = 0.45f,
    val focus: JComponent? = null,
    /** Escape or a click outside. */
    var onCancel: () -> Unit = {},
    /** Return, when no button has the focus. */
    var defaultAction: (() -> Unit)? = null,
    val cancelable: Boolean = true,
)

/** Room around a card for its shadow. */
const val SHADOW = 36

/**
 * The layer above the screens that holds one dialog or menu at a time: it dims (or blurs) what is
 * under it, keeps the mouse from reaching it, and takes Escape and Return. [onChanged] tells the app
 * that something opened or closed (a running game pauses behind it).
 */
class Overlay(private val below: JComponent) : JPanel(null) {
    var current: Modal? = null
        private set
    var onChanged: () -> Unit = {}
    var restoreFocus: () -> Unit = {}
    /** Space at the top of the window that a side panel keeps clear. */
    var topInset = 0

    private var frosted: BufferedImage? = null
    private var appear = 1f
    private var timer: Timer? = null
    private var lastFocus: Component? = null

    val isOpen get() = current != null

    init {
        isOpaque = false
        isVisible = false
        // Tab stays inside the open dialog: the controls behind the scrim are not reachable by keyboard.
        isFocusCycleRoot = true
        isFocusTraversalPolicyProvider = true
        val swallow = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                val m = current ?: return
                if (m.cancelable && !m.content.bounds.contains(e.point)) m.onCancel()
            }
        }
        addMouseListener(swallow)
        addMouseMotionListener(object : MouseAdapter() {})
        addMouseWheelListener { }
        registerKeyboardAction({ current?.let { if (it.cancelable) it.onCancel() } }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), WHEN_IN_FOCUSED_WINDOW)
        registerKeyboardAction({ current?.defaultAction?.invoke() }, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), WHEN_IN_FOCUSED_WINDOW)
    }

    fun show(m: Modal) {
        val first = current == null
        if (first) lastFocus = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        current = m
        removeAll()
        add(m.content)
        frosted = if (m.frosted) (if (first || frosted == null) capture() else frosted) else null
        isVisible = true
        if (first && !isShowing) appear = 1f // nobody is watching (a screenshot): no fade
        if (first && isShowing) {
            appear = 0f
            val start = System.currentTimeMillis()
            timer?.stop()
            timer = Timer(15) {
                appear = ((System.currentTimeMillis() - start) / 140f).coerceIn(0f, 1f)
                repaint()
                if (appear >= 1f) timer?.stop()
            }.also { it.start() }
        }
        revalidate()
        repaint()
        val f = m.focus
        if (f != null) SwingUtilities.invokeLater { f.requestFocusInWindow() }
        onChanged()
    }

    /** Closes [m], or whatever is open when [m] is null. A modal that was already replaced is left alone. */
    fun dismiss(m: Modal? = null) {
        val cur = current ?: return
        if (m != null && m !== cur) return
        current = null
        timer?.stop()
        removeAll()
        isVisible = false
        frosted = null
        repaint()
        onChanged()
        val f = lastFocus
        lastFocus = null
        SwingUtilities.invokeLater {
            if (f != null && f.isShowing && f.isFocusable) f.requestFocusInWindow() else restoreFocus()
        }
    }

    /** What is under the overlay, shrunk and blurred, for the frosted look. */
    private fun capture(): BufferedImage? {
        val w = below.width
        val h = below.height
        if (w <= 0 || h <= 0) return null
        val s = 6
        val small = BufferedImage(max(1, w / s), max(1, h / s), BufferedImage.TYPE_INT_RGB)
        val g = small.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.scale(small.width.toDouble() / w, small.height.toDouble() / h)
        below.paint(g)
        g.dispose()
        return blurred(small, 2, 2)
    }

    override fun doLayout() {
        val m = current ?: return
        val c = m.content
        if (m.side) {
            val w = min(m.width, width - 24) + 2 * SHADOW
            c.setBounds(width - w + SHADOW - 12, topInset + 12 - SHADOW, w, max(100, height - topInset - 24 + 2 * SHADOW))
        } else {
            val w = min(m.width, width - 48) + 2 * SHADOW
            val room = max(120, height - 48 + 2 * SHADOW)
            val h = min(room, heightOf(c, w))
            c.setBounds((width - w) / 2, max(0, (height - h) / 2 - 14), w, h)
        }
    }

    override fun paintComponent(g0: Graphics) {
        val m = current ?: return
        val g = g0.create() as Graphics2D
        frosted?.let {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.composite = AlphaComposite.SrcOver.derive(appear)
            g.drawImage(it, 0, 0, width, height, null)
            g.composite = AlphaComposite.SrcOver
        }
        g.color = Color(0, 0, 0, (m.scrim * appear * 255).toInt())
        g.fillRect(0, 0, width, height)
        g.dispose()
    }

    companion object {
        fun blurred(src: BufferedImage, radius: Int, passes: Int): BufferedImage {
            val w = src.width
            val h = src.height
            var px = src.getRGB(0, 0, w, h, null, 0, w)
            repeat(passes) {
                for (horizontal in booleanArrayOf(true, false)) {
                    val out = IntArray(w * h)
                    for (y in 0 until h) for (x in 0 until w) {
                        var r = 0; var gr = 0; var b = 0; var n = 0
                        for (d in -radius..radius) {
                            val sx = if (horizontal) (x + d).coerceIn(0, w - 1) else x
                            val sy = if (horizontal) y else (y + d).coerceIn(0, h - 1)
                            val c = px[sy * w + sx]
                            r += (c shr 16) and 0xFF; gr += (c shr 8) and 0xFF; b += c and 0xFF; n++
                        }
                        out[y * w + x] = (r / n shl 16) or (gr / n shl 8) or (b / n)
                    }
                    px = out
                }
            }
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, w, h, px, 0, w)
            return img
        }
    }
}

/** A card with room for its shadow: the glass is painted [SHADOW] in from the component's edge. */
open class ShadowCard(private val radius: Float = 24f, inner: Insets, gap: Int = 8, private val fill: Float = 0.92f) :
    VBox(gap, Insets(SHADOW + inner.top, SHADOW + inner.left, SHADOW + inner.bottom, SHADOW + inner.right)) {
    override fun paintComponent(g: Graphics) {
        Glass.paint(g as Graphics2D, SHADOW.toFloat(), SHADOW.toFloat(), (width - 2 * SHADOW).toFloat(), (height - 2 * SHADOW).toFloat(),
            radius, Pal.panel, fill, 0.18f, 0.24f, 28f)
    }

    init {
        // A click on the card itself must not close the dialog.
        addMouseListener(object : MouseAdapter() {})
    }
}

enum class DKind { PRIMARY, DANGER, SECONDARY, NEUTRAL }

class DialogButton(val label: String, val kind: DKind, val dismiss: Boolean = true, val onClick: () -> Unit = {})

/** Builds the app's dialogs on an [Overlay]. */
class Dialogs(private val overlay: Overlay, private val toast: (String) -> Unit) {
    private fun card(title: String?, message: String?, body: JComponent?, buttons: List<DialogButton>, modalRef: Array<Modal?>): Pair<ShadowCard, JComponent?> {
        val c = ShadowCard(24f, Insets(24, 24, 20, 24), 10)
        // What a screen reader announces when the dialog opens.
        c.accessibleContext.accessibleName = title ?: ""
        c.accessibleContext.accessibleDescription = message ?: ""
        title?.let { c.put(TextBlock(it, Fonts.bold(19f), Pal.ink)) }
        message?.let { c.put(TextBlock(it, Fonts.regular(14.5f), Pal.muted, spacing = 1.42f), 8) }
        body?.let { c.put(it, 14) }
        var focus: JComponent? = null
        if (buttons.isNotEmpty()) {
            val stacked = buttons.size > 2
            val ordered = if (stacked) buttons.sortedBy { if (it.kind == DKind.PRIMARY || it.kind == DKind.DANGER) 0 else if (it.kind == DKind.NEUTRAL) 1 else 2 } else buttons
            val views = ordered.map { b ->
                val kind = when (b.kind) {
                    DKind.PRIMARY -> BtnKind.PRIMARY
                    DKind.DANGER -> BtnKind.DANGER
                    DKind.SECONDARY -> BtnKind.CONTROL
                    DKind.NEUTRAL -> BtnKind.TEXT
                }
                GlassButton(b.label, kind) {
                    if (b.dismiss) overlay.dismiss(modalRef[0])
                    b.onClick()
                }
            }
            focus = views.firstOrNull { it.kind == BtnKind.PRIMARY } ?: views.firstOrNull { it.kind == BtnKind.CONTROL } ?: views.firstOrNull()
            if (stacked) {
                for ((i, v) in views.withIndex()) c.put(v, if (i == 0) 20 else 8)
            } else {
                val row = HBox(12)
                for (v in views) row.put(v)
                c.put(row, 20)
            }
        }
        return c to focus
    }

    /** A dialog with [buttons]; Return runs the primary one. */
    fun custom(title: String?, message: String?, buttons: List<DialogButton>, body: JComponent? = null, width: Int = 420, focusBody: JComponent? = null): Modal {
        val ref = arrayOfNulls<Modal>(1)
        val (c, focus) = card(title, message, body, buttons, ref)
        val primary = buttons.firstOrNull { it.kind == DKind.PRIMARY }
        val cancel = buttons.firstOrNull { it.kind == DKind.SECONDARY }
        val m = Modal(c, width = width, focus = focusBody ?: focus, onCancel = {
            overlay.dismiss(ref[0])
            cancel?.onClick?.invoke()
        }, defaultAction = primary?.let { p -> { if (p.dismiss) overlay.dismiss(ref[0]); p.onClick() } })
        ref[0] = m
        overlay.show(m)
        return m
    }

    /** Cancel + an action; [neutral] is a third, quiet choice. */
    fun confirm(
        title: String, message: String, action: String, destructive: Boolean = false,
        neutral: Pair<String, () -> Unit>? = null, cancel: String = "Cancel", onConfirm: () -> Unit,
    ): Modal {
        val buttons = mutableListOf(
            DialogButton(cancel, DKind.SECONDARY),
            DialogButton(action, if (destructive) DKind.DANGER else DKind.PRIMARY, true, onConfirm),
        )
        neutral?.let { buttons += DialogButton(it.first, DKind.NEUTRAL, true, it.second) }
        return custom(title, message, buttons)
    }

    /** A message with one OK (or [ok] plus a retry). */
    fun alert(title: String, message: String, ok: String = "OK", retry: Pair<String, () -> Unit>? = null, neutral: Pair<String, () -> Unit>? = null): Modal {
        val buttons = mutableListOf<DialogButton>()
        if (retry != null) {
            buttons += DialogButton(ok, DKind.SECONDARY)
            buttons += DialogButton(retry.first, DKind.PRIMARY, true, retry.second)
        } else {
            buttons += DialogButton(ok, DKind.PRIMARY)
        }
        neutral?.let { buttons += DialogButton(it.first, DKind.NEUTRAL, true, it.second) }
        return custom(title, message, buttons)
    }

    /**
     * A dialog to paste a code into. [onCode] gets the text and returns null when it is done, or a problem to show
     * (the dialog then stays open). The clipboard is read only when Paste is clicked.
     */
    fun code(title: String, intro: String, hint: String, action: String, problem: String?, onCode: (String) -> String?): Modal {
        val input = CodeInput(hint)
        val error = TextBlock(problem ?: "", Fonts.regular(13f), Pal.bad)
        error.isVisible = problem != null
        val paste = GlassButton("Paste", BtnKind.CONTROL) {
            val t = Clip.text()
            if (t.isNullOrBlank()) toast("Nothing to paste: copy the message first.") else input.area.text = t
        }
        val body = VBox(10)
        body.put(TextBlock(intro, Fonts.regular(13.5f), Pal.muted))
        body.put(input)
        body.put(paste)
        body.put(error)
        val ref = arrayOfNulls<Modal>(1)
        val go = {
            val text = input.area.text
            val trouble = if (text.isBlank()) "Paste the code first." else onCode(text)
            if (trouble == null) overlay.dismiss(ref[0]) else {
                error.text = trouble
                error.isVisible = true
                body.revalidate()
                overlay.revalidate()
            }
        }
        input.onSubmit = go
        val m = custom(title, null, listOf(
            DialogButton("Cancel", DKind.SECONDARY),
            DialogButton(action, DKind.PRIMARY, false, go),
        ), body, 460, input.area)
        ref[0] = m
        // The default action of the generic builder dismisses first; here the action decides.
        m.defaultAction = go
        return m
    }

    /** The controls dialog: which key does what, click a button to change its key. */
    fun controls(onChanged: () -> Unit): Modal {
        val rows = HashMap<Pad, KeyRow>()
        fun refreshAll() { rows.values.forEach { it.refresh() }; onChanged() }
        val capturing = arrayOf<KeyRow?>(null)
        fun pick(b: Pad): KeyRow = KeyRow(b, { r ->
            capturing[0]?.let { if (it !== r) it.capturing = false }
            capturing[0] = r
        }, ::refreshAll).also { rows[b] = it }
        val leftCol = VBox(6)
        val rightCol = VBox(6)
        for (b in listOf(Pad.UP, Pad.DOWN, Pad.LEFT, Pad.RIGHT, Pad.START)) leftCol.put(pick(b))
        for (b in listOf(Pad.A, Pad.B, Pad.L, Pad.R, Pad.SELECT)) rightCol.put(pick(b))
        val grid = HBox(18)
        grid.put(leftCol)
        grid.put(rightCol)
        val body = VBox(14)
        body.put(TextBlock("Click a button, then press the key you want for it.", Fonts.regular(13.5f), Pal.muted))
        body.put(grid)
        val fs = if (Fonts.mac) "Ctrl+Cmd+F" else "F11"
        val mod = if (Fonts.mac) "Cmd" else "Ctrl"
        body.put(TextBlock(
            "Fixed keys: hold Tab for fast forward (tap twice to keep it on), Esc opens the game menu, F1 to F3 load a saved " +
                "state, Shift+F1 to F3 save one, $fs switches full screen, $mod+Plus and $mod+Minus change the window size." +
                // The top row of a Mac laptop is volume and brightness until Fn is held.
                if (Fonts.mac) " On a Mac laptop hold Fn for F1 to F3, or use the Game menu." else "",
            Fonts.regular(12.5f), Pal.faint,
        ))
        val ref = arrayOfNulls<Modal>(1)
        val reset = DialogButton("Reset to defaults", DKind.SECONDARY, false) {
            Settings.resetKeys()
            capturing[0]?.capturing = false
            refreshAll()
        }
        val m = custom("Controls", null, listOf(reset, DialogButton("Done", DKind.PRIMARY)), body, 580)
        ref[0] = m
        return m
    }
}

/** An editable box for a pasted code, with a hint while empty. */
class CodeInput(private val hint: String) : JPanel(null), Flow {
    val area = object : JTextArea() {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (text.isEmpty()) {
                val g2 = g.create() as Graphics2D
                g2.quality()
                g2.font = Fonts.regular(14f)
                g2.color = Pal.faint
                val lm = g2.font.getLineMetrics(hint, Fonts.frc)
                g2.drawString(hint, insets.left.toFloat(), lm.ascent)
                g2.dispose()
            }
        }
    }
    var onSubmit: () -> Unit = {}
    private val padding = 12

    init {
        isOpaque = false
        area.lineWrap = true
        area.wrapStyleWord = false
        area.isOpaque = false
        area.border = null
        area.font = Fonts.mono(13.5f)
        area.foreground = Pal.ink
        area.caretColor = Pal.ink
        area.selectionColor = Pal.accentFill
        area.selectedTextColor = Pal.white
        area.getAccessibleContext().accessibleName = hint
        area.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "submit")
        area.actionMap.put("submit", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) = onSubmit()
        })
        add(area)
    }

    override fun heightFor(width: Int): Int {
        area.setSize(max(40, width - 2 * padding), Short.MAX_VALUE.toInt())
        return max(104, area.preferredSize.height + 2 * padding)
    }

    override fun doLayout() {
        area.setBounds(padding, padding, max(1, width - 2 * padding), max(1, height - 2 * padding))
    }

    override fun getPreferredSize() = Dimension(300, heightFor(if (width > 0) width else 300))

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        Glass.paint(g2, 0f, 0f, width.toFloat(), height.toFloat(), 14f, Pal.bg, 0.55f, if (area.isFocusOwner) 0.5f else 0.14f, 0f, 0f, false)
        if (area.isFocusOwner) {
            g2.color = Pal.alpha(Pal.accentText, 0.6f)
            g2.stroke = java.awt.BasicStroke(1.5f)
            g2.draw(java.awt.geom.RoundRectangle2D.Float(0.75f, 0.75f, width - 1.5f, height - 1.5f, 28f, 28f))
        }
    }

    init {
        area.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) { repaint() }
            override fun focusLost(e: java.awt.event.FocusEvent) { repaint() }
        })
    }
}

/** One line of the controls dialog: the button's name and the key on it. Click to change. */
class KeyRow(
    private val button: Pad,
    private val onCapture: (KeyRow) -> Unit,
    private val changed: () -> Unit,
) : Comp() {
    var capturing = false
        set(v) { field = v; repaint() }

    private var hover = false
    private var viaMouse = false // focus came from a click: no ring then

    init {
        isOpaque = false
        isFocusable = true
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        getAccessibleContext().accessibleName = button.label
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { hover = true; repaint() }
            override fun mouseExited(e: MouseEvent) { hover = false; repaint() }
            override fun mousePressed(e: MouseEvent) { viaMouse = true; begin() }
        })
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (!capturing) {
                    if (e.keyCode == KeyEvent.VK_ENTER || e.keyCode == KeyEvent.VK_SPACE) { begin(); e.consume() }
                    return
                }
                e.consume()
                if (e.keyCode == KeyEvent.VK_ESCAPE) { capturing = false; return }
                if (!usable(e.keyCode)) return
                assign(e.keyCode)
                capturing = false
            }
        })
        addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { capturing = false; viaMouse = false; repaint() }
            override fun focusGained(e: java.awt.event.FocusEvent) { repaint() }
        })
    }

    private fun begin() {
        requestFocusInWindow()
        capturing = true
        onCapture(this)
    }

    /** A key may go on a button unless it is one the app keeps for itself, or only a modifier. */
    private fun usable(code: Int) = !(code == KeyEvent.VK_UNDEFINED || code == KeyEvent.VK_TAB || code == KeyEvent.VK_ESCAPE ||
        code in KeyEvent.VK_F1..KeyEvent.VK_F12 || code == KeyEvent.VK_SHIFT || code == KeyEvent.VK_CONTROL ||
        code == KeyEvent.VK_ALT || code == KeyEvent.VK_META || code == KeyEvent.VK_ALT_GRAPH || code == KeyEvent.VK_CAPS_LOCK ||
        code == KeyEvent.VK_WINDOWS || code == KeyEvent.VK_CONTEXT_MENU)

    private fun assign(code: Int) {
        val old = Settings.key(button)
        val other = Settings.buttonFor(code)
        if (other != null && other != button) Settings.setKey(other, old) // swap: no key ever does two things
        Settings.setKey(button, code)
        changed()
    }

    fun refresh() = repaint()

    override fun getPreferredSize() = Dimension(220, 40)

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.quality()
        val w = width.toFloat()
        val h = height.toFloat()
        g.color = Pal.alpha(Pal.white, if (hover || capturing) 0.08f else 0.04f)
        g.fillRound(0f, 0f, w, h, 10f)
        g.drawText(button.label, 14f, (h - 20f) / 2f, Fonts.regular(14.5f), Pal.ink)
        val text = if (capturing) "Press a key…" else keyName(Settings.key(button))
        val font = Fonts.medium(13f)
        val tw = Fonts.width(font, text) + 22f
        val x = w - tw - 8f
        g.color = if (capturing) Pal.alpha(Pal.accentFill, 0.5f) else Pal.alpha(Pal.white, 0.10f)
        g.fillRound(x, 7f, tw, h - 14f, 8f)
        if (capturing) {
            g.color = Pal.accentText
            g.stroke = java.awt.BasicStroke(1.5f)
            g.draw(java.awt.geom.RoundRectangle2D.Float(x + 0.75f, 7.75f, tw - 1.5f, h - 15.5f, 16f, 16f))
        }
        g.drawText(text, x + 11f, (h - 18f) / 2f + 0.5f, font, if (capturing) Pal.white else Pal.ink)
        if (isFocusOwner && !viaMouse) {
            g.stroke = java.awt.BasicStroke(2f)
            g.color = Pal.alpha(Pal.accentText, 0.9f)
            g.draw(java.awt.geom.RoundRectangle2D.Float(1.5f, 1.5f, w - 3f, h - 3f, 22f, 22f))
        }
        g.dispose()
    }

    companion object {
        /** A key's name for people: arrows as arrows, Enter, Delete (Mac Backspace)... */
        fun keyName(code: Int): String = when (code) {
            KeyEvent.VK_UP -> "↑"
            KeyEvent.VK_DOWN -> "↓"
            KeyEvent.VK_LEFT -> "←"
            KeyEvent.VK_RIGHT -> "→"
            KeyEvent.VK_ENTER -> "Enter"
            KeyEvent.VK_BACK_SPACE -> if (Fonts.mac) "Delete" else "Backspace"
            KeyEvent.VK_SPACE -> "Space"
            else -> KeyEvent.getKeyText(code).let { if (it.length == 1) it.uppercase() else it }
        }
    }
}
