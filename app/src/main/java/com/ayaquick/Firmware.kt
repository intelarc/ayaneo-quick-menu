package com.ayaquick

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * The firmware's hidden "custom_function" system service (android.app.customfunction.CustomFunctionManager,
 * added by AYANEO to framework.jar): the same calls AYASpace's own quick menu makes for the CPU and the
 * controller settings, plus its fan and GPU readings. Reverse-engineered from AYASpace's BW02 device class.
 *
 * Most of it goes through the service's typed setters and getters. A few things nothing else reaches (AYASpace's
 * fan-curve files, the Airplane / Night Light / Location switches, CPU load and temperature) go through its root
 * helper, see [root]; the user can switch that off in Settings.
 */
object Firmware {
    private const val TAG = "AyaQuick"
    private const val SERVICE = "custom_function"

    @Volatile var error: String? = null
        private set
    private var manager: Any? = null
    private val methods = HashMap<String, Method?>()
    private var worker: Handler? = null
    private var rootWorker: Handler? = null

    /** The "Root helper" switch in Settings. Off means [root] does nothing. */
    @Volatile var rootAllowed = true

    val available get() = manager != null

    @Synchronized
    fun init(ctx: Context): Boolean {
        if (manager != null) return true
        return try {
            rootAllowed = Store(ctx).root
            manager = ctx.applicationContext.getSystemService(SERVICE)
            if (manager == null) error = "This device has no \"$SERVICE\" service"
            manager != null
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
            false
        }
    }

    private fun method(name: String, vararg types: Class<*>): Method? {
        val m = manager ?: return null
        val key = name + types.size
        synchronized(methods) {
            if (!methods.containsKey(key)) methods[key] = runCatching { m.javaClass.getMethod(name, *types) }.getOrNull()
            return methods[key]
        }
    }

    private fun call(name: String, types: Array<Class<*>>, args: Array<Any>): Any? {
        val m = method(name, *types) ?: run { error = "$name is not on this firmware"; return null }
        return try {
            m.invoke(manager, *args).also { error = null }
        } catch (e: InvocationTargetException) {
            val c = e.targetException
            error = "$name: ${c.javaClass.simpleName}: ${c.message}"
            Log.w(TAG, error!!)
            null
        } catch (t: Throwable) {
            error = "$name: ${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, error!!)
            null
        }
    }

    private val I = Int::class.javaPrimitiveType!!
    private val Z = Boolean::class.javaPrimitiveType!!
    private val S = String::class.java

    /** Runs firmware writes off the UI thread, in order. A later write with the same [key] replaces a queued one. */
    private fun post(key: String, work: () -> Unit) {
        val h = worker ?: Handler(HandlerThread("firmware").apply { start() }.looper).also { worker = it }
        h.removeCallbacksAndMessages(key)
        h.postAtTime(Runnable { work() }, key, android.os.SystemClock.uptimeMillis())
    }

    // ---------------------------------------------------------------- reads (fast binder calls and properties)

    private fun int(name: String): Int = (call(name, emptyArray(), emptyArray()) as? Int) ?: -1

    fun fanSpeed() = int("getFanSpeed")
    fun fanOn() = int("getFanStatus") == 1
    fun gpuLoad() = int("getCurGpuLoading")
    fun gpuFreqKhz() = int("getCurGpuFrequency")
    fun abxySwap() = int("getABXYSwapMode")

    /** 1 = the triggers act as on/off buttons, 0 = analogue. */
    fun triggerDigital(): Boolean = (call("getTriggerSwitchkeyMode", arrayOf(S), arrayOf("L2")) as? Int) == 1

    // The service mirrors every controller setting into a persist.* property, so the live values can be read back.

    /** "shape,inner %,shape,outer %" as the firmware holds it; shape 0 is a circle. */
    private fun stickZone(left: Boolean): List<Int> =
        Props.get(if (left) "persist.ljoyDMR.value" else "persist.rjoyDMR.value").split(',').mapNotNull { it.trim().toIntOrNull() }

    fun stickDeadzone(left: Boolean): Int = stickZone(left).getOrNull(1) ?: -1

    /** Percent of travel ignored at the start ([start]) or the end of a trigger's pull. */
    fun triggerZone(left: Boolean, start: Boolean): Int =
        Props.get("persist.${if (left) "Left" else "Right"}tri${if (start) "start" else "kend"}zone.value").toIntOrNull() ?: -1

    fun keyVibrate(): Boolean? = when (Props.get("persist.keyvibrate.value")) { "on" -> true; "off" -> false; else -> null }
    fun rightStickAsDpad(): Boolean? = Props.get("persist.rjoytodpad.value").toIntOrNull()?.let { it == 1 }
    fun dpadAsLeftStick(): Boolean? = Props.get("persist.dpadtoljoy.value").toIntOrNull()?.let { it == 1 }

    // ---------------------------------------------------------------- writes

    /**
     * CPU and GPU behaviour. The firmware call behind setCpuFreqLimit caps both clusters at the given steps and
     * fixes MediaTek's ut_fix_freq_idx to them; calling it again with steps it does not know clears that fix. With
     * the fix on the maximum the clock never drops, which is the state AYASpace leaves the device in.
     * setGpuFrequency fixes the GPU at one step of its table; any other value lets it scale again.
     */
    fun setPerformance(mode: PerfMode) = post("perf") {
        call("setCpuSchedulingMode", arrayOf(S), arrayOf(mode.governor))
        call("setCpuFreqLimit", arrayOf(I, I), arrayOf(mode.littleKhz, mode.bigKhz))
        if (!mode.keepFix) call("setCpuFreqLimit", arrayOf(I, I), arrayOf(-1, -1))
        call("setGpuFrequency", arrayOf(I), arrayOf(mode.gpuKhz))
    }

    fun setKeyVibrate(on: Boolean) {
        post("vib") { call("setKeyVibrate", arrayOf(Z), arrayOf(on)) }
        rootPost("vib", "printf %s $on > $AYA_DIR/aya_key_vibration.conf")
    }

    fun setAbxySwap(mode: Int) = post("abxy") { call("setABXYSwapMode", arrayOf(I), arrayOf(mode)) }

    fun setTriggerDigital(on: Boolean) {
        post("trig") { for (k in arrayOf("L2", "R2")) call("setTriggerSwitchkeyMode", arrayOf(S, I), arrayOf(k, if (on) 1 else 0)) }
        rootPost("trig", "printf %s $on > $AYA_DIR/aya_trigger_sensitivity_is_key_mode.conf")
    }

    /** Percent of the trigger's travel ignored at the start and at the end. */
    fun setTriggerDeadzone(left: Boolean, start: Int, end: Int) {
        val side = if (left) "left" else "right"
        post(side + "tdz") { call(if (left) "setLeftTriggerDeadzone" else "setRightTriggerDeadzone", arrayOf(I, I), arrayOf(start, end)) }
        rootPost(side + "tdz", ayaSetting("bw02_${side}_trigger_dead_zone_json_v3", """{"a":$start,"b":$end}"""))
    }

    /** Inner deadzone in percent. The shape and the outer deadzone stay as they are. */
    fun setStickDeadzone(left: Boolean, percent: Int) {
        val z = stickZone(left)
        val shape = z.getOrNull(0) ?: 0; val outerShape = z.getOrNull(2) ?: 0; val outer = z.getOrNull(3) ?: 0
        val side = if (left) "left" else "right"
        post(side + "sdz") {
            call(if (left) "setLeftJoyStickDeadzoneModeAndRange" else "setRightJoyStickDeadzoneModeAndRange",
                arrayOf(I, I, I, I), arrayOf(shape, percent, outerShape, outer))
        }
        rootPost(side + "sdz", ayaSetting("bw02_${side}_joystick_dead_zone_json_v4", """{"a":$shape,"b":$percent,"c":$outerShape,"d":$outer}"""))
    }

    fun setRightStickAsDpad(on: Boolean) = post("rs2d") { call("setRightJoystickToDpad", arrayOf(I), arrayOf(if (on) 1 else 0)) }
    fun setDpadAsLeftStick(on: Boolean) = post("d2ls") { call("setDpadToLeftJoystick", arrayOf(I), arrayOf(if (on) 1 else 0)) }

    // ---------------------------------------------------------------- root helper

    /**
     * The firmware runs a script handed to runShellScriptWithRootPermissionForResult through an init service
     * ("myroot": user root, SELinux domain shell) and returns what it printed. That reaches what `adb shell` can,
     * with root's file access, and nothing this menu needs beyond that.
     *
     * The service waits for the script with no timeout and shares one script file between all callers, so it is
     * only used for short fixed scripts, one at a time on its own thread, each started by something the user
     * pressed (plus the Performance page's readings while that page is open). Returns null when switched off.
     */
    fun root(script: String): String? {
        if (!rootAllowed) return null
        return (call("runShellScriptWithRootPermissionForResult", arrayOf(S), arrayOf("(\n$script\n)")) as? String)?.trim()
    }

    /** The Settings switch. Switching off waits for scripts already queued, so the fan can be handed back first. */
    fun allowRoot(on: Boolean) {
        val h = rootWorker
        if (on || h == null) rootAllowed = on else h.post { rootAllowed = false }
    }

    /** Queues [script] for [root]. A later one with the same [key] replaces a queued one. */
    private fun rootPost(key: String, script: String, done: ((String?) -> Unit)? = null) {
        if (!rootAllowed) { done?.invoke(null); return }
        val h = rootWorker ?: Handler(HandlerThread("roothelper").apply { start() }.looper).also { rootWorker = it }
        h.removeCallbacksAndMessages(key)
        h.postAtTime(Runnable { val out = root(script); done?.invoke(out) }, key, android.os.SystemClock.uptimeMillis())
    }

    /** AYASpace keeps its own copy of a setting under this key and puts it back after a restart, so it is kept in step. */
    private fun ayaSetting(key: String, json: String) = "settings put system ayaneo/share/$key '$json'"

    // AYASpace's fan worker wakes every 5 seconds, reads the curve file of its current fan mode from shared storage
    // ("duty,temperature|duty,temperature|...") and sets the fan from the CPU temperature. A fan speed written
    // straight to the firmware is therefore gone within seconds; a flat curve in those files is not.
    private const val AYA_DIR = "/sdcard/.aya"
    private const val EACH_FAN_FILE = "for m in low high max config; do f=aya_fan_mode_\${m}_v4.conf"
    /** grep pattern for a curve this menu wrote: the same duty at 0 and at 100 degrees. */
    private const val FLAT = "'^\\([0-9]*\\),0|\\1,100\$'"

    /**
     * Hold the fan at [speed] percent (0 = off) whatever the temperature, until [setFanAuto]. Does nothing while
     * AYASpace's own fan mode is Off, which makes its worker hold the fan off regardless of any curve.
     */
    fun setFanManual(speed: Int) {
        val v = speed.coerceIn(0, 99)
        post("fan") {                                  // at once; AYASpace's worker then keeps it there
            call("setFanStatus", arrayOf(I), arrayOf(if (v > 0) 1 else 0))
            call("setFanSpeed", arrayOf(I), arrayOf(v))
        }
        val script = "cd $AYA_DIR || exit 1\n" +
            "$EACH_FAN_FILE\n" +
            "  [ -s \$f ] && [ ! -e \$f.quickmenu ] && ! grep -q $FLAT \$f && cp \$f \$f.quickmenu\n" +
            "  printf %s '$v,0|$v,100' > \$f\n" +
            "done\n" +
            "echo ok"
        rootPost("fan", script) { if (it != "ok") error = "The fan curve could not be written (${it ?: "root helper is off"})" }
    }

    /** Hand the fan back to AYASpace: its own curves return (a curve it had before comes back from the copy). */
    fun setFanAuto() = rootPost("fan",
        "cd $AYA_DIR || exit 1\n" +
        "$EACH_FAN_FILE\n" +
        "  if [ -e \$f.quickmenu ]; then mv \$f.quickmenu \$f; elif grep -q $FLAT \$f 2>/dev/null; then rm -f \$f; fi\n" +
        "done\n" +
        "echo ok")

    fun setAirplane(on: Boolean) = rootPost("air", "cmd connectivity airplane-mode ${if (on) "enable" else "disable"}")
    fun setNightLight(on: Boolean) = rootPost("night", "settings put secure night_display_activated ${if (on) 1 else 0}")
    fun setLocation(on: Boolean) = rootPost("loc", "cmd location set-location-enabled $on")
    fun setDnd(on: Boolean) = rootPost("dnd", "cmd notification set_dnd ${if (on) "priority" else "off"}")

    /** The first line of /proc/stat and the CPU / GPU temperature lines, for [Stats]. Null when the helper is off. */
    fun readLoadAndTemps(done: (String?) -> Unit) =
        rootPost("stats", "head -1 /proc/stat; dumpsys hardware_properties | grep -E '^(CPU|GPU) temperatures'", done)
}
