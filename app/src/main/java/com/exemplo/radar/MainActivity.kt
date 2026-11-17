package com.exemplo.radar

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult as BleResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

class Dev(
    val id: String,
    var name: String,
    val wifi: Boolean,
    var rssi: Int,
    var dist: Double,
    var seen: Long
)

class MainActivity : Activity() {
    private val devs = LinkedHashMap<String, Dev>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var radar: RadarView
    private lateinit var list: TextView
    private var wifiMgr: WifiManager? = null
    private var wifiRx: BroadcastReceiver? = null
    private var started = false
    private var n = 0

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
            addView(sv, LinearLayout.LayoutParams(-1, (200 * d).toInt()))
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
        startBle()
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
            readWifi()
            val now = System.currentTimeMillis()
            devs.values.removeAll { now - it.seen > 20000 }
            refresh()
            handler.postDelayed(this, 3000)
        }
    }

    private fun readWifi() {
        val m = wifiMgr ?: return
        try {
            for (r in m.scanResults) {
                val f = r.frequency.toDouble()
                val dist = 10.0.pow((27.55 - 20 * log10(f) + abs(r.level)) / 20.0)
                val nm = if (r.SSID.isNullOrEmpty()) "Rede oculta" else r.SSID
                upsert(r.BSSID, nm, true, r.level, dist)
            }
        } catch (e: SecurityException) {
        }
    }

    private val bleCb = object : ScanCallback() {
        override fun onScanResult(type: Int, r: BleResult) {
            val addr = r.device.address
            val nm = r.scanRecord?.deviceName ?: ("BT " + addr.takeLast(5))
            val dist = 10.0.pow((-59.0 - r.rssi) / 25.0)
            upsert(addr, nm, false, r.rssi, dist)
        }
    }

    private fun startBle() {
        try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter?.bluetoothLeScanner?.startScan(bleCb)
        } catch (e: Exception) {
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

    private fun refresh() {
        val s = devs.values.sortedBy { it.dist }
        radar.items = s
        list.text = if (s.isEmpty()) {
            "Procurando... ligue a localização e o Bluetooth do celular."
        } else {
            s.take(15).joinToString("\n") {
                (if (it.wifi) "Wi-Fi  " else "BT  ") + it.name +
                    "  ~" + String.format("%.1f", it.dist) + " m  (" + it.rssi + " dBm)"
            }
        }
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
}

class RadarView(c: Context) : View(c) {
    var items: List<Dev> = emptyList()
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

        for (v in items) {
            val a = Math.toRadians(((v.id.hashCode() and 0x7fffffff) % 360).toDouble())
            val f = min(1.0, ln(1.0 + v.dist) / ln(31.0)).toFloat()
            val x = cx + r * f * cos(a).toFloat()
            val y = cy + r * f * sin(a).toFloat()
            p.color = Color.parseColor(if (v.wifi) "#4FC3D4" else "#FFB347")
            cv.drawCircle(x, y, 5f * d, p)
            p.color = Color.parseColor("#DCE7EC")
            cv.drawText(v.name.take(12), x + 8f * d, y + 4f * d, p)
        }

        p.color = Color.parseColor("#4FC3D4")
        cv.drawText("● Wi-Fi", 12f * d, 18f * d, p)
        p.color = Color.parseColor("#FFB347")
        cv.drawText("● Bluetooth", 70f * d, 18f * d, p)

        postInvalidateOnAnimation()
    }
}
