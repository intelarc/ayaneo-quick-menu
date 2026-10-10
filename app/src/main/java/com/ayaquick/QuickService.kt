package com.ayaquick

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/** Which button opens the menu. Read by [QuickService] on every key, so the choice is cached here. */
object Trigger {
    val OPTIONS = listOf("AYA", "=", "RC", "L3+R3")
    const val DEFAULT = 3
    private const val CHORD = 3
    // Linux key codes of the handheld's extra buttons (Generic.kl: key 752 RC, key 753 EQ, key 754 AYA)
    private val SCAN = intArrayOf(754, 753, 752)
    const val AYA = 0
    private const val RC = 2

    /**
     * AYASpace treats RC as its Back button: about 0.4 s after a press it injects Back into whatever has focus.
     * The menu has to swallow that one, or it would close as soon as it opened (and the game would get the next).
     */
    fun alsoSendsBack(ctx: Context) = option(ctx) == RC
    const val BACK_ECHO_MS = 650L

    @Volatile private var option = -1

    fun reload(ctx: Context) { option = Store(ctx).trigger }
    fun option(ctx: Context): Int { if (option < 0) reload(ctx); return option }
    fun isChord(ctx: Context) = option(ctx) == CHORD
    fun matches(ctx: Context, e: KeyEvent) = SCAN.getOrNull(option(ctx)) == e.scanCode

    fun note(option: Int) = when (option) {
        CHORD -> "Press both sticks in together. Games still get the presses."
        AYA -> "AYASpace's own menu still opens underneath this one and closes with it."
        RC -> "RC is also AYASpace's Back button. The menu swallows the Back it sends."
        else -> "AYASpace may also react to this button."
    }
}

/**
 * Optional. Lets a button open the menu on top of whatever is running, draws the performance overlay and
 * carries out the system shortcuts (Recents, Screenshot, Power menu, Lock).
 * It never consumes or changes any input: onKeyEvent always returns false.
 */
class QuickService : AccessibilityService(), Panel.Host {

    private val handler = Handler(Looper.getMainLooper())
    private var panel: Panel? = null
    private var hud: HudView? = null
    private var hudLevel = HudView.OFF
    private var l3 = false
    private var r3 = false
    private var downAt = 0L
    private var overAyaSpace = false        // opened with the AYA button, so AYASpace's own menu came up underneath
    private var closedByAya = false

    override fun onServiceConnected() {
        instance = this
        Trigger.reload(this)
        registerReceiver(screen, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) })
        syncHud()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (Trigger.isChord(this)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_THUMBL -> { val both = down && !l3 && r3; l3 = down; if (both) toggle() }
                KeyEvent.KEYCODE_BUTTON_THUMBR -> { val both = down && !r3 && l3; r3 = down; if (both) toggle() }
            }
        } else if (Trigger.matches(this, event)) {
            if (down) { if (event.repeatCount == 0) downAt = event.eventTime }
            else if (event.eventTime - downAt < 600) toggle(byAya = Trigger.option(this) == Trigger.AYA)   // short presses only
        }
        return false
    }

    fun toggle(byAya: Boolean = false) {
        PanelActivity.front?.let { it.dismiss(); return }      // the menu is already open as its own screen
        val p = panel
        if (p != null) { closedByAya = byAya; p.close(holdMs = if (Trigger.alsoSendsBack(this)) Trigger.BACK_ECHO_MS else 0L) } else showPanel(byAya)
    }

    private fun showPanel(byAya: Boolean) {
        if ((getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked) return   // never on the lock screen
        val p = Panel(this, this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        try {
            wm().addView(p.root, lp)
        } catch (t: Throwable) {
            return
        }
        panel = p
        overAyaSpace = byAya && Sys.ayaHomeOn(this)
        closedByAya = false
        p.onShown()
        if (Trigger.alsoSendsBack(this)) p.ignoreBackFor(Trigger.BACK_ECHO_MS)
    }

    override fun closePanel() {
        val p = panel ?: return
        panel = null
        p.onHidden()
        runCatching { wm().removeView(p.root) }
        // The AYA press that opened this menu opened AYASpace's too. Another AYA press closes both; anything else
        // (B, a tap outside, a shortcut) would leave AYASpace's showing, so it gets a Back.
        if (overAyaSpace && !closedByAya) handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 90)
        overAyaSpace = false
    }

    override fun launch(intent: Intent): Boolean = try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        closePanel()
        true
    } catch (t: Throwable) {
        false
    }

    private fun wm() = getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // ---------------------------------------------------------------- performance overlay

    /** Show, resize or hide the overlay to match the setting. It only ticks while the screen is on. */
    fun syncHud() {
        val want = Store(this).hudLevel
        if (want == hudLevel) return
        handler.removeCallbacks(hudTick)
        hud?.let { runCatching { wm().removeView(it) } }
        hud = null
        hudLevel = HudView.OFF
        if (want == HudView.OFF) return
        val v = HudView(this, want)
        val d = resources.displayMetrics.density
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // only the detailed overlay takes touches (a tap flips it); the small ones let every touch through
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (want == HudView.DETAILED) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = (6 * d).toInt(); y = (4 * d).toInt() }
        if (runCatching { wm().addView(v, lp) }.isFailure) return
        hud = v
        hudLevel = want
        if ((getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) handler.post(hudTick)
    }

    private val hudTick = object : Runnable {
        override fun run() {
            val v = hud ?: return
            Stats.readAll(this@QuickService)
            if (hudLevel == HudView.DETAILED) { Stats.readSwap(); Stats.requestLoadAndTemps(3000) }     // detailed only
            v.update()
            handler.postDelayed(this, 1000)
        }
    }

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            handler.removeCallbacks(hudTick)
            if (i?.action == Intent.ACTION_SCREEN_ON && hud != null) handler.post(hudTick)
            if (i?.action == Intent.ACTION_SCREEN_OFF) panel?.close()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutDown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutDown()
        super.onDestroy()
    }

    private fun shutDown() {
        if (instance !== this) return
        instance = null
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screen) }
        closePanel()
        hud?.let { runCatching { wm().removeView(it) } }
        hud = null
        hudLevel = HudView.OFF
    }

    companion object {
        /** Set while the user has the shortcut switched on in Accessibility settings. */
        @Volatile var instance: QuickService? = null
            private set
    }
}
