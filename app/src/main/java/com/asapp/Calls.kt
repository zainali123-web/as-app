package com.asapp

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import java.util.concurrent.Executors

/**
 * Voice + video calls (WebRTC). Signalling goes through Firebase Realtime Database:
 *   /calls/<receiver AS-ID>/<push id>  = { kind: offer|answer|ice|end, callId, from, ... }
 * The audio/video itself travels directly phone-to-phone (encrypted by WebRTC).
 */
class Calls(private val a: MainActivity) {
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: org.webrtc.AudioSource? = null
    private var audioTrack: org.webrtc.AudioTrack? = null
    private var videoSource: org.webrtc.VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var helper: SurfaceTextureHelper? = null
    private var localView: SurfaceViewRenderer? = null
    private var remoteView: SurfaceViewRenderer? = null
    private var dialog: Dialog? = null
    private var ringDialog: AlertDialog? = null
    private var statusTv: TextView? = null
    private var ringtone: Ringtone? = null
    private val sig = Executors.newSingleThreadExecutor()

    // 0 idle, 1 ringing (incoming), 2 calling (outgoing, waiting), 3 connected
    @Volatile private var state = 0
    private var peerId = ""
    private var callId = ""
    private var withVideo = false
    private var offerSdp = ""
    private var haveRemote = false
    private val pendingIce = ArrayList<IceCandidate>()
    private var connectedAt = 0L
    private var muted = false
    private var speaker = false
    private var polling = false
    private var accepted = false
    private var oldMode = AudioManager.MODE_NORMAL
    private var oldSpeaker = false

    // ---------- polling for incoming signals ----------
    fun start() {
        if (polling) return
        polling = true
        a.ui.post(object : Runnable {
            override fun run() {
                if (a.onlineReady() && a.online()) a.io.execute { try { poll() } catch (e: Exception) { a.st("13 CALLS", "FAILED: poll ${e.message}") } }
                a.ui.postDelayed(this, if (state != 0) 1000L else 3000L)
            }
        })
    }

    private fun poll() {
        val tok = a.token() ?: return
        val me = a.myId()
        val r = a.http("GET", "${a.db()}/calls/$me.json?auth=$tok") ?: return
        if (r.trim() == "null") return
        val o = JSONObject(r)
        val keys = ArrayList<String>()
        val it = o.keys(); while (it.hasNext()) keys.add(it.next())
        keys.sort()
        for (k in keys) {
            val m = o.optJSONObject(k)
            a.http("DELETE", "${a.db()}/calls/$me/$k.json?auth=$tok")
            if (m != null) a.runOnUiThread { try { handle(m) } catch (e: Exception) { a.st("13 CALLS", "FAILED: ${e.message}") } }
        }
    }

    private fun send(to: String, kind: String, cid: String, extra: (JSONObject) -> Unit = {}) {
        sig.execute {
            try {
                val tok = a.token() ?: return@execute
                val o = JSONObject().put("kind", kind).put("callId", cid).put("from", a.myId())
                    .put("ts", JSONObject().put(".sv", "timestamp"))
                extra(o)
                a.http("POST", "${a.db()}/calls/$to.json?auth=$tok", o.toString())
            } catch (e: Exception) { a.st("13 CALLS", "FAILED: send ${e.message}") }
        }
    }

    private fun handle(m: JSONObject) {
        val kind = m.optString("kind"); val cid = m.optString("callId"); val from = m.optString("from")
        when (kind) {
            "offer" -> {
                val ts = m.optLong("ts", 0L)
                if (ts > 0 && Math.abs(System.currentTimeMillis() - ts) > 3 * 60 * 1000L) return   // old, ignore
                if (state != 0) { send(from, "end", cid); return }
                ring(from, cid, m.optBoolean("video"), m.optString("sdp"))
            }
            "answer" -> if (cid == callId && state == 2) {
                val p = pc ?: return
                p.setRemoteDescription(object : Obs() {
                    override fun onSetSuccess() { haveRemote = true; flushIce() }
                    override fun onSetFailure(e: String?) { a.st("13 CALLS", "FAILED: answer $e") }
                }, SessionDescription(SessionDescription.Type.ANSWER, m.optString("sdp")))
            }
            "ice" -> if (cid == callId && state != 0) {
                val c = IceCandidate(m.optString("mid"), m.optInt("idx"), m.optString("cand"))
                if (haveRemote) pc?.addIceCandidate(c) else pendingIce.add(c)
            }
            "end" -> if (cid == callId && state != 0) endCall(false, "CALL ENDED")
        }
    }

    private fun flushIce() {
        val list = pendingIce.toList(); pendingIce.clear()
        list.forEach { pc?.addIceCandidate(it) }
    }

    // ---------- start a call ----------
    private fun perms(video: Boolean): Boolean {
        val need = ArrayList<String>(); need.add(Manifest.permission.RECORD_AUDIO)
        if (video) need.add(Manifest.permission.CAMERA)
        val miss = need.filter { a.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (miss.isEmpty()) return true
        a.requestPermissions(miss.toTypedArray(), 3)
        a.toast("ALLOW MICROPHONE/CAMERA, THEN TRY AGAIN")
        return false
    }

    fun call(to: String, video: Boolean) {
        if (state != 0) { a.toast("YOU ARE ALREADY IN A CALL"); return }
        if (to.isEmpty()) { a.toast("ENTER THE AS-ID FIRST (SEARCH ID OR TYPE IT)"); return }
        if (!a.onlineReady() || !a.online()) { a.toast("CALLS NEED INTERNET AND LOGIN"); return }
        if (to == a.myId()) { a.toast("YOU CANNOT CALL YOURSELF"); return }
        if (!perms(video)) return
        peerId = to; callId = java.lang.Long.toHexString(java.security.SecureRandom().nextLong())
        Store.addCall(to, "out", video)
        withVideo = video; state = 2; haveRemote = false; pendingIce.clear()
        openUi(video, "CALLING $to...")
        try {
            setupPc(video)
            pc!!.createOffer(object : Obs() {
                override fun onCreateSuccess(d: SessionDescription?) {
                    if (d == null) return
                    pc?.setLocalDescription(Obs(), d)
                    send(to, "offer", callId) { it.put("video", video).put("sdp", d.description) }
                }
                override fun onCreateFailure(e: String?) { endCall(false, "CALL FAILED: $e") }
            }, MediaConstraints())
        } catch (e: Exception) { a.st("13 CALLS", "FAILED: ${e.message}"); endCall(false, "CALL FAILED: ${e.message}"); return }
        val myCid = callId
        a.ui.postDelayed({ if (state == 2 && callId == myCid) endCall(true, "NO ANSWER") }, 45000)
    }

    // ---------- incoming ----------
    private fun ring(from: String, cid: String, video: Boolean, sdp: String) {
        accepted = false; state = 1; peerId = from; callId = cid; withVideo = video; offerSdp = sdp; haveRemote = false; pendingIce.clear()
        try {
            ringtone = RingtoneManager.getRingtone(a, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)); ringtone?.play()
        } catch (e: Exception) {}
        ringDialog = AlertDialog.Builder(a)
            .setTitle(if (video) "📹 INCOMING VIDEO CALL" else "📞 INCOMING VOICE CALL")
            .setMessage(from).setCancelable(false)
            .setPositiveButton("ACCEPT") { _, _ -> accept() }
            .setNegativeButton("REJECT") { _, _ -> endCall(true, "CALL REJECTED") }
            .show()
        a.ui.postDelayed({ if (state == 1 && callId == cid) endCall(true, "MISSED CALL FROM $from") }, 45000)
    }

    private fun accept() {
        stopRing(); Store.addCall(peerId, "in", withVideo); accepted = true
        if (!perms(withVideo)) { endCall(true, "ALLOW PERMISSIONS AND CALL BACK"); return }
        state = 2
        openUi(withVideo, "CONNECTING...")
        try {
            setupPc(withVideo)
            val p = pc!!
            p.setRemoteDescription(object : Obs() {
                override fun onSetSuccess() {
                    haveRemote = true; flushIce()
                    p.createAnswer(object : Obs() {
                        override fun onCreateSuccess(d: SessionDescription?) {
                            if (d == null) return
                            p.setLocalDescription(Obs(), d)
                            send(peerId, "answer", callId) { it.put("sdp", d.description) }
                        }
                        override fun onCreateFailure(e: String?) { endCall(true, "CALL FAILED: $e") }
                    }, MediaConstraints())
                }
                override fun onSetFailure(e: String?) { endCall(true, "CALL FAILED: $e") }
            }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        } catch (e: Exception) { a.st("13 CALLS", "FAILED: ${e.message}"); endCall(true, "CALL FAILED: ${e.message}") }
    }

    private fun stopRing() {
        try { ringtone?.stop() } catch (e: Exception) {}
        ringtone = null
        try { ringDialog?.dismiss() } catch (e: Exception) {}
        ringDialog = null
    }

    // ---------- WebRTC setup ----------
    private open class Obs : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) {}
    }

    private fun initFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(a.applicationContext).createInitializationOptions())
        val egl = EglBase.create(); eglBase = egl
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun iceServers(): List<PeerConnection.IceServer> {
        val l = ArrayList<PeerConnection.IceServer>()
        l.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        l.add(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer())
        if (Keys.TURN_URL.isNotEmpty()) {
            val urls = Keys.TURN_URL.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            l.add(PeerConnection.IceServer.builder(urls).setUsername(Keys.TURN_USER).setPassword(Keys.TURN_PASS).createIceServer())
        }
        return l
    }

    private fun setupPc(video: Boolean) {
        initFactory()
        val f = factory!!
        val cfg = PeerConnection.RTCConfiguration(iceServers())
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        cfg.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        val p = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED -> a.runOnUiThread { connected() }
                    PeerConnection.IceConnectionState.FAILED -> endCall(true, "CALL FAILED. NO ROUTE (A TURN SERVER MAY BE NEEDED)")
                    else -> {}
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate?) {
                if (c == null) return
                send(peerId, "ice", callId) { it.put("mid", c.sdpMid ?: "").put("idx", c.sdpMLineIndex).put("cand", c.sdp) }
            }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, s: Array<out MediaStream>?) {}
            override fun onTrack(t: RtpTransceiver?) {
                val track = t?.receiver?.track()
                if (track is VideoTrack) a.runOnUiThread { remoteView?.let { track.addSink(it) } }
            }
        }) ?: throw IllegalStateException("could not create connection")
        pc = p

        audioSource = f.createAudioSource(MediaConstraints())
        audioTrack = f.createAudioTrack("a0", audioSource)
        p.addTrack(audioTrack, listOf("s0"))

        if (video) {
            val en = Camera2Enumerator(a)
            val name = en.deviceNames.firstOrNull { en.isFrontFacing(it) } ?: en.deviceNames.first()
            val cap = en.createCapturer(name, null) ?: throw IllegalStateException("no camera")
            capturer = cap
            helper = SurfaceTextureHelper.create("cap", eglBase!!.eglBaseContext)
            videoSource = f.createVideoSource(false)
            cap.initialize(helper, a, videoSource!!.capturerObserver)
            cap.startCapture(640, 480, 20)
            videoTrack = f.createVideoTrack("v0", videoSource)
            localView?.let { videoTrack?.addSink(it) }
            p.addTrack(videoTrack, listOf("s0"))
        }
    }

    // ---------- call screen ----------
    private fun roundBtn(label: String, icon: String, size: Int = 56, f: (ImageView) -> Unit): LinearLayout = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        val iv = ImageView(a).apply { setImageDrawable(PathIcon(icon, Color.WHITE)); setPadding(a.dpx(14), a.dpx(14), a.dpx(14), a.dpx(14))
            background = Ui.shape(a, 0x33000000, 0xFF14E8C0.toInt(), 40, 2); setOnClickListener { f(this) } }
        addView(iv, LinearLayout.LayoutParams(a.dpx(size), a.dpx(size)))
        addView(TextView(a).apply { text = label; setTextColor(Color.WHITE); textSize = 13f; setPadding(0, a.dpx(6), 0, 0) })
        layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
    }

    private fun openUi(video: Boolean, label: String) {
        val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        oldMode = am.mode; oldSpeaker = am.isSpeakerphoneOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        speaker = video; am.isSpeakerphoneOn = speaker; muted = false

        val d = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        d.setCancelable(false)
        val root = FrameLayout(a)
        if (video) root.setBackgroundColor(Color.BLACK)
        else root.background = android.graphics.drawable.GradientDrawable().apply {
            gradientType = android.graphics.drawable.GradientDrawable.RADIAL_GRADIENT; gradientRadius = a.dpx(460).toFloat(); setGradientCenter(0.5f, 0.36f)
            colors = intArrayOf(0xFF004321.toInt(), 0xFF05140A.toInt(), 0xFF000300.toInt()) }
        if (video) {
            initFactory()
            val rv = SurfaceViewRenderer(a); rv.init(eglBase!!.eglBaseContext, null); rv.setEnableHardwareScaler(true)
            val lv = SurfaceViewRenderer(a); lv.init(eglBase!!.eglBaseContext, null); lv.setEnableHardwareScaler(true)
            lv.setMirror(true); lv.setZOrderMediaOverlay(true)
            remoteView = rv; localView = lv
            root.addView(rv, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            root.addView(lv, FrameLayout.LayoutParams(a.dpx(110), a.dpx(150), Gravity.TOP or Gravity.END).apply { setMargins(0, a.dpx(90), a.dpx(12), 0) })
        }
        root.addView(Ui.logoBar(a).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { topMargin = a.dpx(30) })
        val name = a.net.name(peerId)
        val st = TextView(a).apply { setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER; text = label }
        statusTv = st
        if (!video) {
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            col.addView(Ui.avatar(a, a, peerId, name, "dm", 200).apply { ring = 0xFF14E8C0.toInt(); ringDp = 5f; layoutParams = LinearLayout.LayoutParams(a.dpx(200), a.dpx(200)) })
            col.addView(TextView(a).apply { text = name; setTextColor(Color.WHITE); textSize = 30f; typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(0, a.dpx(18), 0, 0) })
            col.addView(st, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = a.dpx(4) })
            col.addView(LiveBars(a), LinearLayout.LayoutParams(a.dpx(260), a.dpx(46)).apply { topMargin = a.dpx(16) })
            root.addView(col, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { topMargin = a.dpx(100) })
        } else {
            root.addView(st, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP).apply { topMargin = a.dpx(80) })
        }
        val row = LinearLayout(a); row.gravity = Gravity.CENTER
        row.addView(roundBtn(if (speaker) "Speaker" else "Earpiece", Icons.VOLUME) { x -> speaker = !speaker; am.isSpeakerphoneOn = speaker })
        if (video) row.addView(roundBtn("Camera", Icons.CAMERA) { try { capturer?.switchCamera(null) } catch (e: Exception) {} })
        else row.addView(roundBtn("Video", Icons.VIDEOCAM) { val p = peerId; endCall(true, "STARTING VIDEO CALL..."); a.ui.postDelayed({ call(p, true) }, 900) })
        row.addView(roundBtn("Mute", Icons.MIC_OFF) { x -> muted = !muted; audioTrack?.setEnabled(!muted); x.alpha = if (muted) 0.5f else 1f })
        root.addView(row, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = a.dpx(150); leftMargin = a.dpx(20); rightMargin = a.dpx(20) })
        root.addView(FrameLayout(a).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFFA0921.toInt()) }
            addView(ImageView(a).apply { setImageDrawable(PathIcon(Icons.CALL_END, Color.WHITE)) }, FrameLayout.LayoutParams(a.dpx(36), a.dpx(36), Gravity.CENTER))
            setOnClickListener { endCall(true, "CALL ENDED") }
        }, FrameLayout.LayoutParams(a.dpx(76), a.dpx(76), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = a.dpx(50) })

        d.setContentView(root)
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        d.show(); dialog = d
    }

    private fun connected() {
        if (state == 3) return
        state = 3; connectedAt = System.currentTimeMillis()
        a.st("13 CALLS", "OK")
        a.ui.post(object : Runnable {
            override fun run() {
                if (state != 3) return
                val s = (System.currentTimeMillis() - connectedAt) / 1000
                statusTv?.text = "%02d:%02d".format(s / 60, s % 60)
                a.ui.postDelayed(this, 1000)
            }
        })
    }

    fun endCall(notify: Boolean, msg: String) {
        a.runOnUiThread {
            if (state == 0) return@runOnUiThread
            if (state == 1 && !accepted) Store.addCall(peerId, "miss", withVideo)
            if (notify && peerId.isNotEmpty()) send(peerId, "end", callId)
            cleanup()
            a.toast(msg)
        }
    }

    private fun cleanup() {
        state = 0; haveRemote = false; pendingIce.clear()
        stopRing()
        try { capturer?.stopCapture() } catch (e: Exception) {}
        try { capturer?.dispose() } catch (e: Exception) {}
        try { helper?.dispose() } catch (e: Exception) {}
        try { videoTrack?.dispose() } catch (e: Exception) {}
        try { videoSource?.dispose() } catch (e: Exception) {}
        try { audioTrack?.dispose() } catch (e: Exception) {}
        try { audioSource?.dispose() } catch (e: Exception) {}
        try { pc?.close() } catch (e: Exception) {}
        try { pc?.dispose() } catch (e: Exception) {}
        try { localView?.release() } catch (e: Exception) {}
        try { remoteView?.release() } catch (e: Exception) {}
        capturer = null; helper = null; videoTrack = null; videoSource = null; audioTrack = null; audioSource = null
        pc = null; localView = null; remoteView = null
        try { dialog?.dismiss() } catch (e: Exception) {}
        dialog = null; statusTv = null
        try {
            val am = a.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = oldSpeaker; am.mode = oldMode
        } catch (e: Exception) {}
    }

    fun stopAll() { try { if (state != 0) cleanup() } catch (e: Exception) {} }
}
