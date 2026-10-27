package com.asapp

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*

/** Status tab: text / photo / video statuses that disappear after 24 hours. */
class StatusUi(private val a: MainActivity, private val s: Screens) {
    private val net get() = a.net
    private fun d(v: Int) = Ui.dp(a, v)
    private fun t(x: CharSequence, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) = Ui.tv(a, x, size, color, bold)
    private val M = Ui.MATCH; private val W = Ui.WRAP
    private val bgColors = intArrayOf(0xFF0B6B3A.toInt(), 0xFF00695C.toInt(), 0xFF01579B.toInt(), 0xFF4A148C.toInt(), 0xFF880E4F.toInt(), 0xFFBF360C.toInt(), 0xFF263238.toInt())

    fun status() {
        s.chatRefresh = null
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), d(8), d(14), 0) }
        root.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL
            addView(Ui.logoBar(a), Screens.lp0(0, W, 1f))
            addView(Ui.iconBtn(a, Icons.MORE, Color.WHITE, 24, 8) { s.openTab(1) }) })
        root.addView(t("Status", 22f, Color.WHITE, true).apply { setPadding(0, d(10), 0, d(6)) })
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(a).apply { addView(col); isVerticalScrollBarEnabled = false }
        // my status
        val mine = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(10), 0, d(10)) }
        val myAv = Ui.avatar(a, a, a.myId(), "", "me", 56)
        mine.addView(FrameLayout(a).apply { addView(myAv, FrameLayout.LayoutParams(d(56), d(56)))
            addView(Ui.greenCircle(a, Icons.ADD, 22) {}, FrameLayout.LayoutParams(d(22), d(22), Gravity.END or Gravity.BOTTOM)) }, Screens.lp0(d(56), d(56)))
        val myTxt = t("Tap to add status", 14f, Ui.GRAY)
        mine.addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, 0, 0)
            addView(t("My Status", 17f, Color.WHITE, true)); addView(myTxt) }, Screens.lp0(0, W, 1f))
        mine.addView(Ui.greenCircle(a, Icons.CAMERA, 46) { compose() })
        mine.setOnClickListener {
            val my = s.statusCache[a.myId()]
            if (my != null && my.isNotEmpty()) view(a.myId(), my) else compose()
        }
        col.addView(mine)
        col.addView(t("Recent updates", 14f, Ui.GRAY).apply { setPadding(0, d(14), 0, d(6)) })
        val recent = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(recent)
        val empty = t("No updates yet. When your friends post a status it shows here.", 14f, Ui.GRAY).apply { setPadding(0, d(16), 0, 0) }
        recent.addView(empty)
        root.addView(sv, Screens.lp0(M, 0, 1f))
        s.show(root, 1)

        // load statuses: mine + every chat contact + people I follow
        val ids = LinkedHashSet<String>(); ids.add(a.myId())
        Store.all().filter { it.type == "dm" }.forEach { ids.add(it.id) }; net.following().forEach { ids.add(it) }
        for (id in ids) net.fetchStatus(id) { list ->
            s.statusCache[id] = list
            if (s.cur != "status") return@fetchStatus
            if (id == a.myId()) { myTxt.text = if (list.isEmpty()) "Tap to add status" else "${list.size} update(s) · ${Ui.ago(list.last().ts)}"; return@fetchStatus }
            if (list.isEmpty()) return@fetchStatus
            recent.removeView(empty)
            recent.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(8), 0, d(8))
                addView(Ui.avatar(a, a, id, net.name(id), "dm", 54).apply { ring = Ui.GREEN; ringDp = 3f })
                addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, 0, 0)
                    addView(t(net.name(id), 17f, Color.WHITE, true)); addView(t(Ui.ago(list.last().ts), 14f, Ui.GRAY)) })
                setOnClickListener { view(id, list) } })
        }
    }

    // ---------- create ----------
    private fun compose() {
        AlertDialog.Builder(a).setTitle("Add status").setItems(arrayOf("✏️  Text", "🖼  Photo", "🎬  Video")) { _, i ->
            when (i) {
                0 -> textStatus()
                else -> {
                    if (!net.cloudReady()) { a.toast("PHOTO/VIDEO NEEDS CLOUDINARY: FILL CLOUD_NAME + CLOUD_PRESET IN KEYS.KT"); return@setItems }
                    val vid = i == 2
                    a.pick(if (vid) "video/*" else "image/*") { uri ->
                        a.toast("UPLOADING...")
                        net.upload(uri, vid) { url -> if (url != null) net.postStatus(if (vid) "video" else "image", "", url, 0) { ok -> a.toast(if (ok) "STATUS POSTED" else "FAILED"); s.openTab(1) } }
                    }
                }
            }
        }.show()
    }

    private fun textStatus() {
        var bgi = 0
        val dlg = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(a).apply { setBackgroundColor(bgColors[0]) }
        val et = EditText(a).apply { hint = "Type a status"; setHintTextColor(0x99FFFFFF.toInt()); setTextColor(Color.WHITE); textSize = 28f; gravity = Gravity.CENTER; background = null
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        root.addView(et, FrameLayout.LayoutParams(M, M).apply { setMargins(d(24), d(80), d(24), d(80)) })
        root.addView(Ui.iconBtn(a, Icons.CLOSE, Color.WHITE, 26, 10) { dlg.dismiss() }, FrameLayout.LayoutParams(W, W, Gravity.TOP or Gravity.START).apply { topMargin = d(30) })
        root.addView(t("🎨", 26f).apply { setPadding(d(12), d(12), d(12), d(12)); setOnClickListener { bgi = (bgi + 1) % bgColors.size; root.setBackgroundColor(bgColors[bgi]) } },
            FrameLayout.LayoutParams(W, W, Gravity.TOP or Gravity.END).apply { topMargin = d(30) })
        root.addView(Ui.greenCircle(a, Icons.SEND, 58) {
            val x = et.text.toString().trim()
            if (x.isEmpty()) { a.toast("TYPE SOMETHING"); return@greenCircle }
            net.postStatus("text", x, "", bgColors[bgi]) { ok -> a.toast(if (ok) "STATUS POSTED" else "FAILED. CHECK INTERNET AND FIREBASE RULES"); if (ok) { dlg.dismiss(); s.openTab(1) } }
        }, FrameLayout.LayoutParams(d(58), d(58), Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, d(24), d(30)) })
        dlg.setContentView(root); dlg.show()
    }

    // ---------- viewer ----------
    fun view(owner: String, items: List<StatusItem>) {
        if (items.isEmpty()) return
        var idx = 0
        val dlg = Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(a).apply { setBackgroundColor(Color.BLACK) }
        val content = FrameLayout(a); root.addView(content, FrameLayout.LayoutParams(M, M))
        val bars = LinearLayout(a); root.addView(bars, FrameLayout.LayoutParams(M, d(4), Gravity.TOP).apply { setMargins(d(8), d(34), d(8), 0) })
        val who = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(who, FrameLayout.LayoutParams(M, W, Gravity.TOP).apply { topMargin = d(46); leftMargin = d(10) })
        val bottom = FrameLayout(a); root.addView(bottom, FrameLayout.LayoutParams(M, W, Gravity.BOTTOM))
        var timer: Runnable? = null
        val mineStatus = owner == a.myId()
        fun close() { timer?.let { a.ui.removeCallbacks(it) }; dlg.dismiss() }
        fun show(i: Int) {
            timer?.let { a.ui.removeCallbacks(it) }
            if (i >= items.size) { close(); return }
            if (i < 0) { show(0); return }
            idx = i; val it = items[i]
            net.markViewed(owner, it.id)
            content.removeAllViews(); bars.removeAllViews(); who.removeAllViews(); bottom.removeAllViews()
            for (k in items.indices) bars.addView(View(a).apply { setBackgroundColor(if (k <= i) Color.WHITE else 0x55FFFFFF) }, Screens.lp0(0, M, 1f).apply { setMargins(d(2), 0, d(2), 0) })
            who.addView(Ui.avatar(a, a, owner, net.name(owner), if (mineStatus) "me" else "dm", 38))
            who.addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10), 0, 0, 0)
                addView(t(if (mineStatus) "My Status" else net.name(owner), 16f, Color.WHITE, true)); addView(t(Ui.ago(it.ts), 12f, 0xFFCCCCCC.toInt())) })
            var dur = 5000L
            when (it.type) {
                "image" -> content.addView(ImageView(a).apply { scaleType = ImageView.ScaleType.FIT_CENTER; Img.load(a, it.url, this) }, FrameLayout.LayoutParams(M, M))
                "video" -> { val vv = VideoView(a); content.addView(vv, FrameLayout.LayoutParams(M, M, Gravity.CENTER)); vv.setVideoURI(android.net.Uri.parse(it.url))
                    vv.setOnPreparedListener { mp -> mp.start(); dur = Math.min(mp.duration.toLong() + 300, 30000L); timer?.let { r -> a.ui.removeCallbacks(r) }; timer = Runnable { show(idx + 1) }; a.ui.postDelayed(timer!!, dur) } }
                else -> { content.setBackgroundColor(if (it.bg != 0) it.bg else bgColors[0])
                    content.addView(t(it.text, 28f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(d(28), 0, d(28), 0) }, FrameLayout.LayoutParams(M, M)) }
            }
            if (it.type != "video") { timer = Runnable { show(idx + 1) }; a.ui.postDelayed(timer!!, dur) }
            // taps: left = back, right = next
            val tap = LinearLayout(a)
            tap.addView(View(a).apply { setOnClickListener { show(idx - 1) } }, Screens.lp0(0, M, 1f))
            tap.addView(View(a).apply { setOnClickListener { show(idx + 1) } }, Screens.lp0(0, M, 2f))
            content.addView(tap, FrameLayout.LayoutParams(M, M).apply { bottomMargin = d(90); topMargin = d(90) })
            if (mineStatus) {
                val cnt = t("👁 …", 15f, Color.WHITE)
                net.viewCount(owner, it.id) { n -> cnt.text = "👁 $n views" }
                bottom.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(16), d(10), d(16), d(18)); setBackgroundColor(0x88000000.toInt())
                    addView(cnt, Screens.lp0(0, W, 1f))
                    addView(Ui.iconBtn(a, Icons.DELETE, Color.WHITE, 24, 8) { net.deleteStatus(it.id) { close(); s.openTab(1) } }) })
            } else {
                val reply = EditText(a).apply { hint = "Reply..."; setHintTextColor(0xAAFFFFFF.toInt()); setTextColor(Color.WHITE); setSingleLine(); background = Ui.shape(a, 0x66000000, Color.WHITE, 30); setPadding(d(16), d(10), d(16), d(10)) }
                bottom.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(12), d(8), d(12), d(16))
                    addView(reply, Screens.lp0(0, W, 1f))
                    addView(Ui.iconBtn(a, Icons.SEND, Ui.GREEN, 26, 8) {
                        val x = reply.text.toString().trim()
                        if (x.isNotEmpty()) { val c = net.openDm(owner); net.sendText(c, "↩ Status: $x"); reply.setText(""); a.toast("SENT") } }) })
                reply.setOnFocusChangeListener { _, f -> if (f) timer?.let { r -> a.ui.removeCallbacks(r) } }
            }
        }
        dlg.setContentView(root); dlg.setOnDismissListener { timer?.let { a.ui.removeCallbacks(it) } }
        dlg.show(); show(0)
    }
}
