package com.ayaquick

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Date

/**
 * The quick menu itself: a sheet on the right edge with an icon rail (L1 / R1) and one page of controls,
 * laid out like the Steam Deck's quick access menu and rebuilt from AYASpace's own popup (Performance,
 * Controller, Device, Toggles, Settings). It is hosted either by [PanelActivity] or, on top of a running
 * game, by [QuickService]. Nothing here runs unless the sheet is on screen.
 */
class Panel(private val ctx: Context, private val host: Host) {

    interface Host {
        fun closePanel()
        /** Close the menu and start [intent]; false if nothing on the device handles it. */
        fun launch(intent: Intent): Boolean
    }

    enum class Page(val title: String, val icon: Int) {
        PERFORMANCE("Performance", Icon.BOLT), CONTROLLER("Controller", Icon.GAMEPAD), DEVICE("Device", Icon.SLIDERS),
        TOGGLES("Toggles", Icon.GRID), SETTINGS("Settings", Icon.GEAR)
    }

    private val store = Store(ctx)
    private val ui: Ui
    private val handler = Handler(Looper.getMainLooper())
    private var page = lastPage
    private val tabs = LinkedHashMap<Page, RailTab>()
    private lateinit var sheet: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var focusPark: View
    private lateinit var clock: TextView
    private lateinit var battery: BatteryView
    private lateinit var batteryText: TextView
    private var stats: Array<StatTile>? = null
    private var fanNow: TextView? = null
    private var fanBar: SeekBar? = null
    private var fanChoices: List<ChoiceView>? = null
    private var fanNote: TextView? = null
    private val fanPresets = intArrayOf(-1, 30, 60, 99)      // Auto, then the duties behind AYASpace's Low, High and Full
    private var volumeBar: SeekBar? = null
    private var brightnessBar: SeekBar? = null
    private var polled = ""
    private var shown = false
    private var closing = false
    private var ignoreBackUntil = 0L
    val root: View

    init {
        Aya.setAccent(store.accent)
        Firmware.init(ctx)
        ui = Ui(ctx, PAD_DP)
        root = buildShell()
        showPage(page)
    }

    // ================================================================ lifecycle

    /** Call when the sheet becomes visible: slides it in and starts the one-second clock. */
    fun onShown() {
        if (shown) return
        shown = true
        sheet.translationX = ui.pxf(SHEET_DP.toFloat())
        sheet.animate().translationX(0f).setDuration(150).setInterpolator(DecelerateInterpolator()).start()
        polled = poll()
        handler.post(ticker)
        focusFirst()
    }

    fun onHidden() {
        shown = false
        handler.removeCallbacksAndMessages(null)        // nothing ticks while the menu isn't on screen
    }

    /**
     * Slide the sheet out and tell the host. With [holdMs] the (now invisible) window stays that long after the
     * request and swallows Back, for a trigger button that AYASpace follows up with a Back of its own.
     */
    fun close(holdMs: Long = 0L) {
        if (closing) return
        closing = true
        onHidden()
        if (holdMs > 0) { ignoreBackFor(holdMs); root.animate().alpha(0f).setDuration(110).start() }
        sheet.animate().translationX(ui.pxf(SHEET_DP.toFloat())).setDuration(110).withEndAction {
            if (holdMs > 110) handler.postDelayed({ host.closePanel() }, holdMs - 110) else host.closePanel()
        }.start()
    }

    /** Swallow Back for the next [ms] milliseconds. */
    fun ignoreBackFor(ms: Long) { ignoreBackUntil = android.os.SystemClock.uptimeMillis() + ms }

    private val ticker = object : Runnable {
        override fun run() {
            if (!shown) return
            updateStatus()
            if (page == Page.PERFORMANCE) { Stats.requestLoadAndTemps(); updateStats() }
            syncSliders()
            val now = poll()
            if (now != polled) { polled = now; refresh() }
            handler.postDelayed(this, 1000)
        }
    }

    /** L1 / R1 switch pages, B (or Back) closes, like the Steam Deck. */
    private fun onKey(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                    val pages = Page.values()
                    val step = if (e.keyCode == KeyEvent.KEYCODE_BUTTON_L1) pages.size - 1 else 1
                    goTo(pages[(page.ordinal + step) % pages.size])
                }
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE -> {
                if (e.action == KeyEvent.ACTION_UP && android.os.SystemClock.uptimeMillis() >= ignoreBackUntil) close()
                return true
            }
            // Android would turn these into a "select" on the focused control (the stick clicks, which also open
            // the menu, and Y); only A selects here.
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y -> return true
        }
        return false
    }

    private inner class Root(c: Context) : FrameLayout(c) {
        override fun dispatchKeyEvent(event: KeyEvent): Boolean = onKey(event) || super.dispatchKeyEvent(event)
    }

    // ================================================================ shell

    private fun buildShell(): View {
        val hair = maxOf(1, ui.density.toInt())
        val frame = Root(ctx).apply {
            setBackgroundColor(SCRIM)
            setOnClickListener { close() }          // a tap outside the sheet closes it
        }
        sheet = ui.vbox().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Aya.BG_TOP, Aya.BG))
            isClickable = true                      // taps on the sheet never fall through to the scrim
            elevation = ui.pxf(18f)
        }

        // ---- top bar: the same lock-up as AYANEO RGB Control, plus the time
        val bar = ui.hbox().apply { setBackgroundColor(Aya.BAR); setPadding(ui.px(14), 0, ui.px(12), 0) }
        bar.addView(ImageView(ctx).apply { setImageResource(R.drawable.ayaneo_emblem); scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams(ui.px(31), ui.px(12)))
        bar.addView(ImageView(ctx).apply { setImageResource(R.drawable.ayaneo_wordmark); scaleType = ImageView.ScaleType.FIT_START; contentDescription = "AYANEO" },
            LinearLayout.LayoutParams(ui.px(62), ui.px(11)).apply { leftMargin = ui.px(9) })
        bar.addView(View(ctx).apply { setBackgroundColor(Aya.FILL_2) }, LinearLayout.LayoutParams(hair, ui.px(18)).apply { leftMargin = ui.px(11); rightMargin = ui.px(11) })
        bar.addView(ui.text("Quick", 16f, Aya.WHITE, weight = 800))
        bar.addView(ui.text("Menu", 16f, Aya.ACCENT, weight = 600), ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(4) })
        bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        clock = ui.text("", 13f, Aya.DIM, weight = 500).apply { maxLines = 1 }
        bar.addView(clock)
        // parks the controller focus while a page re-renders, so removing the focused view never scrolls the page
        focusPark = View(ctx).apply { isFocusable = false }
        bar.addView(focusPark, LinearLayout.LayoutParams(1, 1))
        sheet.addView(bar, ui.lp(h = ui.px(44)))
        sheet.addView(View(ctx).apply { setBackgroundColor(Aya.DIVIDER) }, ui.lp(h = hair))

        // ---- body: icon rail + the page
        val body = ui.hbox().apply { gravity = Gravity.TOP }
        val rail = ui.vbox().apply { setBackgroundColor(Aya.PANE); gravity = Gravity.CENTER_HORIZONTAL; setPadding(0, ui.px(9), 0, ui.px(9)) }
        rail.addView(ui.glyph("L1"), ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        for (p in Page.values()) {
            val t = RailTab(ctx, p.icon, p.title).apply { setOnClickListener { goTo(p) } }
            tabs[p] = t
            rail.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.px(48)).apply { if (p.ordinal == 0) topMargin = ui.px(7) })
        }
        rail.addView(ui.glyph("R1"), ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, top = 7))
        body.addView(rail, LinearLayout.LayoutParams(ui.px(RAIL_DP), ViewGroup.LayoutParams.MATCH_PARENT))
        body.addView(View(ctx).apply { setBackgroundColor(Aya.DIVIDER) }, LinearLayout.LayoutParams(hair, ViewGroup.LayoutParams.MATCH_PARENT))
        scroll = ScrollView(ctx).apply { isFillViewport = true; isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        content = ui.vbox().apply { setPadding(ui.px(PAD_DP), 0, ui.px(PAD_DP), ui.px(16)) }
        scroll.addView(content)
        body.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        sheet.addView(body, ui.lp(h = 0, weight = 1f))

        // ---- footer: battery on the left, controller hints on the right
        sheet.addView(View(ctx).apply { setBackgroundColor(Aya.DIVIDER) }, ui.lp(h = hair))
        val footer = ui.hbox().apply { setBackgroundColor(Aya.BAR); setPadding(ui.px(14), 0, ui.px(2), 0) }
        battery = BatteryView(ctx)
        footer.addView(battery, LinearLayout.LayoutParams(ui.px(23), ui.px(12)))
        batteryText = ui.text("", 12.5f, Aya.DIM, weight = 500).apply { maxLines = 1 }
        footer.addView(batteryText, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = ui.px(6) })
        footer.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        fun hint(key: String, label: String) {
            footer.addView(ui.glyph(key))
            footer.addView(ui.text(label, 12.5f, Aya.DIM, weight = 500).apply { setPadding(ui.px(6), 0, ui.px(12), 0) })
        }
        hint("A", "Select"); hint("B", "Close")
        sheet.addView(footer, ui.lp(h = ui.px(30)))

        frame.addView(sheet, FrameLayout.LayoutParams(ui.px(SHEET_DP), ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        frame.addView(View(ctx).apply { setBackgroundColor(Aya.DIVIDER) },
            FrameLayout.LayoutParams(hair, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END).apply { rightMargin = ui.px(SHEET_DP) })
        updateStatus()
        return frame
    }

    private fun updateStatus() {
        val t = DateFormat.getTimeFormat(ctx).format(Date())
        if (clock.text.toString() != t) clock.text = t
        Stats.readBattery(ctx)
        battery.set(Stats.battPct, Stats.charging)
        val b = if (Stats.battPct < 0) "" else "${Stats.battPct}%" + if (Stats.charging) " · Charging" else ""
        if (batteryText.text.toString() != b) batteryText.text = b
    }

    // ================================================================ pages

    fun goTo(p: Page) {
        showPage(p)
        focusFirst()
    }

    fun scrollTo(dp: Int) = scroll.post { scroll.scrollTo(0, ui.px(dp)) }

    /** Controller focus onto the first control of the page. */
    private fun focusFirst() {
        scroll.post { if (!scroll.isInTouchMode) content.focusSearchNear(0, 0)?.requestFocus() }
    }

    private fun showPage(p: Page) {
        page = p
        lastPage = p
        for ((k, t) in tabs) t.current = k == p
        buildContent()
        scroll.scrollTo(0, 0)
        polled = poll()
    }

    private fun buildContent() {
        content.removeAllViews()
        stats = null; fanNow = null; fanBar = null; fanChoices = null; fanNote = null; volumeBar = null; brightnessBar = null
        content.addView(ui.text(page.title, 18f, Aya.WHITE, weight = 700).apply { setPadding(ui.px(3), ui.px(12), 0, 0) })
        when (page) {
            Page.PERFORMANCE -> buildPerformance()
            Page.CONTROLLER -> buildController()
            Page.DEVICE -> buildDevice()
            Page.TOGGLES -> buildToggles()
            Page.SETTINGS -> buildSettings()
        }
    }

    /**
     * Re-render the current page in place after something changed underneath it: the scroll position stays put
     * and the controller focus lands back on the nearest control before the next frame is drawn.
     */
    private fun refresh() {
        val y = scroll.scrollY
        val focused = root.findFocus()
        val inContent = focused != null && isInside(focused, content)
        val loc = IntArray(2)
        if (inContent) {
            focused!!.getLocationOnScreen(loc)
            focusPark.isFocusable = true
            focusPark.requestFocus()
        }
        buildContent()
        scroll.scrollTo(0, y)
        content.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                content.viewTreeObserver.removeOnGlobalLayoutListener(this)
                scroll.scrollTo(0, y)
                if (inContent) (content.focusSearchNear(loc[0], loc[1]) ?: tabs[page])?.requestFocus()
                focusPark.isFocusable = false
            }
        })
    }

    /** Re-read the system a moment after a switch was flipped (radios take a second to come up). */
    private fun recheckSoon() {
        handler.postDelayed({ if (shown) { polled = poll(); refresh() } }, 2500)
    }

    /** Everything on the current page that can change behind the menu's back, as one comparable string. */
    private fun poll(): String = when (page) {
        Page.DEVICE -> "${Sys.wifiOn(ctx)}${Sys.btOn()}${Sys.airplaneOn(ctx)}${Sys.canWrite(ctx)}"
        Page.TOGGLES -> "${Sys.wifiOn(ctx)}${Sys.btOn()}${Sys.airplaneOn(ctx)}${Sys.canWrite(ctx)}${Sys.dndAccess(ctx)}${Sys.dndOn(ctx)}" +
            "${Sys.autoRotate(ctx)}${Sys.nightLight(ctx)}${Sys.locationOn(ctx)}${QuickService.instance != null}"
        Page.SETTINGS -> "${QuickService.instance != null}${Sys.canWrite(ctx)}${Sys.dndAccess(ctx)}"
        Page.PERFORMANCE -> "${QuickService.instance != null}"
        Page.CONTROLLER -> ""
    }

    /** Volume keys and other apps move these while the menu is open; follow them unless a finger is on the slider. */
    private fun syncSliders() {
        volumeBar?.let { if (!it.isPressed) Sys.volume(ctx).let { v -> if (it.progress != v) it.progress = v } }
        brightnessBar?.let { if (!it.isPressed) (Sys.brightness(ctx) - 1).let { v -> if (it.progress != v) it.progress = v } }
    }

    // ================================================================ performance

    private fun buildPerformance() {
        val tiles = arrayOf("FPS", "CPU", "GPU", "Fan", "Memory", "Battery").map { StatTile(ui, it) }.toTypedArray()
        stats = tiles
        content.addView(ui.rows(tiles.map { it.view }, 2, equalWidth = true, gapDp = 3), ui.lp(top = 8))
        Stats.readAll(ctx)
        Stats.requestLoadAndTemps()
        updateStats(read = false)

        content.addView(ui.sectionTitle("Performance mode"))
        val modes = PerfMode.values()
        val current = currentMode()
        val about = ui.text(modes.getOrNull(current)?.desc ?: "Pick how the CPU and GPU should run.", 12.5f, Aya.DIM).apply { setPadding(ui.px(4), ui.px(7), ui.px(4), 0) }
        content.addView(choices(modes.map { it.label }, current, 2) { i ->
            store.perfMode = i
            Firmware.setPerformance(modes[i])
            about.text = modes[i].desc
        }.first)
        content.addView(about)

        content.addView(ui.sectionTitle("Fan"))
        if (!Firmware.rootAllowed) {
            val card = ui.card()
            val line = ui.hbox()
            line.addView(ui.text("Run by AYASpace", 15f, weight = 500), ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight = 1f))
            fanNow = ui.text(fanText(), 14f, Aya.DIM, weight = 500)
            line.addView(fanNow)
            card.addView(line)
            card.addView(ui.text("Setting the fan needs the root helper, which is switched off in Settings.", 12.5f, Aya.DIMMER).apply { setPadding(0, ui.px(6), 0, 0) })
            content.addView(card)
        } else {
            val manual = store.fanManual
            val (grid, views) = choices(listOf("Auto", "Low", "High", "Full"), if (manual < 0) 0 else fanPresets.indexOf(manual), 4, 13f) { i -> applyFan(fanPresets[i], fromSlider = false) }
            fanChoices = views
            content.addView(grid)
            val slider = ui.sliderRow("Fan speed", 0, 99, if (manual >= 0) manual else if (Stats.fanOn) Stats.fan.coerceIn(0, 99) else 0, "%",
                format = { if (it == 0) "Off" else "$it%" }) { applyFan(it, fromSlider = true) }
            fanBar = slider.getChildAt(1) as SeekBar
            content.addView(ui.group(slider), ui.lp(top = 6))
            fanNote = note(fanNoteText())
            content.addView(fanNote)
        }

        content.addView(ui.sectionTitle("Performance overlay"))
        val svc = QuickService.instance
        if (svc != null) {
            content.addView(choices(HudView.LABELS, store.hudLevel, 2) { i -> store.hudLevel = i; svc.syncHud() }.first)
            content.addView(note("Drawn in the top left corner, on top of games. Detailed adds a frame-rate graph, per-core clocks, fan, memory and battery."))
        } else {
            content.addView(ui.group(ui.navRow("Performance overlay", "Off") { goTo(Page.SETTINGS) }))
            content.addView(note("The overlay is drawn by the button shortcut. Turn that on in Settings first."))
        }
    }

    /**
     * The mode in force: the one chosen here if the firmware's governor still agrees with it, otherwise what the
     * governor says (AYASpace puts its own back after a restart).
     */
    private fun currentMode(): Int {
        val mine = PerfMode.values().getOrNull(store.perfMode)
        val governor = Props.governor()
        return when {
            governor.isEmpty() -> store.perfMode
            mine != null && mine.governor == governor -> mine.ordinal
            // set by AYASpace: a GPU sitting at its top step while idle is one it has fixed there
            governor == PerfMode.MAX.governor -> if (Stats.gpuMhz * 1000 == GPU_MAX_KHZ && Stats.gpuLoad in 0..50) PerfMode.TURBO.ordinal else PerfMode.MAX.ordinal
            else -> -1
        }
    }

    private fun fanText() = if (!Stats.fanOn) "Off" else if (Stats.fan >= 0) "${Stats.fan}%" else ""

    /** [speed] -1 hands the fan back to AYASpace; anything else holds it at that duty. */
    private fun applyFan(speed: Int, fromSlider: Boolean) {
        store.fanManual = speed
        if (speed < 0) Firmware.setFanAuto() else Firmware.setFanManual(speed)
        if (!fromSlider && speed >= 0) fanBar?.progress = speed
        fanChoices?.forEachIndexed { k, c -> c.chosen = fanPresets[k] == speed }
        fanNote?.text = fanNoteText()
    }

    private fun fanNoteText() = if (store.fanManual < 0) "AYASpace sets the fan from the CPU temperature. Pick a speed to hold it yourself."
        else "Held at this speed whatever the temperature. Auto hands the fan back to AYASpace."

    private fun updateStats(read: Boolean = true) {
        val t = stats ?: return
        if (read) Stats.readAll(ctx)
        val s = Stats
        val deep = s.cpuLoad >= 0
        t[0].set(if (s.fps >= 0) Math.round(s.fps).toString() else "–", "", "frames / s")
        if (deep) t[1].set(s.cpuLoad.toString(), "%", (if (s.cpuTemp > 0) "${Math.round(s.cpuTemp)}°C · " else "") + String.format("%.2f GHz", s.cpuGhz))
        else t[1].set(if (s.cpuGhz > 0) String.format("%.2f", s.cpuGhz) else "–", "GHz", if (s.cpuPct >= 0) "${s.cpuPct}% of full speed" else "")
        t[2].set(if (s.gpuLoad >= 0) s.gpuLoad.toString() else "–", "%", (if (s.gpuTemp > 0) "${Math.round(s.gpuTemp)}°C · " else "") + (if (s.gpuMhz > 0) "${s.gpuMhz} MHz" else ""))
        t[3].set(if (!s.fanOn) "Off" else if (s.fan >= 0) s.fan.toString() else "–", if (s.fanOn && s.fan >= 0) "%" else "", if (store.fanManual >= 0) "held here" else "set by AYASpace")
        t[4].set(if (s.ramPct >= 0) s.ramPct.toString() else "–", "%", if (s.ramPct >= 0) String.format("%.1f of %.1f GB", s.ramUsedGb, s.ramTotalGb) else "")
        t[5].set(if (s.battTemp > 0) Math.round(s.battTemp).toString() else "–", "°C", if (s.watts > 0) String.format("%.1f W", s.watts) + (if (s.charging) " in" else " out") else "")
        fanNow?.let { f -> fanText().let { if (f.text.toString() != it) f.text = it } }
    }

    // ================================================================ controller

    private fun buildController() {
        if (!Firmware.available) { content.addView(note(Firmware.error ?: "The controller service is not available on this device.")); return }

        content.addView(ui.sectionTitle("Buttons"))
        content.addView(ui.group(
            ui.switchRow("Button vibration", "Buzz on every press", Firmware.keyVibrate() ?: store.keyVibrate) { store.keyVibrate = it; Firmware.setKeyVibrate(it) },
            ui.switchRow("Nintendo layout", "A/B and X/Y swapped", Firmware.abxySwap() == 1) { Firmware.setAbxySwap(if (it) 1 else 0) },
        ))

        content.addView(ui.sectionTitle("Triggers"))
        content.addView(choices(listOf("Digital", "Linear"), if (Firmware.triggerDigital()) 0 else 1, 2) { Firmware.setTriggerDigital(it == 0) }.first)
        // the firmware's own values where it publishes them; this menu's last ones otherwise
        Firmware.triggerZone(left = true, start = true).let { if (it >= 0) store.triggerStart = it }
        Firmware.triggerZone(left = true, start = false).let { if (it >= 0) store.triggerEnd = it }
        Firmware.stickDeadzone(true).let { if (it >= 0) store.leftStick = it }
        Firmware.stickDeadzone(false).let { if (it >= 0) store.rightStick = it }
        fun sendTriggers() { for (left in booleanArrayOf(true, false)) Firmware.setTriggerDeadzone(left, store.triggerStart, store.triggerEnd) }
        content.addView(ui.group(
            ui.sliderRow("Start deadzone", 0, 40, store.triggerStart.coerceIn(0, 40), "%") { store.triggerStart = it; sendTriggers() },
            ui.sliderRow("End deadzone", 0, 40, store.triggerEnd.coerceIn(0, 40), "%") { store.triggerEnd = it; sendTriggers() },
        ), ui.lp(top = 6))

        content.addView(ui.sectionTitle("Sticks"))
        content.addView(ui.group(
            ui.sliderRow("Left stick deadzone", 0, 40, store.leftStick.coerceIn(0, 40), "%") { store.leftStick = it; Firmware.setStickDeadzone(true, it) },
            ui.sliderRow("Right stick deadzone", 0, 40, store.rightStick.coerceIn(0, 40), "%") { store.rightStick = it; Firmware.setStickDeadzone(false, it) },
            ui.switchRow("Right stick as D-pad", null, Firmware.rightStickAsDpad() ?: store.rightStickDpad) { store.rightStickDpad = it; Firmware.setRightStickAsDpad(it) },
            ui.switchRow("D-pad as left stick", null, Firmware.dpadAsLeftStick() ?: store.dpadLeftStick) { store.dpadLeftStick = it; Firmware.setDpadAsLeftStick(it) },
        ))
        content.addView(note(if (Firmware.rootAllowed) "These are the firmware's live values. Deadzones, vibration and trigger mode are also written to AYASpace's own settings, so they survive a restart."
            else "These are the firmware's live values. AYASpace may put its own back after a restart."))
    }

    // ================================================================ device

    private fun buildDevice() {
        content.addView(ui.sectionTitle("Display and sound"))
        val display = ArrayList<View>()
        if (Sys.canWrite(ctx)) {
            val b = ui.sliderRow("Brightness", 1, 100, Sys.brightness(ctx), "%") { Sys.setBrightness(ctx, it) }
            brightnessBar = b.getChildAt(1) as SeekBar
            display += b
        } else display += statusRow("Brightness", "Needs your OK to change system settings", "Allow") { launch(Sys.writeSettingsScreen(ctx)) }
        val max = Sys.volumeMax(ctx).coerceAtLeast(1)
        val v = ui.sliderRow("Volume", 0, max, Sys.volume(ctx), "", format = { "${it * 100 / max}%" }) { Sys.setVolume(ctx, it) }
        volumeBar = v.getChildAt(1) as SeekBar
        display += v
        content.addView(ui.group(*display.toTypedArray()))

        content.addView(ui.sectionTitle("Connections"))
        content.addView(ui.group(
            ui.switchRow("Wi-Fi", null, Sys.wifiOn(ctx)) { Sys.setWifi(ctx, it); recheckSoon() },
            ui.switchRow("Bluetooth", null, Sys.btOn()) { Sys.setBt(it); recheckSoon() },
            if (Firmware.rootAllowed) ui.switchRow("Airplane mode", null, Sys.airplaneOn(ctx)) { Firmware.setAirplane(it); recheckSoon() }
            else ui.navRow("Airplane mode", if (Sys.airplaneOn(ctx)) "On" else "Off") { launch(Sys.airplaneScreen(), Sys.wirelessScreen()) },
        ))

        Sys.app(ctx, Sys.RGB_CONTROL)?.let { rgb ->
            content.addView(ui.sectionTitle("Lights"))
            content.addView(ui.group(ui.navRow("Stick lights", "RGB Control") { launch(rgb) }))
        }
    }

    // ================================================================ toggles

    private fun buildToggles() {
        val svc = QuickService.instance
        val canWrite = Sys.canWrite(ctx)
        val root = Firmware.rootAllowed

        fun switch(icon: Int, label: String, on: Boolean, flip: (Boolean) -> Unit) = TileView(ctx, icon, label).apply {
            this.on = on
            setOnClickListener { this.on = !this.on; flip(this.on); recheckSoon() }
        }
        fun opens(icon: Int, label: String, on: Boolean, vararg screens: Intent) = TileView(ctx, icon, label, opens = true).apply {
            this.on = on
            setOnClickListener { launch(*screens) }
        }
        val tiles = listOf(
            switch(Icon.WIFI, "Wi-Fi", Sys.wifiOn(ctx)) { Sys.setWifi(ctx, it) },
            switch(Icon.BLUETOOTH, "Bluetooth", Sys.btOn()) { Sys.setBt(it) },
            if (root || Sys.dndAccess(ctx)) switch(Icon.DND, "Do Not\nDisturb", Sys.dndOn(ctx)) { Sys.setDnd(ctx, it) }
            else opens(Icon.DND, "Do Not\nDisturb", Sys.dndOn(ctx), Sys.dndAccessScreen()),
            if (canWrite) switch(Icon.ROTATE, "Auto-rotate", Sys.autoRotate(ctx)) { Sys.setAutoRotate(ctx, it) }
            else opens(Icon.ROTATE, "Auto-rotate", Sys.autoRotate(ctx), Sys.writeSettingsScreen(ctx)),
            if (root) switch(Icon.PLANE, "Airplane", Sys.airplaneOn(ctx)) { Firmware.setAirplane(it) }
            else opens(Icon.PLANE, "Airplane", Sys.airplaneOn(ctx), Sys.airplaneScreen(), Sys.wirelessScreen()),
            if (root) switch(Icon.MOON, "Night Light", Sys.nightLight(ctx)) { Firmware.setNightLight(it) }
            else opens(Icon.MOON, "Night Light", Sys.nightLight(ctx), Sys.nightLightScreen(), Sys.settingsScreen()),
            if (root) switch(Icon.LOCATION, "Location", Sys.locationOn(ctx)) { Firmware.setLocation(it) }
            else opens(Icon.LOCATION, "Location", Sys.locationOn(ctx), Sys.locationScreen()),
            opens(Icon.HOTSPOT, "Hotspot", false, Sys.hotspotScreen(), Sys.wirelessScreen()),
            opens(Icon.CAST, "Cast", false, Sys.castScreen(), Sys.settingsScreen()),
        )
        content.addView(ui.rows(tiles, 3, equalWidth = true, gapDp = 3), ui.lp(top = 8))

        content.addView(ui.sectionTitle("Shortcuts"))
        fun shortcut(icon: Int, label: String, needsService: Boolean, run: () -> Unit) = TileView(ctx, icon, label, shortcut = true).apply {
            if (needsService && svc == null) alpha = 0.4f
            setOnClickListener {
                if (needsService && svc == null) Toast.makeText(ctx, "Turn on the button shortcut in Settings first", Toast.LENGTH_SHORT).show()
                else run()
            }
        }
        /** Close the menu first, so the system action lands on what was underneath it. */
        fun global(action: Int, delayMs: Long = 220) {
            val s = svc ?: return
            host.closePanel()
            Handler(Looper.getMainLooper()).postDelayed({ s.performGlobalAction(action) }, delayMs)
        }
        content.addView(ui.rows(listOf(
            shortcut(Icon.HOME, "Home", false) { launch(Sys.homeScreen()) },
            shortcut(Icon.RECENTS, "Recents", true) { global(AccessibilityService.GLOBAL_ACTION_RECENTS) },
            shortcut(Icon.BELL, "Notifications", false) {
                if (svc != null) global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                else { host.closePanel(); Sys.expandNotifications(ctx) }
            },
            shortcut(Icon.SCREENSHOT, "Screenshot", true) { global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, 500) },
            shortcut(Icon.POWER, "Power menu", true) { global(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) },
            shortcut(Icon.LOCK, "Lock", true) { global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) },
        ), 3, equalWidth = true, gapDp = 3))
        if (svc == null) content.addView(note("The dimmed shortcuts work once the button shortcut is on (Settings)."))
    }

    // ================================================================ settings

    private fun buildSettings() {
        val svc = QuickService.instance != null

        content.addView(ui.sectionTitle("Button shortcut"))
        val card = ui.card()
        card.addView(ui.text(if (svc) "On" else "Off", 16f, if (svc) Aya.WHITE else Aya.LINK, weight = 600))
        card.addView(ui.text("Opens this menu on top of a running game. It only notices the button you pick below; it never blocks, changes or records what you press.",
            13f, Aya.DIM).apply { setPadding(0, ui.px(5), 0, ui.px(10)) })
        card.addView(ui.pill(if (svc) "Accessibility settings" else "Turn on", primary = !svc, textSp = 13.5f) {
            if (!svc) Toast.makeText(ctx, "Turn on \"Quick Menu – button shortcut\"", Toast.LENGTH_LONG).show()
            launch(Sys.accessibilityScreen())
        }, ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(card)
        content.addView(ui.text("Open with", 13f, Aya.DIM, weight = 500).apply { setPadding(ui.px(4), ui.px(12), 0, ui.px(4)) })
        val hint = ui.text(Trigger.note(store.trigger), 12.5f, Aya.DIMMER).apply { setPadding(ui.px(4), ui.px(7), ui.px(4), 0) }
        content.addView(choices(Trigger.OPTIONS, store.trigger, 4, 13f) { i -> store.trigger = i; Trigger.reload(ctx); hint.text = Trigger.note(i) }.first)
        content.addView(hint)

        content.addView(ui.sectionTitle("Permissions"))
        val perms = ArrayList<View>()
        perms += ui.switchRow("Root helper", "Fan control, Airplane, Night Light, Location, CPU load", store.root) { on ->
            if (!on && store.fanManual >= 0) { store.fanManual = -1; Firmware.setFanAuto() }      // hand the fan back while it still can
            store.root = on
            Firmware.allowRoot(on)
        }
        perms += statusRow("System settings", "Brightness and auto-rotate", if (Sys.canWrite(ctx)) null else "Allow") { launch(Sys.writeSettingsScreen(ctx)) }
        if (!store.root) perms += statusRow("Do Not Disturb", "The Do Not Disturb tile", if (Sys.dndAccess(ctx)) null else "Allow") { launch(Sys.dndAccessScreen()) }
        content.addView(ui.group(*perms.toTypedArray()))
        content.addView(note("The root helper is part of AYANEO's firmware. It runs short fixed commands as root, only for the features named above."))

        content.addView(ui.sectionTitle("Accent colour"))
        val chips = ui.hbox()
        Aya.ACCENTS.forEachIndexed { i, (_, col) ->
            chips.addView(ui.chip(col and 0xFFFFFF, 26, {
                store.accent = i; Aya.setAccent(i)
                for (t in tabs.values) t.invalidate()
                refresh()
            }, selected = i == store.accent))
        }
        content.addView(chips)

        content.addView(ui.sectionTitle("More"))
        val more = ArrayList<View>()
        Sys.app(ctx, Sys.RGB_CONTROL)?.let { i -> more += ui.navRow("RGB Control", null) { launch(i) } }
        more += ui.navRow("AYASpace settings", null) { launch(Sys.ayaSettings(), Sys.app(ctx, Sys.AYASPACE) ?: Sys.settingsScreen()) }
        Sys.app(ctx, Sys.UPDATER)?.let { i -> more += ui.navRow("System update", null) { launch(i) } }
        more += ui.navRow("Android settings", null) { launch(Sys.settingsScreen()) }
        content.addView(ui.group(*more.toTypedArray()))

        content.addView(ui.sectionTitle("About"))
        val about = ui.card()
        about.addView(ui.text("AYANEO Quick Menu ${appVersion()}", 15f, Aya.WHITE, weight = 600))
        about.addView(ui.text("Unofficial. Not made by, endorsed by or affiliated with AYANEO, MediaTek or Arm; their names and logos belong to them.", 12.5f, Aya.DIM).apply { setPadding(0, ui.px(4), 0, ui.px(7)) })
        about.addView(ui.text("A rebuild of AYASpace's quick menu. Performance and controller settings go through the firmware's own control service, the one AYASpace uses; the fan is set through AYASpace's own fan curves." +
            (Firmware.error?.let { "\nLast error: $it" } ?: ""), 12.5f, Aya.DIMMER))
        content.addView(about)
    }

    // ================================================================ helpers

    /** A set of options in a fixed-column grid; picking one restyles them in place. */
    private fun choices(labels: List<String>, selected: Int, perRow: Int, textSp: Float = 13.5f, onPick: (Int) -> Unit): Pair<View, List<ChoiceView>> {
        val views = labels.mapIndexed { i, l -> ChoiceView(ctx, l, textSp).apply { chosen = i == selected } }
        views.forEachIndexed { i, v -> v.setOnClickListener { views.forEachIndexed { k, o -> o.chosen = k == i }; onPick(i) } }
        return ui.rows(views, perRow, equalWidth = true, gapDp = 3) to views
    }

    /** Title + what it is for, with a button when something still has to be allowed. */
    private fun statusRow(title: String, sub: String, action: String?, run: () -> Unit): View {
        val row = ui.hbox().apply { setPadding(ui.px(PAD_DP), ui.px(10), ui.px(PAD_DP - 2), ui.px(10)); minimumHeight = ui.px(54) }
        val texts = ui.vbox()
        texts.addView(ui.text(title, 15f, weight = 500))
        texts.addView(ui.text(if (action == null) "$sub · allowed" else sub, 12.5f, Aya.DIM).apply { setPadding(0, ui.px(3), ui.px(8), 0) })
        row.addView(texts, ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight = 1f))
        if (action != null) row.addView(ui.pill(action, primary = true, textSp = 13f, padH = 12) { run() })
        return row
    }

    private fun note(t: String) = ui.text(t, 12.5f, Aya.DIMMER).apply { setPadding(ui.px(4), ui.px(8), ui.px(4), 0) }

    /** Hand over to the first of [screens] this device can open. */
    private fun launch(vararg screens: Intent) {
        for (s in screens) if (host.launch(s)) return
        Toast.makeText(ctx, "Not available on this device", Toast.LENGTH_SHORT).show()
    }

    private fun isInside(v: View, parent: View): Boolean {
        var p: View? = v
        while (p != null) { if (p === parent) return true; p = p.parent as? View }
        return false
    }

    private fun ViewGroup.focusSearchNear(x: Int, y: Int): View? {
        var best: View? = null
        var bestD = Long.MAX_VALUE
        val loc = IntArray(2)
        fun walk(v: View) {
            if (v.isFocusable && v.visibility == View.VISIBLE) {
                v.getLocationOnScreen(loc)
                val dx = (loc[0] - x).toLong(); val dy = (loc[1] - y).toLong()
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = v }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(this)
        return best
    }

    private fun appVersion(): String = runCatching { "v" + ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrDefault("")

    companion object {
        /** The Pocket Air Mini is 640 x 480 dp: a 300 dp sheet leaves the game visible and gives the page 248 dp. */
        const val SHEET_DP = 300
        private const val RAIL_DP = 51
        private const val PAD_DP = 12
        private const val SCRIM = 0x8C000000.toInt()
        private var lastPage = Page.PERFORMANCE
    }
}
