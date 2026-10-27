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
    fun prefsPublic() = prefs()
    private fun prefs() = getSharedPreferences("as", 0)
    private fun dark() = prefs().getBoolean("dark", true)
    private fun palette(i: Int) = Color.HSVToColor(floatArrayOf((i * 6f) % 360f, if (dark()) 0.65f else 0.25f, if (dark()) 0.11f else 0.97f))
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
    fun say(t: String) {
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
        Store.init(this); Store.listener = { screens.refresh() }
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
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.addView(ImageView(this).apply { setImageBitmap(Art.splash()); scaleType = ImageView.ScaleType.FIT_CENTER },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.setOnClickListener { logoScreen() }
        setContentView(root)
    }

    private fun logoScreen() {
        say("WELCOME TO AS CHATS")
        if (!prefs().getBoolean("in", false)) authScreen() else enter()
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

    fun crownPage() = paywall()
    private fun paywall() {
        val c = col()
        c.addView(tv("AS CHATS", 40f, ac(), true))
        c.addView(tv((if (access()) "\nTRIAL: ${daysLeft().coerceAtLeast(0)} DAYS LEFT.\n" else "\nYOUR 3-MONTH FREE TRIAL HAS ENDED.\n") + "AS CHATS COSTS $5 PER MONTH.\nAFTER YOU PAY, YOU WILL GET AN UNLOCK CODE. PASTE IT BELOW.\n"))
        val box = EditText(this).apply { hint = "PASTE UNLOCK CODE"; setTextColor(fg()); setHintTextColor(Color.GRAY) }
        c.addView(box)
        c.addView(btn("UNLOCK") {
            val exp = verifyCode(box.text.toString())
            if (exp != null && exp >= today()) { prefs().edit().putInt("exp", exp).apply(); enter() }
            else toast("INVALID OR EXPIRED CODE")
        })
        if (access()) c.addView(btn("BACK") { enter() })
        c.setBackgroundColor(Look.BG); setContentView(ScrollView(this).apply { setBackgroundColor(Look.BG); addView(c) })
    }

    // ---------- email login / account ----------
    private fun field(hint: String, pass: Boolean = false) = EditText(this).apply {
        this.hint = hint; setSingleLine(); setTextColor(fg()); setHintTextColor(Color.GRAY)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or
            (if (pass) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
    }
    private fun page(c: View) {
        setContentView(ScrollView(this).apply { background = Look.bgGradient(); isFillViewport = true; addView(c) })
    }

    private fun authScreen(create: Boolean = false, msg: String = "") {
        window.statusBarColor = Look.BG; window.navigationBarColor = Look.BG
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(21), dp(57), dp(21), dp(24))
        }
        c.addView(ImageView(this).apply { setImageBitmap(Art.logo()) }, LinearLayout.LayoutParams(dp(99), dp(98)))
        c.addView(TextView(this).apply {
            text = Look.brand(); textSize = 42f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        })
        c.addView(TextView(this).apply {
            text = "Connect  \u2022  Chat  \u2022  Share"; textSize = 14f; setTextColor(Look.HINT)
            letterSpacing = 0.08f; gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(36))
        })
        val (emailBox, email) = Look.field(this, "Email", Icons.EMAIL, false)
        (emailBox.layoutParams as LinearLayout.LayoutParams).topMargin = 0
        val (pwBox, pw) = Look.field(this, "Password (Min 6 Characters)", Icons.LOCK, true)
        c.addView(emailBox); c.addView(pwBox)
        email.setText(prefs().getString("email", ""))
        var pw2: EditText? = null
        if (create) {
            val (b2, e2) = Look.field(this, "Type Password Again", Icons.LOCK, true)
            pw2 = e2; c.addView(b2)
        }
        val status = TextView(this).apply {
            text = msg; textSize = 13f; setTextColor(Color.YELLOW); gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0)
            visibility = if (msg.isEmpty()) View.GONE else View.VISIBLE
        }
        status.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { status.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE }
        })
        c.addView(status)
        c.addView(Look.mainButton(this, if (create) "CREATE ACCOUNT" else "LOG IN") {
            val e = email.text.toString().trim().lowercase(); val p = pw.text.toString()
            when {
                !e.contains("@") || !e.contains(".") -> status.text = "ENTER A VALID EMAIL."
                p.length < 6 -> status.text = "PASSWORD MUST HAVE AT LEAST 6 CHARACTERS."
                create && p != pw2?.text?.toString() -> status.text = "THE TWO PASSWORDS ARE NOT THE SAME."
                else -> {
                    status.text = "PLEASE WAIT..."
                    io.execute {
                        val err = try { authFlow(create, e, p) } catch (x: Exception) { "ERROR: ${x.message}" }
                        if (err != null) runOnUiThread { status.text = err }
                    }
                }
            }
        })
        c.addView(Look.orDivider(this))
        c.addView(Look.outlineButton(this, Icons.PERSON_ADD, if (create) "I ALREADY HAVE AN ACCOUNT" else "CREATE NEW ACCOUNT") { authScreen(!create) }
            .also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(24) })
        c.addView(Look.outlineButton(this, Icons.GLOBE, "NO INTERNET? USE NEARBY CHAT ONLY", 11f) {
            prefs().edit().putBoolean("in", true).apply()
            if (myId().isEmpty()) myName = "AS-" + (1000..9999).random()
            enter()
        })
        c.addView(TextView(this).apply {
            text = "First login or new account needs internet.\nAfter that you can login without internet on this phone."
            textSize = 12f; setTextColor(Look.HINT); gravity = Gravity.CENTER; setPadding(0, dp(26), 0, 0)
        })
        if (!create) c.addView(TextView(this).apply {
            text = "Forgot password?"; textSize = 12f; setTextColor(Look.GREEN); gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(4))
            setOnClickListener {
                val e = email.text.toString().trim().lowercase()
                if (!e.contains("@")) { status.text = "TYPE YOUR EMAIL FIRST."; return@setOnClickListener }
                if (!online()) { status.text = "NEEDS INTERNET."; return@setOnClickListener }
                status.text = "PLEASE WAIT..."
                io.execute {
                    val r = AuthApi.sendReset(e)
                    runOnUiThread { status.text = if (r.ok) "RESET EMAIL SENT. OPEN IT (CHECK SPAM), TAP THE LINK, SET A NEW PASSWORD, THEN LOGIN." else AuthApi.friendly(r.err) }
                }
            }
        })
        val root = FrameLayout(this)
        root.addView(GlowBg(this), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(c) }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root)
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

    fun logout() {
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
    private fun setNote(s: String) { note = s; if (::modeTv.isInitialized) refreshMode() }





    fun reportProblem() = complaint()
    private fun complaint() {
        val box = EditText(this).apply { hint = "WRITE YOUR PROBLEM" }
        AlertDialog.Builder(this).setTitle("REPORT A PROBLEM").setView(box)
            .setPositiveButton("SEND") { _, _ ->
                val text = "COMPLAINT FROM $myName:\n" + box.text.toString() + "\n\n" + report()
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, text), "SEND TO AS CHATS TEAM"))
            }.setNegativeButton("CANCEL", null).show()
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

    fun sendOnline(to: String, text: String): Int {
        val tok = token() ?: return 0
        val pj = http("GET", "${db()}/users/$to/pub.json?auth=$tok") ?: return 0
        if (pj.trim() == "null") return -1
        return try {
            val key = aesKey(b64d(JSONArray("[$pj]").getString(0)))
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val ct = ci.doFinal(text.toByteArray())
            val body = JSONObject().put("from", myId()).put("iv", b64(iv)).put("ct", b64(ct)).put("ts", System.currentTimeMillis()).toString()
            if (http("POST", "${db()}/inbox/$to.json?auth=$tok", body) != null) 1 else 0
        } catch (e: Exception) { st("8 ONLINE CHAT", "FAILED: ${e.message}"); 0 }
    }

    @Synchronized fun queue(to: String, t: String, cid: String = "", mid: String = "") {
        val a = JSONArray(prefs().getString("outbox", "[]")); a.put(JSONObject().put("to", to).put("text", t).put("cid", cid).put("mid", mid))
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
            val cid = o.optString("cid"); val mid = o.optString("mid")
            val r = sendOnline(to, t)
            if (r == 0) queue(to, t, cid, mid) else net.onQueuedSent(cid, mid)
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
            try { net.onPacket(from, msg) } catch (e: Exception) { st("7 RECEIVE", "FAILED: ${e.message}") }
            st("7 RECEIVE", "OK")
        }
    }

    // ---------- colors ----------

    // ---------- AS AI (free, rule-based for now) ----------
    fun aiReply(q: String): String {
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


    // ---------- profile, DP, search ----------
    fun pushProfileNow() = pushProfile()
    private fun pushProfile() {
        if (!onlineReady() || !online()) return
        val tok = token() ?: return
        val name = prefs().getString("nick", "")?.ifEmpty { myId() } ?: myId()
        val body = JSONObject().put("name", name).put("dp", prefs().getString("dp", "") ?: "").put("bio", prefs().getString("bio", "") ?: "")
            .put("owner", prefs().getString("uid", "") ?: "").toString()
        if (http("PUT", "${db()}/profiles/${myId()}.json?auth=$tok", body) != null)
            prefs().edit().putBoolean("profPosted", true).apply()
    }
    fun shareId() = startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, "CHAT WITH ME ON AS CHATS. MY CODE: ${myId()}"), "SHARE MY CODE"))

    // ---------- nearby chat ----------
    private fun needed(): List<String> {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) perms += listOf(
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.NEARBY_WIFI_DEVICES
        return perms
    }
    fun startNearby() {
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
                    else { net.onNearbyText(String(b).take(2000)); st("7 RECEIVE", "OK") }
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
    fun startWalkie() {
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
    fun stopWalkie() { walkieOn = false }

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
    fun startVoiceMsg() {
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
    fun stopVoiceMsg() {
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
        sendVoiceBytes(data)
        Store.ensure("NEARBY", "Nearby Chat", "nearby")
        Store.add("NEARBY", Msg(java.lang.Long.toHexString(SecureRandom().nextLong()), myId().ifEmpty { myName }, "", System.currentTimeMillis(), 1, "v", f.absolutePath, ((System.currentTimeMillis() - recStart) / 1000).toInt()), true, "🎤 Voice message")
    }
    private fun packChunk(id: Long, ttl: Int, idx: Int, total: Int, from: String, part: ByteArray): ByteArray {
        val bo = java.io.ByteArrayOutputStream()
        val d = java.io.DataOutputStream(bo)
        d.writeByte(1); d.writeLong(id); d.writeByte(ttl); d.writeShort(idx); d.writeShort(total)
        d.writeUTF(from); d.writeInt(part.size); d.write(part); d.flush()
        return bo.toByteArray()
    }
    fun sendVoiceBytes(data: ByteArray) {
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
            net.onNearbyVoice(who, f); runOnUiThread { say("NEW MESSAGE"); playVoice(f) }
        } catch (e: Exception) { st("11 VOICE MESSAGE", "FAILED: receive ${e.message}") }
    }
    fun playVoice(f: File) {
        try {
            try { player?.release() } catch (_: Exception) {}
            val mp = MediaPlayer()
            mp.setDataSource(f.absolutePath); mp.prepare(); mp.start()
            mp.setOnCompletionListener { it.release() }
            player = mp
        } catch (e: Exception) { st("11 VOICE MESSAGE", "FAILED: play ${e.message}") }
    }

    // ================= new design: wiring =================
    val net by lazy { Net(this) }
    val screens by lazy { Screens(this) }
    val statusUi by lazy { StatusUi(this, screens) }
    val profileUi by lazy { ProfileUi(this, screens) }
    val settingsUi by lazy { SettingsUi(this, screens) }
    val reelsUi by lazy { ReelsUi(this, screens) }
    private var glow: GlowBg? = null
    private var pickCb: ((android.net.Uri) -> Unit)? = null
    private var camUri: android.net.Uri? = null
    private var camCb: ((android.net.Uri) -> Unit)? = null

    private fun buildUi() {
        window.statusBarColor = Color.BLACK; window.navigationBarColor = Color.BLACK
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val frame = FrameLayout(this)
        glow = GlowBg(this); frame.addView(glow, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        val host = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(host, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        screens.host = host
        setContentView(frame)
        modeTv = TextView(this)
        refreshMode(); applyGlow()
        startPolling(); startColors(); calls.start()
        screens.seed()
        screens.tab("home") { screens.home() }
        say(if (online()) "CHATTING MODE IS ACTIVATED" else "OFFLINE MODE IS ACTIVATED")
    }
    private fun refreshMode() {
        val sub = if (prefs().getInt("exp", 0) >= today()) "SUBSCRIBED" else "TRIAL: ${daysLeft()} DAYS LEFT"
        if (::modeTv.isInitialized) modeTv.text = (if (online()) "ONLINE MODE" else "OFFLINE MODE") + "  |  $sub\nID: ${myId()}  |  $note"
    }
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (prefs().getBoolean("in", false) && screens.back()) return; @Suppress("DEPRECATION") super.onBackPressed() }

    fun glowColor(i: Int): Int = Color.HSVToColor(floatArrayOf((i * 6f) % 360f, 0.75f, 0.27f))
    fun applyGlow() {
        val fix = prefs().getInt("bgfix", -1)
        Look.glow = glowColor(if (fix >= 0) fix else bgIdx); glow?.invalidate()
    }
    private fun startColors() {
        if (colorsOn) return
        colorsOn = true
        ui.postDelayed(object : Runnable {
            override fun run() {
                if (prefs().getInt("bgfix", -1) < 0 && prefs().getBoolean("bgauto", true)) { bgIdx = (bgIdx + 1) % 60; applyGlow() }
                ui.postDelayed(this, 15000)
            }
        }, 15000)
    }
    fun colorRowPublic(key: String, f: (Int) -> Int): View {
        val row = LinearLayout(this)
        row.addView(Button(this).apply { text = "AUTO"; textSize = 11f; setOnClickListener { prefs().edit().putInt(key, -1).apply(); applyGlow(); toast("AUTO COLOUR") } })
        for (i in 0 until 60) row.addView(Button(this).apply {
            minWidth = 0; minHeight = 0; setPadding(0, 0, 0, 0); setBackgroundColor(f(i))
            setOnClickListener { prefs().edit().putInt(key, i).apply(); applyGlow(); toast("COLOUR SET") }
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { setMargins(6, 6, 6, 6) })
        return HorizontalScrollView(this).apply { addView(row) }
    }

    fun noteNewMessage() { say("NEW MESSAGE") }
    fun peerCount() = peers.size
    fun stopTts() { tts?.stop() }
    fun versionName(): String = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
    fun showDiagnostics() = showReport("DIAGNOSTICS", report())
    fun shareText(t: String) = startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "SHARE"))
    fun playLast() { lastVoice?.let { playVoice(it) } ?: toast("NO VOICE MESSAGE YET") }

    fun sendNearbyText(t: String): Boolean {
        val p = peers.toList()
        if (p.isEmpty()) { toast("NOT CONNECTED. OPEN NEARBY AND WAIT FOR A FRIEND TO CONNECT."); return false }
        client.sendPayload(p, Payload.fromBytes("$myName: $t".toByteArray()))
            .addOnSuccessListener { st("6 SEND", "OK") }.addOnFailureListener { e -> st("6 SEND", "FAILED: ${e.message}") }
        return true
    }

    // generic voice recording for chats
    private var recM: MediaRecorder? = null
    private var recF: File? = null
    private var recS = 0L
    fun beginRecording(): Boolean {
        if (recM != null) return false
        if (!micOk()) return false
        return try {
            val f = File(cacheDir, "rec_" + System.currentTimeMillis() + ".3gp")
            val r = MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC); r.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB); r.setAudioSamplingRate(8000); r.setAudioEncodingBitRate(12200)
            r.setMaxDuration(30000); r.setOutputFile(f.absolutePath); r.prepare(); r.start()
            recM = r; recF = f; recS = System.currentTimeMillis(); true
        } catch (e: Exception) { recM = null; st("11 VOICE MESSAGE", "FAILED: record ${e.message}"); false }
    }
    fun endRecording(): Pair<File, Int>? {
        val r = recM ?: return null; recM = null
        val f = recF ?: return null
        try { r.stop() } catch (e: Exception) { try { r.release() } catch (_: Exception) {}; f.delete(); toast("TOO SHORT. HOLD THE BUTTON LONGER."); return null }
        try { r.release() } catch (_: Exception) {}
        lastVoice = f
        return Pair(f, ((System.currentTimeMillis() - recS) / 1000).toInt().coerceAtLeast(1))
    }

    // pictures / videos from the phone
    fun pick(mime: String, cb: (android.net.Uri) -> Unit) {
        pickCb = cb
        startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE), 90)
    }
    fun capturePhoto(cb: (android.net.Uri) -> Unit) {
        if (checkSelfPermission(Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 3); toast("ALLOW THE CAMERA, THEN TRY AGAIN"); return
        }
        if (Build.VERSION.SDK_INT < 29) { toast("ON THIS PHONE USE THE GALLERY BUTTON (📎)"); return }
        try {
            val v = android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "AS_${System.currentTimeMillis()}.jpg"); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg") }
            camUri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v); camCb = cb
            startActivityForResult(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE).putExtra(android.provider.MediaStore.EXTRA_OUTPUT, camUri), 91)
        } catch (e: Exception) { toast("CAMERA NOT AVAILABLE") }
    }
    fun pickDp(done: () -> Unit) { pick("image/*") { uri -> setDpFromUri(uri); done() } }
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc == 90) { val cb = pickCb; pickCb = null; val u = data?.data; if (res == RESULT_OK && u != null) cb?.invoke(u); return }
        if (rc == 91) { val cb = camCb; camCb = null; val u = camUri; if (res == RESULT_OK && u != null) cb?.invoke(u); return }
    }
    private fun setDpFromUri(uri: android.net.Uri) {
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
    fun setNick(v: String) {
        prefs().edit().putString("nick", v).putBoolean("profPosted", false).apply()
        myName = if (v.isNotEmpty()) v else prefs().getString("id", myName) ?: myName
        io.execute { pushProfile() }
    }
    fun setBio(v: String) { prefs().edit().putString("bio", v).putBoolean("profPosted", false).apply(); io.execute { pushProfile() } }

    // reels files / language
    fun reelFiles(): List<File> = reelDir().listFiles()?.filter { it.name.endsWith(".mp4") && it.length() > 0 } ?: emptyList()
    fun autoSaveReel(id: String, link: String) { val n = id.toLongOrNull() ?: return; if (wifiOk()) io.execute { download(n, link) } }
    fun langQuery(): String = langQ[prefs().getInt("lang", 0)]
    fun langName(): String = langs[prefs().getInt("lang", 0)]
    fun nextLang() { prefs().edit().putInt("lang", (prefs().getInt("lang", 0) + 1) % langs.size).apply() }
    fun reelsSizeMb(): Long = dirSize() / 1048576
    fun clearReels() { reelDir().listFiles()?.forEach { it.delete() } }
    fun downloadReelToPhone(id: String, url: String) {
        toast("DOWNLOADING...")
        io.execute {
            try {
                val f = File(reelDir(), id.replace(Regex("[^A-Za-z0-9_]"), "_") + ".mp4")
                if (!f.exists()) {
                    if (url.startsWith("http")) URL(url).openStream().use { i -> f.outputStream().use { o -> i.copyTo(o) } } else File(url).copyTo(f, true)
                }
                var saved = false
                if (Build.VERSION.SDK_INT >= 29) {
                    val v = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, "AS_CHATS_" + f.name); put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/AS CHATS") }
                    val u = contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                    if (u != null) contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) }; saved = true }
                }
                runOnUiThread { toast(if (saved) "SAVED TO YOUR GALLERY (MOVIES / AS CHATS) AND OFFLINE REELS" else "SAVED TO OFFLINE REELS") }
            } catch (e: Exception) { runOnUiThread { toast("DOWNLOAD FAILED") } }
        }
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
