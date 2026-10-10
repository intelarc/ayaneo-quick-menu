package com.ayaquick

import android.app.ActivityManager
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings
import java.io.File

/** What the menu remembers. Controller values start at AYASpace's own defaults and are only sent once changed here. */
class Store(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("quick", Context.MODE_PRIVATE)
    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var accent get() = int("accent", 0); set(v) = put("accent", v)
    /** PerfMode ordinal last chosen here, or -1. */
    var perfMode get() = int("perfMode", -1); set(v) = put("perfMode", v)
    /** Performance overlay size, one of [HudView]'s levels. An overlay switched on in 1.0 carries over as Compact. */
    var hudLevel get() = int("hudLevel", if (bool("hud", false)) HudView.COMPACT else HudView.OFF); set(v) = put("hudLevel", v)
    /** The detailed overlay as a strip along the top (true) or a panel (false); a tap on it flips this. */
    var hudStrip get() = bool("hudStrip", false); set(v) = put("hudStrip", v)
    /** Whether the firmware's root helper may be used (fan control, the Airplane / Night Light / Location switches, CPU load). */
    var root get() = bool("root", true); set(v) = put("root", v)
    /** The fan speed held by this menu in percent, or -1 while AYASpace runs the fan. */
    var fanManual get() = int("fanManual", -1); set(v) = put("fanManual", v)
    /** Which button opens the menu, an index into [Trigger.OPTIONS]. */
    var trigger get() = int("trigger", Trigger.DEFAULT); set(v) = put("trigger", v)
    var keyVibrate get() = bool("keyVibrate", false); set(v) = put("keyVibrate", v)
    var triggerStart get() = int("triggerStart", 10); set(v) = put("triggerStart", v)
    var triggerEnd get() = int("triggerEnd", 10); set(v) = put("triggerEnd", v)
    var leftStick get() = int("leftStick", 10); set(v) = put("leftStick", v)
    var rightStick get() = int("rightStick", 10); set(v) = put("rightStick", v)
    var rightStickDpad get() = bool("rightStickDpad", false); set(v) = put("rightStickDpad", v)
    var dpadLeftStick get() = bool("dpadLeftStick", false); set(v) = put("dpadLeftStick", v)
}

/**
 * How the CPU runs (MT6785: six Cortex-A55 up to 2.0 GHz, two Cortex-A76 up to 2.05 GHz), measured on the device:
 * ECO scales between the lowest step and its cap, Balanced between the lowest step and the maximum, Max never
 * leaves the maximum, Turbo also fixes the GPU at its top step. AYASpace's own modes all hold the CPU at the
 * maximum on this model, and its "Max" is [TURBO] here.
 */
enum class PerfMode(val label: String, val governor: String, val littleKhz: Int, val bigKhz: Int, val keepFix: Boolean, val gpuKhz: Int, val desc: String) {
    ECO("ECO", "schedutil", 1175000, 1308000, true, 0, "CPU follows the load, capped at about 60% speed. Cooler, longer battery."),
    BALANCED("Balanced", "schedutil", 2000000, 2050000, false, 0, "CPU and GPU follow the load, up to full speed."),
    MAX("Max", "performance", 2000000, 2050000, true, 0, "CPU held at full speed, GPU follows the load."),
    TURBO("Turbo", "performance", 2000000, 2050000, true, GPU_MAX_KHZ, "CPU and GPU both held at full speed. The most this firmware allows."),
}

/** The top step of the stock kernel's GPU table (Mali-G76 MC4). */
const val GPU_MAX_KHZ = 806000

/** System properties the firmware publishes (frame rate, CPU governor). Empty when one cannot be read. */
object Props {
    private val getter by lazy {
        runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java) }.getOrNull()
    }

    fun get(key: String): String = runCatching { getter?.invoke(null, key) as? String }.getOrNull() ?: ""

    /** "performance", "schedutil" or "powersave": the governor last set through the firmware service. */
    fun governor() = get("persist.cpuschedulingmode.value")
}

/** Live readings an ordinary app can get on this firmware. -1 means not available. */
object Stats {
    var fps = -1f; private set
    var cpuGhz = -1f; private set           // average current clock of the cores that are online
    var cpuPct = -1; private set            // that clock as a share of each core's maximum
    var gpuLoad = -1; private set
    var gpuMhz = -1; private set
    var fan = -1; private set
    var fanOn = false; private set
    var ramPct = -1; private set
    var ramUsedGb = 0f; private set
    var battPct = -1; private set
    var charging = false; private set
    var battTemp = -1f; private set
    var watts = -1f; private set
    var ramTotalGb = 0f; private set
    var swapUsedGb = 0f; private set
    var swapTotalGb = 0f; private set

    // Only the firmware's root helper can read these; they arrive a moment after [requestLoadAndTemps].
    /** From the root read on the Performance page: whether a fan speed this menu set is still in force. */
    @Volatile var fanHeldByUs: Boolean? = null
    @Volatile var cpuLoad = -1; private set         // percent of all cores busy
    @Volatile var cpuTemp = -1f; private set
    @Volatile var gpuTemp = -1f; private set
    private var lastTotal = 0L
    private var lastIdle = 0L
    private var askedAt = 0L

    private val memInfo = ActivityManager.MemoryInfo()
    private val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    private val maxKhz = IntArray(8)
    /** Each core's clock as a share of its maximum, 0 for a core that is offline. */
    val coreShare = FloatArray(8)
    /** Each core's clock in MHz, 0 for a core that is offline. */
    val coreMhz = IntArray(8)

    fun readBattery(ctx: Context) {
        val i = ctx.applicationContext.registerReceiver(null, batteryFilter) ?: return
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        battPct = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        battTemp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -10) / 10f
        val mv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val ua = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        // the fuel gauge reports microamps; anything outside a handheld's range means the unit is not what we expect
        val w = kotlin.math.abs(ua) / 1e6f * mv / 1000f
        watts = if (mv > 0 && ua != Int.MIN_VALUE && w in 0.05f..45f) w else -1f
    }

    /**
     * Read only the figures the caller shows, so a bare FPS overlay costs one property read instead of a dozen
     * sysfs opens and binder calls every second. [cpu] reads the eight per-core clocks, [gpu] the firmware's GPU
     * and fan state, [mem] the memory figures; the full Performance page asks for everything.
     */
    fun readAll(ctx: Context, cpu: Boolean = true, gpu: Boolean = true, mem: Boolean = true, battery: Boolean = true) {
        if (battery) readBattery(ctx)
        fps = Props.get("com.ayaneo.fps").toFloatOrNull() ?: -1f
        if (cpu) {
            var sum = 0L; var share = 0f; var n = 0
            for (c in 0 until 8) {
                val cur = readInt("/sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq")
                if (cur <= 0) { coreShare[c] = 0f; coreMhz[c] = 0; continue }
                coreMhz[c] = cur / 1000
                if (maxKhz[c] == 0) maxKhz[c] = readInt("/sys/devices/system/cpu/cpu$c/cpufreq/cpuinfo_max_freq").coerceAtLeast(cur)
                coreShare[c] = cur.toFloat() / maxKhz[c]
                sum += cur; share += coreShare[c]; n++
            }
            cpuGhz = if (n > 0) sum / n / 1e6f else -1f
            cpuPct = if (n > 0) (share / n * 100).toInt().coerceIn(0, 100) else -1
        }
        if (gpu && Firmware.init(ctx)) {
            gpuLoad = Firmware.gpuLoad()
            gpuMhz = Firmware.gpuFreqKhz().let { if (it > 0) it / 1000 else -1 }
            fan = Firmware.fanSpeed()
            fanOn = Firmware.fanOn()
        }
        if (mem) {
            (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(memInfo)
            if (memInfo.totalMem > 0) {
                val used = memInfo.totalMem - memInfo.availMem
                ramPct = (used * 100 / memInfo.totalMem).toInt()
                ramUsedGb = used / 1073741824f
                ramTotalGb = memInfo.totalMem / 1073741824f
            }
        }
        if (android.os.SystemClock.uptimeMillis() - askedAt > 9000) { cpuLoad = -1; cpuTemp = -1f; gpuTemp = -1f }   // nobody is asking any more
    }

    /** Ask the root helper for CPU load and temperatures, at most every [everyMs]. No-op while the helper is off. */
    fun requestLoadAndTemps(everyMs: Long = 2500) {
        val now = android.os.SystemClock.uptimeMillis()
        if (!Firmware.rootAllowed || now - askedAt < everyMs) return
        askedAt = now
        Firmware.readLoadAndTemps { out -> if (out != null) parse(out) }
    }

    private fun parse(out: String) {
        for (line in out.lines()) when {
            line.startsWith("cpu ") -> {
                val f = line.trim().split(' ').mapNotNull { it.toLongOrNull() }
                if (f.size >= 5) {
                    val total = f.take(8).sum(); val idle = f[3] + f[4]
                    if (lastTotal in 1 until total) cpuLoad = (100 - (idle - lastIdle) * 100 / (total - lastTotal)).toInt().coerceIn(0, 100)
                    lastTotal = total; lastIdle = idle
                }
            }
            line.startsWith("CPU temperatures") -> cpuTemp = firstNumber(line)
            line.startsWith("GPU temperatures") -> gpuTemp = firstNumber(line)
            line.startsWith("QMFAN=") -> fanHeldByUs = line.substringAfter('=') == "held"
        }
    }

    /** "CPU temperatures: [39.83]" -> 39.83 */
    private fun firstNumber(line: String): Float =
        line.substringAfter('[', "").substringBefore(']').substringBefore(',').trim().toFloatOrNull() ?: -1f

    /** Swap in use, for the detailed overlay. /proc/meminfo is one of the few /proc files an app may read. */
    fun readSwap() {
        var total = -1L; var free = -1L
        runCatching {
            File("/proc/meminfo").forEachLine { l ->
                if (l.startsWith("SwapTotal:")) total = kb(l) else if (l.startsWith("SwapFree:")) free = kb(l)
            }
        }
        if (total > 0 && free >= 0) { swapTotalGb = total / 1048576f; swapUsedGb = (total - free) / 1048576f }
    }

    private fun kb(line: String): Long = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: -1L

    private fun readInt(path: String): Int = runCatching { File(path).readText().trim().toInt() }.getOrDefault(-1)
}

/** Android's own switches, as far as an ordinary app may flip them. Everything else opens the matching settings screen. */
object Sys {
    fun canWrite(ctx: Context) = Settings.System.canWrite(ctx)

    /** Screen brightness in percent of the panel's range, the same scale AYASpace shows. */
    fun brightness(ctx: Context): Int =
        ((Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 + 127) / 255).coerceIn(1, 100)

    fun setBrightness(ctx: Context, pct: Int) {
        if (!canWrite(ctx)) return
        runCatching { Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, (pct * 255 / 100).coerceIn(3, 255)) }
    }

    private fun audio(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    fun volume(ctx: Context) = audio(ctx).getStreamVolume(AudioManager.STREAM_MUSIC)
    fun volumeMax(ctx: Context) = audio(ctx).getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    fun setVolume(ctx: Context, v: Int) { runCatching { audio(ctx).setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } }

    private fun wifi(ctx: Context) = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    fun wifiOn(ctx: Context) = runCatching { wifi(ctx).isWifiEnabled }.getOrDefault(false)
    @Suppress("DEPRECATION")
    fun setWifi(ctx: Context, on: Boolean): Boolean = runCatching { wifi(ctx).setWifiEnabled(on) }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun bt(): BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    @Suppress("MissingPermission")
    fun btOn() = runCatching { bt()?.isEnabled == true }.getOrDefault(false)
    @Suppress("MissingPermission", "DEPRECATION")
    fun setBt(on: Boolean): Boolean = runCatching { bt()?.let { if (on) it.enable() else it.disable() } == true }.getOrDefault(false)

    fun airplaneOn(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    private fun nm(ctx: Context) = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    fun dndAccess(ctx: Context) = nm(ctx).isNotificationPolicyAccessGranted
    fun dndOn(ctx: Context) = nm(ctx).currentInterruptionFilter.let { it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
    /** With Do Not Disturb access this is Android's own call; without it the root helper does the same. */
    fun setDnd(ctx: Context, on: Boolean) {
        if (!dndAccess(ctx)) { Firmware.setDnd(on); return }
        runCatching { nm(ctx).setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL) }
    }

    fun autoRotate(ctx: Context) = Settings.System.getInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
    fun setAutoRotate(ctx: Context, on: Boolean) {
        if (!canWrite(ctx)) return
        runCatching { Settings.System.putInt(ctx.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0) }
    }

    fun nightLight(ctx: Context) = runCatching { Settings.Secure.getInt(ctx.contentResolver, "night_display_activated", 0) == 1 }.getOrDefault(false)
    fun locationOn(ctx: Context) = runCatching { (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isLocationEnabled }.getOrDefault(false)

    fun serviceOn(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.split(':').any { it.startsWith(ctx.packageName + "/") }
    }

    /** AYASpace's own accessibility service ("AYAHome"), which opens its menu on the AYA button. */
    fun ayaHomeOn(ctx: Context): Boolean =
        (Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").split(':').any { it.startsWith("$AYASPACE/") }

    // ---------------------------------------------------------------- screens to hand over to

    fun writeSettingsScreen(ctx: Context) = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + ctx.packageName))
    fun dndAccessScreen() = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
    fun accessibilityScreen() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    fun airplaneScreen() = Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
    fun nightLightScreen() = Intent(Settings.ACTION_NIGHT_DISPLAY_SETTINGS)
    fun locationScreen() = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    fun castScreen() = Intent(Settings.ACTION_CAST_SETTINGS)
    fun hotspotScreen() = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings")
    fun wirelessScreen() = Intent(Settings.ACTION_WIRELESS_SETTINGS)
    fun settingsScreen() = Intent(Settings.ACTION_SETTINGS)
    fun homeScreen() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    fun app(ctx: Context, pkg: String): Intent? = ctx.packageManager.getLaunchIntentForPackage(pkg)
    fun ayaSettings() = Intent(Intent.ACTION_MAIN).setClassName(AYASPACE, "com.ayaneo.settings.ui.global.SettingMainActivity")

    /** Pulls the notification shade down without the accessibility service (EXPAND_STATUS_BAR is an ordinary permission). */
    fun expandNotifications(ctx: Context): Boolean = runCatching {
        val sb = ctx.getSystemService("statusbar") ?: return false
        sb.javaClass.getMethod("expandNotificationsPanel").invoke(sb)
        true
    }.getOrDefault(false)

    const val RGB_CONTROL = "com.rgbplus.ayaneo"
    const val AYASPACE = "com.ayaneo.home"
    const val UPDATER = "com.ayaneo.update"
}
