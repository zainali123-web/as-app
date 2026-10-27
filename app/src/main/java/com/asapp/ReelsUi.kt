package com.asapp

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/** Reels: TikTok style feed with like / comment / share / repost / download / save / follow. */
class ReelsUi(private val a: MainActivity, private val s: Screens) {
    private val net get() = a.net
    private fun d(v: Int) = Ui.dp(a, v)
    private fun t(x: CharSequence, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) = Ui.tv(a, x, size, color, bold)
    private val M = Ui.MATCH; private val W = Ui.WRAP
    private var offline = false
    private var all: List<ReelItem> = emptyList()
    private val liked = HashSet<String>()
    private val saved = HashSet<String>()
    private val counted = HashSet<String>()

    fun open() { s.go("reels") { home() } }

    fun nav(sel: Int): View {
        val bar = LinearLayout(a).apply { setBackgroundColor(0xFF020A0B.toInt()); setPadding(0, d(6), 0, d(4)); gravity = Gravity.CENTER_VERTICAL }
        fun item(i: Int, label: String, ic: String, f: () -> Unit) = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            val on = i == sel
            addView(Ui.icon(a, ic, if (on) Ui.GREEN else Ui.GRAY, 26)); addView(t(label, 12f, if (on) Ui.GREEN else Ui.GRAY))
            addView(View(a).apply { setBackgroundColor(if (on) Ui.GREEN else Color.TRANSPARENT) }, Screens.lp0(d(40), d(3)).apply { topMargin = d(3) })
            setOnClickListener { f() }
        }
        bar.addView(item(0, "Home", Icons.CHAT) { s.openTab(0) }, Screens.lp0(0, W, 1f))
        bar.addView(item(1, "Reels", Icons.PLAY) { s.tab("reels") { home() } }, Screens.lp0(0, W, 1f))
        bar.addView(Ui.greenCircle(a, Icons.ADD, 54) { upload() }.apply { layoutParams = Screens.lp0(d(54), d(54)).apply { setMargins(d(8), 0, d(8), 0) } })
        bar.addView(item(3, "Discover", Icons.SEARCH) { s.go("discover") { discover() } }, Screens.lp0(0, W, 1f))
        bar.addView(item(4, "Profile", Icons.PERSON) { s.go("profile:me") { a.profileUi.profile(a.myId()) } }, Screens.lp0(0, W, 1f))
        return bar
    }

    private fun header(): View = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(Ui.iconBtn(a, Icons.MENU, Color.WHITE, 26, 8) { s.openTab(0) })
        addView(Ui.logoBar(a), Screens.lp0(0, W, 1f))
        addView(Ui.iconBtn(a, Icons.SEARCH, Color.WHITE, 26, 8) { s.go("discover") { discover() } })
        addView(Ui.iconBtn(a, Icons.PREMIUM, Ui.GREEN, 26, 8) { a.crownPage() })
    }

    // ---------- loading ----------
    private fun pexels(done: (List<ReelItem>) -> Unit) {
        val k = Keys.PEXELS
        if (k.isEmpty() || !a.online()) { done(emptyList()); return }
        a.io.execute {
            val out = ArrayList<ReelItem>()
            try {
                val q = URLEncoder.encode(a.langQuery(), "UTF-8")
                val r = a.http("GET", "https://api.pexels.com/videos/search?query=$q&per_page=15&page=${(1..5).random()}&orientation=portrait", null, mapOf("Authorization" to k))
                if (r != null) {
                    val vs = JSONObject(r).getJSONArray("videos")
                    for (i in 0 until vs.length()) {
                        val v = vs.getJSONObject(i); val fl = v.getJSONArray("video_files"); var best: JSONObject? = null
                        for (j in 0 until fl.length()) { val f = fl.getJSONObject(j)
                            if (f.optString("file_type") == "video/mp4" && f.optInt("width") > 0 && (best == null || Math.abs(f.optInt("width") - 720) < Math.abs(best.optInt("width") - 720))) best = f }
                        if (best != null) out.add(ReelItem("pex_" + v.getLong("id"), best.getString("link"), "OFFICIAL", "AS CHATS Official",
                            "Video by " + v.getJSONObject("user").optString("name") + " (Pexels)", "#ASChats #Life #Vibes", 0L, true, v.optString("image")))
                    }
                    a.st("9 REELS", "OK")
                } else a.st("9 REELS", "FAILED: Pexels request (check key)")
            } catch (e: Exception) { a.st("9 REELS", "FAILED: ${e.message}") }
            a.runOnUiThread { done(out) }
        }
    }
    private fun savedFiles(): List<ReelItem> = a.reelFiles().map { ReelItem("off_" + it.name, it.absolutePath, "OFFICIAL", "AS CHATS Official", "Saved reel", "#Saved", 0L, true) }

    private fun load(done: (List<ReelItem>) -> Unit) {
        if (offline) { all = savedFiles().shuffled(); done(all); return }
        net.fetchReels { users ->
            pexels { pex ->
                val mixed = ArrayList<ReelItem>(); var i = 0; var j = 0
                while (i < users.size || j < pex.size) { if (i < users.size) mixed.add(users[i++]); if (j < pex.size) mixed.add(pex[j++]) }
                val res = if (mixed.isEmpty()) savedFiles() else mixed
                all = res; done(res)
                pex.take(6).forEach { a.autoSaveReel(it.id.removePrefix("pex_"), it.url) }
            }
        }
    }

    // ---------- reels home ----------
    fun home() {
        s.chatRefresh = null
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), d(8), d(14), 0) }
        root.addView(header())
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(a).apply { addView(col); isVerticalScrollBarEnabled = false }, Screens.lp0(M, 0, 1f))
        col.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(6), 0, d(10))
            addView(Ui.icon(a, Icons.PLAY, Ui.GREEN, 34)); addView(t("Reels", 24f, Color.WHITE, true).apply { setPadding(d(12), 0, 0, 0) }) })
        val n = a.reelFiles().size
        fun card(title: String, sub: String, ic: String, on: Boolean, f: () -> Unit) = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(d(14), d(14), d(14), d(14))
            background = if (on) android.graphics.drawable.GradientDrawable().apply { setColor(0xFF06281F.toInt()); cornerRadius = d(16).toFloat(); setStroke(d(2), Ui.GREEN) } else Ui.card(a, 16)
            addView(Ui.icon(a, ic, if (on) Ui.GREEN else Ui.TEAL, 34)); addView(t(title, 17f, if (on) Ui.GREEN else Color.WHITE, true).apply { setPadding(0, d(8), 0, 0) })
            addView(t(sub, 12f, if (on) Ui.GREEN else Ui.GRAY)); setOnClickListener { f() } }
        col.addView(LinearLayout(a).apply {
            addView(card("Offline Reels", "Watch without internet ($n saved)", Icons.DOWN, false) { offline = true; a.say("ENJOY YOUR REEL"); s.go("feed") { feed(0) } }, Screens.lp0(0, W, 1f).apply { rightMargin = d(6) })
            addView(card("Online Reels", "Stream & explore", Icons.GLOBE, true) { offline = false; a.say("ENJOY YOUR REEL"); s.go("feed") { feed(0) } }, Screens.lp0(0, W, 1f).apply { leftMargin = d(6) }) })
        val rows = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(rows)
        col.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; background = Ui.card(a, 16); setPadding(d(14), d(14), d(14), d(14))
            addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL
                addView(t("Create Your Own Reel", 16f, Color.WHITE, true)); addView(t("Share your moments, get noticed!", 12f, Ui.GREEN)) }, Screens.lp0(0, W, 1f))
            addView(t("Upload Reel  ›", 14f, 0xFF03241A.toInt(), true).apply { background = Ui.shape(a, Ui.GREEN, 0, 30); setPadding(d(14), d(10), d(14), d(10)); setOnClickListener { upload() } })
        }, Screens.lp0(M, W).apply { topMargin = d(16); bottomMargin = d(10) })
        s.show(root, 11)
        offline = false
        load { list ->
            if (s.cur != "reels") return@load
            rows.removeAllViews()
            if (list.isEmpty()) { rows.addView(t("No reels yet. Add your Pexels key in Keys.kt or upload your own.", 14f, Ui.GRAY).apply { setPadding(0, d(16), 0, d(8)) }); return@load }
            rows.addView(thumbRow("Trending Now", Icons.INFO, list.take(8), 0))
            rows.addView(thumbRow("Recommended For You", Icons.FAVORITE, list.drop(4).ifEmpty { list }.take(8), 4))
            val creators = list.filter { !it.official }.groupBy { it.owner }.entries.sortedByDescending { it.value.size }.take(8)
            if (creators.isNotEmpty()) {
                rows.addView(t("Popular Creators", 17f, Color.WHITE, true).apply { setPadding(0, d(14), 0, d(8)) })
                val h = LinearLayout(a)
                creators.forEach { e -> h.addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(d(6), 0, d(6), 0)
                    addView(Ui.avatar(a, a, e.key, e.value[0].name, "dm", 68).apply { ring = Ui.GREEN; ringDp = 3f })
                    addView(t(e.value[0].name.take(12), 13f, Color.WHITE, true).apply { gravity = Gravity.CENTER })
                    addView(followPill(e.key))
                    setOnClickListener { s.go("profile:${e.key}") { a.profileUi.profile(e.key) } } }) }
                rows.addView(HorizontalScrollView(a).apply { addView(h); isHorizontalScrollBarEnabled = false })
            }
        }
    }

    private fun followPill(owner: String): View {
        val tx = t(if (net.following().contains(owner)) "Following" else "Follow", 12f, Ui.GREEN)
        tx.background = Ui.shape(a, 0, Ui.GREEN, 20); tx.setPadding(d(14), d(4), d(14), d(4)); tx.gravity = Gravity.CENTER
        tx.setOnClickListener { val on = !net.following().contains(owner); net.setFollow(owner, on); tx.text = if (on) "Following" else "Follow" }
        return tx.also { it.layoutParams = Screens.lp0(W, W).apply { topMargin = d(4) } }
    }

    private fun thumbRow(title: String, icon: String, items: List<ReelItem>, startAt: Int): View {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        box.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(16), 0, d(8))
            addView(Ui.icon(a, icon, 0xFFFFC107.toInt(), 22)); addView(t(title, 17f, Color.WHITE, true).apply { setPadding(d(8), 0, 0, 0) }, Screens.lp0(0, W, 1f)) })
        val h = LinearLayout(a)
        items.forEachIndexed { i, r -> h.addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, d(10), 0)
            addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF0A1C1E.toInt()); clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, d(12).toFloat()) } }
                val th = if (r.thumb.isNotEmpty()) r.thumb else if (r.url.startsWith("http")) net.thumb(r.url) else ""
                if (th.isNotEmpty()) Img.load(a, th, this) }, Screens.lp0(d(100), d(130)))
            addView(t(r.cap.take(16), 12f, Color.WHITE, true).apply { maxLines = 1 }, Screens.lp0(d(100), W))
            addView(t(r.name.take(14), 11f, Ui.GRAY).apply { maxLines = 1 }, Screens.lp0(d(100), W))
            setOnClickListener { s.go("feed") { feed(startAt + i) } } }) }
        box.addView(HorizontalScrollView(a).apply { addView(h); isHorizontalScrollBarEnabled = false })
        return box
    }

    // ---------- the feed (swipe up / down) ----------
    private var chip = 0
    fun feed(start: Int) {
        s.chatRefresh = null
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10), d(6), d(10), 0) }
        root.addView(header())
        val chipsRow = LinearLayout(a)
        val names = listOf("Reels", "For You", "Following", "Trending")
        names.forEachIndexed { i, nm -> chipsRow.addView(Ui.chip(a, nm, chip == i) { chip = i; s.go("feed") { feed(0) } }, Screens.lp0(0, W, 1f).apply { rightMargin = d(6) }) }
        root.addView(chipsRow, Screens.lp0(M, W).apply { topMargin = d(6); bottomMargin = d(8) })
        val holder = FrameLayout(a); root.addView(holder, Screens.lp0(M, 0, 1f).apply { bottomMargin = d(6) })
        s.show(root, 11)
        val msg = t("Loading reels...", 14f, Ui.GRAY).apply { gravity = Gravity.CENTER }
        holder.addView(msg, FrameLayout.LayoutParams(M, M))
        fun go(list0: List<ReelItem>) {
            var list = when (chip) { 2 -> list0.filter { net.following().contains(it.owner) }; 3 -> list0.sortedByDescending { it.ts }; else -> list0 }
            if (s.cur != "feed") return
            if (list.isEmpty()) { msg.text = if (chip == 2) "Follow creators to see their reels here." else "No reels yet. " + (if (offline) "Open Online Reels on Wi-Fi to save some." else if (Keys.PEXELS.isEmpty()) "Add your Pexels key in Keys.kt." else "Connect to the internet once."); return }
            holder.removeAllViews(); playIn(holder, list, Math.min(start, list.size - 1))
        }
        if (all.isNotEmpty() && !offline) go(all) else load { go(it) }
    }

    private fun playIn(holder: FrameLayout, list: List<ReelItem>, start: Int) {
        var idx = start
        val card = FrameLayout(a).apply { background = Ui.shape(a, Color.BLACK, 0xFF0C4A42.toInt(), 18); clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, d(18).toFloat()) } } }
        holder.addView(card, FrameLayout.LayoutParams(M, M))
        val vv = VideoView(a); card.addView(vv, FrameLayout.LayoutParams(M, M, Gravity.CENTER))
        val overlay = FrameLayout(a); card.addView(overlay, FrameLayout.LayoutParams(M, M))
        val heart = ImageView(a).apply { setImageDrawable(PathIcon(Icons.FAVORITE, Ui.RED)); alpha = 0f }
        card.addView(heart, FrameLayout.LayoutParams(d(110), d(110), Gravity.CENTER))
        val progress = View(a).apply { setBackgroundColor(Ui.GREEN) }
        val track = FrameLayout(a).apply { setBackgroundColor(0x55FFFFFF); addView(progress, FrameLayout.LayoutParams(0, M)) }
        card.addView(track, FrameLayout.LayoutParams(M, d(3), Gravity.BOTTOM))
        val tick = object : Runnable { override fun run() {
            if (vv.duration > 0) progress.layoutParams = FrameLayout.LayoutParams((track.width * vv.currentPosition.toFloat() / vv.duration).toInt(), M)
            a.ui.postDelayed(this, 250) } }
        a.ui.postDelayed(tick, 250)

        fun show() {
            val r = list[idx]
            overlay.removeAllViews()
            vv.setVideoURI(if (r.url.startsWith("http")) Uri.parse(r.url) else Uri.fromFile(File(r.url)))
            vv.setOnPreparedListener { it.isLooping = true; vv.start() }
            vv.setOnErrorListener { _, _, _ -> a.toast("THIS REEL FAILED. SWIPE UP."); true }
            if (counted.add(r.id)) net.addView(r.id)
            overlay.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; background = Ui.shape(a, 0x99000000.toInt(), 0, 20); setPadding(d(10), d(6), d(12), d(6))
                addView(Ui.icon(a, Icons.SEARCH, Ui.GREEN, 16)); addView(t("For You", 13f).apply { setPadding(d(6), 0, 0, 0) }) },
                FrameLayout.LayoutParams(W, W, Gravity.TOP or Gravity.START).apply { setMargins(d(12), d(12), 0, 0) })
            val views = t("▶ …", 13f).apply { background = Ui.shape(a, 0x99000000.toInt(), 0, 20); setPadding(d(12), d(6), d(12), d(6)) }
            overlay.addView(views, FrameLayout.LayoutParams(W, W, Gravity.TOP or Gravity.END).apply { setMargins(0, d(12), d(12), 0) })
            net.viewsOf(r.id) { n -> views.text = "▶ " + compact(n) }

            // right column
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val likeIv = ImageView(a); val likeTx = t("0", 13f, Color.WHITE, true); val comTx = t("0", 13f, Color.WHITE, true)
            val avWrap = FrameLayout(a).apply {
                addView(Ui.avatar(a, a, r.owner, r.name, if (r.official) "official" else "dm", 54), FrameLayout.LayoutParams(d(54), d(54)))
                addView(Ui.greenCircle(a, Icons.ADD, 22) {}, FrameLayout.LayoutParams(d(22), d(22), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                setOnClickListener { if (!r.official) s.go("profile:${r.owner}") { a.profileUi.profile(r.owner) } } }
            col.addView(avWrap, Screens.lp0(d(54), d(60)))
            fun act(ic: ImageView, tx: TextView?, f: () -> Unit) { col.addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(0, d(8), 0, d(2))
                addView(ic, Screens.lp0(d(34), d(34))); tx?.let { addView(it) }; setOnClickListener { f() } }) }
            fun ico(p: String, c: Int = Color.WHITE) = ImageView(a).apply { setImageDrawable(PathIcon(p, c)) }
            likeIv.setImageDrawable(PathIcon(Icons.FAVORITE, if (liked.contains(r.id)) Ui.RED else Color.WHITE))
            fun doLike() {
                val on = !liked.contains(r.id); if (on) liked.add(r.id) else liked.remove(r.id)
                likeIv.setImageDrawable(PathIcon(Icons.FAVORITE, if (on) Ui.RED else Color.WHITE)); net.setLike(r.id, on)
                likeTx.text = compact(Math.max(0, (likeTx.tag as? Int ?: 0) + if (on) 1 else -1)); likeTx.tag = (likeTx.tag as? Int ?: 0) + if (on) 1 else -1
            }
            act(likeIv, likeTx) { doLike() }
            act(ico(Icons.CHAT_OUTLINE), comTx) { comments(r) }
            val saveIv = ico(Icons.STATUS, if (saved.contains(r.id)) Ui.GREEN else Color.WHITE)
            act(ico(Icons.SHARE), t("Share", 12f)) { a.shareText("Watch this reel on AS CHATS: ${r.url}") }
            act(saveIv, t("Save", 12f)) { if (!saved.remove(r.id)) saved.add(r.id); saveIv.setImageDrawable(PathIcon(Icons.STATUS, if (saved.contains(r.id)) Ui.GREEN else Color.WHITE)); a.toast(if (saved.contains(r.id)) "SAVED" else "REMOVED") }
            act(ico(Icons.CALL_MADE), t("Repost", 12f)) { repost(r) }
            act(ico(Icons.DOWN), t("Download", 12f)) { a.downloadReelToPhone(r.id, r.url) }
            col.addView(AvatarView(a).apply { fill = 0xFF03161A.toInt(); ring = Ui.GREEN; setIconPath(Icons.GRAPHIC_EQ, Ui.GREEN) }, Screens.lp0(d(44), d(44)).apply { topMargin = d(10) })
            overlay.addView(col, FrameLayout.LayoutParams(W, W, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, d(8), d(46)) })

            // bottom info
            val info = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12), d(40), d(70), d(14))
                background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xCC000000.toInt())) }
            val followTx = t(if (net.following().contains(r.owner)) "Following" else "Follow", 14f, Color.WHITE).apply {
                background = Ui.shape(a, 0x55000000, Ui.GREEN, 30); setPadding(d(16), d(6), d(16), d(6)); setOnClickListener { val on = !net.following().contains(r.owner); net.setFollow(r.owner, on); text = if (on) "Following" else "Follow" } }
            info.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL
                addView(Ui.avatar(a, a, r.owner, r.name, if (r.official) "official" else "dm", 40))
                addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(8), 0, d(10), 0)
                    addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; addView(t(r.name.take(18), 16f, Color.WHITE, true)); if (r.official) addView(Ui.icon(a, Icons.VERIFIED, Ui.GREEN, 16)) })
                    addView(t("@" + r.owner.lowercase().replace("-", "_"), 12f, 0xFFCCCCCC.toInt())) })
                if (r.owner != a.myId()) addView(followTx) })
            info.addView(t(r.cap, 14f).apply { maxLines = 2; setPadding(0, d(8), 0, d(6)) })
            val tags = LinearLayout(a)
            r.tags.split(" ").filter { it.startsWith("#") }.take(4).forEach { tg -> tags.addView(t(tg, 12f, Ui.GREEN).apply { background = Ui.shape(a, 0x66000000, Ui.CARD_STROKE, 20); setPadding(d(10), d(4), d(10), d(4)) }, Screens.lp0(W, W).apply { rightMargin = d(6) }) }
            info.addView(tags)
            overlay.addView(info, FrameLayout.LayoutParams(M, W, Gravity.BOTTOM))
            net.reelStats(r.id, if (r.official) "" else r.owner) { l, c, lk, _, _ ->
                likeTx.tag = l; likeTx.text = compact(l); comTx.text = compact(c); if (lk) { liked.add(r.id); likeIv.setImageDrawable(PathIcon(Icons.FAVORITE, Ui.RED)) } }
        }
        val gd = GestureDetector(a, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (vy < -400) idx = (idx + 1) % list.size else if (vy > 400) idx = (idx - 1 + list.size) % list.size else return true
                show(); return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if (vv.isPlaying) vv.pause() else vv.start(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val r = list[idx]; if (!liked.contains(r.id)) { liked.add(r.id); net.setLike(r.id, true); show() }
                heart.animate().cancel(); heart.alpha = 1f; heart.scaleX = 0.4f; heart.scaleY = 0.4f
                heart.animate().scaleX(1.2f).scaleY(1.2f).setDuration(250).withEndAction { heart.animate().alpha(0f).setDuration(350).start() }.start(); return true }
        })
        // touches on empty parts of the card (buttons keep their own clicks)
        overlay.setOnTouchListener { _, e -> gd.onTouchEvent(e); true }
        // let the buttons receive clicks first
        overlay.isClickable = false
        show()
    }

    private fun compact(n: Int): String = if (n >= 1000000) "%.1fM".format(n / 1000000f) else if (n >= 1000) "%.1fK".format(n / 1000f) else n.toString()

    private fun repost(r: ReelItem) {
        if (r.url.startsWith("/")) { a.toast("OPEN THE ONLINE REEL TO REPOST"); return }
        net.postReel(r.url, "🔁 " + r.cap.take(120), r.tags) { ok -> a.toast(if (ok) "REPOSTED TO YOUR PROFILE" else "REPOST FAILED. NEEDS INTERNET + LOGIN") }
    }

    private fun comments(r: ReelItem) {
        val dlg = Dialog(a, android.R.style.Theme_Black_NoTitleBar)
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF03100F.toInt()) }
        root.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(8), d(12), d(8), d(8))
            addView(Ui.iconBtn(a, Icons.CLOSE, Color.WHITE, 24, 8) { dlg.dismiss() }); addView(t("Comments", 20f, Color.WHITE, true)) })
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, d(14), 0) }
        root.addView(ScrollView(a).apply { addView(list) }, Screens.lp0(M, 0, 1f))
        fun reload() { net.comments(r.id) { cs -> list.removeAllViews()
            if (cs.isEmpty()) list.addView(t("No comments yet. Be the first!", 14f, Ui.GRAY).apply { setPadding(0, d(20), 0, 0) })
            cs.forEach { c -> list.addView(LinearLayout(a).apply { setPadding(0, d(8), 0, d(8))
                addView(Ui.avatar(a, a, c.from, c.name, "dm", 40)); addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10), 0, 0, 0)
                    addView(t(c.name + "  ·  " + Ui.ago(c.ts), 12f, Ui.GRAY)); addView(t(c.text, 15f)) }) }) } } }
        reload()
        val et = EditText(a).apply { hint = "Add a comment..."; setHintTextColor(Ui.GRAY); setTextColor(Color.WHITE); setSingleLine(); background = Ui.shape(a, 0xFF04191C.toInt(), Ui.TEAL, 30); setPadding(d(16), d(10), d(16), d(10)) }
        root.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(10), d(8), d(10), d(10))
            addView(et, Screens.lp0(0, W, 1f)); addView(Ui.greenCircle(a, Icons.SEND, 46) {
                val x = et.text.toString().trim(); if (x.isNotEmpty()) { et.setText(""); net.addComment(r.id, x) { reload() } } }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = d(8) }) })
        dlg.setContentView(root); dlg.show()
    }

    // ---------- upload ----------
    fun upload() {
        if (!net.cloudReady()) { a.toast("REEL UPLOAD NEEDS CLOUDINARY: FILL CLOUD_NAME + CLOUD_PRESET IN KEYS.KT"); return }
        if (!a.onlineReady() || !a.online()) { a.toast("UPLOAD NEEDS INTERNET + LOGIN"); return }
        a.pick("video/*") { uri ->
            val cap = EditText(a).apply { hint = "Write a caption"; maxLines = 3 }
            val tags = EditText(a).apply { hint = "#Hashtags  #Like  #This"; setSingleLine() }
            val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(20), d(8), d(20), 0); addView(cap); addView(tags) }
            AlertDialog.Builder(a).setTitle("Upload Reel").setView(box).setPositiveButton("UPLOAD") { _, _ ->
                a.toast("UPLOADING... PLEASE WAIT")
                net.upload(uri, true) { url -> if (url != null) net.postReel(url, cap.text.toString().trim(), tags.text.toString().trim()) { ok -> a.toast(if (ok) "REEL POSTED 🎉" else "POST FAILED (CHECK RULES)"); if (ok) { all = emptyList(); s.tab("reels") { home() } } } }
            }.setNegativeButton("CANCEL", null).show()
        }
    }

    // ---------- discover ----------
    fun discover() {
        s.chatRefresh = null
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), d(8), d(14), 0) }
        root.addView(header())
        val q = EditText(a).apply { hint = "Search creators by AS-ID or name"; setHintTextColor(Ui.GRAY); setTextColor(Color.WHITE); setSingleLine(); background = Ui.shape(a, 0xFF04191C.toInt(), Ui.CARD_STROKE, 30); setPadding(d(16), d(10), d(16), d(10))
            filters = arrayOf(android.text.InputFilter.AllCaps()) }
        q.setOnEditorActionListener { _, _, _ -> val id = q.text.toString().trim(); if (id.isNotEmpty()) { s.go("profile:$id") { a.profileUi.profile(id) } }; true }
        root.addView(q, Screens.lp0(M, W).apply { topMargin = d(8); bottomMargin = d(8) })
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(a).apply { addView(col) }, Screens.lp0(M, 0, 1f))
        s.show(root, 13)
        col.addView(t("Trending hashtags", 17f, Color.WHITE, true).apply { setPadding(0, d(8), 0, d(8)) })
        val grid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(t("Latest reels", 17f, Color.WHITE, true).apply { setPadding(0, d(14), 0, d(8)) }); col.addView(grid)
        val tagRow = LinearLayout(a); col.addView(HorizontalScrollView(a).apply { addView(tagRow); isHorizontalScrollBarEnabled = false }, 1)
        load { list ->
            if (s.cur != "discover") return@load
            val tg = list.flatMap { it.tags.split(" ") }.filter { it.startsWith("#") }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(10)
            tg.forEach { e -> tagRow.addView(t(e.key, 14f, Ui.GREEN).apply { background = Ui.shape(a, 0xFF05181A.toInt(), Ui.CARD_STROKE, 20); setPadding(d(14), d(8), d(14), d(8)) }, Screens.lp0(W, W).apply { rightMargin = d(8) }) }
            list.chunked(3).forEachIndexed { ri, row -> grid.addView(LinearLayout(a).apply {
                row.forEachIndexed { ci, r -> addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF0A1C1E.toInt())
                    val th = if (r.thumb.isNotEmpty()) r.thumb else if (r.url.startsWith("http")) net.thumb(r.url) else ""; if (th.isNotEmpty()) Img.load(a, th, this)
                    setOnClickListener { s.go("feed") { feed(ri * 3 + ci) } } }, Screens.lp0(0, d(150), 1f).apply { setMargins(d(2), d(2), d(2), d(2)) }) } }) }
        }
    }
}
