package com.asapp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.*
import android.content.Intent
import java.io.File
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*

class MainActivity : Activity() {
    private val serviceId = "com.asapp.chat"
    private lateinit var client: ConnectionsClient
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var nick: EditText
    private var peer: String? = null
    private val state = linkedMapOf(
        "1 Play Services" to "not checked", "2 Permissions" to "not checked",
        "3 Advertise" to "not started", "4 Discovery" to "not started",
        "5 Connect" to "not started", "6 Send" to "not started", "7 Receive" to "not started")
    private val logLines = ArrayList<String>()
    private fun st(k: String, v: String) {
        state[k] = v
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        logLines.add("$t [$k] $v"); if (logLines.size > 200) logLines.removeAt(0)
    }
    private fun report(): String {
        val sb = StringBuilder("AS v0.4 | Android ${Build.VERSION.SDK_INT} | ${Build.MODEL}\n\n")
        try {
            val ok = com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == 0
            st("1 Play Services", if (ok) "OK" else "FAILED: Google Play Services missing/old")
        } catch (e: Exception) { st("1 Play Services", "FAILED: ${e.message}") }
        try {
            val bt = (getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
            sb.append(if (bt?.isEnabled == true) "Bluetooth: ON\n" else "Bluetooth: OFF (turn it on)\n")
        } catch (e: Exception) { sb.append("Bluetooth: unknown\n") }
        try {
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            val on = if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else true
            sb.append(if (on) "Location: ON\n" else "Location: OFF (turn it on, Nearby needs it)\n")
        } catch (e: Exception) { sb.append("Location: unknown\n") }
        val miss = needed().filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        st("2 Permissions", if (miss.isEmpty()) "OK" else "MISSING: " + miss.joinToString { it.substringAfterLast('.') })
        sb.append("\n")
        for ((k, v) in state) sb.append(if (v == "OK") "✅ " else if (v.startsWith("FAILED") || v.startsWith("MISSING")) "❌ " else "⚪ ").append("$k: $v\n")
        sb.append("\nLOG:\n").append(logLines.takeLast(40).joinToString("\n"))
        return sb.toString()
    }
    private fun showReport(title: String, text: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(text)
            .setPositiveButton("Share") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text), "Send report"))
            }.setNegativeButton("Close", null).show()
    }
    private var myName = "AS-" + (1000..9999).random()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        client = Nearby.getConnectionsClient(this)
        prefs().getString("id", null)?.let { myName = it }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash.txt").writeText(android.util.Log.getStackTraceString(e) + "\n\n" + logLines.joinToString("\n")) } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
        // Splash: AS logo
        setContentView(TextView(this).apply {
            text = "AS"; textSize = 96f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        Handler(Looper.getMainLooper()).postDelayed({ if (prefs().getString("id", null) == null) onboarding() else enter() }, 1500)
    }

    private val pubKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEbBftarbfOnZimpgwcsq1A1LIARWIy0/OmaST7idzYW14z8/nM74z+8bzCcZ+xStiOBMbR8zrZvloYkR3EkdrYw=="
    private fun today() = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date()).toInt()
    private fun daysLeft(): Int = 90 - ((System.currentTimeMillis() - prefs().getLong("start", 0)) / 86400000L).toInt()

    private fun access(): Boolean {
        val now = System.currentTimeMillis()
        val last = prefs().getLong("last", 0)
        if (now < last - 86400000L) return false // clock set back
        if (now > last) prefs().edit().putLong("last", now).apply()
        return daysLeft() > 0 || prefs().getInt("exp", 0) >= today()
    }

    private fun enter() {
        if (prefs().getLong("start", 0) == 0L) prefs().edit().putLong("start", System.currentTimeMillis()).apply()
        if (access()) { buildUi(); checkCrash() } else paywall()
    }

    private fun verifyCode(code: String): Int? = try {
        val f = android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
        val parts = code.trim().split(".")
        val payload = android.util.Base64.decode(parts[0], f)
        val sig = android.util.Base64.decode(parts[1], f)
        val pub = java.security.KeyFactory.getInstance("EC").generatePublic(
            java.security.spec.X509EncodedKeySpec(android.util.Base64.decode(pubKey, android.util.Base64.DEFAULT)))
        val v = java.security.Signature.getInstance("SHA256withECDSA")
        v.initVerify(pub); v.update(payload)
        val fields = String(payload).split("|")
        if (v.verify(sig) && fields[0] == "AS1") fields[1].toInt() else null
    } catch (e: Exception) { null }

    private fun paywall() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 160, 48, 48) }
        root.addView(TextView(this).apply { text = "AS"; textSize = 64f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        root.addView(TextView(this).apply { text = "\nYour 3-month free trial has ended.\nAS costs $5 per month.\nAfter you pay, you will get an unlock code. Paste it below."; textSize = 16f })
        val box = EditText(this).apply { hint = "Paste unlock code" }
        root.addView(box)
        root.addView(Button(this).apply {
            text = "Unlock"
            setOnClickListener {
                val exp = verifyCode(box.text.toString())
                if (exp != null && exp >= today()) { prefs().edit().putInt("exp", exp).apply(); enter() }
                else toast("Invalid or expired code")
            }
        })
        setContentView(root)
    }

    private fun prefs() = getSharedPreferences("as", 0)

    private fun onboarding() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 160, 48, 48) }
        root.addView(TextView(this).apply { text = "AS"; textSize = 64f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        root.addView(TextView(this).apply { text = "\nEnter your phone number"; textSize = 18f })
        val num = EditText(this).apply { inputType = android.text.InputType.TYPE_CLASS_PHONE; hint = "+92 300 1234567" }
        root.addView(num)
        root.addView(TextView(this).apply { text = "Your number stays on this phone only. Others will see just your random AS-ID.\n(Number is not verified yet.)"; textSize = 13f })
        root.addView(Button(this).apply {
            text = "Continue"
            setOnClickListener {
                val n = num.text.toString().filter { it.isDigit() || it == '+' }
                if (n.count { it.isDigit() } < 8) { toast("Enter a valid number"); return@setOnClickListener }
                val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
                val r = java.security.SecureRandom()
                val id = "AS-" + (1..5).map { chars[r.nextInt(chars.length)] }.joinToString("")
                prefs().edit().putString("phone", n).putString("id", id).apply()
                myName = id
                enter()
            }
        })
        setContentView(root)
    }

    private fun checkCrash() {
        val f = File(filesDir, "crash.txt")
        if (f.exists()) { val t = f.readText().take(3500); f.delete(); showReport("App crashed last time", t) }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 48, 24, 24) }
        root.addView(TextView(this).apply { text = "AS"; textSize = 32f; typeface = Typeface.DEFAULT_BOLD })
        status = TextView(this).apply { text = "Your ID: $myName  |  Not connected  |  " + (if (prefs().getInt("exp", 0) >= today()) "Subscribed" else "Trial: ${daysLeft()} days left") }
        root.addView(status)
        nick = EditText(this).apply { hint = "Nickname (optional)"; setSingleLine() }
        root.addView(nick)
        root.addView(Button(this).apply { text = "Find nearby AS users"; setOnClickListener { start() } })
        root.addView(Button(this).apply { text = "Diagnostics (which part has error)"; setOnClickListener { showReport("Diagnostics", report()) } })
        log = TextView(this).apply { textSize = 16f }
        scroll = ScrollView(this).apply { addView(log) }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val input = EditText(this).apply { hint = "Message" }
        val row = LinearLayout(this)
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply {
            text = "Send"
            setOnClickListener {
                val t = input.text.toString().trim()
                val p = peer
                if (t.isEmpty()) return@setOnClickListener
                if (p == null) { toast("Not connected"); return@setOnClickListener }
                client.sendPayload(p, Payload.fromBytes("$myName: $t".toByteArray()))
                    .addOnSuccessListener { st("6 Send", "OK") }.addOnFailureListener { e -> st("6 Send", "FAILED: ${e.message}") }
                add("Me: $t"); input.setText("")
            }
        })
        root.addView(row)
        setContentView(root)
    }

    private fun needed(): List<String> {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) perms += listOf(
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.NEARBY_WIFI_DEVICES
        return perms
    }

    private fun start() {
        val perms = needed()
        if (perms.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) go()
        else { st("2 Permissions", "asking user"); requestPermissions(perms.toTypedArray(), 1) }
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r.all { it == 0 }) go() else { st("2 Permissions", "MISSING: user denied"); toast("Permissions needed") }
    }

    private fun go() {
        val n = nick.text.toString().trim()
        if (n.isNotEmpty()) myName = n
        val opts = { AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build() }
        client.startAdvertising(myName, serviceId, lifecycle, opts())
            .addOnSuccessListener { st("3 Advertise", "OK") }.addOnFailureListener { e -> st("3 Advertise", "FAILED: ${e.message}") }
        client.startDiscovery(serviceId, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(id: String, i: DiscoveredEndpointInfo) {
                client.requestConnection(myName, id, lifecycle)
                    .addOnSuccessListener { st("5 Connect", "requested") }.addOnFailureListener { e -> st("5 Connect", "FAILED: ${e.message}") }
            }
            override fun onEndpointLost(id: String) {}
        }, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnSuccessListener { st("4 Discovery", "OK") }.addOnFailureListener { e -> st("4 Discovery", "FAILED: ${e.message}") }
        status.text = "Searching..."
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, i: ConnectionInfo) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Connect to ${i.endpointName}?")
                .setMessage("Code: ${i.authenticationDigits}\nIs this the same code on the other phone?")
                .setPositiveButton("Yes") { _, _ -> client.acceptConnection(id, payloads) }
                .setNegativeButton("No") { _, _ -> client.rejectConnection(id) }
                .show()
        }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            if (r.status.isSuccess) {
                peer = id; status.text = "Connected"; st("5 Connect", "OK")
                client.stopAdvertising(); client.stopDiscovery()
            } else { status.text = "Connection failed, try again"; st("5 Connect", "FAILED: code ${r.status.statusCode}") }
        }
        override fun onDisconnected(id: String) { peer = null; status.text = "Disconnected" }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            p.asBytes()?.let { add(String(it).take(2000)); st("7 Receive", "OK") }
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {}
    }

    private fun add(s: String) { log.append(s + "\n"); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    override fun onDestroy() { client.stopAllEndpoints(); super.onDestroy() }
}
