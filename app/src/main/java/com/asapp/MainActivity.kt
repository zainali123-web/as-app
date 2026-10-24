package com.asapp

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelFileDescriptor
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.*
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.io.File
import android.net.Uri
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.GestureDetector
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.util.Locale

class MainActivity : Activity() {
    private val serviceId = "com.asapp.chat"
    private lateinit var client: ConnectionsClient
    private lateinit var modeTv: TextView
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var content: FrameLayout
    private val peers = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private val asked = java.util.Collections.synchronizedSet(HashSet<String>())
    private val seenChunks = java.util.Collections.synchronizedSet(HashSet<String>())
    private val inVoice = HashMap<Long, Array<ByteArray?>>()
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var recStart = 0L
    @Volatile private var walkieOn = false
    private var lastVoice: File? = null
    private var player: MediaPlayer? = null
    private var tab = 0
    private var note = ""
    private val chatLog = StringBuilder()
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pend: String? = null
    private var lastOnline = true
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var myName = "AS-" + (1000..9999).random()
    private val green = "#00E676"
    val io = java.util.concurrent.Executors.newCachedThreadPool()
    val ui = Handler(Looper.getMainLooper())
    private var polling = false
    private var idToken: String? = null
    private var tokenAt = 0L
    private var reels = ArrayList<String>()
    private var reelCredit = ArrayList<String>()
    private var ridx = 0
    private val maxBytes = 8L * 1024 * 1024 * 1024
    private val langQ = listOf("city life", "pakistan", "india", "punjab india", "afghanistan", "dubai")
    private var target = ""
    private var reelOffline = false
    private var bgIdx = 0
    private var bgNow = 0
    private var rootView: View? = null
    private var colorsOn = false
    private var sessPw: String? = null
    val calls by lazy { Calls(this) }

    // ---------- diagnostics ----------
    private val state = linkedMapOf(
        "1 PLAY SERVICES" to "not checked", "2 PERMISSIONS" to "not checked",
        "3 ADVERTISE" to "not started", "4 DISCOVERY" to "not started",
        "5 CONNECT" to "not started", "6 SEND" to "not started", "7 RECEIVE" to "not started",
        "8 ONLINE CHAT" to "not started", "9 REELS" to "not started",
        "10 WALKIE-TALKIE" to "not started", "11 VOICE MESSAGE" to "not started",
        "12 EMAIL LOGIN" to "not started", "13 CALLS" to "not started")
    private val logLines = ArrayList<String>()
    @Synchronized fun st(k: String, v: String) {
        state[k] = v
        val t = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())
        logLines.add("$t [$k] $v"); if (logLines.size > 200) logLines.removeAt(0)
    }
    private fun report(): String {
        val sb = StringBuilder("AS CHATS v0.5 | Android ${Build.VERSION.SDK_INT} | ${Build.MODEL}\n\n")
        try {
            val ok = com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == 0
            st("1 PLAY SERVICES", if (ok) "OK" else "FAILED: Google Play Services missing/old")
        } catch (e: Exception) { st("1 PLAY SERVICES", "FAILED: ${e.message}") }
        try {
            val bt = (getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
            sb.append(if (bt?.isEnabled == true) "Bluetooth: ON\n" else "Bluetooth: OFF (turn it on)\n")
        } catch (e: Exception) { sb.append("Bluetooth: unknown\n") }
        try {
            val lm = getSystemService(LOCATION_SERVICE) as android.location.LocationManager
            val on = if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled else true
            sb.append(if (on) "Location: ON\n" else "Location: OFF (turn it on)\n")
        } catch (e: Exception) { sb.append("Location: unknown\n") }
        val miss = needed().filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        st("2 PERMISSIONS", if (miss.isEmpty()) "OK" else "MISSING: " + miss.joinToString { it.substringAfterLast('.') })
        sb.append("\n")
        for ((k, v) in synchronized(this) { state.toList() }) sb.append(if (v == "OK") "✅ " else if (v.startsWith("FAILED") || v.startsWith("MISSING")) "❌ " else "⚪ ").append("$k: $v\n")
        sb.append("\nLOG:\n").append(logLines.takeLast(40).joinToString("\n"))
        return sb.toString()
    }
    private fun showReport(title: String, text: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(text)
            .setPositiveButton("SHARE") { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text), "SEND REPORT"))
            }.setNegativeButton("CLOSE", null).show()
    }

    // ---------- helpers ----------
    private fun prefs() = getSharedPreferences("as", 0)
    private fun dark() = prefs().getBoolean("dark", true)
    private fun palette(i: Int) = Color.HSVToColor(floatArrayOf((i * 6f) % 360f, if (dark()) 0.7f else 0.25f, if (dark()) 0.30f else 0.97f))
    private fun toonColor(i: Int) = Color.HSVToColor(floatArrayOf((i * 6f) % 360f, 0.9f, if (dark()) 1f else 0.75f))
    private fun bg(): Int {
        val fx = prefs().getInt("bgfix", -1)
        return if (fx >= 0) palette(fx) else if (prefs().getBoolean("bgauto", true)) palette(bgIdx) else if (dark()) Color.BLACK else Color.WHITE
    }
    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    fun dpx(x: Int) = dp(x)
    private fun sm(x: Float): Float { val v = x.coerceIn(0f, 1f); return v * v * (3 - 2 * v) }
    private fun lerpF(a: Float, b: Float, f: Float) = a + (b - a) * f
    private fun fg() = if (dark()) Color.WHITE else Color.BLACK
    private fun ac() = prefs().getInt("accent", Color.parseColor(green))
    private fun fs() = prefs().getInt("size", 16).toFloat()
    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun tv(s: String, size: Float = fs(), color: Int = fg(), bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun btn(s: String, f: () -> Unit) = Button(this).apply {
        text = s; setTextColor(Color.BLACK)
        background = GradientDrawable().apply { setColor(ac()); cornerRadius = 40f }
        setOnClickListener { f() }
    }
    private fun col() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(32, 48, 32, 32)
    }
    private fun say(t: String) {
        if (!prefs().getBoolean("voice", true)) return
        if (ttsReady) tts?.speak(t, TextToSpeech.QUEUE_ADD, null, "as") else pend = t
    }

    // ---------- network (online / offline mode) ----------
    fun online(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val c = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return c?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
    private fun netState(on: Boolean) {
        if (on == lastOnline) return
        lastOnline = on
        if (::modeTv.isInitialized) refreshMode()
        say(if (on) "ONLINE MODE IS ACTIVATED" else "OFFLINE MODE IS ACTIVATED")
        if (on) io.execute { flushOutbox(); autoSave() }
    }
    private fun watchNet() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            netCb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) { runOnUiThread { netState(true) } }
                override fun onLost(n: Network) { runOnUiThread { netState(online()) } }
            }
            cm.registerDefaultNetworkCallback(netCb!!)
        } catch (e: Exception) { st("NET", "FAILED: ${e.message}") }
    }

    // ---------- lifecycle ----------
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        client = Nearby.getConnectionsClient(this)
        prefs().getString("id", null)?.let { myName = it }
        prefs().getString("nick", "")?.takeIf { it.isNotEmpty() }?.let { myName = it }
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash.txt").writeText(android.util.Log.getStackTraceString(e) + "\n\n" + logLines.joinToString("\n")) } catch (_: Exception) {}
            prev?.uncaughtException(t, e)
        }
        tts = TextToSpeech(this) {
            if (it == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US; ttsReady = true
                pend?.let { p -> pend = null; say(p) }
            }
        }
        lastOnline = online(); watchNet()
        welcome()
    }

    // ---------- welcome + logo screens ----------
    private fun welcome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK); setPadding(48, 48, 48, 48)
        }
        val t = TextView(this).apply {
            textSize = 26f; setTextColor(Color.parseColor(green)); typeface = Typeface.MONOSPACE; gravity = Gravity.CENTER
        }
        val tap = TextView(this).apply {
            text = "TAP TO CONTINUE"; textSize = 16f; setTextColor(Color.WHITE); alpha = 0f
            gravity = Gravity.CENTER; setPadding(0, 96, 0, 0)
        }
        root.addView(t); root.addView(tap)
        setContentView(root)
        val full = "WELCOME TO AHSAN WORLD"
        var i = 0
        val h = Handler(Looper.getMainLooper())
        h.post(object : Runnable {
            override fun run() {
                if (i <= full.length) { t.text = full.substring(0, i) + "_"; i++; h.postDelayed(this, 90) }
                else {
                    t.text = full
                    tap.animate().alpha(1f).setDuration(600).start()
                    root.setOnClickListener { logoScreen() }
                }
            }
        })
    }

    private fun logoScreen() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val logo = TextView(this).apply {
            text = "AS CHATS"; textSize = 44f; typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER; setTextColor(Color.WHITE); letterSpacing = 0.15f
        }
        root.addView(logo, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
        val a1 = ValueAnimator.ofFloat(1f, 1.12f).apply {
            duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { logo.scaleX = it.animatedValue as Float; logo.scaleY = it.animatedValue as Float }
            start()
        }
        val a2 = ValueAnimator.ofArgb(Color.WHITE, Color.parseColor(green), Color.CYAN, Color.MAGENTA, Color.WHITE).apply {
            duration = 3000; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { logo.setTextColor(it.animatedValue as Int) }
            start()
        }
        say("WELCOME TO AS CHATS")
        Handler(Looper.getMainLooper()).postDelayed({
            a1.cancel(); a2.cancel()
            if (!prefs().getBoolean("in", false)) authScreen() else enter()
        }, 3000)
    }

    // ---------- trial / code ----------
    private val pubKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEbBftarbfOnZimpgwcsq1A1LIARWIy0/OmaST7idzYW14z8/nM74z+8bzCcZ+xStiOBMbR8zrZvloYkR3EkdrYw=="
    private fun today() = java.text.SimpleDateFormat("yyyyMMdd", Locale.US).format(java.util.Date()).toInt()
    private fun daysLeft(): Int = 90 - ((System.currentTimeMillis() - prefs().getLong("start", 0)) / 86400000L).toInt()
    private fun access(): Boolean {
        val now = System.currentTimeMillis()
        val last = prefs().getLong("last", 0)
        if (now < last - 86400000L) return false
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
        val c = col()
        c.addView(tv("AS CHATS", 40f, ac(), true))
        c.addView(tv("\nYOUR 3-MONTH FREE TRIAL HAS ENDED.\nAS CHATS COSTS $5 PER MONTH.\nAFTER YOU PAY, YOU WILL GET AN UNLOCK CODE. PASTE IT BELOW.\n"))
        val box = EditText(this).apply { hint = "PASTE UNLOCK CODE"; setTextColor(fg()); setHintTextColor(Color.GRAY) }
        c.addView(box)
        c.addView(btn("UNLOCK") {
            val exp = verifyCode(box.text.toString())
            if (exp != null && exp >= today()) { prefs().edit().putInt("exp", exp).apply(); enter() }
            else toast("INVALID OR EXPIRED CODE")
        })
        c.setBackgroundColor(bg()); setContentView(c)
    }

    // ---------- email login / account ----------
    private fun field(hint: String, pass: Boolean = false) = EditText(this).apply {
        this.hint = hint; setSingleLine(); setTextColor(fg()); setHintTextColor(Color.GRAY)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            (if (pass) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
    }
    private fun page(c: View) {
        setContentView(ScrollView(this).apply { setBackgroundColor(bg()); addView(c) })
    }

    private fun authScreen(create: Boolean = false, msg: String = "") {
        val c = col().apply { setPadding(32, 48, 32, 32) }
        c.addView(tv("AS CHATS", 40f, ac(), true))
        c.addView(tv(if (create) "\nCREATE ACCOUNT WITH EMAIL" else "\nLOGIN WITH EMAIL", 18f))
        val email = field("EMAIL").apply { setText(prefs().getString("email", "")) }
        val pw = field("PASSWORD (MIN 6 CHARACTERS)", true)
        val pw2 = field("TYPE PASSWORD AGAIN", true)
        c.addView(email); c.addView(pw); if (create) c.addView(pw2)
        val status = tv(msg, 13f, Color.YELLOW)
        c.addView(status)
        c.addView(btn(if (create) "CREATE ACCOUNT" else "LOGIN") {
            val e = email.text.toString().trim().lowercase(); val p = pw.text.toString()
            when {
                !e.contains("@") || !e.contains(".") -> status.text = "ENTER A VALID EMAIL."
                p.length < 6 -> status.text = "PASSWORD MUST HAVE AT LEAST 6 CHARACTERS."
                create && p != pw2.text.toString() -> status.text = "THE TWO PASSWORDS ARE NOT THE SAME."
                else -> {
                    status.text = "PLEASE WAIT..."
                    io.execute {
                        val err = try { authFlow(create, e, p) } catch (x: Exception) { "ERROR: ${x.message}" }
                        if (err != null) runOnUiThread { status.text = err }
                    }
                }
            }
        })
        if (!create) c.addView(btn("FORGOT PASSWORD? SEND RESET EMAIL") {
            val e = email.text.toString().trim().lowercase()
            if (!e.contains("@")) { status.text = "TYPE YOUR EMAIL FIRST."; return@btn }
            if (!online()) { status.text = "NEEDS INTERNET."; return@btn }
            status.text = "PLEASE WAIT..."
            io.execute {
                val r = AuthApi.sendReset(e)
                runOnUiThread { status.text = if (r.ok) "RESET EMAIL SENT. OPEN IT (CHECK SPAM), TAP THE LINK, SET A NEW PASSWORD, THEN LOGIN." else AuthApi.friendly(r.err) }
            }
        })
        c.addView(btn(if (create) "I ALREADY HAVE AN ACCOUNT" else "CREATE NEW ACCOUNT") { authScreen(!create) })
        c.addView(btn("NO INTERNET? USE NEARBY CHAT ONLY") {
            prefs().edit().putBoolean("in", true).apply()
            if (myId().isEmpty()) myName = "AS-" + (1000..9999).random()
            enter()
        })
        c.addView(tv("\nFIRST LOGIN OR NEW ACCOUNT NEEDS INTERNET. AFTER THAT YOU CAN LOGIN WITHOUT INTERNET ON THIS PHONE.", 11f, Color.GRAY))
        page(c)
    }

    private fun savePwHash(pw: String) {
        val salt = AuthApi.rnd(16)
        prefs().edit().putString("pwsalt", b64(salt)).putString("pwhash", b64(AuthApi.pbkdf2(pw, salt))).apply()
    }
    private fun offlineLogin(email: String, pw: String): String? {
        val p = prefs()
        val salt = p.getString("pwsalt", null); val hash = p.getString("pwhash", null)
        if (p.getString("email", "") != email || salt == null || hash == null || p.getString("id", "").isNullOrEmpty())
            return "NO INTERNET, AND THIS ACCOUNT WAS NEVER LOGGED IN ON THIS PHONE. CONNECT TO THE INTERNET ONCE."
        if (!AuthApi.same(AuthApi.pbkdf2(pw, b64d(salt)), b64d(hash))) return "WRONG PASSWORD."
        p.edit().putBoolean("in", true).apply()
        myName = p.getString("nick", "")?.takeIf { it.isNotEmpty() } ?: myId()
        st("12 EMAIL LOGIN", "OK (offline)")
        runOnUiThread { enter() }
        return null
    }
    private fun wipeAccount() {
        prefs().edit().remove("id").remove("uid").remove("kpriv").remove("kpub").remove("kuid").remove("pubPosted")
            .remove("profPosted").remove("outbox").remove("dp").remove("nick").remove("rtoken").remove("pwsalt").remove("pwhash").apply()
        chatLog.setLength(0); idToken = null
    }

    /** returns an error text, or null when the next screen has been started */
    private fun authFlow(create: Boolean, email: String, pw: String): String? {
        if (!create && !online()) return offlineLogin(email, pw)
        if (Keys.FB_API_KEY.isEmpty() || Keys.FB_DB_URL.isEmpty()) return "FIREBASE KEYS ARE MISSING IN KEYS.KT."
        val r = if (create) AuthApi.signUp(email, pw) else AuthApi.signIn(email, pw)
        if (!r.ok) {
            if (!create && r.err == "NETWORK") return offlineLogin(email, pw)
            st("12 EMAIL LOGIN", "FAILED: ${r.err}")
            return AuthApi.friendly(r.err)
        }
        val o = r.json!!
        val uid = o.getString("localId"); val rt = o.getString("refreshToken"); val tok = o.getString("idToken")
        if (prefs().getString("uid", "") != uid) wipeAccount()
        prefs().edit().putString("uid", uid).putString("email", email).putString("rtoken", rt).apply()
        savePwHash(pw)
        if (create) AuthApi.sendVerify(tok)
        val verified: Boolean? = if (create) false else AuthApi.lookupVerified(tok)
        if (verified == null) return "NO INTERNET."
        if (!verified) { runOnUiThread { verifyScreen(email, pw) }; return null }
        val err = completeSetup(email, pw)
        if (err != null) return err
        runOnUiThread { welcomeId() }
        return null
    }

    private fun verifyScreen(email: String, pw: String, msg: String = "") {
        val c = col().apply { setPadding(32, 48, 32, 32) }
        c.addView(tv("VERIFY YOUR EMAIL", 26f, ac(), true))
        c.addView(tv("\nWE SENT AN EMAIL TO\n$email\n\nOPEN IT (CHECK SPAM TOO) AND TAP THE LINK. THEN COME BACK HERE AND PRESS THE BUTTON.\n"))
        val status = tv(msg, 13f, Color.YELLOW)
        c.addView(status)
        c.addView(btn("I HAVE VERIFIED") {
            status.text = "CHECKING..."
            io.execute {
                val r = AuthApi.signIn(email, pw)
                val v = if (r.ok) AuthApi.lookupVerified(r.json!!.getString("idToken")) else null
                if (!r.ok) runOnUiThread { status.text = AuthApi.friendly(r.err) }
                else if (v == null) runOnUiThread { status.text = "NO INTERNET." }
                else if (!v) runOnUiThread { status.text = "NOT VERIFIED YET. TAP THE LINK IN THE EMAIL FIRST." }
                else {
                    prefs().edit().putString("rtoken", r.json!!.getString("refreshToken")).apply()
                    val err = completeSetup(email, pw)
                    runOnUiThread { if (err != null) status.text = err else welcomeId() }
                }
            }
        })
        c.addView(btn("SEND EMAIL AGAIN") {
            io.execute {
                val r = AuthApi.signIn(email, pw)
                val s = if (r.ok) AuthApi.sendVerify(r.json!!.getString("idToken")) else r
                runOnUiThread { status.text = if (s.ok) "EMAIL SENT AGAIN." else AuthApi.friendly(s.err) }
            }
        })
        c.addView(btn("BACK") { authScreen() })
        page(c)
    }

    /** after the email is verified: refresh token, find/claim the AS-ID, restore/create the chat key */
    private fun completeSetup(email: String, pw: String): String? {
        val p = prefs()
        val uid = p.getString("uid", "") ?: ""
        val rt = p.getString("rtoken", null) ?: return "LOGIN AGAIN."
        val rr = AuthApi.refresh(rt)
        if (!rr.ok) return AuthApi.friendly(rr.err)
        val tok = rr.json!!.getString("id_token")
        p.edit().putString("rtoken", rr.json.getString("refresh_token")).apply()
        idToken = tok; tokenAt = System.currentTimeMillis()

        // 1) AS-ID (same account = same ID on every phone)
        var id: String? = null
        for (n in 0..19) {
            val cand = AuthApi.candId(uid, n)
            val ow = http("GET", "${db()}/users/$cand/owner.json?auth=$tok")
                ?: return "COULD NOT REACH THE DATABASE. CHECK FB_DB_URL AND THE RULES."
            val t = ow.trim()
            if (t == "null" || t.trim('"') == uid) { id = cand; break }
        }
        if (id == null) return "COULD NOT MAKE AN ID. TRY AGAIN."

        // 2) chat key: this phone -> backup in my Firebase space -> new
        var havePair = p.getString("kuid", "") == uid && p.getString("kpriv", null) != null && p.getString("kpub", null) != null
        var upload = false
        if (!havePair) {
            val bk = http("GET", "${db()}/keybackup/$uid.json?auth=$tok") ?: return "COULD NOT REACH THE DATABASE. CHECK THE RULES."
            if (bk.trim() != "null") {
                val got = AuthApi.openBackup(pw, JSONObject(bk))
                if (got != null) { p.edit().putString("kpriv", got.first).putString("kpub", got.second).putString("kuid", uid).apply(); havePair = true }
            }
            if (!havePair) {
                p.edit().remove("kpriv").remove("kpub").apply()
                myKeys()   // makes a new pair
                p.edit().putString("kuid", uid).apply(); upload = true
            }
        }
        val priv = p.getString("kpriv", "") ?: ""; val pub = p.getString("kpub", "") ?: ""
        if (upload && http("PUT", "${db()}/keybackup/$uid.json?auth=$tok", AuthApi.makeBackup(pw, priv, pub).toString()) == null)
            return "COULD NOT SAVE YOUR KEY BACKUP. CHECK THE RULES."
        if (http("PUT", "${db()}/users/$id.json?auth=$tok", JSONObject().put("pub", pub).put("owner", uid).toString()) == null)
            return "COULD NOT SAVE YOUR ID. CHECK THE RULES AND THAT EMAIL LOGIN IS ON."

        p.edit().putString("id", id).putBoolean("pubPosted", true).putBoolean("profPosted", false).putBoolean("in", true).apply()
        myName = p.getString("nick", "")?.takeIf { it.isNotEmpty() } ?: id
        sessPw = pw
        st("12 EMAIL LOGIN", "OK"); st("8 ONLINE CHAT", "OK")
        return null
    }

    private fun welcomeId() {
        AlertDialog.Builder(this).setTitle("YOUR AS CODE")
            .setMessage("${myId()}\n\nSHARE THIS CODE WITH FRIENDS SO THEY CAN FIND, CHAT AND CALL YOU. IT STAYS THE SAME ON EVERY PHONE WHEN YOU LOGIN WITH THIS EMAIL. YOUR EMAIL STAYS HIDDEN.")
            .setCancelable(false)
            .setPositiveButton("SHARE") { _, _ -> shareId(); enter() }
            .setNegativeButton("CONTINUE") { _, _ -> enter() }.show()
    }

    private fun logout() {
        prefs().edit().putBoolean("in", false).apply()
        idToken = null; sessPw = null
        calls.stopAll()
        authScreen()
    }

    private fun checkCrash() {
        val f = File(filesDir, "crash.txt")
        if (f.exists()) { val t = f.readText().take(3500); f.delete(); showReport("APP CRASHED LAST TIME", t) }
    }

    // ---------- main screen ----------
    private fun refreshMode() {
        val sub = if (prefs().getInt("exp", 0) >= today()) "SUBSCRIBED" else "TRIAL: ${daysLeft()} DAYS LEFT"
        modeTv.text = (if (online()) "ONLINE MODE" else "OFFLINE MODE") + "  |  $sub\nID: ${myId()}  |  $note"
    }
    private fun setNote(s: String) { note = s; if (::modeTv.isInitialized) refreshMode() }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg()) }
        rootView = root; bgNow = bg()
        if (prefs().getBoolean("toons", true))
            root.addView(Buddies(this), LinearLayout.LayoutParams(MATCH_PARENT, dp(46)).apply { topMargin = dp(24) })
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(24, 4, 24, 8) }
        val spk = tv(if (prefs().getBoolean("voice", true)) "🔊" else "🔇", 26f)
        spk.setPadding(0, 0, 32, 0)
        spk.setOnClickListener {
            val v = !prefs().getBoolean("voice", true)
            prefs().edit().putBoolean("voice", v).apply()
            if (!v) tts?.stop()
            spk.text = if (v) "🔊" else "🔇"
        }
        head.addView(spk)
        head.addView(tv("AS CHATS", 24f, ac(), true))
        root.addView(head)
        modeTv = tv("", 11f, Color.GRAY).apply { setPadding(24, 0, 24, 8) }
        root.addView(modeTv)
        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val nav = LinearLayout(this)
        listOf("💬 CHATS", "🎬 REELS", "⚙ SETTINGS").forEachIndexed { i, s ->
            nav.addView(btn(s) { showTab(i) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        root.addView(nav)
        setContentView(root)
        refreshMode()
        startPolling(); startColors(); calls.start()
        showTab(tab)
        if (!online() && tab == 0) say("OFFLINE MODE IS ACTIVATED")
    }

    private fun showTab(i: Int) {
        tab = i; content.removeAllViews()
        when (i) {
            0 -> { content.addView(chatsView()); say("CHATTING MODE IS ACTIVATED") }
            1 -> content.addView(reelsHome())
            else -> content.addView(settingsView())
        }
    }

    private fun chatsView(): View {
        val c = col().apply { setPadding(24, 16, 24, 16) }
        val top = LinearLayout(this)
        top.addView(btn("🔍 NEARBY") { start() }.apply { textSize = 11f }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(btn("🔎 SEARCH ID") { searchId() }.apply { textSize = 11f }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(btn("🤖 AS AI") { asAi() }.apply { textSize = 11f }, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(top)
        val radio = LinearLayout(this)
        val ptt = btn("🎙 HOLD: WALKIE-TALKIE") {}.apply {
            textSize = 11f
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> startWalkie()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopWalkie()
                }
                true
            }
        }
        val vm = btn("🎤 HOLD: VOICE MSG") {}.apply {
            textSize = 11f
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> startVoiceMsg()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stopVoiceMsg()
                }
                true
            }
        }
        radio.addView(ptt, LinearLayout.LayoutParams(0, -2, 1f))
        radio.addView(vm, LinearLayout.LayoutParams(0, -2, 1f))
        radio.addView(btn("▶ LAST") { lastVoice?.let { playVoice(it) } ?: toast("NO VOICE MESSAGE YET") }.apply { textSize = 11f }, LinearLayout.LayoutParams(0, -2, 0.6f))
        c.addView(radio)
        val callRow = LinearLayout(this)
        callRow.addView(btn("📞 VOICE CALL") { calls.call(target, false) }.apply { textSize = 11f }, LinearLayout.LayoutParams(0, -2, 1f))
        callRow.addView(btn("📹 VIDEO CALL") { calls.call(target, true) }.apply { textSize = 11f }, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(callRow)
        val to = EditText(this).apply {
            hint = "ONLINE: SEND TO AS-ID (E.G. AS-7K2P9)"; setText(target); setSingleLine()
            setTextColor(fg()); setHintTextColor(Color.GRAY)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) { target = e.toString().trim().uppercase() }
                override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, d: Int) {}
                override fun onTextChanged(t: CharSequence?, a: Int, b: Int, d: Int) {}
            })
        }
        c.addView(to)
        log = tv(chatLog.toString())
        scroll = ScrollView(this).apply { addView(log) }
        c.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val input = EditText(this).apply { hint = "MESSAGE"; setTextColor(fg()); setHintTextColor(Color.GRAY) }
        val row = LinearLayout(this)
        row.addView(input, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn("SEND ➤") {
            val t = input.text.toString().trim()
            if (t.isEmpty()) return@btn
            sendMsg(t); input.setText("")
        })
        c.addView(row)
        return c
    }

    private fun sendMsg(t: String) {
        if (target.isNotEmpty()) {
            if (!onlineReady()) { toast("ONLINE CHAT NEEDS FIREBASE KEYS (KEYS.KT)"); return }
            if (!online()) { queue(target, t); add("ME → $target: $t\n⏳ WAITING FOR INTERNET (WILL SEND BY ITSELF)"); return }
            val dest = target
            io.execute {
                val ok = sendOnline(dest, t)
                if (!ok) queue(dest, t)
                runOnUiThread { add("ME → $dest: $t  " + if (ok) "✓ SENT" else "⏳ QUEUED") }
            }
            return
        }
        val p = peers.toList()
        if (p.isEmpty()) { toast("NOT CONNECTED. ENTER AN AS-ID OR FIND NEARBY USERS."); return }
        client.sendPayload(p, Payload.fromBytes("$myName: $t".toByteArray()))
            .addOnSuccessListener { st("6 SEND", "OK") }.addOnFailureListener { e -> st("6 SEND", "FAILED: ${e.message}") }
        add("ME: $t")
    }

    private fun complaint() {
        val box = EditText(this).apply { hint = "WRITE YOUR PROBLEM" }
        AlertDialog.Builder(this).setTitle("REPORT A PROBLEM").setView(box)
            .setPositiveButton("SEND") { _, _ ->
                val text = "COMPLAINT FROM $myName:\n" + box.text.toString() + "\n\n" + report()
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text), "SEND TO AS CHATS TEAM"))
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun reelsHome(): View {
        val c = col().apply { gravity = Gravity.CENTER }
        c.addView(tv("🎬", 64f))
        c.addView(tv("REELS\n", 28f, ac(), true))
        c.addView(btn("🌐 ONLINE REELS") { openReels(false) })
        val n = reelDir().listFiles()?.count { it.name.endsWith(".mp4") } ?: 0
        c.addView(btn("💾 OFFLINE REELS ($n SAVED)") { openReels(true) })
        return c
    }
    private fun openReels(off: Boolean) {
        reelOffline = off
        say("ENJOY YOUR REEL")
        content.removeAllViews(); content.addView(reelsView())
    }

    private fun reelsView(): View {
        val f = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val vv = VideoView(this)
        f.addView(vv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER))
        val info = tv("LOADING REELS...", 13f, Color.WHITE).apply { setPadding(24, 24, 24, 24); setBackgroundColor(Color.parseColor("#66000000")) }
        f.addView(info, FrameLayout.LayoutParams(MATCH_PARENT, -2, Gravity.BOTTOM))
        fun play() {
            if (reels.isEmpty()) {
                info.text = "NO REELS YET. " + (if (reelOffline) "SAVE REELS FIRST: OPEN ONLINE REELS ON WI-FI." else if (Keys.PEXELS.isEmpty()) "ADD YOUR PEXELS KEY IN KEYS.KT." else "CONNECT TO INTERNET ONCE TO SAVE REELS.")
                return
            }
            val p = reels[ridx]
            vv.setVideoURI(if (p.startsWith("http")) Uri.parse(p) else Uri.fromFile(File(p)))
            vv.setOnPreparedListener { it.isLooping = true; vv.start() }
            vv.setOnErrorListener { _, _, _ -> info.text = "THIS REEL FAILED. SWIPE UP."; true }
            info.text = "🎬 ${ridx + 1}/${reels.size}  •  ${reelCredit.getOrElse(ridx) { "" }}  •  VIDEOS: PEXELS  •  SWIPE ⬆"
        }
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
                if (reels.isEmpty()) return true
                if (vy < -400) ridx = (ridx + 1) % reels.size
                else if (vy > 400) ridx = (ridx - 1 + reels.size) % reels.size
                else return true
                play(); return true
            }
        })
        f.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }
        loadReels { play() }
        return f
    }

    private fun reelDir() = File(filesDir, "reels").apply { mkdirs() }
    private fun dirSize() = reelDir().listFiles()?.sumOf { it.length() } ?: 0L
    private fun wifiOk(): Boolean {
        if (!prefs().getBoolean("wifionly", true)) return true
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val c = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        return c?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    private fun fetchRemote(): List<Triple<Long, String, String>> {
        val k = Keys.PEXELS
        if (k.isEmpty() || !online()) return emptyList()
        val q = URLEncoder.encode(langQ[prefs().getInt("lang", 0)], "UTF-8")
        val r = http("GET", "https://api.pexels.com/videos/search?query=$q&per_page=15&page=${(1..5).random()}&orientation=portrait", null, mapOf("Authorization" to k))
        if (r == null) { st("9 REELS", "FAILED: Pexels request (check key)"); return emptyList() }
        val out = ArrayList<Triple<Long, String, String>>()
        try {
            val vs = JSONObject(r).getJSONArray("videos")
            for (i in 0 until vs.length()) {
                val v = vs.getJSONObject(i); val fl = v.getJSONArray("video_files")
                var best: JSONObject? = null
                for (j in 0 until fl.length()) {
                    val f = fl.getJSONObject(j)
                    if (f.optString("file_type") == "video/mp4" && f.optInt("width") > 0 &&
                        (best == null || Math.abs(f.optInt("width") - 720) < Math.abs(best.optInt("width") - 720))) best = f
                }
                if (best != null) out.add(Triple(v.getLong("id"), best.getString("link"), v.getJSONObject("user").optString("name")))
            }
            st("9 REELS", "OK")
        } catch (e: Exception) { st("9 REELS", "FAILED: ${e.message}") }
        return out
    }
    private fun trimReels(extra: Long) {
        val fs = reelDir().listFiles()?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var total = fs.sumOf { it.length() }
        while (total + extra > maxBytes && fs.isNotEmpty()) { val f = fs.removeAt(0); total -= f.length(); f.delete() }
    }
    private fun download(id: Long, link: String) {
        val f = File(reelDir(), "$id.mp4")
        if (f.exists() || reelDir().usableSpace < (1L shl 30)) return
        trimReels(60L * 1024 * 1024)
        val tmp = File(reelDir(), "$id.part")
        try {
            val c = URL(link).openConnection() as HttpURLConnection
            c.connectTimeout = 10000; c.readTimeout = 20000
            c.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            tmp.renameTo(f)
        } catch (e: Exception) { tmp.delete(); st("9 REELS", "FAILED: save ${e.message}") }
    }
    private fun autoSave() { if (wifiOk()) fetchRemote().take(6).forEach { download(it.first, it.second) } }
    private fun loadReels(done: () -> Unit) {
        io.execute {
            val items = ArrayList<String>(); val cr = ArrayList<String>()
            val remote: List<Triple<Long, String, String>> = if (reelOffline) emptyList() else fetchRemote()
            for ((id, link, who) in remote) {
                val f = File(reelDir(), "$id.mp4")
                items.add(if (f.exists()) f.absolutePath else link); cr.add(who)
            }
            if (items.isEmpty()) reelDir().listFiles()?.filter { it.name.endsWith(".mp4") && it.length() > 0 }?.shuffled()?.forEach { items.add(it.absolutePath); cr.add("SAVED") }
            runOnUiThread { reels = items; reelCredit = cr; ridx = 0; done() }
            if (wifiOk()) remote.take(6).forEach { download(it.first, it.second) }
        }
    }

    private val langs = listOf("ENGLISH", "URDU", "HINDI", "PUNJABI", "PASHTO", "ARABIC")

    private fun settingsView(): View {
        val c = col()
        c.addView(tv("⚙ SETTINGS", 24f, ac(), true))
        c.addView(Switch(this).apply {
            text = "🔊 VOICE ANNOUNCEMENTS"; setTextColor(fg()); isChecked = prefs().getBoolean("voice", true)
            setOnCheckedChangeListener { _, v -> prefs().edit().putBoolean("voice", v).apply(); if (!v) tts?.stop(); buildUi() }
        })
        c.addView(btn(if (dark()) "☀ LIGHT THEME" else "🌙 DARK THEME") { prefs().edit().putBoolean("dark", !dark()).apply(); buildUi() })
        c.addView(tv("\n🎨 COLOR", 14f))
        val colors = LinearLayout(this)
        listOf(green, "#00B0FF", "#FF4081", "#FF9100", "#B388FF").forEach { hex ->
            colors.addView(Button(this).apply {
                text = "●"; textSize = 22f; setTextColor(Color.parseColor(hex)); setBackgroundColor(Color.TRANSPARENT)
                setOnClickListener { prefs().edit().putInt("accent", Color.parseColor(hex)).apply(); buildUi() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        c.addView(colors)
        c.addView(tv("🔤 TEXT SIZE", 14f))
        val sizes = LinearLayout(this)
        sizes.addView(btn("A-") { prefs().edit().putInt("size", (fs().toInt() - 2).coerceAtLeast(12)).apply(); buildUi() }, LinearLayout.LayoutParams(0, -2, 1f))
        sizes.addView(btn("A+") { prefs().edit().putInt("size", (fs().toInt() + 2).coerceAtMost(26)).apply(); buildUi() }, LinearLayout.LayoutParams(0, -2, 1f))
        c.addView(sizes)
        c.addView(tv("\n✏ NICKNAME", 14f))
        val nk = EditText(this).apply {
            setText(prefs().getString("nick", "")); hint = "NICKNAME"; setTextColor(fg()); setHintTextColor(Color.GRAY)
        }
        c.addView(nk)
        c.addView(btn("SAVE NICKNAME") {
            val n = nk.text.toString().trim()
            prefs().edit().putString("nick", n).apply()
            myName = if (n.isNotEmpty()) n else prefs().getString("id", myName) ?: myName
            refreshMode(); toast("SAVED"); io.execute { pushProfile() }
        })
        c.addView(btn("🌐 REELS LANGUAGE: " + langs[prefs().getInt("lang", 0)]) {
            prefs().edit().putInt("lang", (prefs().getInt("lang", 0) + 1) % langs.size).apply(); showTab(2)
        })
        c.addView(Switch(this).apply {
            text = "📶 SAVE REELS ON WI-FI ONLY"; setTextColor(fg()); isChecked = prefs().getBoolean("wifionly", true)
            setOnCheckedChangeListener { _, v -> prefs().edit().putBoolean("wifionly", v).apply() }
        })
        c.addView(tv("💾 SAVED REELS: ${dirSize() / 1048576} MB / 8 GB", 13f, Color.GRAY))
        c.addView(btn("🗑 CLEAR SAVED REELS") { reelDir().listFiles()?.forEach { it.delete() }; showTab(2) })
        c.addView(tv("🆔 MY CODE: ${myId()}", 14f, ac(), true))
        c.addView(btn("📤 SHARE MY CODE") { shareId() })
        c.addView(tv("📧 ${prefs().getString("email", "") ?: ""}", 12f, Color.GRAY))
        c.addView(btn("🔑 RESET PASSWORD (EMAIL)") {
            val e = prefs().getString("email", "") ?: ""
            if (e.isEmpty() || !online()) { toast("NEEDS LOGIN + INTERNET"); return@btn }
            io.execute { val r = AuthApi.sendReset(e); runOnUiThread { toast(if (r.ok) "RESET EMAIL SENT TO $e" else AuthApi.friendly(r.err)) } }
        })
        c.addView(btn("🚪 LOGOUT") { logout() })
        c.addView(Switch(this).apply {
            text = "🧍 CARTOON BUDDIES"; setTextColor(fg()); isChecked = prefs().getBoolean("toons", true)
            setOnCheckedChangeListener { _, v -> prefs().edit().putBoolean("toons", v).apply(); buildUi() }
        })
        c.addView(Switch(this).apply {
            text = "🌈 AUTO BACKGROUND (EVERY 15 SEC)"; setTextColor(fg()); isChecked = prefs().getBoolean("bgauto", true)
            setOnCheckedChangeListener { _, v -> prefs().edit().putBoolean("bgauto", v).apply(); buildUi() }
        })
        c.addView(tv("🎨 BACKGROUND COLOR (60)", 13f))
        c.addView(colorRow("bgfix") { palette(it) })
        c.addView(tv("🎨 CARTOON COLOR (60)", 13f))
        c.addView(colorRow("cfix") { toonColor(it) })
        c.addView(btn("🖼 SET PROFILE PICTURE") { pickDp() })
        c.addView(btn("📩 REPORT A PROBLEM") { complaint() })
        c.addView(btn("🛠 DIAGNOSTICS") { showReport("DIAGNOSTICS", report()) })
        return ScrollView(this).apply { addView(c) }
    }

    // ---------- online chat (Firebase Realtime Database over REST, end-to-end encrypted) ----------
    fun myId() = prefs().getString("id", "") ?: ""
    fun db() = Keys.FB_DB_URL.trimEnd('/')
    fun onlineReady() = Keys.FB_API_KEY.isNotEmpty() && Keys.FB_DB_URL.isNotEmpty() && myId().isNotEmpty() && prefs().getBoolean("in", false) && prefs().getString("rtoken", null) != null
    private fun b64(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
    private fun b64d(s: String) = android.util.Base64.decode(s, android.util.Base64.DEFAULT)

    fun http(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap(),
                     ct: String = "application/json"): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = 10000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", ct); c.outputStream.use { it.write(body.toByteArray()) } }
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText()
        else { st("HTTP", "$method ${c.responseCode}"); null }
    } catch (e: Exception) { st("HTTP", e.message ?: "error"); null }

    private fun myKeys(): KeyPair {
        val p = prefs(); val kf = KeyFactory.getInstance("EC")
        val priv = p.getString("kpriv", null); val pub = p.getString("kpub", null)
        if (priv != null && pub != null) return KeyPair(kf.generatePublic(X509EncodedKeySpec(b64d(pub))), kf.generatePrivate(PKCS8EncodedKeySpec(b64d(priv))))
        val g = KeyPairGenerator.getInstance("EC"); g.initialize(ECGenParameterSpec("secp256r1"))
        val kp = g.generateKeyPair()
        p.edit().putString("kpriv", b64(kp.private.encoded)).putString("kpub", b64(kp.public.encoded)).apply()
        return kp
    }
    private fun aesKey(theirPub: ByteArray): SecretKeySpec {
        val ka = KeyAgreement.getInstance("ECDH"); ka.init(myKeys().private)
        ka.doPhase(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirPub)), true)
        return SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(ka.generateSecret()), "AES")
    }

    @Synchronized fun token(): String? {
        if (idToken != null && System.currentTimeMillis() - tokenAt < 50 * 60 * 1000) return idToken
        idToken = null
        val rt = prefs().getString("rtoken", null)
        if (rt == null || Keys.FB_API_KEY.isEmpty()) { st("8 ONLINE CHAT", "FAILED: not logged in"); return null }
        val r = AuthApi.refresh(rt)
        if (!r.ok) {
            st("8 ONLINE CHAT", "FAILED: login expired (${r.err}). LOGOUT AND LOGIN AGAIN")
            return null
        }
        try {
            val o = r.json!!
            idToken = o.getString("id_token")
            prefs().edit().putString("rtoken", o.getString("refresh_token")).apply()
        } catch (e: Exception) { idToken = null; return null }
        tokenAt = System.currentTimeMillis()
        st("8 ONLINE CHAT", "OK")
        return idToken
    }

    private fun sendOnline(to: String, text: String): Boolean {
        val tok = token() ?: return false
        val pj = http("GET", "${db()}/users/$to/pub.json?auth=$tok") ?: return false
        if (pj.trim() == "null") { runOnUiThread { add("❌ $to NOT FOUND (CHECK THE ID)") }; return true }
        return try {
            val key = aesKey(b64d(JSONArray("[$pj]").getString(0)))
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val ct = ci.doFinal(text.toByteArray())
            val body = JSONObject().put("from", myId()).put("iv", b64(iv)).put("ct", b64(ct)).put("ts", System.currentTimeMillis()).toString()
            http("POST", "${db()}/inbox/$to.json?auth=$tok", body) != null
        } catch (e: Exception) { st("8 ONLINE CHAT", "FAILED: ${e.message}"); false }
    }

    @Synchronized private fun queue(to: String, t: String) {
        val a = JSONArray(prefs().getString("outbox", "[]")); a.put(JSONObject().put("to", to).put("text", t))
        prefs().edit().putString("outbox", a.toString()).apply()
    }
    @Synchronized private fun takeOutbox(): JSONArray {
        val a = JSONArray(prefs().getString("outbox", "[]")); prefs().edit().putString("outbox", "[]").apply(); return a
    }
    private fun flushOutbox() {
        if (!onlineReady() || !online()) return
        val a = takeOutbox()
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i); val to = o.getString("to"); val t = o.getString("text")
            if (sendOnline(to, t)) runOnUiThread { add("✓ SENT TO $to: $t") } else queue(to, t)
        }
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        ui.post(object : Runnable {
            override fun run() {
                if (onlineReady() && online()) io.execute { try { if (!prefs().getBoolean("profPosted", false)) pushProfile(); flushOutbox(); pollInbox() } catch (e: Exception) { st("8 ONLINE CHAT", "FAILED: ${e.message}") } }
                ui.postDelayed(this, 6000)
            }
        })
    }
    private fun pollInbox() {
        val tok = token() ?: return
        val r = http("GET", "${db()}/inbox/${myId()}.json?auth=$tok") ?: return
        if (r.trim() == "null") return
        val o = JSONObject(r)
        for (k in o.keys()) {
            val m = o.getJSONObject(k); val from = m.optString("from")
            var msg = "(COULD NOT DECRYPT)"
            try {
                val pj = http("GET", "${db()}/users/$from/pub.json?auth=$tok")
                val key = aesKey(b64d(JSONArray("[$pj]").getString(0)))
                val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, b64d(m.getString("iv"))))
                msg = String(ci.doFinal(b64d(m.getString("ct"))))
            } catch (e: Exception) {}
            http("DELETE", "${db()}/inbox/${myId()}/$k.json?auth=$tok")
            runOnUiThread { add("$from: ${msg.take(2000)}"); st("7 RECEIVE", "OK"); say("NEW MESSAGE") }
        }
    }

    // ---------- colors ----------
    private fun colorRow(key: String, f: (Int) -> Int): View {
        val row = LinearLayout(this)
        row.addView(Button(this).apply { text = "AUTO"; textSize = 11f
            setOnClickListener { prefs().edit().putInt(key, -1).apply(); buildUi() } })
        for (i in 0 until 60) row.addView(Button(this).apply {
            minWidth = 0; minHeight = 0; setPadding(0, 0, 0, 0); setBackgroundColor(f(i))
            setOnClickListener { prefs().edit().putInt(key, i).apply(); buildUi() }
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(6, 6, 6, 6) })
        return HorizontalScrollView(this).apply { addView(row) }
    }
    private fun startColors() {
        if (colorsOn) return
        colorsOn = true
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (prefs().getInt("bgfix", -1) < 0 && prefs().getBoolean("bgauto", true)) {
                    val from = bgNow; bgIdx = (bgIdx + 1) % 60
                    ValueAnimator.ofArgb(from, bg()).apply {
                        duration = 1500
                        addUpdateListener { bgNow = it.animatedValue as Int; rootView?.setBackgroundColor(bgNow) }
                        start()
                    }
                }
                ui.postDelayed(this, 15000)
            }
        }, 15000)
    }

    // ---------- AS AI (free, rule-based for now) ----------
    private fun aiReply(q: String): String {
        val s = q.lowercase()
        return when {
            "trial" in s || "free" in s || "price" in s || "paisa" in s || "\$" in s -> "AS CHATS IS FREE FOR 3 MONTHS. AFTER THAT IT IS \$5 PER MONTH (UNLOCK CODE)."
            "offline" in s || "bluetooth" in s || "internet" in s -> "NEARBY CHAT WORKS WITHOUT INTERNET (BLUETOOTH / WIFI DIRECT). BOTH PHONES NEED AS CHATS. ONLINE CHAT NEEDS INTERNET."
            " id" in s || s.startsWith("id") || "number" in s || "code" in s -> "YOUR EMAIL STAYS HIDDEN. OTHERS SEE ONLY YOUR AS-ID. FIND IT IN SETTINGS (MY CODE). THE SAME EMAIL GIVES THE SAME ID ON ANY PHONE."
            "reel" in s || "video" in s -> "OPEN REELS, THEN CHOOSE ONLINE OR OFFLINE. SWIPE UP FOR THE NEXT REEL. ONLINE REELS SAVE ON WI-FI (UP TO 8 GB)."
            "dp" in s || "photo" in s || "profile" in s -> "SETTINGS > SET PROFILE PICTURE. OTHERS CAN FIND YOU WITH SEARCH ID."
            "color" in s || "colour" in s || "theme" in s -> "SETTINGS > BACKGROUND COLOR / CARTOON COLOR. CHOOSE ONE TO STOP AUTO CHANGE."
            "voice" in s || "speaker" in s || "sound" in s -> "TAP THE SPEAKER AT THE TOP LEFT TO TURN VOICE ON OR OFF."
            else -> "I AM A SIMPLE FREE HELPER FOR NOW. USE SETTINGS > REPORT A PROBLEM FOR OTHER HELP."
        }
    }
    private fun asAi() {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 0) }
        val out = TextView(this).apply { text = "AS AI: HELLO! ASK ME ABOUT AS CHATS." }
        val inp = EditText(this).apply { hint = "TYPE HERE" }
        v.addView(ScrollView(this).apply { addView(out) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(160)))
        v.addView(inp)
        val d = AlertDialog.Builder(this).setTitle("🤖 AS AI").setView(v)
            .setPositiveButton("ASK", null).setNegativeButton("CLOSE", null).create()
        d.show()
        d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val q = inp.text.toString()
            if (q.isNotBlank()) { out.append("\n\nYOU: $q\nAS AI: ${aiReply(q)}"); inp.setText("") }
        }
    }

    // ---------- cartoon buddies (girl S on the left, boy A on the right) ----------
    private class G(val kind: Int, val ball: Int, val r: Float, val arc: Float, val sp: Float)
    private val games = listOf(
        G(0, Color.WHITE, 4f, 0.25f, 0.9f), G(0, Color.parseColor("#FF9100"), 4f, 0.55f, 0.8f),
        G(0, Color.YELLOW, 4f, 0.80f, 0.7f), G(0, Color.parseColor("#C6FF00"), 2.5f, 0.22f, 1.3f),
        G(0, Color.WHITE, 2.5f, 0.70f, 1.0f), G(0, Color.RED, 3.5f, 0.45f, 0.8f),
        G(1, Color.RED, 3.5f, 0f, 1f), G(1, Color.WHITE, 3f, 0f, 1.2f),
        G(2, 0, 0f, 0f, 1f), G(2, 0, 0f, 0f, 1f), G(3, 0, 0f, 0f, 1f), G(3, 0, 0f, 0f, 1.8f),
        G(4, 0, 0f, 0f, 1f), G(5, 0, 0f, 0f, 1f), G(6, 0, 0f, 0f, 1f), G(7, 0, 0f, 0f, 1f))

    inner class Buddies(ctx: Context) : View(ctx) {
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val fillP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD }
        private val skirt = Path()
        private val hairLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val t0 = System.nanoTime()
        private var col = bg()   // starts invisible (same as background), changes every 10 sec
        private val tick = object : Runnable {
            override fun run() {
                val fx = prefs().getInt("cfix", -1)
                col = toonColor(if (fx >= 0) fx else (0 until 60).random())
                ui.postDelayed(this, 10000)
            }
        }
        init {
            val fx = prefs().getInt("cfix", -1)
            if (fx >= 0) col = toonColor(fx) else ui.postDelayed(tick, 10000)
        }
        override fun onDetachedFromWindow() { ui.removeCallbacks(tick); super.onDetachedFromWindow() }

        private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rf = RectF()
        private fun lighter(c: Int) = Color.argb(255, (Color.red(c) + 255) / 2, (Color.green(c) + 255) / 2, (Color.blue(c) + 255) / 2)
        private fun darker(c: Int): Int { val hsv = FloatArray(3); Color.colorToHSV(c, hsv); hsv[2] *= 0.55f; return Color.HSVToColor(hsv) }

        private fun shoe(c: Canvas, fx: Float, fy: Float, face: Int, h: Float) {
            val e = fx + face * h * 0.12f
            rf.set(minOf(fx, e), fy - h * 0.05f, maxOf(fx, e), fy + h * 0.01f)
            solid.color = Color.argb(230, 255, 255, 255); c.drawRoundRect(rf, 3f, 3f, solid)
        }
        private fun head(c: Canvas, x: Float, hc: Float, hr: Float, girl: Boolean, face: Int) {
            c.drawCircle(x, hc, hr, fillP); c.drawCircle(x, hc, hr, line)
            val hair = darker(col)
            solid.color = hair
            if (girl) {
                c.drawCircle(x - face * hr * 0.3f, hc - hr * 1.1f, hr * 0.5f, solid)           // hair bun
                rf.set(x - hr, hc - hr, x + hr, hc + hr); c.drawArc(rf, 180f, 180f, true, solid)   // hair top
                c.drawLine(x - hr, hc, x - hr * 0.9f, hc + hr * 1.5f, line)                        // long strands
                c.drawLine(x + hr, hc, x + hr * 0.9f, hc + hr * 1.5f, line)
                solid.color = Color.WHITE; c.drawCircle(x - face * hr * 0.3f, hc - hr * 1.1f, hr * 0.22f, solid) // flower
            } else {
                rf.set(x - hr * 1.1f, hc - hr * 1.15f, x + hr * 1.1f, hc + hr * 0.2f); c.drawArc(rf, 180f, 180f, true, solid) // messy hair
                for (i in -2..2) c.drawLine(x + i * hr * 0.4f, hc - hr * 0.95f, x + i * hr * 0.55f, hc - hr * 1.4f, hairLine)
                solid.color = Color.BLACK                                                          // sunglasses
                rf.set(x - hr * 0.85f, hc - hr * 0.05f, x + hr * 0.85f, hc + hr * 0.35f); c.drawRoundRect(rf, 2f, 2f, solid)
            }
        }
        private fun torso(c: Canvas, x: Float, top: Float, bot: Float, h: Float, girl: Boolean) {
            val wd = h * (if (girl) 0.10f else 0.15f)
            rf.set(x - wd, top, x + wd, bot); c.drawRoundRect(rf, wd * 0.6f, wd * 0.6f, fillP); c.drawRoundRect(rf, wd * 0.6f, wd * 0.6f, line)
            if (girl) {
                skirt.reset(); skirt.moveTo(x - wd, bot - h * 0.06f); skirt.lineTo(x - h * 0.2f, bot + h * 0.06f)
                skirt.lineTo(x + h * 0.2f, bot + h * 0.06f); skirt.lineTo(x + wd, bot - h * 0.06f); skirt.close()
                c.drawPath(skirt, fillP); c.drawPath(skirt, line)
            } else {
                c.drawLine(x - wd * 0.3f, top + h * 0.03f, x - wd * 0.3f, top + h * 0.14f, line)   // hoodie strings
                c.drawLine(x + wd * 0.3f, top + h * 0.03f, x + wd * 0.3f, top + h * 0.14f, line)
            }
        }
        // arm with an elbow and a small hand
        private fun arm(c: Canvas, sx: Float, sy: Float, a: Float, face: Int, armL: Float, h: Float) {
            val hx = sx + face * sin(a) * armL; val hy = sy + cos(a) * armL
            val ex = (sx + hx) / 2f + (-face * cos(a)) * h * 0.05f
            val ey = (sy + hy) / 2f + sin(a) * h * 0.05f
            c.drawLine(sx, sy, ex, ey, line); c.drawLine(ex, ey, hx, hy, line)
            solid.color = Color.argb(line.alpha, 255, 255, 255); c.drawCircle(hx, hy, h * 0.035f, solid)
        }
        // leg with a knee: the shin folds back when the leg swings back
        private fun leg(c: Canvas, hx: Float, hy: Float, a: Float, face: Int, legL: Float, h: Float) {
            val a2 = if (a < 0f) a * 1.8f else a * 0.9f
            val kx = hx + face * sin(a) * legL / 2f; val ky = hy + cos(a) * legL / 2f
            val fx = kx + face * sin(a2) * legL / 2f; val fy = ky + cos(a2) * legL / 2f
            c.drawLine(hx, hy, kx, ky, line); c.drawLine(kx, ky, fx, fy, line)
            shoe(c, fx, fy, face, h)
        }
        private fun person(c: Canvas, x: Float, gnd: Float, h: Float, girl: Boolean, face: Int,
                           leg: Float, af: Float, ab: Float, jump: Float, label: String, d: Float) {
            val hip = gnd - jump - h * 0.40f; val sh = hip - h * 0.28f; val hr = h * 0.12f; val hc = sh - hr * 1.15f
            val legL = h * 0.40f; val armL = h * 0.30f
            val hw = h * (if (girl) 0.05f else 0.07f); val sy = sh + h * 0.02f
            line.alpha = 140                                   // far side limbs a little dimmer (depth)
            leg(c, x - hw, hip, -leg, face, legL, h)
            arm(c, x - hw, sy, ab, face, armL, h)
            line.alpha = 255
            leg(c, x + hw, hip, leg, face, legL, h)
            torso(c, x, sh, hip, h, girl)
            arm(c, x + hw, sy, af, face, armL, h)
            head(c, x, hc, hr, girl, face)
            c.drawText(label, x, hc - hr * 1.75f - 2f * d, tp)
        }
        // resting pose: sitting on the edge, like the picture (girl rests her face on her hands)
        private fun sitting(c: Canvas, x: Float, gnd: Float, h: Float, girl: Boolean, face: Int, label: String, d: Float, t: Float) {
            val seat = gnd - h * 0.22f
            val sh = seat - h * 0.30f + sin(t * 2f) * h * 0.01f; val hr = h * 0.12f; val hc = sh - hr * 1.15f
            val kx = x + face * h * 0.28f
            c.drawLine(x, seat, kx, seat, line); c.drawLine(kx, seat, kx, seat + h * 0.22f, line)
            shoe(c, kx, seat + h * 0.22f, face, h)
            torso(c, x, sh, seat, h, girl)
            if (girl) {
                val ex = x + face * h * 0.10f; val ey = sh + h * 0.12f
                c.drawLine(x, sh, ex, ey, line); c.drawLine(ex, ey, x + face * hr * 0.9f, hc + hr * 0.6f, line)
            } else {
                val ex = x + face * h * 0.14f
                c.drawLine(x, sh, ex, seat - h * 0.05f, line); c.drawLine(ex, seat - h * 0.05f, kx, seat - h * 0.02f, line)
            }
            head(c, x, hc, hr, girl, face)
            c.drawText(label, x, hc - hr * 1.75f - 2f * d, tp)
        }

        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val t = (System.nanoTime() - t0) / 1e9f
            val w = width.toFloat(); val hh = height.toFloat()
            val gnd = hh - 3f * d; val h = hh * 0.62f; val armL = h * 0.30f
            line.color = lighter(col); line.strokeWidth = 1.4f * d; fillP.color = Color.argb(95, Color.red(col), Color.green(col), Color.blue(col))
            hairLine.color = darker(col); hairLine.strokeWidth = 1.3f * d; tp.color = lighter(col); tp.textSize = hh * 0.2f
            val gi = (t / 9f).toInt() % games.size; val g = games[gi]
            val ph = t % 9f
            val ap = sm(ph / 1.5f) * (1f - sm((ph - 7.5f) / 1.5f))
            val gap = when (g.kind) { 2 -> if (gi == 9) armL * 0.5f else armL; 6 -> armL * 2.2f; else -> 0.16f * w }
            var gx = lerpF(0.10f * w, w / 2 - gap, ap); var bx = lerpF(0.90f * w, w / 2 + gap, ap)
            var gl = 0f; var bl = 0f; var gaf = 0.25f; var gab = -0.25f; var baf = 0.25f; var bab = -0.25f
            var gj = 0f; var bj = 0f
            val s = sin(t * 9f)
            val p = (t * g.sp) % 2f
            if (ap in 0.02f..0.97f) { gl = 0.6f * s; bl = -0.6f * s; gaf = 0.7f * s; gab = -gaf; baf = -0.7f * s; bab = -baf }
            else if (ap > 0.97f) {
                when (g.kind) {
                    0 -> {
                        gaf = if (p < 0.3f) 2.0f else 0.4f; baf = if (p in 1f..1.3f) 2.0f else 0.4f
                        if (gi == 0) { gl = if (p < 0.3f) -0.9f else 0f; bl = if (p in 1f..1.3f) -0.9f else 0f }
                    }
                    1 -> { baf = if (p < 1f) p * 6.28f else 0.3f; gaf = if (p in 0.85f..1.35f) 2.6f else 0.9f; gab = gaf - 0.2f }
                    2 -> { val a = if (gi == 9) 2.6f else 1.5f + 0.15f * sin(t * 14f); gaf = a; baf = a }
                    3 -> {
                        val q = t * 6f * g.sp
                        gaf = 1.2f + 1.2f * sin(q); gab = 1.2f + 1.2f * sin(q + 3.14f)
                        baf = 1.2f + 1.2f * sin(q + 1.5f); bab = 1.2f + 1.2f * sin(q + 4.6f)
                        gl = 0.35f * sin(q); bl = -gl; gj = abs(sin(q)) * h * 0.08f; bj = gj
                    }
                    4 -> { gl = 0.9f * s; bl = -gl; gaf = -gl; gab = gl; baf = gl; bab = -gl
                           val dx = 0.12f * w * sin(t * 2f); gx += dx; bx -= dx }
                    5 -> { gj = abs(sin(t * 4f)) * h * 0.22f; bj = gj; gaf = 2.8f; baf = 2.8f; gab = 2.8f; bab = 2.8f }
                    6 -> { gaf = 1.3f; baf = 1.3f; gab = 1.2f; bab = 1.2f; gl = 0.15f; bl = 0.15f
                           val lean = 0.04f * w * sin(t * 3f); gx += lean; bx += lean }
                    7 -> { val wv = 2.6f + 0.5f * sin(t * 10f); gaf = wv; baf = wv }
                }
            }
            if (ap <= 0.02f) {
                sitting(c, gx, gnd, h, true, 1, "S", d, t); sitting(c, bx, gnd, h, false, -1, "A", d, t)
            } else {
                person(c, gx, gnd, h, true, 1, gl, gaf, gab, gj, "S", d)
                person(c, bx, gnd, h, false, -1, bl, baf, bab, bj, "A", d)
            }
            if (ap > 0.97f) {
                val ghx = gx + h * 0.05f + sin(gaf) * armL; val ghy = gnd - gj - h * 0.66f + cos(gaf) * armL
                if (g.kind <= 1) {
                    val fromG = p < 1f; val u = p % 1f
                    val bxp = lerpF(if (fromG) gx else bx, if (fromG) bx else gx, u)
                    val arc = if (g.kind == 1) (if (fromG) 0.1f else 0.9f) else g.arc
                    val byp = gnd - h * 0.30f - arc * h * 2f * 4f * u * (1f - u)
                    solid.color = g.ball; c.drawCircle(bxp, byp, g.r * d, solid)
                }
                if (g.kind == 1) c.drawLine(ghx, ghy, ghx + sin(gaf + 0.6f) * armL * 0.9f, ghy + cos(gaf + 0.6f) * armL * 0.9f, line)
                if (g.kind == 6) c.drawLine(ghx, ghy, bx + h * 0.07f - sin(baf) * armL, gnd - bj - h * 0.66f + cos(baf) * armL, line)
            }
            postInvalidateOnAnimation()
        }
    }

    // ---------- profile, DP, search ----------
    private fun pushProfile() {
        if (!onlineReady() || !online()) return
        val tok = token() ?: return
        val name = prefs().getString("nick", "")?.ifEmpty { myId() } ?: myId()
        val body = JSONObject().put("name", name).put("dp", prefs().getString("dp", "") ?: "")
            .put("owner", prefs().getString("uid", "") ?: "").toString()
        if (http("PUT", "${db()}/profiles/${myId()}.json?auth=$tok", body) != null)
            prefs().edit().putBoolean("profPosted", true).apply()
    }
    private fun shareId() = startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "CHAT WITH ME ON AS CHATS. MY CODE: ${myId()}"), "SHARE MY CODE"))
    private fun pickDp() { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 77) }
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        val uri = data?.data ?: return
        if (rc != 77 || res != RESULT_OK) return
        try {
            val o = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bmp = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return
            val s = Math.min(bmp.width, bmp.height)
            val sq = Bitmap.createBitmap(bmp, (bmp.width - s) / 2, (bmp.height - s) / 2, s, s)
            val small = Bitmap.createScaledBitmap(sq, 128, 128, true)
            val bo = java.io.ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, 70, bo)
            prefs().edit().putString("dp", b64(bo.toByteArray())).putBoolean("profPosted", false).apply()
            toast("PROFILE PICTURE SAVED"); io.execute { pushProfile() }
        } catch (e: Exception) { toast("COULD NOT LOAD IMAGE") }
    }
    private fun searchId() {
        val box = EditText(this).apply { hint = "AS-XXXXX"; setSingleLine() }
        AlertDialog.Builder(this).setTitle("SEARCH AS-ID").setView(box)
            .setPositiveButton("SEARCH") { _, _ ->
                val id = box.text.toString().trim().uppercase()
                if (!onlineReady() || !online()) { toast("NEEDS INTERNET + FIREBASE KEYS"); return@setPositiveButton }
                io.execute {
                    val tok = token()
                    val r = if (tok == null) null else http("GET", "${db()}/profiles/$id.json?auth=$tok")
                    runOnUiThread {
                        if (r == null || r.trim() == "null") toast("ID NOT FOUND")
                        else { val o = JSONObject(r); showProfile(id, o.optString("name", id), o.optString("dp", "")) }
                    }
                }
            }.setNegativeButton("CANCEL", null).show()
    }
    private fun showProfile(id: String, name: String, dp: String) {
        val v = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(32, 32, 32, 16) }
        if (dp.isNotEmpty()) try {
            val b = b64d(dp)
            v.addView(ImageView(this).apply { setImageBitmap(BitmapFactory.decodeByteArray(b, 0, b.size)) }, LinearLayout.LayoutParams(256, 256))
        } catch (e: Exception) {}
        v.addView(TextView(this).apply { text = "$name\n$id"; textSize = 18f; gravity = Gravity.CENTER })
        AlertDialog.Builder(this).setView(v)
            .setPositiveButton("💬 CHAT") { _, _ -> target = id; showTab(0) }
            .setNegativeButton("CLOSE", null).show()
    }

    // ---------- nearby chat ----------
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
        else { st("2 PERMISSIONS", "asking user"); requestPermissions(perms.toTypedArray(), 1) }
    }
    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (c == 2 || c == 3) { if (r.isEmpty() || r[0] != 0) toast("MICROPHONE PERMISSION NEEDED"); return }
        if (r.isNotEmpty() && r.all { it == 0 }) go() else { st("2 PERMISSIONS", "MISSING: user denied"); toast("PERMISSIONS NEEDED") }
    }
    private fun go() {
        val opts = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(myName, serviceId, lifecycle, opts)
            .addOnSuccessListener { st("3 ADVERTISE", "OK") }.addOnFailureListener { e -> st("3 ADVERTISE", "FAILED: ${e.message}") }
        client.startDiscovery(serviceId, object : EndpointDiscoveryCallback() {
            override fun onEndpointFound(id: String, i: DiscoveredEndpointInfo) {
                if (peers.contains(id) || !asked.add(id)) return
                client.requestConnection(myName, id, lifecycle)
                    .addOnSuccessListener { st("5 CONNECT", "requested") }
                    .addOnFailureListener { e -> asked.remove(id); st("5 CONNECT", "FAILED: ${e.message}") }
            }
            override fun onEndpointLost(id: String) { asked.remove(id) }
        }, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
            .addOnSuccessListener { st("4 DISCOVERY", "OK") }.addOnFailureListener { e -> st("4 DISCOVERY", "FAILED: ${e.message}") }
        setNote("SEARCHING...")
    }
    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, i: ConnectionInfo) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("CONNECT TO ${i.endpointName}?")
                .setMessage("CODE: ${i.authenticationDigits}\nIS THIS THE SAME CODE ON THE OTHER PHONE?")
                .setPositiveButton("YES") { _, _ -> client.acceptConnection(id, payloads) }
                .setNegativeButton("NO") { _, _ -> client.rejectConnection(id) }
                .show()
        }
        override fun onConnectionResult(id: String, r: ConnectionResolution) {
            if (r.status.isSuccess) {
                peers.add(id); setNote("CONNECTED (${peers.size} PHONES)"); st("5 CONNECT", "OK")
            } else { asked.remove(id); setNote("CONNECTION FAILED, TRY AGAIN"); st("5 CONNECT", "FAILED: code ${r.status.statusCode}") }
        }
        override fun onDisconnected(id: String) {
            peers.remove(id); asked.remove(id)
            setNote(if (peers.isEmpty()) "DISCONNECTED" else "CONNECTED (${peers.size} PHONES)")
        }
    }
    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            when (p.type) {
                Payload.Type.BYTES -> {
                    val b = p.asBytes() ?: return
                    if (b.isNotEmpty() && b[0].toInt() == 1) handleChunk(id, b)
                    else { add(String(b).take(2000)); st("7 RECEIVE", "OK") }
                }
                Payload.Type.STREAM -> p.asStream()?.asInputStream()?.let { playLive(it) }
                else -> {}
            }
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {}
    }
    // ---------- walkie-talkie + voice message (no internet) ----------
    private fun micOk(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) return true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
        toast("ALLOW MICROPHONE, THEN HOLD THE BUTTON AGAIN")
        return false
    }

    // live voice: hold the button, speak, release
    private fun startWalkie() {
        if (walkieOn) return
        val to = peers.toList()
        if (to.isEmpty()) { toast("NOT CONNECTED. TAP NEARBY FIRST."); return }
        if (!micOk()) return
        try {
            val rate = 8000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
            val pipe = ParcelFileDescriptor.createPipe()
            client.sendPayload(to, Payload.fromStream(pipe[0]))
            val out = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            walkieOn = true
            rec.startRecording()
            st("10 WALKIE-TALKIE", "OK")
            setNote("🎙 TALKING... RELEASE TO STOP")
            io.execute {
                val buf = ByteArray(1600)
                try {
                    while (walkieOn) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) out.write(buf, 0, n)
                    }
                } catch (e: Exception) { st("10 WALKIE-TALKIE", "FAILED: ${e.message}") }
                try { rec.stop() } catch (_: Exception) {}
                try { rec.release() } catch (_: Exception) {}
                try { out.close() } catch (_: Exception) {}
                runOnUiThread { setNote(if (peers.isEmpty()) "DISCONNECTED" else "CONNECTED (${peers.size} PHONES)") }
            }
        } catch (e: Exception) { walkieOn = false; st("10 WALKIE-TALKIE", "FAILED: ${e.message}") }
    }
    private fun stopWalkie() { walkieOn = false }

    private fun playLive(ins: java.io.InputStream) {
        io.execute {
            var track: AudioTrack? = null
            try {
                val min = AudioTrack.getMinBufferSize(8000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val t = AudioTrack(AudioManager.STREAM_MUSIC, 8000, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096), AudioTrack.MODE_STREAM)
                track = t
                t.play()
                runOnUiThread { setNote("🔊 LIVE VOICE...") }
                val buf = ByteArray(1600)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (n > 0) t.write(buf, 0, n)
                }
                st("7 RECEIVE", "OK")
            } catch (e: Exception) { st("7 RECEIVE", "FAILED: live voice ${e.message}") }
            try { track?.stop() } catch (_: Exception) {}
            try { track?.release() } catch (_: Exception) {}
            try { ins.close() } catch (_: Exception) {}
            runOnUiThread { setNote(if (peers.isEmpty()) "DISCONNECTED" else "CONNECTED (${peers.size} PHONES)") }
        }
    }

    // voice message: record up to 30 s, send in chunks, every phone passes it on (max 8 hops)
    private fun startVoiceMsg() {
        if (recorder != null) return
        if (peers.isEmpty()) { toast("NOT CONNECTED. TAP NEARBY FIRST."); return }
        if (!micOk()) return
        try {
            val f = File(cacheDir, "out_" + System.currentTimeMillis() + ".3gp")
            val r = MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
            r.setAudioSamplingRate(8000)
            r.setAudioEncodingBitRate(12200)
            r.setMaxDuration(30000)
            r.setOutputFile(f.absolutePath)
            r.prepare(); r.start()
            recorder = r; recFile = f; recStart = System.currentTimeMillis()
            setNote("🎤 RECORDING (MAX 30 S)... RELEASE TO SEND")
        } catch (e: Exception) { recorder = null; st("11 VOICE MESSAGE", "FAILED: record ${e.message}") }
    }
    private fun stopVoiceMsg() {
        val r = recorder ?: return
        recorder = null
        val f = recFile ?: return
        setNote(if (peers.isEmpty()) "DISCONNECTED" else "CONNECTED (${peers.size} PHONES)")
        try { r.stop() } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            f.delete(); toast("TOO SHORT, HOLD THE BUTTON LONGER"); return
        }
        try { r.release() } catch (_: Exception) {}
        val data = f.readBytes()
        lastVoice = f
        if (data.isEmpty()) return
        sendVoice(data)
    }
    private fun packChunk(id: Long, ttl: Int, idx: Int, total: Int, from: String, part: ByteArray): ByteArray {
        val bo = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(bo)
        d.writeByte(1); d.writeLong(id); d.writeByte(ttl); d.writeShort(idx); d.writeShort(total)
        d.writeUTF(from); d.writeInt(part.size); d.write(part); d.flush()
        return bo.toByteArray()
    }
    private fun sendVoice(data: ByteArray) {
        val to = peers.toList()
        if (to.isEmpty()) { toast("NOT CONNECTED"); return }
        val id = SecureRandom().nextLong()
        val size = 20000
        val total = (data.size + size - 1) / size
        for (i in 0 until total) {
            val part = data.copyOfRange(i * size, minOf(data.size, (i + 1) * size))
            seenChunks.add("$id:$i")
            client.sendPayload(to, Payload.fromBytes(packChunk(id, 8, i, total, myName, part)))
                .addOnSuccessListener { st("11 VOICE MESSAGE", "OK") }
                .addOnFailureListener { e -> st("11 VOICE MESSAGE", "FAILED: send ${e.message}") }
        }
        add("ME: 🎤 VOICE MESSAGE SENT (${data.size / 1024 + 1} KB)")
    }
    private fun handleChunk(from: String, bytes: ByteArray) {
        try {
            val d = java.io.DataInputStream(java.io.ByteArrayInputStream(bytes))
            d.readByte()
            val id = d.readLong(); val ttl = d.readByte().toInt()
            val idx = d.readShort().toInt(); val total = d.readShort().toInt()
            val who = d.readUTF(); val n = d.readInt()
            if (n < 0 || n > 30000 || total <= 0 || total > 20 || idx < 0 || idx >= total) return
            val part = ByteArray(n); d.readFully(part)
            if (!seenChunks.add("$id:$idx")) return
            if (ttl > 0) {
                val others = peers.filter { it != from }
                if (others.isNotEmpty()) client.sendPayload(others, Payload.fromBytes(packChunk(id, ttl - 1, idx, total, who, part)))
            }
            var all: ByteArray? = null
            synchronized(inVoice) {
                val arr = inVoice.getOrPut(id) { arrayOfNulls<ByteArray>(total) }
                arr[idx] = part
                if (arr.all { it != null }) {
                    val bo = java.io.ByteArrayOutputStream()
                    arr.forEach { bo.write(it!!) }
                    all = bo.toByteArray(); inVoice.remove(id)
                }
            }
            val done = all ?: return
            val f = File(cacheDir, "in_$id.3gp"); f.writeBytes(done); lastVoice = f
            st("7 RECEIVE", "OK")
            runOnUiThread { add("$who: 🎤 VOICE MESSAGE (${done.size / 1024 + 1} KB) PLAYING"); playVoice(f) }
        } catch (e: Exception) { st("11 VOICE MESSAGE", "FAILED: receive ${e.message}") }
    }
    private fun playVoice(f: File) {
        try {
            try { player?.release() } catch (_: Exception) {}
            val mp = MediaPlayer()
            mp.setDataSource(f.absolutePath); mp.prepare(); mp.start()
            mp.setOnCompletionListener { it.release() }
            player = mp
        } catch (e: Exception) { st("11 VOICE MESSAGE", "FAILED: play ${e.message}") }
    }

    private fun add(s: String) {
        chatLog.append(s).append("\n")
        if (::log.isInitialized) { log.text = chatLog.toString(); scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) } }
    }
    override fun onDestroy() {
        walkieOn = false
        try { calls.stopAll() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        try { client.stopAdvertising(); client.stopDiscovery() } catch (_: Exception) {}
        client.stopAllEndpoints(); tts?.shutdown(); ui.removeCallbacksAndMessages(null)
        try { netCb?.let { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) } } catch (_: Exception) {}
        super.onDestroy()
    }
}
