package com.linkplay.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.opengl.GLSurfaceView
import android.view.Choreographer
import android.view.Surface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Layout
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.linkplay.app.net.Candidate
import com.linkplay.app.net.CodeError
import com.linkplay.app.net.CodeResult
import com.linkplay.app.net.Codes
import com.linkplay.app.net.Invite
import com.linkplay.app.net.LinkBackend
import com.linkplay.app.net.LinkListener
import com.linkplay.app.net.Reply
import com.linkplay.app.net.RomId
import com.linkplay.app.net.RoomKey
import com.linkplay.app.net.RoomPlayer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("settings", 0) }
    private val dp by lazy { resources.displayMetrics.density }

    private var rom: RomInfo? = null
    private var gameLoaded = false
    private var lobbyRunning = false // a linked game left running behind the lobby screens
    private val keys = KeyMixer()

    // Autosave (see AutoSave): snapshots of the solo game, to come back to after quitting or a link ending.
    private val autosave by lazy { AutoSave(this) { rom?.sha1 } }
    private val ticker = Handler(Looper.getMainLooper())
    private var resumeArmed = true // the first Play / host / join after the app starts continues the newest snapshot
    private var skipResumeOnce = false // "Start from in-game save instead"
    private var booting = false // a snapshot is being loaded into a game that just started
    private var linkSnapping = false // the before-link snapshot is being taken
    private var linkSnapDone = false
    private var pendingJoined: JoinedArgs? = null
    private class JoinedArgs(val roomName: String, val linkId: Int, val players: List<RoomPlayer>, val hosting: Boolean)
    private val tick = object : Runnable {
        override fun run() {
            val running = gameLoaded && screen == "game" && resumed && !booting
            if (autosave.tick(AutoSave.TICK_MS, running, inRoom || linkAttached)) autoSnapshot(quiet = false)
            ticker.postDelayed(this, AutoSave.TICK_MS)
        }
    }

    private var screen = ""
    private var gl: GLSurfaceView? = null
    private var controls: ControlsView? = null
    private var statusLine: TextView? = null
    private var homeStatus: TextView? = null
    // Invite / joining screens: the parts that change while they're up.
    private var titleView: TextView? = null
    private var codeView: TextView? = null
    private var playersView: TextView? = null
    private var cableView: CableView? = null
    private var infoView: TextView? = null
    private var noteView: TextView? = null
    private var promptView: TextView? = null // joining: when to send the reply code
    private var codeButtons: List<Button> = emptyList()
    // Advanced section of the home screen.
    private var advancedOpen = false
    private var netInfo: TextView? = null
    // Android 12+: "Invite links open the browser on this phone. [Turn on]" (home, invite, joining).

    // Link play. [backend] is where the local GBA's link traffic goes: a room hosted on
    // this phone or our place in a friend's ([link]).
    private var link: PhoneLink? = null
    @Volatile private var backend: LinkBackend? = null
    // Bumped whenever a phone link starts or ends, so a finished link's late callbacks are dropped.
    @Volatile private var linkGen = 0
    private var inRoom = false
    private var linkAttached = false
    private var linkId = -1
    private var roomLabel = ""
    private var roster: List<RoomPlayer> = emptyList()
    private var linkNote = "" // latest news for the invite / joining screens
    private var seenInviteVersion = 0
    // Guest: the reply code version on screen, the newest one shared or copied (-1: none yet), and
    // the shared one we last warned about, so a reply sent before it changed gets one warning.
    private var seenReplyVersion = 0
    private var sentReplyVersion = -1
    private var warnedReplyVersion = -1
    private var lastInvite: Invite? = null // for "Try again" after losing a friend's room
    private var pendingCode: String? = null // shared to us before a ROM was picked
    // The thread shuttling link traffic; a poll thread that is no longer this one stops.
    @Volatile private var pollThread: Thread? = null
    private var resumed = false // between onResume and onPause: the game may make sound
    private var backCallback: Any? = null
    // LinkService's words as last handed to it; null while it isn't wanted (no phone link).
    private var serviceText: String? = null
    private var stoppedInLink = false // onStop came during a phone link: log the way back

    private val colors = object {
        val bg = Pal.bg
        val panel = Pal.panel
        val text = Pal.text
        val muted = Pal.muted
        val accent = Pal.accent
        val accentText = Pal.accentText
        val ok = Pal.ok
        val bad = Pal.bad
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.setBackgroundColor(colors.bg)
        @Suppress("DEPRECATION") run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        Glass.refreshHighContrast(this)
        // The relay server mode is gone; forget its address.
        if (prefs.contains("server")) prefs.edit().remove("server").apply()
        rom = RomStore.current(this)
        if (rom == null) showSetup() else showHome()
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ---------------------------------------------------------------- views

    private fun column(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
    }

    private fun label(text: String, size: Float = 16f, color: Int = colors.text, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
    }

    /** Title: 30 sp bold, tight tracking. */
    private fun titleText(text: String) = label(text, 30f, bold = true).apply {
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = -0.02f
        isAccessibilityHeading = true
    }

    /** Headline: 20 sp medium. */
    private fun headline(text: String) = label(text, 20f, bold = true).apply { isAccessibilityHeading = true }

    /** Caption: 13 sp, ink-2. */
    private fun caption(text: String) = label(text, 13f, colors.muted)

    /** A small section label (14 sp medium, ink-2). */
    private fun heading(text: String) = label(text, 14f, colors.muted, bold = true).apply {
        setPadding(0, (16 * dp).toInt(), 0, (8 * dp).toInt())
    }

    /** A Glass/Regular card (home cards 72% fill, radius 18, 20 dp padding), 16 dp below the previous part. */
    private fun card(pad: Int = 20, topGap: Int = 16) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Glass.regular(dp, 18f, 0.72f)
        val p = (pad * dp).toInt()
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (topGap * dp).toInt()
        }
    }

    /**
     * The one button system: 52 dp tall, 14 dp corners, 16 sp medium. Primary = accent fill with a white label
     * (5.4:1); everything else = Glass/Control. Style, not size, marks the preferred choice.
     */
    private fun button(text: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        stateListAnimator = null
        minHeight = (52 * dp).toInt()
        minimumHeight = (52 * dp).toInt()
        minWidth = 0
        minimumWidth = 0
        setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
        background = if (primary) Glass.primary(dp) else Glass.control(dp)
        val c = if (primary) Color.WHITE else if (danger) Pal.bad else Pal.text
        setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()), intArrayOf(Glass.alpha(c, 0.45f), c)))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * dp).toInt()
        }
        setOnClickListener { onClick() }
    }

    /** A quiet text-only button (accent-ink), 48 dp tall. */
    private fun textButton(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 15f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Pal.accentText)
        gravity = Gravity.CENTER
        minHeight = (48 * dp).toInt()
        minWidth = (48 * dp).toInt()
        setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), Glass.control(dp, 12f))
        }
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    /** Buttons side by side, sharing the width, 12 dp apart. */
    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i < views.size - 1) marginEnd = (12 * dp).toInt()
                topMargin = (8 * dp).toInt()
            })
        }
    }

    private fun field(hint: String, value: String) = EditText(this).apply {
        this.hint = hint
        setText(value)
        setTextColor(colors.text)
        setHintTextColor(Glass.alpha(Pal.text, 0.6f))
        textSize = 16f
        background = null
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO // no "save password?" popups (Samsung Pass)
    }

    /** A box for pasting a whole chat message that has a code in it. */
    private fun codeField(hint: String) = EditText(this).apply {
        this.hint = hint
        setTextColor(colors.text)
        setHintTextColor(Glass.alpha(Pal.text, 0.6f))
        textSize = 14f
        typeface = Typeface.MONOSPACE
        background = Glass.well(dp)
        val p = (12 * dp).toInt()
        setPadding(p, p, p, p)
        minLines = 3
        maxLines = 6
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
    }

    /** An invite or reply code in its recessed well: monospace 15 sp, selectable, wraps anywhere. */
    private fun codeBox() = TextView(this).apply {
        typeface = Typeface.MONOSPACE
        textSize = 15f
        letterSpacing = 0.02f
        setTextColor(colors.text)
        background = Glass.well(dp)
        setTextIsSelectable(true)
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE // no fake "-" where a line wraps
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        val p = (14 * dp).toInt()
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = (8 * dp).toInt()
            bottomMargin = (4 * dp).toInt()
        }
    }

    /** The scene behind the glass: the live frame in a game, else the last one played (null: the gradient shows). */
    private var sceneRaw: Bitmap? = null
    private var sceneSha = ""
    private var frostSrc: Bitmap? = null
    private var frostCache: Bitmap? = null

    private fun lastScene(): Bitmap? {
        val r = rom ?: return null
        if (sceneSha != r.sha1) { sceneRaw = Glass.loadLastFrame(this, r.sha1); sceneSha = r.sha1 }
        return sceneRaw
    }

    private fun sceneBitmap(): Bitmap? =
        (if (gameLoaded && screen == "game") Glass.currentFrame() else null) ?: lastScene()

    private fun homeFrost(): Bitmap? {
        val raw = lastScene() ?: return null
        if (frostSrc !== raw) { frostCache = Glass.frost(raw); frostSrc = raw }
        return frostCache
    }

    /** A glass dialog over the current scene. */
    private fun gdialog() = GlassDialog(this, sceneBitmap())

    private fun confirm(title: String, message: String?, action: String, destructive: Boolean = false,
                        neutral: Pair<String, () -> Unit>? = null, onConfirm: () -> Unit) =
        confirmDialog(title, message, action, destructive, neutral, sceneBitmap(), onConfirm)

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun toast(text: String, long: Boolean = false) =
        Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    private fun alert(title: String, text: String) {
        gdialog().title(title).message(text).primary("OK").show()
    }

    private fun setScreen(name: String, view: View) {
        panel?.dismiss()
        screen = name
        updateBackHandling()
        // Glass screens draw edge to edge on the frosted backdrop; the game screen manages its own insets.
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(name == "game")
        else if (name != "game") @Suppress("DEPRECATION") {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        setContentView(view)
        view.requestApplyInsets()
    }

    /** A scrolling screen on the frosted backdrop of the last scene (55% scrim). */
    private fun glassScreen(name: String, content: View) {
        val root = FrameLayout(this)
        root.addView(FrostedBackdrop(this).apply { scrim = 0.55f; setFrosted(homeFrost()) }, FrameLayout.LayoutParams(-1, -1))
        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            isFillViewport = true
            addView(content)
        }
        root.addView(sv, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            var l = 0; var t = 0; var r = 0; var b = 0
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                l = i.left; t = i.top; r = i.right; b = i.bottom
            } else @Suppress("DEPRECATION") {
                l = insets.systemWindowInsetLeft; t = insets.systemWindowInsetTop; r = insets.systemWindowInsetRight; b = insets.systemWindowInsetBottom
            }
            sv.setPadding(l, t, r, b)
            insets
        }
        setScreen(name, root)
    }

    /** Drops the previous screen's live views, so updates don't go to views nobody sees. */
    private fun forgetViews() {
        homeStatus = null
        titleView = null
        codeView = null
        playersView = null
        cableView = null
        infoView = null
        noteView = null
        promptView = null
        codeButtons = emptyList()
        netInfo = null
    }

    // ---------------------------------------------------------------- screens

    private fun showSetup() {
        leaveGameMode()
        forgetViews()
        val c = column()
        c.addView(titleText("Link Play"))
        val cd = card()
        cd.addView(label("Pick your GBA ROM file. It stays on this phone; only its fingerprint is used to make sure everyone in a room has the same ROM.", 16f, colors.muted))
        cd.addView(button("Choose ROM file", primary = true) { pickFile(REQ_ROM) }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = (16 * dp).toInt()
        })
        c.addView(cd)
        val status = caption(if (pendingCode != null) "Pick your ROM first; then the code you shared opens." else "")
        c.addView(status)
        glassScreen("setup", c)
        homeStatus = status
    }

    private var nameField: EditText? = null
    private fun rememberName() {
        nameField?.let { prefs.edit().putString("name", it.text.toString().trim()).apply() }
    }

    /** A Host / Join tile: icon, title, caption on Glass/Regular. */
    private fun tile(icon: String, title: String, sub: String, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = (16 * dp).toInt()
        setPadding(p, p, p, p)
        minimumHeight = (96 * dp).toInt()
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), GlassDrawable(dp, 18f, Glass.accent, 0.92f, 0.18f, 0.22f, 0f, true))
            addState(intArrayOf(), Glass.regular(dp, 18f, 0.72f))
        }
        isClickable = true
        isFocusable = true
        addView(TextView(context).apply {
            text = icon; textSize = 22f; setTextColor(Pal.accentText)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        addView(label(title, 16f, bold = true).apply { setPadding(0, (8 * dp).toInt(), 0, 0) })
        addView(caption(sub).apply { setPadding(0, 0, 0, 0) })
        setOnClickListener { onClick() }
    }

    private fun showHome() {
        leaveGameMode()
        forgetViews()
        val rom = rom ?: return showSetup()
        val land = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val right = if (land) LinearLayout(this).apply { orientation = LinearLayout.VERTICAL } else left

        // Title + ROM chip.
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(titleText("Link Play"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(TextView(this).apply {
            text = "${rom.title.trim().ifEmpty { rom.code }} ✓"
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Pal.text)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = (170 * dp).toInt()
            background = Glass.regular(dp, 16f, 0.72f)
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            contentDescription = "ROM loaded: ${rom.title} (${rom.code})"
        })
        left.addView(head)

        // Hero card: last scene, game, the one accent Play button.
        val hero = card()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Glass.alpha(Color.WHITE, 0.10f))
            lastScene()?.let { setImageBitmap(it) }
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, 10 * dp)
            }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        top.addView(thumb, LinearLayout.LayoutParams((96 * dp).toInt(), (64 * dp).toInt()).apply { marginEnd = (16 * dp).toInt() })
        val resumeFile = if (!gameLoaded && resumeArmed) autosave.newestUsable() else null
        val sub = when {
            inRoom -> "In $roomLabel"
            gameLoaded -> "Game running"
            resumeFile != null -> "Continue · ${autosave.agoText(resumeFile)}"
            rom?.let { RomStore.saveFile(this, it).length() > 0 } == true -> "Continue from in-game save" // e.g. just imported
            else -> "New game"
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(headline(rom.title.trim().ifEmpty { rom.code }).apply { maxLines = 2 })
        col.addView(caption(sub))
        top.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        hero.addView(top)
        hero.addView(button(if (inRoom) "Return to game" else "Play", primary = true) { rememberName(); showGame() }.apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = (16 * dp).toInt()
        })
        if (!gameLoaded && resumeArmed && autosave.newestUsable() != null) {
            hero.addView(textButton("Start from in-game save instead") { rememberName(); skipResumeOnce = true; showGame() })
        }
        left.addView(hero)

        // Link play.
        right.addView(heading("Link play · just phones, no server"))
        val l = link
        val status = caption(homeText())
        if (l == null) {
            right.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(tile("⇄", "Host a game", "Share an invite") { rememberName(); hostGame() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (8 * dp).toInt() })
                addView(tile("⤓", "Join a game", "Paste an invite") { rememberName(); joinDialog() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (8 * dp).toInt() })
            })
            right.addView(status)
        } else {
            val lc = card(pad = 16, topGap = 0)
            lc.addView(status.apply { textSize = 14f })
            lc.addView(when {
                l.hosting -> row(button("Invite players") { showInvite() }, button("Close room", danger = true) { closeRoom() })
                !inRoom -> row(button("Show reply code") { showJoining() }, button("Cancel", danger = true) { leaveRoom() })
                else -> row(button("Leave room", danger = true) { leaveRoom() })
            })
            right.addView(lc)
        }

        // Name row.
        val name = field("Your name", prefs.getString("name", "") ?: "")
        nameField = name
        val nameRow = card(pad = 4, topGap = 16).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (4 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
        }
        nameRow.addView(label("Name", 14f, colors.muted, bold = true).apply { minWidth = (52 * dp).toInt() })
        nameRow.addView(name, LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f))
        nameRow.addView(TextView(this).apply {
            text = "✎"; textSize = 18f; setTextColor(Pal.muted)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        right.addView(nameRow)

        // Quiet text buttons; Advanced unfolds below.
        val adv = card().apply { visibility = if (advancedOpen) View.VISIBLE else View.GONE }
        val toggle = textButton(if (advancedOpen) "Advanced ▾" else "Advanced ›") {}
        toggle.setOnClickListener {
            advancedOpen = !advancedOpen
            adv.visibility = if (advancedOpen) View.VISIBLE else View.GONE
            toggle.text = if (advancedOpen) "Advanced ▾" else "Advanced ›"
            if (advancedOpen) refreshNetInfo()
        }
        right.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(textButton("Change ROM") { pickFile(REQ_ROM) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(textButton("Import save") { importSave() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(textButton("Export save") { exportSave() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = (8 * dp).toInt() })

        fun check(text: String, key: String) = CheckBox(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(colors.text)
            buttonTintList = ColorStateList.valueOf(Pal.accentText)
            isChecked = prefs.getBoolean(key, false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        adv.addView(check("Ask public STUN servers (Google and Cloudflare) for my public IPv4 address, and check whether this network lets friends in over IPv4. Helps some Wi-Fi networks without IPv6; off by default because it contacts third parties.", "stun"))
        adv.addView(check("Keep link play on this Wi-Fi. For friends on the same Wi-Fi: no mobile data is used, but friends elsewhere can't reach you when the Wi-Fi has no IPv6.", "stayOnWifi"))
        val netLine = caption("Network: …")
        adv.addView(netLine)
        // MPL-2.0 asks us to say where mGBA's source is; the MIT notices are in the source too.
        adv.addView(caption("Emulation by mGBA (MPL-2.0, source: github.com/mgba-emu/mgba). " +
            "Filters: AMD FSR 1.0, MMPX, Anime4K (MIT licenses). Link Play ${appVersion()}").apply { textSize = 12f })
        right.addView(adv)

        val content: View = if (land) LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt())
            addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (8 * dp).toInt() })
            addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (8 * dp).toInt() })
        } else left.apply { setPadding((24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt(), (24 * dp).toInt()) }
        glassScreen("home", content)
        homeStatus = status
        netInfo = netLine
        if (advancedOpen) refreshNetInfo()
    }

    private fun homeText(): String {
        val l = link
        return when {
            l?.hosting == true -> "Hosting ${l.invite.roomName} · ${maxOf(1, roster.size)}/${l.invite.maxPlayers} players"
            l != null && !inRoom -> guestWait(l, "tap Show reply code and send it")
            inRoom -> "In $roomLabel"
            else -> ""
        }
    }

    /**
     * A guest that isn't in the room yet, in one line ([how] says where the reply code is). Asks for the
     * reply code only once the host has been silent a while and the code can help.
     */
    private fun guestWait(l: PhoneLink, how: String): String {
        val host = l.invite.hostName
        return when {
            l.connectedVia != null -> "Connected, joining ${l.invite.roomName}…"
            !l.hostSilent -> "Connecting to ${l.invite.roomName}…"
            !l.replyCanHelp -> "No answer from $host's phone yet"
            replySent(l) -> "Reply code sent — waiting for $host to paste it"
            else -> "No answer from $host's phone yet — $how"
        }
    }

    /** Guest: Share / Copy handed out the reply code as it is now. */
    private fun replySent(l: PhoneLink) = sentReplyVersion >= 0 && sentReplyVersion == l.replyVersion

    /** Advanced: what NetEnv sees (off the main thread; it asks ConnectivityManager about every network). */
    private fun refreshNetInfo() {
        val view = netInfo ?: return
        Thread {
            val s = NetEnv.snapshot(this)
            val text = if (s.network == null) "Network: none" else buildString {
                append("Network: ").append(s.label.replaceFirstChar { it.uppercase() })
                append(" · IPv6 ").append(if (s.hasIpv6) "✓" else "✗")
                if (!s.hasIpv6 && s.mobileHasIpv6) append(" · mobile data IPv6 ✓ (link play uses it)")
                append('\n').append(s.describe())
            }
            runOnUiThread { view.text = text }
        }.apply { isDaemon = true }.start()
    }

    private fun showGame() {
        val rom = rom ?: return showSetup()
        if (!gameLoaded || booting) {
            if (!booting) ensureGameLoaded { showGame() }
            return
        }
        forgetViews()
        Native.glSetPictureRect(0, 0, 0, 0)
        // Also reached from the background (a friend's room let us in while we're in WhatsApp, or a
        // configuration change while paused). A solo game then waits for onResume; a linked one runs
        // right away (muted until onResume), so the others' games don't wait for ours.
        Native.setPaused(!resumed && !inRoom)
        enterGameMode()

        val root = FrameLayout(this)
        Native.setSpeed(1)
        val surface = GLSurfaceView(this).apply {
            setEGLContextClientVersion(3)
            setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) { Native.glInit(); Native.glSetAmbient(!Glass.highContrast) }
                override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) = Native.glResize(width, height)
                override fun onDrawFrame(unused: GL10?) = Native.glDraw(prefs.getInt("filter", DEFAULT_FILTER))
            })
            // Draw only when a new GBA frame arrived (frameCb), not at the panel's 120 Hz.
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
        if (Build.VERSION.SDK_INT >= 30) {
            surface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(h: android.view.SurfaceHolder) {
                    try { h.surface.setFrameRate(59.73f, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE) } catch (_: Throwable) {}
                }
                override fun surfaceChanged(h: android.view.SurfaceHolder, f: Int, w: Int, hh: Int) {}
                override fun surfaceDestroyed(h: android.view.SurfaceHolder) {}
            })
        }
        gl = surface
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val screenW = resources.displayMetrics.widthPixels
        // Landscape keeps a side panel on each edge for the stick and buttons, so
        // they never cover the picture (wide phones only lose a few percent of size).
        val side = (ControlsView.SIDE_PANEL_DP * dp).toInt()
        val surfaceLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        var cutL = 0; var cutT = 0; var cutR = 0
        root.addView(surface, surfaceLp) // the GL surface covers the whole screen; it draws the ambient layer and the letterboxed picture
        val overlay = ControlsView(this, keys)
        controls = overlay
        overlay.dpadMode = prefs.getBoolean("dpad", false)
        overlay.hapticsEnabled = prefs.getBoolean("haptics", true)
        overlay.highContrast = Glass.refreshHighContrast(this)
        overlay.controlsOpacity = prefs.getFloat("controlsOpacity", 1f)
        overlay.onUnavailableTap = { toast("Fast-forward is off during link play") }
        overlay.fastForwardEnabled = !inRoom
        overlay.fastForwardLabel = "4×"
        overlay.onFastForward = { on -> if (!inRoom) Native.setSpeed(if (on) 4 else 1) }
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The picture keeps the rect it had when the surface was inset: the same rect goes to the renderer
        // (surface pixels) and to the controls (they keep clear of it).
        fun layoutPicture() {
            val w = root.width.toFloat()
            val h = root.height.toFloat()
            if (w <= 0f || h <= 0f) return
            val area = if (portrait) RectF(0f, 60 * dp + cutT, w, 60 * dp + cutT + screenW * 2 / 3f)
                       else RectF(side + cutL.toFloat(), 0f, w - side - cutR, h)
            val scale = minOf(area.width() / Native.WIDTH, area.height() / Native.HEIGHT)
            val gw = Native.WIDTH * scale
            val gh = Native.HEIGHT * scale
            val rw = Math.round(gw); val rh = Math.round(gh)
            val rl = Math.round(area.centerX() - gw / 2); val rt = Math.round(area.centerY() - gh / 2)
            overlay.gameRect = RectF(rl.toFloat(), rt.toFloat(), (rl + rw).toFloat(), (rt + rh).toFloat())
            Native.glSetPictureRect(rl, rt, rw, rh)
            surface.requestRender()
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPicture() }

        // Status + menu: a top bar in portrait; in landscape they sit at the top of
        // the side panels, clear of the picture.
        val status = label(if (inRoom) roomLabel else SOLO, 13f, colors.muted).apply {
            maxLines = if (portrait) 1 else 3
            // The ambient layer behind it is a bright-ish blur of the game: give the text a glass chip.
            background = Glass.control(dp, 12f)
            setPadding((10 * dp).toInt(), (6 * dp).toInt(), (10 * dp).toInt(), (6 * dp).toInt())
        }
        statusLine = status
        val menuButton = Button(this).apply {
            text = "⋯"
            textSize = 20f
            setTextColor(colors.text)
            isAllCaps = false
            minWidth = 0; minHeight = 0; minimumWidth = 0; minimumHeight = 0
            setPadding(0, 0, 0, 0)
            stateListAnimator = null
            background = Glass.control(dp, 14f)
            contentDescription = "Game menu"
            setOnClickListener { gameMenu() }
        }
        if (portrait) {
            val bar = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((12 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), 0)
            }
            bar.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            bar.addView(menuButton, LinearLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt()))
            root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        } else {
            root.addView(status, FrameLayout.LayoutParams(side - (16 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = (8 * dp).toInt(); leftMargin = (8 * dp).toInt()
            })
            root.addView(menuButton, FrameLayout.LayoutParams((48 * dp).toInt(), (48 * dp).toInt(), Gravity.TOP or Gravity.END).apply {
                topMargin = (6 * dp).toInt(); rightMargin = (8 * dp).toInt()
            })
        }
        // Camera cutout (S23 Ultra punch-hole): keep the picture and the panels clear of it.
        var statusLp: FrameLayout.LayoutParams? = null
        var menuLp: FrameLayout.LayoutParams? = null
        var barView: View? = null
        if (!portrait) {
            statusLp = status.layoutParams as FrameLayout.LayoutParams
            menuLp = menuButton.layoutParams as FrameLayout.LayoutParams
        } else {
            barView = status.parent as View
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val cut = insets.displayCutout
            val l = cut?.safeInsetLeft ?: 0
            val r = cut?.safeInsetRight ?: 0
            val t = cut?.safeInsetTop ?: 0
            cutL = l; cutR = r; cutT = t
            if (Build.VERSION.SDK_INT >= 30) overlay.bottomGestureInset = insets.getInsets(WindowInsets.Type.systemGestures()).bottom
            if (portrait) {
                barView?.setPadding((12 * dp).toInt(), (4 * dp).toInt() + t, (8 * dp).toInt(), 0)
                overlay.safeInsets = Rect(0, t, 0, 0)
            } else {
                statusLp?.leftMargin = l + (8 * dp).toInt()
                menuLp?.rightMargin = (8 * dp).toInt() + r
                overlay.safeInsets = Rect(l, 0, r, 0)
            }
            layoutPicture()
            statusLp?.let { status.layoutParams = it }
            menuLp?.let { menuButton.layoutParams = it }
            v.onApplyWindowInsets(insets)
            insets
        }
        setScreen("game", root)
        root.requestApplyInsets()
        startFrameClock()
    }

    // Render pacing: one requestRender per new emulated frame, so the GPU and panel idle between frames.
    private var frameClockOn = false
    private var lastDrawn = -1L
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!frameClockOn) return
            val n = Native.frameCount()
            if (n != lastDrawn) { lastDrawn = n; gl?.requestRender() }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun startFrameClock() {
        if (frameClockOn || gl == null) return
        frameClockOn = true
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    private fun stopFrameClock() {
        frameClockOn = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
    }

    // ---------------------------------------------------------------- game menu

    private var panel: MenuPanel? = null

    private fun openPanel(dim: Float = 0.5f): MenuPanel {
        panel?.dismiss()
        return MenuPanel(this, dim) { Glass.currentFrame() }.also { panel = it }
    }

    private fun gameMenu() = mainMenu(openPanel())

    private fun filterIndex() = prefs.getInt("filter", DEFAULT_FILTER).coerceIn(0, FILTERS.size - 1)

    /** Sections: Game, Display & controls, Link (when hosting or in a room), Other. */
    private fun mainMenu(p: MenuPanel) {
        val inLink = inRoom || linkAttached
        val off = "Off during link play"
        val game = mutableListOf(
            MenuRow("Save state", enabled = !inLink, reason = off, chevron = true) { stateMenu(p, save = true) },
            MenuRow("Load state", enabled = !inLink, reason = off, chevron = true) { stateMenu(p, save = false) },
        )
        val auto = autosave.autoFile()
        if (auto != null) {
            val ago = autosave.agoText(auto)
            game += MenuRow("Load autosave ($ago)…", enabled = !inLink, reason = off) {
                p.dismiss()
                val msg = if (autosave.matchesSave(auto)) "Goes back to where the game was $ago. Anything since then is lost, except what you saved in the game itself."
                    else "Goes back to where the game was $ago. " + "Your in-game save is newer than this. If you save in the game after loading, that newer save is replaced."
                confirm("Load autosave?", msg, "Load") {
                    loadSnapshot(auto, "Autosave loaded")
                }
            }
        }
        val dpad = prefs.getBoolean("dpad", false)
        val display = listOf(
            MenuRow("Screen: ${FILTERS[filterIndex()]}", chevron = true) { filterMenu(p) },
            MenuRow("Controls: ${if (dpad) "D-pad" else "Thumbstick"}", chevron = true) { controlsMenu(p) },
            MenuRow("Volume", chevron = true) { volumeMenu(p) },
        )
        val sections = mutableListOf(MenuSection("Game", game), MenuSection("Display & controls", display))
        val l = link
        if (l != null || inRoom) {
            val rows = mutableListOf<MenuRow>()
            if (l?.hosting == true) {
                rows += MenuRow("Invite players…") { p.dismiss(); showInvite() }
                rows += MenuRow("Close room…") { p.dismiss(); closeRoom() }
            } else rows += MenuRow("Leave room") { p.dismiss(); leaveRoom() }
            sections += MenuSection("Link", rows)
        }
        sections += MenuSection("Other", listOf(
            MenuRow("Back to lobby") { p.dismiss(); showHome() },
            MenuRow("Soft reset…", destructive = true) { p.dismiss(); softResetDialog() },
        ))
        p.show("Game menu", sections)
    }

    private fun filterMenu(p: MenuPanel) {
        // A light scrim so the picture stays visible while choosing.
        val q = openPanel(0.4f)
        val cur = filterIndex()
        val rows = FILTERS.indices.map { i ->
            MenuRow(FILTERS[i], detail = FILTER_INFO[i] + if (i == DEFAULT_FILTER) " · Recommended" else "", checked = i == cur) {
                prefs.edit().putInt("filter", i).apply()
                lastDrawn = -1
                gl?.requestRender()
                q.dismiss()
            }
        }
        q.show("Screen", listOf(MenuSection(null, rows))) { openPanel().let { mainMenu(it) } }
    }

    private fun controlsMenu(p: MenuPanel) {
        val dpad = prefs.getBoolean("dpad", false)
        val haptics = prefs.getBoolean("haptics", true)
        val opacity = prefs.getFloat("controlsOpacity", 1f)
        fun move(on: Boolean) {
            prefs.edit().putBoolean("dpad", on).apply()
            controls?.dpadMode = on
            controlsMenu(p)
        }
        fun setOpacity(v: Float) {
            prefs.edit().putFloat("controlsOpacity", v).apply()
            controls?.controlsOpacity = v
            controlsMenu(p)
        }
        p.show("Controls", listOf(
            MenuSection("Movement", listOf(
                MenuRow("Thumbstick", detail = "Appears under your thumb", checked = !dpad) { move(false) },
                MenuRow("D-pad", detail = "A fixed cross, eight directions", checked = dpad) { move(true) },
            )),
            MenuSection("Feedback", listOf(
                MenuRow("Haptics", detail = "A small tick on each button press", value = if (haptics) "On" else "Off") {
                    prefs.edit().putBoolean("haptics", !haptics).apply()
                    controls?.hapticsEnabled = !haptics
                    controlsMenu(p)
                },
            )),
            MenuSection("Controls opacity", listOf(0.6f, 0.8f, 1f).map { v ->
                MenuRow("${(v * 100).toInt()}%", checked = abs(opacity - v) < 0.01f) { setOpacity(v) }
            }),
        )) { mainMenu(p) }
    }

    private fun volumeMenu(p: MenuPanel) {
        val bar = SeekBar(this).apply {
            max = 100
            progress = (prefs.getFloat("volume", 0.8f) * 100).toInt()
            setPadding((24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt())
            progressTintList = ColorStateList.valueOf(Pal.accent)
            thumbTintList = ColorStateList.valueOf(Pal.accentText)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    Native.setVolume(v / 100f)
                    prefs.edit().putFloat("volume", v / 100f).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        p.show("Volume", listOf(MenuSection(null, view = bar))) { mainMenu(p) }
    }

    // ---------------------------------------------------------------- save / load state

    private fun stateFile(slot: Int): java.io.File? {
        val r = rom ?: return null
        return java.io.File(java.io.File(filesDir, "states/${r.sha1}").apply { mkdirs() }, "slot$slot.ss")
    }

    private fun thumbFile(slot: Int): java.io.File? = stateFile(slot)?.let { java.io.File(it.parentFile, "slot$slot.png") }

    /** "Today 14:32", "Yesterday 09:05", or "12 Oct 14:32". */
    private fun savedText(t: Long): String {
        val now = java.util.Calendar.getInstance()
        val then = java.util.Calendar.getInstance().apply { timeInMillis = t }
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(t))
        val days = (now.get(java.util.Calendar.YEAR) * 366 + now.get(java.util.Calendar.DAY_OF_YEAR)) -
            (then.get(java.util.Calendar.YEAR) * 366 + then.get(java.util.Calendar.DAY_OF_YEAR))
        return when (days) {
            0 -> "Today $time"
            1 -> "Yesterday $time"
            else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).format(java.util.Date(t)) + " $time"
        }
    }

    private fun stateMenu(p: MenuPanel, save: Boolean) {
        if (inRoom || linkAttached) { toast("Save states are off during link play."); return }
        if (!gameLoaded || booting) return
        val rows = (1..3).map { n ->
            val f = stateFile(n) ?: return
            val used = f.exists()
            val bmp = if (used) thumbFile(n)?.takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) } else null
            MenuRow("Slot $n", detail = if (used) savedText(f.lastModified()) else "Empty",
                enabled = save || used, hasThumb = true, thumb = bmp) {
                p.dismiss()
                if (save) {
                    if (used) confirm("Replace slot $n?", "The state saved there now is lost.", "Replace") { saveSlot(n) }
                    else saveSlot(n)
                } else {
                    val slot = stateFile(n)
                    val msg = if (slot != null && !autosave.matchesSave(slot)) "Unsaved progress since then is lost. " + "Your in-game save is newer than this. If you save in the game after loading, that newer save is replaced." else "Unsaved progress since then is lost."
                    confirm("Load slot $n?", msg, "Load") { loadSlot(n) }
                }
            }
        }
        p.show(if (save) "Save state" else "Load state", listOf(MenuSection(null, rows))) { mainMenu(p) }
    }

    private fun saveSlot(n: Int) {
        val f = stateFile(n) ?: return
        val png = thumbFile(n) ?: return
        Thread {
            // The picture first, so the thumbnail matches the saved moment.
            val px = ShortArray(Native.WIDTH * Native.HEIGHT)
            val haveFrame = try { Native.copyFrame(px) } catch (e: Throwable) { false }
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            val ok = try { Native.saveState(tmp.absolutePath) && tmp.renameTo(f) } catch (e: Throwable) { false }
            if (!ok) tmp.delete() else autosave.stampSave(f)
            if (ok) {
                png.delete()
                if (haveFrame) try {
                    val argb = IntArray(px.size) {
                        val c = px[it].toInt()
                        val r = (c shr 11) and 31; val g = (c shr 5) and 63; val b = c and 31
                        Color.rgb(r * 255 / 31, g * 255 / 63, b * 255 / 31)
                    }
                    val bmp = android.graphics.Bitmap.createBitmap(argb, Native.WIDTH, Native.HEIGHT, android.graphics.Bitmap.Config.ARGB_8888)
                    val ptmp = java.io.File(png.parentFile, png.name + ".tmp")
                    java.io.FileOutputStream(ptmp).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    ptmp.renameTo(png)
                } catch (_: Throwable) {}
            }
            runOnUiThread { if (!isDestroyed) toast(if (ok) "Saved to slot $n" else "Couldn't save the state. Try again.", long = !ok) }
        }.start()
    }

    private fun loadSlot(n: Int) {
        val f = stateFile(n) ?: return
        if (inRoom || linkAttached) return
        Thread {
            val ok = try { Native.loadState(f.absolutePath) } catch (e: Throwable) { false }
            runOnUiThread { if (!isDestroyed) toast(if (ok) "Loaded slot $n" else "Couldn't load that state. The slot may be damaged.", long = !ok) }
        }.start()
    }

    private fun softResetDialog(reason: String? = null) {
        val back = if (reason != null && gameLoaded && autosave.beforeLinkFile() != null) "Go back to before the link" to { goBackToBeforeLink() } else null
        confirm(reason ?: "Soft reset?", "Goes back to the title screen (A+B+Start+Select). Anything since your last in-game save is lost.",
            "Soft reset", destructive = true, neutral = back) {
            Thread {
                keys.touch = Native.A or Native.B or Native.START or Native.SELECT
                Thread.sleep(700)
                keys.touch = 0
            }.start()
        }
    }

    // ---------------------------------------------------------------- autosave

    private fun addBackToLinkButton(d: GlassDialog) {
        if (gameLoaded && autosave.beforeLinkFile() != null) d.neutral("Go back to before the link") { goBackToBeforeLink() }
    }

    /** Leaves the room (detaching the cable) and loads the snapshot taken right before the link. */
    private fun goBackToBeforeLink() {
        val f = autosave.beforeLinkFile() ?: return
        val go = {
            if (link != null || inRoom) leaveRoom()
            loadSnapshot(f, "Back to before the link")
        }
        // The game saves by itself during some link activities (a trade, for example). Going back would undo
        // that in the running game, and saving afterwards would replace the save that holds it.
        if (autosave.matchesSave(f)) go()
        else confirm("Your game saved during the link", "For example after a trade. Going back returns to before that. If you then save in the game, the save made during the link is replaced.", "Go back") { go() }
    }

    private fun loadSnapshot(f: java.io.File, doneText: String) {
        if (!gameLoaded || booting) return
        val ago = autosave.agoText(f)
        autosave.load(f) { ok ->
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (ok) { autosave.booted(); toast("$doneText (saved $ago)") } else toast("Couldn't load that save.", long = true)
            }
        }
    }

    /** A periodic or leaving snapshot of the solo game; a tiny "Autosaved" in the status line when [quiet] is false. */
    private fun autoSnapshot(quiet: Boolean = true) {
        if (!gameLoaded || booting || inRoom || linkAttached || linkSnapping) return
        autosave.snapshot(AutoSave.Kind.AUTO) { ok ->
            if (ok && !quiet) runOnUiThread {
                if (isDestroyed || inRoom) return@runOnUiThread
                statusLine?.text = "Autosaved"
                ticker.postDelayed({ if (!inRoom && statusLine?.text == "Autosaved") statusLine?.text = SOLO }, 2000)
            }
        }
    }

    // ---------------------------------------------------------------- hosting (phone link)

    private fun playerName() = prefs.getString("name", "")?.takeIf { it.isNotBlank() } ?: "Player"

    private fun romId(r: RomInfo) = RomId(r.sha1, r.title, r.code, r.size)

    private fun hostGame() {
        val rom = rom ?: return showSetup()
        if (link?.hosting == true) return showInvite()
        if (inRoom || link != null) return leaveFirst("Host a new game?") { hostGame() }
        ensureGameLoaded {
            val name = playerName()
            log("host: opening a room as $name")
            startLink(true, Codes.newInvite(name, "$name's room", romId(rom), 4, emptyList()))
            showInvite()
            askNotifications()
        }
    }

    private fun startLink(hosting: Boolean, invite: Invite) {
        val rom = rom ?: return
        val gen = ++linkGen
        roster = emptyList()
        linkNote = ""
        seenInviteVersion = 0
        seenReplyVersion = 0
        sentReplyVersion = -1
        warnedReplyVersion = -1
        if (!hosting) lastInvite = invite
        val l = PhoneLink(this, hosting, invite, playerName(), romId(rom), prefs.getBoolean("stun", false), prefs.getBoolean("stayOnWifi", false), Tap(gen, hosting)) { onLinkUpdate(it) }
        link = l
        l.start()
        syncService() // keeps the link on the network while the app is in the background
    }

    private fun showInvite() {
        val l = link?.takeIf { it.hosting } ?: return showHome()
        leaveGameMode()
        forgetViews()
        val c = column()
        val lc = card(topGap = 0)
        val title = headline("Opening your room…")
        lc.addView(title)
        val cable = CableView(this)
        lc.addView(cable)
        lc.addView(caption("Send this invite code to your friends (WhatsApp, SMS…). They paste it in Link Play → Join a game:"))
        val code = codeBox()
        lc.addView(code)
        val share = button("Share invite", primary = true) {
            link?.code?.takeIf { it.isNotEmpty() }?.let { log("host: sharing the invite"); share(inviteMessage(it), "Send the invite") }
        }
        val copy = button("Copy") { link?.code?.takeIf { it.isNotEmpty() }?.let { log("host: invite copied"); copy("Link Play invite", inviteMessage(it)) } }
        lc.addView(row(share, copy))
        val players = label("", 14f, colors.muted)
        lc.addView(players)
        c.addView(lc)
        c.addView(heading("Can't connect? Ask your friend for their reply code:"))
        c.addView(button("Paste reply code") { pasteReply() })
        val note = label("", 14f)
        c.addView(note)
        val info = caption("")
        c.addView(info)
        c.addView(row(button("Start playing") { showGame() }, button("Close room", danger = true) { closeRoom() }))
        glassScreen("invite", c)
        titleView = title
        codeView = code
        codeButtons = listOf(share, copy)
        playersView = players
        cableView = cable
        noteView = note
        infoView = info
        fillInvite(l)
    }

    private fun fillInvite(l: PhoneLink) {
        val open = l.code.isNotEmpty()
        val ready = open && !l.offline // offline, the invite's addresses are dead: don't hand it out
        titleView?.text = if (open) "Your room is open" else "Opening your room…"
        codeView?.apply {
            text = when {
                ready -> l.code
                open -> "No network right now. Your invite shows here again once this phone is back online."
                else -> l.progress.ifEmpty { "Getting your invite ready…" }
            }
            setTextColor(if (ready) colors.text else colors.muted)
        }
        codeButtons.forEach { it.isEnabled = ready }
        val list = roster.ifEmpty { listOf(RoomPlayer(0, playerName(), true)) }
        val first = list.first()
        val second = list.getOrNull(1)
        cableView?.set("P${first.linkId + 1} ${first.name}", second?.let { "P${it.linkId + 1} ${it.name}" }, second != null)
        playersView?.text = if (list.size > 2) "Also in the room: " + list.drop(2).joinToString(" · ") { "P${it.linkId + 1} ${it.name}" } +
            "   (${list.size}/${l.invite.maxPlayers})" else "${list.size}/${l.invite.maxPlayers} players"
        noteView?.text = if (ready && l.progress.isNotEmpty()) l.progress else linkNote
        infoView?.text = if (open) hostInfo(l) else ""
    }

    /** Which network the room uses and what friends can reach, in plain words. */
    private fun hostInfo(l: PhoneLink): String {
        val net = l.net ?: return ""
        if (net.network == null) return "No network: connect to Wi-Fi or mobile data."
        val c = l.candidates
        val v6 = c.any { it.kind == Candidate.Kind.IPV6 }
        val lan = c.any { it.kind == Candidate.Kind.LAN }
        val public4 = c.any { it.kind == Candidate.Kind.MAPPED }
        val via = mutableListOf("IPv6 " + if (v6) "✓" else "✗")
        if (lan) via += if (net.label == "Wi-Fi") "Wi-Fi LAN" else "LAN"
        l.routerPort?.let { via += "router port " + if (it) "✓" else "✗" }
        if (public4 && l.routerPort != true) via += "public IPv4 ✓"
        val lines = mutableListOf(linkUses(l, net), "Reachable via: " + via.joinToString(" · "))
        when {
            net.isVpn -> lines += "A VPN is on, so friends can only reach you through it. Turn it off to play directly."
            !v6 && !public4 -> lines += "Friends in other places can't reach this network. Your friend must be on the same Wi-Fi as you, " +
                "or you both switch to mobile data (turn Wi-Fi off) and you host again."
        }
        strictAdvice(l, net)?.let { lines += it }
        // Every guest connects to the host, so in a bigger room the most reachable phone should host. Only
        // while there is room for more, the host can be reached from elsewhere at all, and nobody joined
        // over Wi-Fi (on one Wi-Fi, moving the host to mobile data would break that path).
        if (roster.size < l.invite.maxPlayers && (v6 || public4) && !l.lanGuest && !(net.isCellular && net.hasIpv6)) {
            lines += "Tip: playing with 3 or 4 in different places? Everyone connects through the host, so a phone on mobile data with IPv6 is the best host."
        }
        return lines.joinToString("\n")
    }

    /**
     * The STUN self-test (Advanced, opt-in) found a NAT that makes a new port for every friend, so our
     * public IPv4 is no use to friends. Nothing to say when the router opened a port (that works through
     * any NAT) or IPv6 does the job anyway.
     */
    private fun strictAdvice(l: PhoneLink, net: NetEnv.Snapshot): String? {
        if (l.nat?.strict != true || l.routerPort == true) return null
        val host = l.invite.hostName
        // Mobile data has IPv6 although this network has none (e.g. the user chose to stay on Wi-Fi).
        val toMobile = if (!net.isCellular && net.mobileHasIpv6) " Turn Wi-Fi off to use mobile data, which has IPv6." else ""
        return when {
            l.hosting && net.hasIpv6 -> "Your network is strict for IPv4, so friends reach you over IPv6."
            l.hosting -> "Your network is strict for IPv4 and has no IPv6, so friends in other places can't reach you.$toMobile"
            net.hasIpv6 -> if (l.invite.candidates.any { it.kind == Candidate.Kind.IPV6 }) "Your network is strict for IPv4, so you reach $host over IPv6." else null
            else -> "Your network is strict for IPv4 and has no IPv6, so your reply code can't open a path to you.$toMobile"
        }
    }

    private fun linkUses(l: PhoneLink, net: NetEnv.Snapshot): String {
        val s = "Link uses ${net.label} (${if (net.hasIpv6) "IPv6" else "no IPv6"})"
        return if (l.onMobileData) "$s. Your Wi-Fi has no IPv6, so only the game goes over mobile data." else s
    }

    private fun closeRoom() {
        if (roster.size <= 1) return leaveRoom()
        confirm("Close your room?", "Everyone else in it loses the link.", "Close room", destructive = true) { leaveRoom() }
    }

    private fun pasteReply() {
        val l = link?.takeIf { it.hosting } ?: return
        if (l.code.isEmpty()) return toast("Your room isn't ready yet.")
        if (l.offline) return toast("No network right now: connect to Wi-Fi or mobile data first.")
        // The clipboard is read only now, because the host asked for it.
        val text = clipboardText()
        if (text.isNullOrBlank()) return replyDialog(null)
        replyProblem(Codes.find(text, l.key))?.let { replyDialog(it) }
    }

    private fun replyDialog(problem: String?) = codeDialog(
        "Paste reply code", "Paste your friend's reply code (it starts with LP1R-). The whole message is fine.",
        "Reply code (LP1R-…)", "Add", problem, link?.key,
    ) { replyProblem(it) }

    /** Null when [r] is a reply for this room and its addresses are being probed; else what to tell the host. */
    private fun replyProblem(r: CodeResult): String? = when (r) {
        is Reply -> if (addReply(r)) null else "Your room isn't ready yet. Try again in a moment."
        is Invite -> if (r.roomId == link?.key?.roomId) "That's your own invite. Your friend's reply code starts with LP1R-."
                     else "That's an invite to someone's room, not a reply code. Your friend's reply code starts with LP1R-."
        is CodeError -> r.message
    }

    private fun addReply(r: Reply): Boolean {
        val l = link?.takeIf { it.hosting } ?: return false
        val who = r.guestName.ifBlank { "your friend" }
        when (l.addReply(r)) {
            PhoneLink.ReplyResult.NOT_READY -> return false
            // It got in with the invite alone: keep the news line ("… connected directly") as it is.
            PhoneLink.ReplyResult.CONNECTED -> toast("${who.replaceFirstChar { it.uppercase() }} is already connected to your room.")
            PhoneLink.ReplyResult.PROBING -> {
                log("host: reply code pasted for $who")
                linkStatus("Opening a path to $who…")
                toast("Opening a path to $who…")
            }
        }
        return true
    }

    // ---------------------------------------------------------------- joining (phone link)

    private fun joinDialog() = codeDialog(
        "Join a game", "Paste the invite your friend sent you. The whole message is fine.",
        "Invite code (LP1I-…)", "Join", null, null,
    ) { r ->
        when (r) {
            is Invite -> { joinInvite(r); null }
            is Reply -> "That's a reply code. It goes back to the friend who hosts the room; paste their invite (LP1I-…) here."
            is CodeError -> r.message
        }
    }

    private fun joinInvite(inv: Invite) {
        val rom = rom ?: return showSetup()
        val l = link
        if (l?.hosting == true && inv.roomId == l.key.roomId) return toast("That's your own room's invite. Send it to your friends.", long = true)
        if (l != null && !l.hosting && inv.roomId == l.key.roomId) {
            val old = l.invite
            val same = inv.secret.contentEquals(old.secret) && inv.candidates.toSet() == old.candidates.toSet()
            if (inRoom || same) return if (inRoom) showGame() else showJoining()
            // Still connecting, and this is the host's newer invite (its network changed): the
            // attempt running now only tries the old addresses. Start over with the new ones; a
            // new attempt also means a new reply code, which the joining screen shows.
            leaveRoom()
            toast("Trying ${inv.hostName}'s new invite. Your reply code is new too: send it if ${inv.hostName} asks.", long = true)
            return joinInvite(inv)
        }
        if (!inv.romMatches(rom.sha1)) {
            val theirs = inv.romSha1Prefix.joinToString("") { "%02x".format(it) }.take(12)
            return alert("Different ROM", "${inv.hostName}'s room plays a different ROM file (fingerprint $theirs). " +
                "Yours is ${rom.title} (${rom.shortHash}). Both phones need the exact same ROM file.")
        }
        if (inRoom || l != null) return leaveFirst("Join ${inv.roomName}?") { joinInvite(inv) }
        ensureGameLoaded {
            startLink(false, inv)
            showJoining()
            askNotifications()
        }
    }

    private fun showJoining() {
        val l = link?.takeIf { !it.hosting } ?: return showHome()
        leaveGameMode()
        forgetViews()
        val c = column()
        val lc = card(topGap = 0)
        val title = headline("Connecting to ${l.invite.roomName}…")
        lc.addView(title)
        val cable = CableView(this)
        lc.addView(cable)
        val info = label("", 14f, colors.muted)
        lc.addView(info)
        val prompt = heading("")
        lc.addView(prompt)
        val code = codeBox()
        lc.addView(code)
        val share = button("Share reply", primary = true) {
            link?.code?.takeIf { it.isNotEmpty() }?.let { sentReply(); share(replyMessage(it), "Send the reply code") }
        }
        val copy = button("Copy") { link?.code?.takeIf { it.isNotEmpty() }?.let { sentReply(); copy("Link Play reply code", replyMessage(it)) } }
        lc.addView(row(share, copy))
        val note = label("", 14f)
        lc.addView(note)
        c.addView(lc)
        c.addView(button("Cancel", danger = true) { leaveRoom() })
        glassScreen("joining", c)
        titleView = title
        cableView = cable
        infoView = info
        promptView = prompt
        codeView = code
        codeButtons = listOf(share, copy)
        noteView = note
        fillJoining(l)
    }

    private fun fillJoining(l: PhoneLink) {
        val inv = l.invite
        val host = inv.hostName
        val n = inv.candidates.size
        val via = l.connectedVia
        titleView?.text = when {
            via == null -> "Connecting to ${inv.roomName}…"
            l.connectedAfterReply -> "Connected after $host added your reply code"
            else -> "Connected directly"
        }
        val me = playerName()
        cableView?.set("P1 $host", if (linkId >= 0) "P${linkId + 1} $me" else "You · $me", via != null)
        val lines = mutableListOf(
            when {
                via != null -> "Joining ${inv.roomName}…"
                n == 0 -> "$host's invite has no address to try"
                else -> "Trying $n address${if (n == 1) "" else "es"} (${inv.candidates.map { kindName(it.kind) }.distinct().joinToString()})"
            }
        )
        l.net?.let { net ->
            lines += if (net.network == null) "No network: connect to Wi-Fi or mobile data." else linkUses(l, net)
            if (via == null) guestHint(l, net)?.let { lines += it }
            if (net.network != null) strictAdvice(l, net)?.let { lines += it }
        }
        infoView?.text = lines.joinToString("\n")
        promptView?.apply {
            when {
                via != null -> { text = ""; visibility = View.GONE }
                // The host hasn't answered the invite alone. We can't tell why, only that the reply code
                // can help (it holds an address the host can reach): say that plainly and loudly.
                l.hostSilent && l.replyCanHelp && replySent(l) ->
                    loud("Reply code sent. Once $host taps Paste reply code, you should connect within seconds.", colors.ok)
                l.hostSilent && l.replyCanHelp -> loud("No answer from $host's phone yet. Send $host your reply code now.", colors.bad)
                // Offline, or nothing in our reply that the host could reach: the hints above say what to do.
                l.hostSilent -> quiet("No answer from $host's phone yet.")
                else -> quiet("If it doesn't connect within a few seconds, send this reply code back to $host:")
            }
        }
        val made = l.code.isNotEmpty()
        val ready = made && !l.offline // offline, the reply's addresses are dead: don't hand it out
        val needed = via == null // connected: the reply code has done its job (or wasn't needed)
        codeView?.apply {
            visibility = if (needed) View.VISIBLE else View.GONE
            text = when {
                ready -> l.code
                made -> "No network right now. Your reply code shows here again once this phone is back online."
                else -> l.progress.ifEmpty { "Getting your reply code ready…" }
            }
            setTextColor(if (ready) colors.text else colors.muted)
        }
        codeButtons.forEach {
            it.visibility = if (needed) View.VISIBLE else View.GONE
            it.isEnabled = ready && needed
        }
        noteView?.text = linkNote
    }

    /** A big, bold line in [color]. */
    private fun TextView.loud(line: String, color: Int) {
        visibility = View.VISIBLE
        text = line
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(color)
    }

    /** A small, plain hint. */
    private fun TextView.quiet(line: String) {
        visibility = View.VISIBLE
        text = line
        textSize = 13f
        typeface = Typeface.DEFAULT
        setTextColor(colors.muted)
    }

    /** Guest: Share / Copy handed out the reply code on screen. */
    private fun sentReply() {
        val l = link?.takeIf { !it.hosting } ?: return
        log("guest: reply code v${l.replyVersion} ${if (l.hostSilent) "sent after the prompt" else "sent"}")
        sentReplyVersion = l.replyVersion
        if (screen == "joining") fillJoining(l)
        if (linkNote == replyChanged(l)) { // that advice is now followed
            linkNote = ""
            noteView?.text = ""
        }
    }

    private fun replyChanged(l: PhoneLink) =
        "Your phone's addresses changed, so your reply code changed. Send the new one to ${l.invite.hostName}."

    /**
     * Why this guest may not get through, in plain words; null when nothing stands out. We can't
     * tell whether we share the host's Wi-Fi (a matching 192.168.1.x proves nothing), so the
     * wording fits both; on the same Wi-Fi the LAN path connects within a second anyway.
     */
    private fun guestHint(l: PhoneLink, net: NetEnv.Snapshot): String? {
        val host = l.invite.hostName
        val c = l.invite.candidates
        val hostV6 = c.any { it.kind == Candidate.Kind.IPV6 }
        val hostPublic = c.any { it.kind == Candidate.Kind.MAPPED }
        val hostLan = c.any { it.kind == Candidate.Kind.LAN }
        return when {
            net.network == null -> null
            c.isEmpty() -> "$host's phone can't be reached directly: send $host the reply code below."
            !hostV6 && !hostPublic -> "$host's phone can only be reached on its own Wi-Fi. If you're not on it, join the same Wi-Fi, " +
                "or you both switch to mobile data and $host hosts again."
            hostV6 && !net.hasIpv6 && !hostPublic -> "Your network has no IPv6, so it can't reach $host's phone" +
                (if (hostLan) " unless you're on the same Wi-Fi" else "") + ". Turn Wi-Fi off to use mobile data, then join again."
            else -> null
        }
    }

    private fun kindName(k: Candidate.Kind) = when (k) {
        Candidate.Kind.LAN -> "LAN"
        Candidate.Kind.IPV6 -> "IPv6"
        Candidate.Kind.MAPPED -> "public IPv4"
    }

    // ---------------------------------------------------------------- codes, clipboard, sharing

    private fun gameWord(): String {
        val t = rom?.title?.uppercase().orEmpty()
        return if ("POKEMON" in t || t.startsWith("PKM")) "Pokémon" else "GBA"
    }

    // Share and Copy hand out the code alone. Friends paste it in Link Play, or share it straight to the
    // app from their chat (see handleIntent); Codes.find takes it from any text around it.
    private fun inviteMessage(code: String) = code

    private fun replyMessage(code: String) = code

    /**
     * A dialog to paste a code into. [onCode] gets what Codes.find made of the text and
     * returns null when it's done, or a problem to show (the dialog then stays open).
     */
    private fun codeDialog(title: String, intro: String, hint: String, action: String, problem: String?,
                           hostKey: RoomKey?, onCode: (CodeResult) -> String?) {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(caption(intro).apply { textSize = 14f })
        val input = codeField(hint)
        c.addView(input, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * dp).toInt() })
        val error = label(problem ?: "", 13f, colors.bad).apply { visibility = if (problem == null) View.GONE else View.VISIBLE }
        c.addView(button("Paste") {
            // Read only on this tap.
            val text = clipboardText()
            if (text.isNullOrBlank()) toast("Nothing to paste: copy the message first.") else input.setText(text)
        })
        c.addView(error)
        var dlg: GlassDialog? = null
        dlg = gdialog().title(title).view(c).secondary("Cancel").primary(action, dismiss = false) {
            val text = input.text.toString()
            val trouble = if (text.isBlank()) "Paste the code first." else onCode(Codes.find(text, hostKey))
            if (trouble == null) dlg?.dismiss() else {
                error.text = trouble
                error.visibility = View.VISIBLE
            }
        }.show()
    }

    private fun clipboardText(): String? = try {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(this)?.toString()
    } catch (e: Exception) { // some phones refuse clipboard access in odd states
        null
    }

    private fun copy(what: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(what, text))
        if (Build.VERSION.SDK_INT < 33) toast("Copied") // newer Android shows its own confirmation
    }

    private fun share(text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        // We take shared text ourselves (pasted codes); leave us out of our own share sheet.
        val chooser = Intent.createChooser(send, title)
            .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(this, MainActivity::class.java)))
        try {
            startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            copy(title, text)
        }
    }

    /**
     * Codes that reach us from outside: "Share → Link Play" from WhatsApp etc. (ACTION_SEND). An invite
     * opens the join flow, a reply goes to our room. Also the notification's Leave.
     */
    private fun handleIntent(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_SEND && action != LinkService.ACTION_LEAVE) return
        if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return // an old one, reopened from recents
        if (action == LinkService.ACTION_LEAVE && intent.getStringExtra(LinkService.EXTRA_TOKEN) != LinkService.TOKEN) return // not from our notification
        val text = when (action) {
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            else -> null
        }
        setIntent(Intent(this, MainActivity::class.java)) // handled; don't redo it
        if (action == LinkService.ACTION_LEAVE) return leaveFromNotification()
        if (text.isNullOrBlank()) return
        log("opened from a share") // never the code itself
        if (rom == null) {
            pendingCode = text
            showSetup()
            return
        }
        handleCode(text)
    }

    private fun handleCode(text: String) {
        val l = link
        when (val r = Codes.find(text, l?.takeIf { it.hosting }?.key)) {
            is Invite -> joinInvite(r)
            is Reply -> if (l?.hosting == true) {
                if (screen != "invite") showInvite()
                replyProblem(r)?.let { replyDialog(it) }
            } else {
                alert("This is a reply code", "Reply codes go back to the phone that hosts the room. " +
                    "To join a friend's room, paste the invite they sent you in Join a game.")
            }
            is CodeError -> alert("Couldn't use that code", r.message)
        }
    }

    /** The notification's Leave (guest) or Close room (host, which asks first when others are in it). */
    private fun leaveFromNotification() {
        val l = link ?: return syncService() // nothing to leave; makes sure the service is gone too
        log("notification: ${if (l.hosting) "Close room" else "Leave"} tapped")
        if (l.hosting) closeRoom() else {
            leaveRoom()
            toast("You left the room.")
        }
    }

    // ---------------------------------------------------------------- staying connected in the background

    /**
     * Keeps LinkService (a foreground service, so Android leaves the link its network in the background)
     * in step with the link: running while one exists, with words that match it, stopped once it's gone.
     * A new service is only started while we're resumed (Android refuses it from the background); onResume
     * calls this again, so one that couldn't start, or that Android refused, gets another try.
     */
    private fun syncService() {
        val l = link
        if (l == null) {
            if (serviceText != null || LinkService.running) LinkService.stop(this)
            serviceText = null
            return
        }
        val text = "${if (l.hosting || inRoom) "In" else "Joining"} ${l.invite.roomName} · tap to return"
        if (text == serviceText && LinkService.running) return
        if (!resumed && !LinkService.running) return
        serviceText = if (LinkService.start(this, text, l.hosting)) text else null
    }

    /** Android 13+: the service's notification needs this. Asked once, when hosting or joining; the link works without it. */
    private fun askNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean("askedNotifications", false)) return
        prefs.edit().putBoolean("askedNotifications", true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_NOTIFICATIONS) return
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        log("notifications ${if (granted) "allowed" else "not allowed (the link still stays up in the background)"}")
        if (granted) {
            serviceText = null // post the notification again, now that it can show
            syncService()
        }
    }

    // ---------------------------------------------------------------- rooms (all backends)

    /** A phone link's events (transport / room threads). Once that link ended ([gen] is stale) they are dropped. */
    private inner class Tap(private val gen: Int, private val hosting: Boolean) : LinkListener {
        private val live: Boolean get() = gen == linkGen

        private fun ui(block: () -> Unit) = runOnUiThread { if (live) block() }

        override fun onStatus(text: String) = ui { linkStatus(text) }

        override fun onJoined(roomName: String, linkId: Int, players: List<RoomPlayer>) = ui {
            link?.backend?.let { backend = it }
            joined(roomName, linkId, players, hosting)
        }

        override fun onLinkFrame(frame: ByteArray) {
            if (live) Native.netPush(frame)
        }

        override fun onPartnerLeft(name: String) = ui { partnerLeft(name) }

        // No Native.netUnplug here: linkLost -> leaveRoom detaches the cable at once anyway, and
        // an unplug still pending in a paused emulator would hit the next room's new cable.
        override fun onSessionLost(reason: String) = ui { linkLost(reason) }

        override fun onFatal(message: String) = ui { linkFailed(if (hosting) "Couldn't open the room" else "Couldn't join", message) }
    }

    /** PhoneLink's own news (main thread): code ready or remade, progress, setup failure. */
    private fun onLinkUpdate(l: PhoneLink) {
        if (l !== link) return
        l.failure?.let { return linkFailed(if (l.hosting) "Couldn't open the room" else "Couldn't join", it) }
        l.backend?.let { backend = it }
        if (l.hosting && l.inviteVersion != seenInviteVersion) {
            seenInviteVersion = l.inviteVersion
            linkNote = "Your network changed, so your invite changed. Send the new invite to friends who haven't joined yet."
            toast(linkNote, long = true)
        }
        if (!l.hosting && l.replyVersion != seenReplyVersion) {
            seenReplyVersion = l.replyVersion
            // Only a reply already handed out is worth a word (once per one sent): the host probes
            // its addresses, and those changed.
            if (!inRoom && sentReplyVersion >= 0 && warnedReplyVersion != sentReplyVersion) {
                warnedReplyVersion = sentReplyVersion
                linkNote = replyChanged(l)
                toast(linkNote, long = true)
            }
        }
        when {
            screen == "invite" && l.hosting -> fillInvite(l)
            screen == "joining" && !l.hosting -> fillJoining(l)
            screen == "home" -> homeStatus?.text = homeText()
            // Waiting in a solo game: the status line is all the guest sees.
            screen == "game" && !l.hosting && !inRoom && l.hostSilent && l.connectedVia == null ->
                statusLine?.text = guestWait(l, "send your reply code (⋯ → Back to lobby → Show reply code)")
        }
    }

    private fun linkStatus(text: String) {
        linkNote = text
        noteView?.text = text
        if (screen == "home") homeStatus?.text = text
        if (!inRoom) statusLine?.text = text
    }

    private fun joined(roomName: String, linkId: Int, players: List<RoomPlayer>, hosting: Boolean) {
        if (booting) { // a resume load is still queued: attach the cable only after it has run
            pendingJoined = JoinedArgs(roomName, linkId, players, hosting)
            return
        }
        ensureGameLoaded(resume = false) {}
        if (!linkAttached && !linkSnapDone) {
            // The snapshot before the cable goes in is taken off the UI thread; then this runs again.
            pendingJoined = JoinedArgs(roomName, linkId, players, hosting)
            if (!linkSnapping) {
                linkSnapping = true
                autosave.snapshot(AutoSave.Kind.BEFORE_LINK) {
                    runOnUiThread {
                        linkSnapping = false
                        val p = pendingJoined
                        if (isDestroyed || p == null) return@runOnUiThread
                        pendingJoined = null
                        linkSnapDone = true
                        joined(p.roomName, p.linkId, p.players, p.hosting)
                    }
                }
            }
            return
        }
        if (!linkAttached) {
            // Whatever an earlier room's cable queued after its last poll belongs to that room.
            Native.netPoll()
            Native.netAttach(linkId)
            linkAttached = true
        } else if (linkId != this.linkId) {
            Native.netSetLinkId(linkId) // someone left and the room renumbered us
        }
        this.linkId = linkId
        val wasIn = inRoom
        inRoom = true
        Native.setSpeed(1)
        controls?.fastForwardOn = false
        controls?.fastForwardEnabled = false
        startPolling()
        if (!wasIn) {
            // Waiting in a solo game, maybe in the background (sending the reply code): a linked
            // game runs even there (muted), or the others' games would wait for ours.
            if (gameLoaded) {
                Native.setPaused(false)
                if (screen != "game") lobbyRunning = true // leaveRoom undoes this
            }
            syncService() // "Joining …" becomes "In …"
        }
        if (hosting && wasIn && players.size > roster.size) {
            val names = players.drop(roster.size).joinToString { it.name }
            toast("$names joined!")
        }
        roster = players
        roomLabel = "Room \"$roomName\" · " + players.joinToString { "P${it.linkId + 1} ${it.name}${if (it.connected) "" else " (reconnecting)"}" }
        statusLine?.text = roomLabel
        homeStatus?.text = homeText()
        val l = link
        if (l != null && screen == "invite" && l.hosting) fillInvite(l)
        // The host stays on the invite screen; a guest goes straight to the game.
        if (!wasIn) log("${if (hosting) "host" else "guest"}: in room \"$roomName\" as P${linkId + 1} with ${players.joinToString { it.name }}")
        if (!hosting && !wasIn && screen != "game") {
            val how = when {
                l == null || l.hosting || l.connectedVia == null -> ""
                l.connectedAfterReply -> " (connected after ${l.invite.hostName} added your reply code)"
                else -> " (connected directly)"
            }
            Toast.makeText(this, "Joined$how! In the game: Start → MPLAYER to link up.", Toast.LENGTH_LONG).show()
            showGame()
        } else if (!wasIn) {
            updateBackHandling() // Back on the home screen now returns to the game
        }
    }

    private fun partnerLeft(name: String) {
        statusLine?.text = "$name left the link"
        if (link != null) linkStatus("$name left the room")
        if (screen == "game") softResetDialog("$name left the link. If the game is stuck waiting for them, soft reset.")
    }

    /** A guest lost the friend's room after being in it. */
    private fun linkLost(reason: String) {
        val inv = lastInvite
        val host = inv?.hostName ?: "the host"
        log("guest: lost the link to $host's room: $reason")
        leaveRoom()
        val why = when (reason) {
            "timeout" -> "Nothing came from $host's phone for 20 seconds, so the connection is gone."
            "too far behind" -> "The connection was too slow to keep up."
            else -> reason.replaceFirstChar { it.uppercase() }
        }
        statusLine?.text = "Link lost: $why"
        val d = gdialog().title("Lost the link to $host's room").message(why)
        if (inv != null && !reason.contains("closed the room", ignoreCase = true)) {
            d.secondary("OK").primary("Try again") { joinInvite(inv) }
        } else {
            d.primary("OK")
        }
        addBackToLinkButton(d)
        d.show()
    }

    /** A phone link could not start (no network, wrong ROM, room full, host unreachable…). */
    private fun linkFailed(title: String, message: String) {
        val retry = link?.takeIf { !it.hosting }?.invite
        log("${if (link?.hosting == true) "host" else "guest"}: $title: $message")
        leaveRoom()
        // GuestSession reports every transport ending before we're in the room as
        // "Couldn't reach <host>'s phone: <reason>"; only some reasons mean unreachable.
        val prefix = retry?.let { "Couldn't reach ${it.hostName}'s phone: " }
        val reason = if (prefix != null && message.startsWith(prefix)) message.removePrefix(prefix) else null
        if (retry != null && reason != null) {
            val host = retry.hostName
            val (text, again) = when {
                reason == "could not reach the host" || reason == "timeout" -> "Couldn't reach $host's phone. " +
                    "Check that both phones have a network, then try again and send $host the new reply code it shows." to true
                "refused" in reason -> "$host's room didn't let this phone in. It may have just closed, or too many " +
                    "phones are connecting at once. Check with $host that the room is open, then try again." to true
                "closed the room" in reason -> "$host closed the room. Ask $host for a new invite if they host again." to false
                else -> message to true
            }
            homeStatus?.text = text
            val d = gdialog().title(title).message(text)
            if (again) d.secondary("OK").primary("Try again") { joinInvite(retry) }
            else d.primary("OK")
            d.show()
            return
        }
        homeStatus?.text = message
        alert(title, message)
    }

    /** Runs [then] after leaving the current room, asking first. */
    private fun leaveFirst(title: String, then: () -> Unit) {
        gdialog().title(title)
            .message(if (link?.hosting == true) "This closes your room." else "This leaves your current room.")
            .secondary("Cancel").primary("Continue") { leaveRoom(); then() }.show()
    }

    /**
     * Starts the game if it isn't running, then runs [then] (on the UI thread). The first start after the
     * app opens continues the newest snapshot when [resume] and there is one, before any cable goes in.
     */
    private fun ensureGameLoaded(resume: Boolean = true, then: () -> Unit) {
        val rom = rom ?: return
        if (booting) return
        if (gameLoaded) return then()
        if (!Native.load(rom.path, RomStore.saveFile(this, rom).absolutePath)) {
            toast("Could not start the game.", long = true)
            return
        }
        applyVolume()
        Native.start()
        Native.setPaused(true)
        gameLoaded = true
        autosave.booted()
        val wanted = resume && resumeArmed && !skipResumeOnce
        val f = if (wanted) autosave.newestUsable() else null
        // An autosave older than the in-game save would undo it; start from the in-game save instead.
        if (wanted && f == null && autosave.newest() != null) toast("Started from your in-game save: it is newer than the last autosave.", long = true)
        resumeArmed = false
        skipResumeOnce = false
        if (f == null) return then()
        booting = true
        val ago = autosave.agoText(f)
        autosave.load(f) { ok ->
            runOnUiThread {
                booting = false
                if (isDestroyed) return@runOnUiThread
                val waiting = pendingJoined
                if (waiting != null) pendingJoined = null
                if (ok) { autosave.booted(); toast("Continued where you left off (saved $ago)", long = true) }
                else toast("Couldn't load the save; starting normally.", long = true)
                then()
                if (waiting != null && link != null) joined(waiting.roomName, waiting.linkId, waiting.players, waiting.hosting)
            }
        }
    }

    /** Closes / leaves the phone link: transport, port mapping, mobile-data request, foreground service and all. */
    private fun leaveRoom() {
        val l = link
        link = null
        linkGen++
        l?.close()
        syncService() // stops LinkService
        backend = null
        stopPolling()
        if (linkAttached) Native.netDetach()
        linkAttached = false
        linkSnapDone = false
        pendingJoined = null
        inRoom = false
        controls?.fastForwardEnabled = true
        linkId = -1
        roomLabel = ""
        roster = emptyList()
        linkNote = ""
        statusLine?.text = SOLO
        if (screen != "game" && lobbyRunning) {
            if (gameLoaded) Native.setPaused(true) // solo games don't run behind the lobby
            lobbyRunning = false
        }
        // Nor in the background: the linked game kept running there, the solo one waits for onResume.
        if (screen == "game" && !resumed && gameLoaded) Native.setPaused(true)
        if (screen == "home" || screen == "invite" || screen == "joining") showHome()
    }

    // Shuttle link traffic from the local GBA to the room. Each thread runs only while it is
    // [pollThread]: a stop followed at once by a start (leave one room, join the next) must not
    // leave the old thread draining too, or two threads could hand commands over out of order.
    private fun startPolling() {
        if (pollThread != null) return
        val t = Thread {
            val me = Thread.currentThread()
            while (pollThread === me) {
                Native.netWait(50)
                // Re-check after waking: the next room's cable may already have queued output that
                // belongs to the next polling thread.
                if (pollThread !== me) break
                val data = Native.netPoll()
                // Backend first, then the check: leaveRoom clears [backend] before stopping us and the
                // next room sets its own only after, so a batch never reaches the next room's backend.
                val b = backend
                if (data != null && b != null && pollThread === me) split(data, b)
            }
        }.apply { name = "link-poll"; isDaemon = true }
        pollThread = t
        t.start()
    }

    private fun stopPolling() {
        pollThread = null
    }

    private fun split(data: ByteArray, b: LinkBackend) {
        var off = 0
        while (off + 2 <= data.size) {
            val len = if (data[off].toInt() == 1) 4 else 18
            if (off + len > data.size) break
            b.sendLink(data.copyOfRange(off, off + len))
            off += len
        }
    }

    // ---------------------------------------------------------------- files

    private fun pickFile(request: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, request)
    }

    /** Asks first when there is a save to replace: the picked file takes its place. */
    private fun importSave() {
        val r = rom
        if (r == null || RomStore.saveFile(this, r).length() == 0L) return pickFile(REQ_SAVE)
        confirm("Replace your in-game save?", "The file you pick replaces this phone's save for ${r.title}. " +
            "To keep the current one, tap Export save first.", "Choose file") { pickFile(REQ_SAVE) }
    }

    /** Writes the in-game save to a file the user picks (Downloads, Drive…): a backup, or to move phones. */
    private fun exportSave() {
        val r = rom ?: return toast("Pick a ROM first.")
        if (RomStore.saveFile(this, r).length() == 0L) return toast("No in-game save yet: save in the game first.")
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_TITLE, RomStore.exportName(r))
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_EXPORT)
    }

    @Deprecated("Activity result API without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        homeStatus?.text = when (requestCode) {
            REQ_ROM -> "Importing ROM…"
            REQ_EXPORT -> "Exporting save…"
            else -> "Importing save…"
        }
        Thread {
            val result = runCatching {
                if (requestCode == REQ_ROM) {
                    if (gameLoaded) error("Restart the app to change the ROM while a game is running.")
                    rom = RomStore.import(this, uri)
                    "Loaded ${rom!!.title}"
                } else if (requestCode == REQ_EXPORT) {
                    RomStore.exportSave(this, rom ?: error("Pick a ROM first."), uri)
                    "Save exported"
                } else {
                    if (gameLoaded) error("Import the save before starting the game (restart the app).")
                    RomStore.importSave(this, rom ?: error("Pick a ROM first."), uri)
                    "Save imported"
                }
            }
            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(this, it, Toast.LENGTH_SHORT).show()
                    showHome()
                    pendingCode?.let { code -> pendingCode = null; handleCode(code) }
                }.onFailure { homeStatus?.text = it.message; Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    // ---------------------------------------------------------------- system UI, back, gamepads

    private fun enterGameMode() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    /** The game's current frame becomes the lobby's backdrop and the hero card's thumbnail (files/states/<sha1>/last-frame.png). */
    private fun saveScene() {
        val r = rom ?: return
        val f = Glass.currentFrame() ?: return
        sceneRaw = f
        sceneSha = r.sha1
        Glass.saveLastFrame(this, r.sha1, f)
    }

    private fun leaveGameMode() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.show(WindowInsets.Type.systemBars())
        else @Suppress("DEPRECATION") { window.decorView.systemUiVisibility = 0 }
        stopFrameClock()
        Native.setSpeed(1)
        gl = null
        controls = null
        statusLine = null
        if (gameLoaded && screen == "game") {
            saveScene()
            autoSnapshot() // back to the lobby
            Native.setPaused(!inRoom) // keep linked games running in the lobby, and in the background
            lobbyRunning = inRoom
        }
    }

    /** Back: the GBA's B button in the game; the lobby screens step back towards it. */
    private fun backAction(): (() -> Unit)? = when {
        screen == "game" -> { { keys.pulse(Native.B) } }
        screen == "invite" || screen == "joining" -> { { showHome() } } // the room / connection carries on
        screen == "home" && inRoom -> { { showGame() } }
        // Still connecting (a guest, or a host before its room is up): before Android 12, Back on
        // the root activity finishes it, and onDestroy would end the link (and the reply code sent).
        screen == "home" && link != null -> { { moveTaskToBack(true) } }
        else -> null
    }

    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return
        val dispatcher = onBackInvokedDispatcher
        (backCallback as? OnBackInvokedCallback)?.let { dispatcher.unregisterOnBackInvokedCallback(it) }
        backCallback = null
        if (backAction() == null) return
        // Looked up again on use: the room may have started or ended since.
        val cb = OnBackInvokedCallback {
            val action = backAction()
            if (action != null) action() else moveTaskToBack(true) // what Android does for a root activity
        }
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        backCallback = cb
    }

    @Deprecated("Pre-Android 13 back handling")
    override fun onBackPressed() {
        val action = backAction()
        if (action != null) action() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun padKey(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> Native.A
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X -> Native.B
        KeyEvent.KEYCODE_BUTTON_Y -> Native.A
        KeyEvent.KEYCODE_BUTTON_L1 -> Native.L
        KeyEvent.KEYCODE_BUTTON_R1 -> Native.R
        KeyEvent.KEYCODE_BUTTON_START -> Native.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> Native.SELECT
        KeyEvent.KEYCODE_DPAD_UP -> Native.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Native.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Native.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Native.RIGHT
        else -> 0
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val k = if (screen == "game") padKey(keyCode) else 0
        if (k == 0) return super.onKeyDown(keyCode, event)
        keys.pad = keys.pad or k
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val k = if (screen == "game") padKey(keyCode) else 0
        if (k == 0) return super.onKeyUp(keyCode, event)
        keys.pad = keys.pad and k.inv()
        return true
    }

    private var stickKeys = 0
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (screen != "game" || event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return super.onGenericMotionEvent(event)
        val x = event.getAxisValue(MotionEvent.AXIS_X).let { if (abs(it) < 0.5f) event.getAxisValue(MotionEvent.AXIS_HAT_X) else it }
        val y = event.getAxisValue(MotionEvent.AXIS_Y).let { if (abs(it) < 0.5f) event.getAxisValue(MotionEvent.AXIS_HAT_Y) else it }
        var k = 0
        if (x < -0.5f) k = k or Native.LEFT
        if (x > 0.5f) k = k or Native.RIGHT
        if (y < -0.5f) k = k or Native.UP
        if (y > 0.5f) k = k or Native.DOWN
        keys.pad = (keys.pad and stickKeys.inv()) or k
        stickKeys = k
        return true
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        panel?.dismiss()
        if (screen == "home") rememberName()
        when (screen) {
            "game" -> showGame() // rebuild the layout for the new orientation
            "home" -> showHome()
            "setup" -> showSetup()
            "invite" -> showInvite()
            "joining" -> showJoining()
        }
    }

    /** A linked game's GBA is running: it carries on in the background (muted), or the others' games would stall on ours. */
    private fun linkedGameRunning() = gameLoaded && inRoom && (screen == "game" || lobbyRunning)

    /** The game makes sound only while we're resumed. */
    private fun applyVolume() = Native.setVolume(if (resumed) prefs.getFloat("volume", 0.8f) else 0f)

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
        resumed = false
        if (screen == "game" && gameLoaded) saveScene()
        stopFrameClock()
        gl?.onPause()
        applyVolume()
        if (!gameLoaded) return
        Native.setPaused(true) // solo games pause (and write the save)
        // A linked game runs on, muted (LinkService keeps the link on the network); the pause just
        // now still wrote its save.
        if (linkedGameRunning()) Native.setPaused(false)
        else autoSnapshot() // solo game: leaving the app
    }

    override fun onResume() {
        super.onResume()
        ticker.removeCallbacks(tick)
        ticker.postDelayed(tick, AutoSave.TICK_MS)
        resumed = true
        controls?.highContrast = Glass.refreshHighContrast(this)
        gl?.queueEvent { Native.glSetAmbient(!Glass.highContrast) }
        gl?.onResume()
        lastDrawn = -1
        startFrameClock()
        applyVolume()
        if (gameLoaded && (screen == "game" || (inRoom && lobbyRunning))) Native.setPaused(false)
        if (screen == "game") enterGameMode()
        syncService() // one that couldn't start in the background, or that Android refused, gets another try
    }

    override fun onStart() {
        super.onStart()
        if (stoppedInLink) log("app: back in the foreground (${linkState()})")
        stoppedInLink = false
    }

    override fun onStop() {
        super.onStop()
        if (link == null) return
        stoppedInLink = true
        log("app: in the background during a link (${linkState()}); GBA ${if (linkedGameRunning()) "running, muted" else "paused"}; " +
            "foreground service ${if (LinkService.running) "on" else "OFF, so Android may cut the link's network"}")
    }

    /** For the log: what the link is doing (never a code). */
    private fun linkState(): String {
        val l = link ?: return "the link ended meanwhile"
        val who = if (l.hosting) "hosting" else "guest"
        return if (inRoom) "$who, in ${l.invite.roomName} with ${roster.size} player${if (roster.size == 1) "" else "s"}" else "$who, connecting"
    }

    override fun onDestroy() {
        panel?.dismiss()
        stopPolling()
        linkGen++
        link?.close() // transport, room / session, port mapping, mobile-data request, network watch
        link = null
        backend = null
        LinkService.stop(this) // nothing left for it to keep up
        serviceText = null
        ticker.removeCallbacks(tick)
        autosave.shutdown(wait = !isChangingConfigurations)
        if (gameLoaded) Native.stop()
        super.onDestroy()
    }

    private fun log(msg: String) {
        Log.i(PhoneLink.TAG, msg)
    }

    companion object {
        private val FILTERS = arrayOf("Pixel", "Sharp", "Smooth", "FSR", "MMPX", "AI")
        private val FILTER_INFO = arrayOf("Original pixels", "Crisp pixels, smooth scaling", "Soft and blurry",
            "Sharp upscaling (AMD FSR 1.0)", "Smooth pixel art (MMPX)", "Neural upscaling (Anime4K)")
        private const val DEFAULT_FILTER = 3
        private const val REQ_ROM = 1
        private const val REQ_SAVE = 2
        private const val REQ_NOTIFICATIONS = 3
        private const val REQ_EXPORT = 4
        private const val SOLO = "Solo · host or join a game from the lobby (⋯)"
        @Suppress("unused") private fun le16(b: ByteArray, off: Int) = ByteBuffer.wrap(b, off, 2).order(ByteOrder.LITTLE_ENDIAN).short
    }
}
