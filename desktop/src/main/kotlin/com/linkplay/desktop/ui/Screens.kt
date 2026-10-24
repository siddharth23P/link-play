package com.linkplay.desktop.ui

import com.linkplay.desktop.Settings
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import kotlin.math.max

/** What the lobby screens ask of the app. */
interface LobbyActions {
    fun chooseRom()
    fun play()
    fun playFromInGameSave()
    fun hostGame()
    fun joinGame()
    fun showInvite()
    fun showJoining()
    fun closeRoom()
    fun leaveRoom()
    fun importSave()
    fun exportSave()
    fun showLobby()
    fun copyCode()
    fun pasteReply()
    fun startPlaying()
    fun openSource()
    fun about()
    fun backgroundChanged()
    fun advancedToggled(open: Boolean)
}

/** Everything the home screen shows, worked out by the app from its state (so a screenshot can fake it). */
class HomeModel(
    val romTitle: String,
    val thumb: BufferedImage?,
    val sub: String,
    val primary: String,
    val primaryEnabled: Boolean,
    val fromSave: Boolean,
    /** 0 no link, 1 hosting, 2 guest connecting, 3 in a room as a guest. */
    val linkMode: Int,
    val status: String,
    val netInfo: String,
)

class InviteModel(
    val title: String,
    val code: String,
    val codeReady: Boolean,
    val left: String,
    val right: String?,
    val linked: Boolean,
    val players: String,
    val note: String,
    val info: String,
)

enum class Prompt { NONE, QUIET, LOUD_OK, LOUD_BAD }

class JoiningModel(
    val title: String,
    val left: String,
    val right: String?,
    val linked: Boolean,
    val info: String,
    val prompt: String,
    val promptStyle: Prompt,
    val code: String,
    val codeReady: Boolean,
    val codeVisible: Boolean,
    val note: String,
)

private fun filler() = object : JComponent() {
    override fun getPreferredSize() = Dimension(1, 0)
}

/** A button of a fixed width on the left, the rest of the row empty. */
private fun leftAligned(button: GlassButton, w: Int): HBox {
    val row = HBox(0)
    row.put(button, w)
    row.put(filler())
    return row
}

/**
 * The width a quiet text button needs: its label and 14 px each side (a text button has no frame, so the 20 px
 * its preferredSize leaves at each end is more than the hover pill needs). Equal shares of a row would clip
 * the longer labels with an ellipsis.
 */
private fun textWidth(b: GlassButton): Int = b.preferredSize.width - 12

/**
 * A button whose emphasis can change (a GlassButton's kind is fixed): two buttons for one job, only one shown.
 * Add [primary] and [control] to the same row; the label, the enabled state and the click are kept the same on both.
 */
private class Emphasis(text: String, onClick: () -> Unit) {
    val primary = GlassButton(text, BtnKind.PRIMARY, onClick)
    val control = GlassButton(text, BtnKind.CONTROL, onClick)
    private var strong = true

    init { control.isVisible = false }

    var text: String
        get() = primary.text
        set(v) { primary.text = v; control.text = v }

    var isEnabled: Boolean
        get() = primary.isEnabled
        set(v) { primary.isEnabled = v; control.isEnabled = v }

    /** True: the accent button; false: the glass one. The keyboard focus follows the swap. */
    fun emphasise(on: Boolean) {
        if (on == strong) return
        strong = on
        val hadFocus = primary.isFocusOwner || control.isFocusOwner
        primary.isVisible = on
        control.isVisible = !on
        if (hadFocus) (if (on) primary else control).requestFocusInWindow()
    }
}

/** The macOS title bar (traffic lights) sits on top of the content: the screens keep clear of it. */
object Chrome {
    var topInset = if (Fonts.mac) 28 else 0
}

/** First run: pick a ROM. */
class SetupScreen private constructor(col: VBox, actions: LobbyActions) : ScreenHost(col, 520, centerV = true) {
    constructor(actions: LobbyActions) : this(VBox(16), actions)

    private val status = caption("", 13.5f, Pal.muted, SwingConstants.CENTER)

    init {
        topInset = Chrome.topInset
        col.put(AppIcon())
        col.put(TextBlock("Link Play", Fonts.bold(34f), Pal.ink, spacing = 1.2f, align = SwingConstants.CENTER), 6)
        val card = Card(18f, Insets(24, 24, 24, 24), 18)
        card.put(TextBlock(
            "Pick your GBA ROM file. It stays on this computer; only its fingerprint is used to make sure everyone in a room has the same ROM.",
            Fonts.regular(15f), Pal.muted, spacing = 1.45f,
        ))
        card.put(GlassButton("Choose ROM file", BtnKind.PRIMARY) { actions.chooseRom() })
        col.put(card, 22)
        col.put(status, 14)
        col.put(caption("You can also drop a ROM file on this window.", 13f, Pal.faint, SwingConstants.CENTER), 4)
    }

    fun setStatus(text: String) {
        status.text = text
    }
}

/**
 * [row] (the name row: a glass card around a bare text field) with a 1.5 px accent outline while [field] has the
 * keyboard focus. The field itself draws no frame, so without this nothing shows that typing goes there.
 */
private class FieldRing(private val row: JComponent, private val field: JComponent) : JPanel(null), Flow {
    init {
        isOpaque = false
        add(row)
        field.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { repaint() }
            override fun focusLost(e: FocusEvent) { repaint() }
        })
    }

    override fun heightFor(width: Int) = heightOf(row, width)
    override fun getPreferredSize() = row.preferredSize
    override fun doLayout() { row.setBounds(0, 0, width, height) }

    override fun paint(g0: Graphics) {
        super.paint(g0) // the row and the field first, the outline over them
        if (!field.isFocusOwner) return
        val g = g0.create() as Graphics2D
        g.quality()
        g.stroke = BasicStroke(1.5f)
        g.color = Pal.alpha(Pal.accentText, 0.9f)
        g.draw(RoundRectangle2D.Float(0.75f, 0.75f, width - 1.5f, height - 1.5f, 36f, 36f))
        g.dispose()
    }
}

/** A rounded picture of the game's last scene, or an empty frame. */
private class ThumbView : JComponent() {
    var image: BufferedImage? = null
        set(v) { field = v; repaint() }

    override fun getPreferredSize() = Dimension(144, 96)

    override fun paintComponent(g0: Graphics) {
        val g = g0.create() as Graphics2D
        g.quality()
        val w = 144f
        val h = 96f
        val x = (width - w) / 2f
        val y = (height - h) / 2f
        g.color = Pal.alpha(Pal.white, 0.10f)
        g.fillRound(x, y, w, h, 10f)
        image?.let {
            g.clip(RoundRectangle2D.Float(x, y, w, h, 20f, 20f))
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(it, x.toInt(), y.toInt(), w.toInt(), h.toInt(), null)
        }
        g.dispose()
    }
}

/** Centres one child vertically in the height the row gives it. */
private class VCenter(private val child: JComponent) : JPanel(null), Flow {
    init { isOpaque = false; add(child) }
    override fun heightFor(width: Int) = heightOf(child, width)
    override fun doLayout() {
        val h = heightOf(child, width)
        child.setBounds(0, (height - h) / 2, width, h)
    }
    override fun getPreferredSize() = Dimension(child.preferredSize.width, heightFor(if (width > 0) width else child.preferredSize.width))
}

/** [child] centred vertically in a row of fixed height [h]. */
private class Boxed(private val child: JComponent, private val h: Int) : JPanel(null), Flow {
    init { isOpaque = false; add(child) }
    override fun heightFor(width: Int) = h
    override fun doLayout() {
        val ch = heightOf(child, width)
        child.setBounds(0, h - ch - 8, width, ch)
    }
    override fun getPreferredSize() = Dimension(child.preferredSize.width, h)
}

/** The app's icon, big, for the first screen. */
private class AppIcon : JComponent(), Flow {
    private val image: BufferedImage? = try {
        AppIcon::class.java.getResourceAsStream("/icons/linkplay-128.png")?.use { javax.imageio.ImageIO.read(it) }
    } catch (e: Exception) { null }

    override fun heightFor(width: Int) = if (image == null) 0 else 84
    override fun getPreferredSize() = Dimension(84, heightFor(84))

    override fun paintComponent(g0: Graphics) {
        val img = image ?: return
        val g = g0.create() as Graphics2D
        g.quality()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
        val s = 76
        val x = (width - s) / 2
        g.clip(RoundRectangle2D.Float(x.toFloat(), 4f, s.toFloat(), s.toFloat(), 34f, 34f))
        g.drawImage(img, x, 4, s, s, null)
        g.dispose()
    }
}

/** The lobby: the game and the way into link play. */
class HomeScreen private constructor(left: VBox, right: VBox, private val actions: LobbyActions) :
    ScreenHost(Columns(left, right, 28, 920), 1040) {
    constructor(actions: LobbyActions) : this(VBox(16), VBox(0), actions)

    private val chip = RomChip()
    private val thumb = ThumbView()
    private val heroTitle = TextBlock("", Fonts.bold(19f), Pal.ink, maxLines = 2, spacing = 1.25f)
    private val heroSub = caption("", 13.5f, Pal.muted)
    private val play = GlassButton("Play", BtnKind.PRIMARY) { actions.play() }
    private val fromSave = GlassButton("Start from in-game save instead", BtnKind.TEXT) { actions.playFromInGameSave() }

    private val tiles = HBox(14)
    private val tileStatus = caption("", 13.5f, Pal.muted)
    private val linkCard = Card(18f, Insets(16, 18, 16, 18), 12)
    private val linkStatus = TextBlock("", Fonts.regular(14f), Pal.ink)
    private val linkButtons = HBox(12)
    private var linkModeShown = -1

    val nameField = StyledField("Your name")
    private val advanced = Card(18f, Insets(14, 18, 14, 18), 6)
    // One label for both states (the card under it shows which): Segoe UI has no small down-pointing triangle.
    private val advancedToggle = GlassButton("Advanced ›", BtnKind.TEXT)
    private val netLine = caption("Network: …", 12.5f, Pal.muted)
    private var advancedOpen = false

    init {
        topInset = Chrome.topInset
        // Left: title and ROM chip, the game.
        val head = object : JPanel(BorderLayout()), Flow {
            override fun heightFor(width: Int) = 44
            override fun getPreferredSize() = Dimension(300, 44)
        }
        head.isOpaque = false
        head.add(TextBlock("Link Play", Fonts.bold(30f), Pal.ink, spacing = 1.2f).also { it.preferredSize = Dimension(200, 40) }, BorderLayout.WEST)
        val chipBox = JPanel(java.awt.GridBagLayout())
        chipBox.isOpaque = false
        chipBox.add(chip)
        head.add(chipBox, BorderLayout.EAST)
        left.put(head)

        val hero = Card(18f, Insets(20, 20, 20, 20), 16)
        val top = HBox(18)
        top.put(thumb, 144)
        val words = VBox(4)
        words.put(heroTitle)
        words.put(heroSub)
        top.put(VCenter(words))
        hero.put(top)
        hero.put(play, 18)
        hero.put(fromSave, 4)
        left.put(hero)

        // Right: link play, name, the quiet buttons, advanced.
        right.put(Boxed(heading("Link play · with a friend, no server"), 44))
        tiles.put(Tile({ g, x, y, s, c -> Icons.swap(g, x, y, s, c) }, "Host a game", "Share an invite") { actions.hostGame() })
        tiles.put(Tile({ g, x, y, s, c -> Icons.download(g, x, y, s, c) }, "Join a game", "Paste an invite") { actions.joinGame() })
        right.put(tiles, 16)
        right.put(tileStatus, 10)
        linkCard.put(linkStatus)
        linkCard.put(linkButtons)
        linkCard.isVisible = false
        right.put(linkCard, 10)

        right.put(FieldRing(NameRow(nameField), nameField), 16)

        // Each label gets the width it needs (equal parts clipped the longest), and what does not fit goes on a second line.
        val quiet = WrapRow(4)
        for (b in listOf(
            GlassButton("Change ROM", BtnKind.TEXT) { actions.chooseRom() },
            GlassButton("Import save", BtnKind.TEXT) { actions.importSave() },
            GlassButton("Export save", BtnKind.TEXT) { actions.exportSave() },
            advancedToggle,
        )) quiet.put(b, textWidth(b))
        advancedToggle.onClick = { setAdvanced(!advancedOpen) }
        right.put(quiet, 10)

        advanced.put(CheckRow(
            "Ask public STUN servers (Google and Cloudflare) for my public IPv4 address, and check whether this network lets friends in over IPv4. " +
                "Helps some Wi-Fi networks without IPv6; off by default because it contacts third parties.",
            Settings.stun,
        ) { Settings.stun = it })
        advanced.put(CheckRow("Pause and mute while Link Play is in the background (a linked game keeps running).", Settings.muteInBackground) {
            Settings.muteInBackground = it
            actions.backgroundChanged()
        }, 8)
        advanced.put(netLine, 10)
        // MPL-2.0 asks us to say where mGBA's source is, in words as well as behind the button.
        advanced.put(caption("Emulation by mGBA (MPL-2.0, source: github.com/mgba-emu/mgba). Link Play ${com.linkplay.desktop.AppInfo.VERSION}", 12f, Pal.faint), 8)
        val links = WrapRow(4)
        for (b in listOf(
            GlassButton("mGBA source", BtnKind.TEXT) { actions.openSource() },
            GlassButton("About and licences", BtnKind.TEXT) { actions.about() },
        )) links.put(b, textWidth(b))
        advanced.put(links, 2)
        advanced.isVisible = false
        right.put(advanced, 6)
    }

    private fun setAdvanced(open: Boolean) {
        advancedOpen = open
        advanced.isVisible = open
        revalidate()
        repaint()
        actions.advancedToggled(open)
    }

    /** For snapshots. */
    fun showAdvanced(open: Boolean) = setAdvanced(open)

    val isAdvancedOpen get() = advancedOpen

    fun update(m: HomeModel) {
        chip.text = m.romTitle
        heroTitle.text = m.romTitle
        heroSub.text = m.sub
        thumb.image = m.thumb
        play.text = m.primary
        play.isEnabled = m.primaryEnabled
        fromSave.isVisible = m.fromSave
        netLine.text = m.netInfo
        if (m.linkMode == 0) {
            tiles.isVisible = true
            linkCard.isVisible = false
            tileStatus.isVisible = m.status.isNotEmpty()
            tileStatus.text = m.status
        } else {
            tiles.isVisible = false
            tileStatus.isVisible = false
            linkCard.isVisible = true
            linkStatus.text = m.status
            if (linkModeShown != m.linkMode) {
                linkButtons.removeAll()
                when (m.linkMode) {
                    1 -> {
                        linkButtons.put(GlassButton("Invite players", BtnKind.CONTROL) { actions.showInvite() })
                        linkButtons.put(GlassButton("Close room", BtnKind.DANGER) { actions.closeRoom() })
                    }
                    2 -> {
                        linkButtons.put(GlassButton("Show reply code", BtnKind.CONTROL) { actions.showJoining() })
                        linkButtons.put(GlassButton("Cancel", BtnKind.DANGER) { actions.leaveRoom() })
                    }
                    else -> linkButtons.put(GlassButton("Leave room", BtnKind.DANGER) { actions.leaveRoom() })
                }
            }
        }
        linkModeShown = m.linkMode
        revalidate()
        repaint()
    }

    /** The name as typed. */
    fun name(): String = nameField.text.trim()

    fun showName(s: String) {
        if (nameField.text != s) nameField.text = s
    }
}

/** The host's screen: the invite to send, who is in, the way to add a reply code. */
class InviteScreen private constructor(col: VBox, private val actions: LobbyActions) : ScreenHost(col, 780) {
    constructor(actions: LobbyActions) : this(VBox(16), actions)

    private val title = TextBlock("", Fonts.bold(22f), Pal.ink, spacing = 1.25f)
    private val cable = CableView()
    private val codeBox = CodeBox()
    private val copy = Emphasis("Copy") { actions.copyCode() }
    private val players = TextBlock("", Fonts.regular(14f), Pal.muted)
    private val paste = GlassButton("Paste reply code", BtnKind.CONTROL) { actions.pasteReply() }
    private val note = TextBlock("", Fonts.regular(14f), Pal.ink)
    private val info = caption("", 13f, Pal.muted)
    /** Once a friend is linked, starting is the next step and the invite is done with: the accent moves from Copy to Start playing. */
    private val startBtn = Emphasis("Start playing") { actions.startPlaying() }

    init {
        topInset = Chrome.topInset
        startBtn.emphasise(false)
        col.put(leftAligned(GlassButton("‹  Lobby", BtnKind.TEXT) { actions.showLobby() }, 100))
        val card = Card(20f, Insets(24, 24, 24, 24), 14)
        card.put(title)
        card.put(cable, 6)
        card.put(caption("Send this invite code to your friends (WhatsApp, SMS…). They paste it in Link Play → Join a game:", 14f, Pal.muted))
        card.put(codeBox)
        val copyRow = HBox(0)
        copyRow.put(copy.primary, 180)
        copyRow.put(copy.control, 180)
        copyRow.put(filler())
        card.put(copyRow, 12)
        card.put(players, 6)
        col.put(card, 6)
        col.put(heading("Can't connect? Ask your friend for their reply code:"), 22)
        col.put(leftAligned(paste, 200), 8)
        col.put(note, 10)
        col.put(info, 6)
        col.put(caption(
            "The first time you host, your computer may ask whether Link Play can accept connections. Choose Allow, so friends can get in.",
            12.5f, Pal.faint,
        ), 10)
        val bottom = HBox(12)
        bottom.put(startBtn.primary)
        bottom.put(startBtn.control)
        bottom.put(GlassButton("Close room", BtnKind.DANGER) { actions.closeRoom() })
        col.put(bottom, 22)
    }

    private var copyUntil = 0L

    /** The Copy button says "Copied" for a moment. */
    fun flashCopied() {
        copy.text = "Copied"
        copyUntil = System.currentTimeMillis() + 1500
        javax.swing.Timer(1550) { if (System.currentTimeMillis() >= copyUntil) copy.text = "Copy" }.apply { isRepeats = false; start() }
    }

    fun update(m: InviteModel) {
        title.text = m.title
        cable.set(m.left, m.right, m.linked)
        codeBox.show(m.code, m.codeReady)
        copy.isEnabled = m.codeReady
        copy.emphasise(!m.linked)
        startBtn.emphasise(m.linked)
        players.text = m.players
        note.text = m.note
        note.isVisible = m.note.isNotEmpty()
        info.text = m.info
        info.isVisible = m.info.isNotEmpty()
        revalidate()
        repaint()
    }
}

/** The guest's screen: connecting, and (when the host does not answer) the reply code to send back. */
class JoiningScreen private constructor(col: VBox, private val actions: LobbyActions) : ScreenHost(col, 780) {
    constructor(actions: LobbyActions) : this(VBox(16), actions)

    private val title = TextBlock("", Fonts.bold(22f), Pal.ink, spacing = 1.25f)
    private val cable = CableView()
    private val info = TextBlock("", Fonts.regular(14f), Pal.muted, spacing = 1.45f)
    private val prompt = TextBlock("", Fonts.regular(13.5f), Pal.muted, spacing = 1.35f)
    private val codeBox = CodeBox()
    private val copy = GlassButton("Copy", BtnKind.PRIMARY) { actions.copyCode() }
    private val copyRow = leftAligned(copy, 180)
    private val note = TextBlock("", Fonts.regular(14f), Pal.ink)

    init {
        topInset = Chrome.topInset
        col.put(leftAligned(GlassButton("‹  Lobby", BtnKind.TEXT) { actions.showLobby() }, 100))
        val card = Card(20f, Insets(24, 24, 24, 24), 14)
        card.put(title)
        card.put(cable, 6)
        card.put(info)
        card.put(prompt, 8)
        card.put(codeBox)
        card.put(copyRow, 12)
        card.put(note, 6)
        col.put(card, 6)
        col.put(GlassButton("Cancel", BtnKind.DANGER) { actions.leaveRoom() }, 22)
    }

    private var copyUntil = 0L

    fun flashCopied() {
        copy.text = "Copied"
        copyUntil = System.currentTimeMillis() + 1500
        javax.swing.Timer(1550) { if (System.currentTimeMillis() >= copyUntil) copy.text = "Copy" }.apply { isRepeats = false; start() }
    }

    fun update(m: JoiningModel) {
        title.text = m.title
        cable.set(m.left, m.right, m.linked)
        info.text = m.info
        when (m.promptStyle) {
            Prompt.NONE -> prompt.isVisible = false
            Prompt.QUIET -> { prompt.isVisible = true; prompt.font2 = Fonts.regular(13.5f); prompt.color = Pal.muted }
            Prompt.LOUD_OK -> { prompt.isVisible = true; prompt.font2 = Fonts.bold(17f); prompt.color = Pal.ok }
            Prompt.LOUD_BAD -> { prompt.isVisible = true; prompt.font2 = Fonts.bold(17f); prompt.color = Pal.bad }
        }
        prompt.text = m.prompt
        codeBox.isVisible = m.codeVisible
        codeBox.show(m.code, m.codeReady)
        copyRow.isVisible = m.codeVisible
        copy.isEnabled = m.codeReady
        note.text = m.note
        note.isVisible = m.note.isNotEmpty()
        revalidate()
        repaint()
    }
}
