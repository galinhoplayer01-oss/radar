package com.exemplo.radar

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanResult as BleResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

// ===== AJUSTES (mexa aqui para calibrar) =====
// Sinal esperado a 1 metro. Mais negativo = distâncias maiores.
private const val WIFI_REF_24 = -40.0   // Wi-Fi 2.4 GHz
private const val WIFI_REF_5 = -47.0    // Wi-Fi 5 GHz
private const val WIFI_N = 3.0          // 2 = espaço aberto, 3 = casa com paredes, 4 = muitas paredes
private const val BT_REF = -59.0        // Bluetooth a 1 metro
private const val BT_N = 2.5
private const val HIDDEN = "Rede oculta"

class Dev(
    val id: String,
    var name: String,
    val wifi: Boolean,
    var rssi: Int,
    var dist: Double,
    var seen: Long
)

// O que aparece na tela (Wi-Fi 2.4G e 5G do mesmo roteador viram um item só)
class Item(
    val key: String,
    val name: String,
    val wifi: Boolean,
    val rssi: Int,
    val dist: Double,
    val connected: Boolean,
    val count: Int
)

private fun baseName(n: String): String {
    val r = n.replace(Regex("(?i)[-_ ]?(2\\.4ghz|2\\.4g|2\\.4|2ghz|2g|5ghz|5g)$"), "").trim()
    return if (r.isEmpty()) n else r
}class MainActivity : Activity() {
    private val devs = LinkedHashMap<String, Dev>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var radar: RadarView
    private lateinit var list: TextView
    private var wifiMgr: WifiManager? = null
    private var wifiRx: BroadcastReceiver? = null
    private var started = false
    private var n = 0
    private var bleOn = false
    private var bleErr: String? = null
    private var connBssid: String? = null

    private fun needed(): Array<String> {
        val l = arrayListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) l.add(Manifest.permission.BLUETOOTH_SCAN)
        return l.toTypedArray()
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val d = resources.displayMetrics.density
        radar = RadarView(this)
        list = TextView(this).apply {
            setTextColor(Color.parseColor("#DCE7EC"))
            textSize = 13f
            setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
        }
        val sv = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0F1A20"))
            addView(list)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(radar, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(sv, LinearLayout.LayoutParams(-1, (220 * d).toInt()))
        }
        setContentView(root)
        if (needed().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) start()
        else requestPermissions(needed(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) start()
        else list.text = "Permissões negadas. Libere Localização e Dispositivos próximos nas configurações do app."
    }

    private fun start() {
        if (started) return
        started = true
        wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                readWifi()
            }
        }
        wifiRx = rx
        val f = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(rx, f)
        tryStartBle()
        refresh()
        handler.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            n++
            if (n % 5 == 1) {
                try {
                    wifiMgr?.startScan()
                } catch (e: Exception) {
                }
            }
            // Bluetooth: se estiver desligado, para; quando ligar, volta a escanear sozinho
            if (!btEnabled()) bleOn = false
            tryStartBle()
            readWifi()
            val now = System.currentTimeMillis()
            devs.values.removeAll { now - it.seen > (if (it.wifi) 60000 else 20000) }
            refresh()
            handler.postDelayed(this, 3000)
        }
    }

    @Suppress("DEPRECATION")
    private fun readWifi() {
        val m = wifiMgr ?: return
        try {
            val cb = m.connectionInfo?.bssid
            connBssid = if (cb == null || cb.startsWith("02:00:00")) null else cb
            for (r in m.scanResults) {
                val ref = if (r.frequency > 4900) WIFI_REF_5 else WIFI_REF_24
                val dist = 10.0.pow((ref - r.level) / (10.0 * WIFI_N))
                val nm = if (r.SSID.isNullOrEmpty()) HIDDEN else r.SSID
                upsert(r.BSSID, nm, true, r.level, dist)
            }
        } catch (e: SecurityException) {
        }
    }

    private val bleCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: BleResult) {
            val addr = r.device.address
            val nm = r.scanRecord?.deviceName ?: ("BT " + addr.takeLast(5))
            val dist = 10.0.pow((BT_REF - r.rssi) / (10.0 * BT_N))
            upsert(addr, nm, false, r.rssi, dist)
        }

        override fun onScanFailed(errorCode: Int) {
            bleOn = false
            bleErr = "Falha no scan Bluetooth (código $errorCode)"
        }
    }

    private fun btEnabled(): Boolean {
        return try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter?.isEnabled == true
        } catch (e: Exception) {
            false
        }
    }

    private fun locationOn(): Boolean {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else true
        } catch (e: Exception) {
            true
        }
    }

    private fun tryStartBle() {
        if (bleOn) return
        try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val ad = bm.adapter ?: return
            if (!ad.isEnabled) return
            val st = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            ad.bluetoothLeScanner?.startScan(null, st, bleCb)
            bleOn = true
            bleErr = null
        } catch (e: Exception) {
            bleErr = "Erro no Bluetooth: " + e.javaClass.simpleName
        }
    }

    private fun upsert(id: String, name: String, wifi: Boolean, rssi: Int, dist: Double) {
        val dd = dist.coerceIn(0.3, 100.0)
        val now = System.currentTimeMillis()
        val e = devs[id]
        if (e == null) {
            devs[id] = Dev(id, name, wifi, rssi, dd, now)
        } else {
            e.rssi = rssi
            e.dist = e.dist * 0.6 + dd * 0.4
            e.seen = now
            if (!name.startsWith("BT ")) e.name = name
        }
    }

    private fun buildItems(): List<Item> {
        val out = ArrayList<Item>()
        val groups = LinkedHashMap<String, MutableList<Dev>>()
        for (d in devs.values) {
            if (!d.wifi) {
                out.add(Item(d.id, d.name, false, d.rssi, d.dist, false, 1))
            } else {
                val key = if (d.name == HIDDEN) d.id else "n:" + baseName(d.name).lowercase()
                groups.getOrPut(key) { ArrayList() }.add(d)
            }
        }
        for ((key, g) in groups) {
            val best = g.maxByOrNull { it.rssi } ?: continue
            val conn = g.any { it.id.equals(connBssid, true) }
            val nm = if (best.name == HIDDEN) HIDDEN else baseName(best.name)
            out.add(Item(key, nm, true, best.rssi, best.dist, conn, g.size))
        }
        return out.sortedBy { it.dist }
    }

    private fun problems(): String {
        val l = ArrayList<String>()
        if (!btEnabled()) l.add("⚠ Bluetooth desligado: ligue para ver dispositivos BT.")
        if (!locationOn()) l.add("⚠ Localização desligada: ligue no painel rápido do celular.")
        bleErr?.let { l.add("⚠ $it") }
        return l.joinToString("\n")
    }

    private fun refresh() {
        val s = buildItems()
        radar.items = s
        val prob = problems()
        val body = if (s.isEmpty()) {
            "Procurando..."
        } else {
            s.take(30).joinToString("\n") {
                (if (it.connected) "★ " else "") +
                    (if (it.wifi) "Wi-Fi  " else "BT  ") + it.name +
                    (if (it.count > 1) " (×" + it.count + ")" else "") +
                    "  ~" + String.format("%.1f", it.dist) + " m  (" + it.rssi + " dBm)"
            }
        }
        list.text = if (prob.isEmpty()) body else prob + "\n\n" + body
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tick)
        try {
            wifiRx?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
        }
        try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter?.bluetoothLeScanner?.stopScan(bleCb)
        } catch (e: Exception) {
        }
    }
}private class Pt(val v: Item, val x: Float, val y: Float)

class RadarView(c: Context) : View(c) {
    var items: List<Item> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var sweep = 0f

    override fun onDraw(cv: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy) - 28f * d
        cv.drawColor(Color.parseColor("#0B1A1F"))
        p.strokeWidth = 1f * d
        p.textSize = 10f * d

        for (m in intArrayOf(1, 3, 10, 30)) {
            val rr = (ln(1.0 + m) / ln(31.0)).toFloat() * r
            p.style = Paint.Style.STROKE
            p.color = Color.parseColor("#2A4A55")
            cv.drawCircle(cx, cy, rr, p)
            p.style = Paint.Style.FILL
            p.color = Color.parseColor("#8AA0AB")
            cv.drawText("$m m", cx + 4f * d, cy - rr + 12f * d, p)
        }

        sweep = (sweep + 2f) % 360f
        val sa = Math.toRadians(sweep.toDouble())
        p.style = Paint.Style.STROKE
        p.strokeWidth = 2f * d
        p.color = Color.parseColor("#664FC3D4")
        cv.drawLine(cx, cy, cx + r * cos(sa).toFloat(), cy + r * sin(sa).toFloat(), p)

        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        cv.drawCircle(cx, cy, 4f * d, p)

        // 1) pontos
        val pts = ArrayList<Pt>()
        for (v in items) {
            val a = Math.toRadians(((v.key.hashCode() and 0x7fffffff) % 360).toDouble())
            val f = min(1.0, ln(1.0 + v.dist) / ln(31.0)).toFloat()
            val x = cx + r * f * cos(a).toFloat()
            val y = cy + r * f * sin(a).toFloat()
            pts.add(Pt(v, x, y))
            p.style = Paint.Style.FILL
            p.color = Color.parseColor(if (v.wifi) "#4FC3D4" else "#FFB347")
            cv.drawCircle(x, y, (if (v.connected) 8f else 5f) * d, p)
            if (v.connected) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2f * d
                p.color = Color.WHITE
                cv.drawCircle(x, y, 10f * d, p)
            }
        }

        // 2) nomes: do mais perto para o mais longe, pulando os que bateriam em outro nome
        p.style = Paint.Style.FILL
        val placed = ArrayList<RectF>()
        for (pt in pts) {
            val txt = (if (pt.v.connected) "★ " else "") + pt.v.name.take(14)
            val w = p.measureText(txt)
            val h = p.textSize
            var lx = pt.x + 12f * d
            if (lx + w > width - 4f * d) lx = pt.x - 12f * d - w
            lx = max(lx, 2f * d)
            val base = pt.y + 4f * d
            val rect = RectF(lx, base - h, lx + w, base + h * 0.25f)
            if (placed.none { RectF.intersects(it, rect) }) {
                placed.add(rect)
                p.color = Color.parseColor("#DCE7EC")
                cv.drawText(txt, lx, base, p)
            }
        }

        p.color = Color.parseColor("#4FC3D4")
        cv.drawText("● Wi-Fi", 12f * d, 18f * d, p)
        p.color = Color.parseColor("#FFB347")
        cv.drawText("● Bluetooth", 70f * d, 18f * d, p)
        p.color = Color.WHITE
        cv.drawText("★ conectada", 160f * d, 18f * d, p)

        postInvalidateOnAnimation()
    }
}
