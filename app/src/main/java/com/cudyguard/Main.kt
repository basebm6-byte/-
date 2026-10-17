package com.cudyguard

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap
import org.json.JSONArray
import org.json.JSONObject

/** Local storage only (app-private SharedPreferences). No accounts, no cloud. */
class Store(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("cudyguard", Context.MODE_PRIVATE)

    var manualIp: String
        get() = p.getString("ip", "") ?: ""
        set(v) { p.edit().putString("ip", v).apply() }

    var adminUser: String
        get() = p.getString("user", "admin") ?: "admin"
        set(v) { p.edit().putString("user", v).apply() }

    var adminPassword: String
        get() = p.getString("pw", "") ?: ""
        set(v) { p.edit().putString("pw", v).apply() }

    var monitoring: Boolean
        get() = p.getBoolean("mon", true)
        set(v) { p.edit().putBoolean("mon", v).apply() }

    var lastGateway: String
        get() = p.getString("gw", "") ?: ""
        set(v) { p.edit().putString("gw", v).apply() }

    var lastUptime: Long
        get() = p.getLong("up", 0L)
        set(v) { p.edit().putLong("up", v).apply() }

    var loginOk: Boolean
        get() = p.getBoolean("loginOk", false)
        set(v) { p.edit().putBoolean("loginOk", v).apply() }

    var loginBadNotified: Boolean
        get() = p.getBoolean("loginBad", false)
        set(v) { p.edit().putBoolean("loginBad", v).apply() }

    var status: String
        get() = p.getString("status", "المراقبة متوقفة") ?: ""
        set(v) { p.edit().putString("status", v).apply() }

    var snapshot: Map<String, String>
        get() {
            val s = p.getString("snap", "") ?: ""
            if (s.isEmpty()) return emptyMap()
            return try {
                val o = JSONObject(s)
                o.keys().asSequence().associateWith { o.getString(it) }
            } catch (e: Exception) {
                emptyMap()
            }
        }
        set(v) {
            p.edit().putString("snap", if (v.isEmpty()) "" else JSONObject(v).toString()).apply()
        }

    @Synchronized
    fun addEvent(msg: String) {
        val old = try { JSONArray(p.getString("events", "[]")) } catch (e: Exception) { JSONArray() }
        val out = JSONArray()
        out.put(JSONObject().put("t", System.currentTimeMillis()).put("m", msg))
        for (i in 0 until minOf(old.length(), 99)) out.put(old.get(i))
        p.edit().putString("events", out.toString()).apply()
    }

    fun eventsText(): String {
        val arr = try { JSONArray(p.getString("events", "[]")) } catch (e: Exception) { JSONArray() }
        if (arr.length() == 0) return "لا توجد أحداث بعد."
        val f = SimpleDateFormat("dd/MM HH:mm", Locale.US)
        val sb = StringBuilder()
        for (i in 0 until minOf(arr.length(), 40)) {
            val o = arr.getJSONObject(i)
            if (i > 0) sb.append("\n\n")
            sb.append(f.format(Date(o.getLong("t")))).append("  ").append(o.getString("m"))
        }
        return sb.toString()
    }
}

object Net {

    /** The Wi-Fi network the phone is currently on, or null if not on Wi-Fi. */
    @Suppress("DEPRECATION")
    fun wifiNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (n in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n
        }
        return null
    }

    /** Auto-detect the router IP = the default gateway of the Wi-Fi network. */
    fun gateway(ctx: Context, net: Network): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(net) ?: return null
        for (r in lp.routes) {
            val gw = r.gateway
            if (r.isDefaultRoute && gw is Inet4Address) return gw.hostAddress
        }
        return null
    }

    /** TCP connect through the given network (so mobile data can't give a false "up"). */
    fun tcp(net: Network, host: String, port: Int, timeoutMs: Int = 3000): Boolean {
        return try {
            val s = net.socketFactory.createSocket()
            s.use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Internet reachable through the router's Wi-Fi network. */
    fun internet(net: Network): Boolean {
        val targets = listOf("1.1.1.1" to 443, "8.8.8.8" to 443, "9.9.9.9" to 443)
        return targets.any { tcp(net, it.first, it.second, 2500) }
    }
}

/**
 * Talks to the router's LuCI web admin (Cudy firmware is LuCI/OpenWrt based).
 * Login form fields and page paths are best-effort: pages that don't exist on
 * your firmware (404) are skipped. Adjust PAGES below after the first test.
 */
class RouterClient(
    private val net: Network,
    private val ip: String,
    private val user: String,
    private val pass: String
) {
    enum class Login { OK, BAD, ERROR }

    private val cookies = LinkedHashMap<String, String>()

    companion object {
        val PAGES = listOf(
            "/cgi-bin/luci/admin/network/wireless/config",
            "/cgi-bin/luci/admin/network/wireless",
            "/cgi-bin/luci/admin/network/lan/config",
            "/cgi-bin/luci/admin/network/wan/config",
            "/cgi-bin/luci/admin/network/dhcp"
        )
        const val UPTIME_PATH = "/cgi-bin/luci/admin/status/overview?status=1"

        private val INPUT = Regex("<input\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val SELECT = Regex(
            "<select\\b([^>]*)>(.*?)</select>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        private val OPTION = Regex("<option\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val ATTR = Regex("([a-zA-Z_:-]+)\\s*=\\s*\"([^\"]*)\"")
        private val CHECKED = Regex("\\schecked(\\s|=|/|>)", RegexOption.IGNORE_CASE)
        private val SELECTED = Regex("\\sselected(\\s|=|/|>)", RegexOption.IGNORE_CASE)
        private val SKIP_TYPES = setOf("hidden", "submit", "button", "image", "reset", "file")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun open(path: String, post: String?): HttpURLConnection {
        val c = net.openConnection(URL("http://$ip$path")) as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 8000
        c.instanceFollowRedirects = false
        if (cookies.isNotEmpty()) {
            c.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        if (post != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(post.toByteArray()) }
        }
        return c
    }

    fun login(): Login {
        cookies.clear()
        val body = "luci_username=${enc(user)}&luci_password=${enc(pass)}"
        var c: HttpURLConnection? = null
        return try {
            c = open("/cgi-bin/luci/", body)
            c.responseCode
            for ((k, list) in c.headerFields) {
                if (k != null && k.equals("Set-Cookie", ignoreCase = true)) {
                    for (h in list) {
                        val kv = h.substringBefore(';')
                        val name = kv.substringBefore('=').trim()
                        val value = kv.substringAfter('=', "")
                        if (name.isNotEmpty()) cookies[name] = value
                    }
                }
            }
            if (cookies.keys.any { it.startsWith("sysauth") }) Login.OK else Login.BAD
        } catch (e: Exception) {
            Login.ERROR
        } finally {
            try { c?.disconnect() } catch (e: Exception) { }
        }
    }

    fun get(path: String): Pair<Int, String>? {
        return try {
            val c = open(path, null)
            try {
                val code = c.responseCode
                val body = if (code in 200..299) c.inputStream.bufferedReader().use { it.readText() } else ""
                Pair(code, body)
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Router uptime in seconds, or null if this firmware doesn't expose it here. */
    fun uptimeSeconds(): Long? {
        val r = get(UPTIME_PATH) ?: return null
        if (r.first != 200) return null
        return Regex("\"uptime\"\\s*:\\s*(\\d+)").find(r.second)?.groupValues?.get(1)?.toLongOrNull()
    }

    /** Reads all form values of the config pages into one sorted map. Null = couldn't read. */
    fun readConfig(): Map<String, String>? {
        val out = TreeMap<String, String>()
        var any = false
        for (p in PAGES) {
            val r = get(p) ?: continue
            if (r.first != 200) continue
            if (r.second.contains("luci_password")) return null // session lost
            any = true
            parse(p, r.second, out)
        }
        return if (any) out else null
    }

    private fun attrs(tag: String): Map<String, String> =
        ATTR.findAll(tag).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

    private fun parse(page: String, html: String, out: MutableMap<String, String>) {
        for (m in INPUT.findAll(html)) {
            val tag = m.value
            val a = attrs(tag)
            val type = (a["type"] ?: "text").lowercase()
            if (type in SKIP_TYPES) continue
            val name = a["name"] ?: a["id"] ?: continue
            val key = "$page|$name"
            if (type == "checkbox" || type == "radio") {
                val v = a["value"] ?: ""
                out["$key[$v]"] = if (CHECKED.containsMatchIn(tag)) "on" else "off"
            } else {
                out[key] = a["value"] ?: ""
            }
        }
        for (m in SELECT.findAll(html)) {
            val a = attrs("<select " + m.groupValues[1] + ">")
            val name = a["name"] ?: a["id"] ?: continue
            var sel = ""
            for (o in OPTION.findAll(m.groupValues[2])) {
                if (SELECTED.containsMatchIn(o.value)) {
                    sel = attrs(o.value)["value"] ?: ""
                    break
                }
            }
            out["$page|$name"] = sel
        }
    }
}

object Notifier {
    const val CH_ALERT = "alerts"
    const val CH_SVC = "service"

    fun init(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "تنبيهات الراوتر", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SVC, "حالة المراقبة", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun foreground(ctx: Context, text: String): Notification =
        Notification.Builder(ctx, CH_SVC)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("CudyGuard")
            .setContentText(text)
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .build()

    fun alert(ctx: Context, title: String, text: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val n = Notification.Builder(ctx, CH_ALERT)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .build()
            nm.notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
        } catch (e: Exception) {
            // notification permission missing; event is still saved in the app log
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Store(context).monitoring) {
            try {
                context.startForegroundService(Intent(context, MonitorService::class.java))
            } catch (e: Exception) {
            }
        }
    }
}

enum class State { UNKNOWN, NO_WIFI, ROUTER_DOWN, INTERNET_DOWN, OK }

class MonitorService : Service() {

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private lateinit var store: Store

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        Notifier.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = Notifier.foreground(this, "مراقبة الراوتر شغالة")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        if (!running) {
            running = true
            thread = Thread { loop() }.also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        thread?.interrupt()
        super.onDestroy()
    }

    private fun fire(title: String, text: String) {
        store.addEvent("$title — $text")
        Notifier.alert(this, title, text)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s < 60) "$s ثانية" else "${s / 60} دقيقة و${s % 60} ثانية"
    }

    private fun loop() {
        var state = State.UNKNOWN
        var pending = State.UNKNOWN
        var pendingCount = 0
        var downSince = 0L
        var recovered = false
        var outageMs = 0L
        var tick = 0
        val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

        while (running) {
            try {
                val net = Net.wifiNetwork(this)
                var ip: String? = null
                val cur: State
                if (net == null) {
                    cur = State.NO_WIFI
                } else {
                    val manual = store.manualIp.trim()
                    val gw = Net.gateway(this, net)
                    if (gw != null) store.lastGateway = gw
                    ip = if (manual.isNotEmpty()) manual else (gw ?: store.lastGateway.ifEmpty { null })
                    val up = ip != null && (Net.tcp(net, ip, 80) || Net.tcp(net, ip, 443))
                    cur = if (!up) State.ROUTER_DOWN
                    else if (Net.internet(net)) State.OK
                    else State.INTERNET_DOWN
                }

                // Debounce: down states must be seen twice in a row before alerting.
                if (cur == pending) pendingCount++ else { pending = cur; pendingCount = 1 }
                if ((cur == State.OK || pendingCount >= 2) && cur != state) {
                    val old = state
                    state = cur
                    val now = System.currentTimeMillis()
                    if ((old == State.UNKNOWN || old == State.OK) && cur != State.OK) downSince = now
                    val dur = if (cur == State.OK && downSince > 0) now - downSince else 0L
                    if (cur == State.OK) downSince = 0L
                    val wasRouterDown = old == State.NO_WIFI || old == State.ROUTER_DOWN
                    val reachableNow = cur == State.OK || cur == State.INTERNET_DOWN
                    if (wasRouterDown && reachableNow) {
                        recovered = true
                        outageMs = if (downSince > 0) now - downSince else dur
                    }
                    announce(old, cur, dur)
                }

                val reachable = cur == State.OK || cur == State.INTERNET_DOWN
                if (!reachable) recovered = false

                tick++
                if (reachable && net != null && ip != null && (recovered || tick % 4 == 1)) {
                    routerCheck(net, ip, recovered, outageMs)
                    recovered = false
                }

                val line = when (cur) {
                    State.OK -> "✅ الراوتر ${ip ?: ""} شغال والإنترنت شغال"
                    State.INTERNET_DOWN -> "⚠️ الراوتر شغال لكن الإنترنت مقطوع"
                    State.ROUTER_DOWN -> "❌ ما أكدر أوصل للراوتر ${ip ?: ""}"
                    State.NO_WIFI -> "❌ الهاتف مو متصل بالواي فاي"
                    State.UNKNOWN -> "..."
                }
                val hint = if (store.adminPassword.isEmpty())
                    "\nأدخل كلمة مرور دخول الراوتر لتفعيل كشف تغيير الإعدادات." else ""
                store.status = line + "\nآخر فحص: " + clock.format(Date()) + hint
            } catch (e: Exception) {
                // keep monitoring no matter what
            }
            try {
                Thread.sleep(15_000)
            } catch (e: InterruptedException) {
                if (!running) break
            }
        }
    }

    private fun announce(old: State, cur: State, durMs: Long) {
        val dur = if (durMs > 0) " (مدة الانقطاع: ${fmt(durMs)})" else ""
        when (cur) {
            State.OK -> {
                if (old != State.UNKNOWN) {
                    val msg = if (old == State.INTERNET_DOWN) "رجع الإنترنت." else "رجع الاتصال بالراوتر والإنترنت شغال."
                    fire("رجع الاتصال ✅", msg + dur)
                }
            }
            State.INTERNET_DOWN -> {
                if (old == State.NO_WIFI || old == State.ROUTER_DOWN) {
                    fire("رجع الراوتر لكن الإنترنت مقطوع ⚠️", "الاتصال بالراوتر سليم، بس الإنترنت ما يشتغل.")
                } else {
                    fire("الإنترنت مقطوع ⚠️", "الراوتر شغال والاتصال به سليم، لكن الإنترنت مقطوع (غالباً من المزوّد).")
                }
            }
            State.ROUTER_DOWN -> fire(
                "انقطع الاتصال بالراوتر ❌",
                "الهاتف متصل بالواي فاي لكن الراوتر ما يستجيب. ممكن يكون يعيد التشغيل أو تعطّل."
            )
            State.NO_WIFI -> fire(
                "انفصل الهاتف عن الواي فاي ❌",
                "ممكن الراوتر انطفى، أو تغيّر اسم/كلمة مرور الشبكة، أو ابتعدت عن التغطية."
            )
            else -> {}
        }
    }

    private class Diff(var ssid: Boolean = false, var pass: Boolean = false, var other: Boolean = false)

    private fun diff(old: Map<String, String>, cur: Map<String, String>): Diff {
        val d = Diff()
        for (k in old.keys + cur.keys) {
            if (old[k] == cur[k]) continue
            val n = k.substringAfter('|').lowercase()
            when {
                n.contains("ssid") -> d.ssid = true
                n.contains("key") || n.contains("psk") || n.contains("pass") -> d.pass = true
                else -> d.other = true
            }
        }
        return d
    }

    private fun ssids(m: Map<String, String>): String =
        m.filterKeys { it.substringAfter('|').lowercase().contains("ssid") }.values
            .filter { it.isNotEmpty() }.joinToString("، ")

    private val defaultSsid = Regex("^Cudy-[0-9A-Fa-f]{4,8}(-5G)?$")

    private fun hasDefaultSsid(m: Map<String, String>): Boolean {
        val list = m.filterKeys { it.substringAfter('|').lowercase().contains("ssid") }.values
        return list.isNotEmpty() && list.any { defaultSsid.matches(it) }
    }

    private fun routerCheck(net: Network, ip: String, recovered: Boolean, outageMs: Long) {
        val pass = store.adminPassword
        if (pass.isEmpty()) return
        val c = RouterClient(net, ip, store.adminUser.ifEmpty { "admin" }, pass)
        val lr = c.login()
        if (lr == RouterClient.Login.ERROR) return

        // Reboot detection (uptime going backwards), with a fallback guess.
        var reboot = false
        var certain = true
        if (lr == RouterClient.Login.OK) {
            val up = c.uptimeSeconds()
            if (up != null) {
                val prev = store.lastUptime
                if (prev > 0 && up + 5 < prev) reboot = true
                store.lastUptime = up
            } else if (recovered && outageMs in 10_000L..600_000L) {
                reboot = true
                certain = false
            }
        } else if (recovered && outageMs in 10_000L..600_000L) {
            reboot = true
            certain = false
        }

        if (lr == RouterClient.Login.BAD) {
            if (store.loginOk) {
                if (reboot || recovered) {
                    fire(
                        "إعادة ضبط المصنع للراوتر ⚠️",
                        "الراوتر رجع بعد انقطاع وما عاد يقبل كلمة المرور المحفوظة، غالباً انعمل له ضبط مصنع. حدّث كلمة المرور في التطبيق."
                    )
                    store.snapshot = emptyMap()
                    store.loginOk = false
                } else if (!store.loginBadNotified) {
                    fire("تغيّرت كلمة مرور إدارة الراوتر ⚠️", "الراوتر ما عاد يقبل كلمة المرور المحفوظة في التطبيق.")
                }
                store.loginBadNotified = true
            }
            return
        }

        store.loginOk = true
        store.loginBadNotified = false

        var cfg = c.readConfig()
        if (cfg == null && c.login() == RouterClient.Login.OK) cfg = c.readConfig()

        var resetSuspect = false
        if (cfg != null) {
            val old = store.snapshot
            if (old.isEmpty()) {
                store.snapshot = cfg
                store.addEvent("تم حفظ إعدادات الراوتر الحالية كمرجع للمقارنة.")
            } else if (cfg != old) {
                resetSuspect = reboot && hasDefaultSsid(cfg) && !hasDefaultSsid(old)
                if (resetSuspect) {
                    fire(
                        "إعادة ضبط المصنع للراوتر ⚠️",
                        "الراوتر أعاد التشغيل ورجع لاسم الشبكة الافتراضي. غالباً انعمل له ضبط مصنع."
                    )
                } else {
                    val d = diff(old, cfg)
                    if (d.ssid) fire("تغيّر اسم شبكة الواي فاي ⚠️", "القديم: ${ssids(old)}\nالجديد: ${ssids(cfg)}")
                    if (d.pass) fire("تغيّرت كلمة مرور الواي فاي (أو الإدارة) ⚠️", "تم اكتشاف تغيير في كلمة المرور بإعدادات الراوتر.")
                    if (d.other) fire("تغيّرت إعدادات الراوتر ⚠️", "تم اكتشاف تغيير في إعدادات الراوتر (شبكة/DHCP/WAN).")
                }
                store.snapshot = cfg
            }
        }

        if (reboot && !resetSuspect) {
            fire(
                if (certain) "تمت إعادة تشغيل الراوتر 🔄" else "على الأغلب الراوتر أعاد التشغيل 🔄",
                if (certain) "وقت التشغيل (uptime) رجع للصفر." else "انقطع الراوتر لفترة قصيرة ثم رجع."
            )
        }
    }
}

class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var statusTv: TextView
    private lateinit var eventsTv: TextView
    private lateinit var toggleBtn: Button
    private lateinit var ipEt: EditText
    private lateinit var userEt: EditText
    private lateinit var passEt: EditText
    private val h = Handler(Looper.getMainLooper())

    private val refresh = object : Runnable {
        override fun run() {
            statusTv.text = store.status
            eventsTv.text = store.eventsText()
            toggleBtn.text = if (store.monitoring) "إيقاف المراقبة" else "تشغيل المراقبة"
            h.postDelayed(this, 2000)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float = 14f, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(4))
        }

    private fun button(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        Notifier.init(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        root.addView(label("CudyGuard 🛡️", 26f, true))
        root.addView(label("مراقبة راوتر Cudy AX3000 v2", 14f))

        statusTv = label(store.status, 16f, true)
        root.addView(statusTv)

        root.addView(label("عنوان الراوتر (اتركه فارغ للاكتشاف التلقائي)"))
        ipEt = EditText(this).apply { setText(store.manualIp); inputType = InputType.TYPE_CLASS_TEXT }
        root.addView(ipEt)

        root.addView(label("اسم مستخدم دخول الراوتر"))
        userEt = EditText(this).apply { setText(store.adminUser); inputType = InputType.TYPE_CLASS_TEXT }
        root.addView(userEt)

        root.addView(label("كلمة مرور دخول الراوتر"))
        passEt = EditText(this).apply {
            hint = if (store.adminPassword.isNotEmpty()) "محفوظة (اتركه فارغ للإبقاء عليها)" else "مطلوبة لكشف تغيير الإعدادات"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(passEt)

        root.addView(button("حفظ الإعدادات") {
            store.manualIp = ipEt.text.toString().trim()
            store.adminUser = userEt.text.toString().trim().ifEmpty { "admin" }
            val np = passEt.text.toString()
            if (np.isNotEmpty()) {
                store.adminPassword = np
                store.loginOk = false
                store.loginBadNotified = false
                passEt.setText("")
                passEt.hint = "محفوظة (اتركه فارغ للإبقاء عليها)"
            }
            Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
        })

        root.addView(button("السماح بالإشعارات والعمل بالخلفية") { askPermissions() })

        toggleBtn = button("") {
            if (store.monitoring) {
                store.monitoring = false
                stopService(Intent(this, MonitorService::class.java))
                store.status = "المراقبة متوقفة"
            } else {
                store.monitoring = true
                startMonitor()
            }
        }
        root.addView(toggleBtn)

        root.addView(label("آخر الأحداث", 18f, true))
        eventsTv = TextView(this).apply { textSize = 14f }
        root.addView(eventsTv)

        setContentView(ScrollView(this).apply { addView(root) })

        askPermissions()
        if (store.monitoring) startMonitor()
    }

    override fun onResume() {
        super.onResume()
        h.post(refresh)
    }

    override fun onPause() {
        h.removeCallbacks(refresh)
        super.onPause()
    }

    private fun startMonitor() {
        try {
            startForegroundService(Intent(this, MonitorService::class.java))
        } catch (e: Exception) {
            Toast.makeText(this, "تعذّر تشغيل المراقبة: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun askPermissions() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            return
        }
        askBattery()
    }

    private fun askBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        askBattery()
    }
}
