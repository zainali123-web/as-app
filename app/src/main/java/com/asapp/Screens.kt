package com.asapp

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import java.io.File

/** All the screens of the new design (home, chat, groups, calls). */
class Screens(val a: MainActivity) {
    val net get() = a.net
    lateinit var host: LinearLayout
    private val stack = ArrayList<Pair<String, () -> Unit>>()
    var cur = ""
    var chatRefresh: (() -> Unit)? = null
    var filter = 0
    var query = ""
    val statusCache = HashMap<String, List<StatusItem>>()

    fun d(v: Int) = Ui.dp(a, v)
    fun t(s: CharSequence, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) = Ui.tv(a, s, size, color, bold)
    fun lp(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt)
    companion object { fun lp0(w: Int, h: Int, wt: Float = 0f) = LinearLayout.LayoutParams(w, h, wt) }
    private val M = Ui.MATCH
    private val W = Ui.WRAP

    // ---------- navigation ----------
    fun go(name: String, f: () -> Unit) { stack.add(Pair(name, f)); cur = name; f() }
    fun tab(name: String, f: () -> Unit) { stack.clear(); go(name, f) }
    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1); val s = stack.last(); cur = s.first; s.second(); return true
    }
    fun refreshProfile() { val top = stack.lastOrNull(); if (top != null && top.first.startsWith("profile")) top.second() }
    fun refresh() {
        if (!::host.isInitialized) return
        when {
            cur.startsWith("chat:") -> chatRefresh?.invoke()
            cur == "home" || cur == "calls" -> stack.lastOrNull()?.second?.invoke()
        }
    }
    fun show(v: View, nav: Int) {
        host.removeAllViews()
        host.addView(v, lp(M, 0, 1f))
        if (nav in 0..3) host.addView(mainNav(nav)) else if (nav >= 10) host.addView(a.reelsUi.nav(nav - 10))
    }
    private fun mainNav(sel: Int): View {
        val items = listOf(Triple("Chats", Icons.CHAT, "home"), Triple("Status", Icons.STATUS, "status"), Triple("Calls", Icons.CALL, "calls"), Triple("Settings", Icons.SETTINGS, "settings"))
        val bar = LinearLayout(a).apply { setBackgroundColor(0xFF020A0B.toInt()); setPadding(0, d(6), 0, d(4)) }
        items.forEachIndexed { i, it ->
            val on = i == sel
            bar.addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                addView(Ui.icon(a, it.second, if (on) Ui.GREEN else Ui.GRAY, 26))
                addView(t(it.first, 12f, if (on) Ui.GREEN else Ui.GRAY))
                addView(View(a).apply { setBackgroundColor(if (on) Ui.GREEN else Color.TRANSPARENT) }, lp(d(54), d(3)).apply { topMargin = d(4) })
                setOnClickListener { openTab(i) }
            }, lp(0, W, 1f))
        }
        return bar
    }
    fun openTab(i: Int) {
        when (i) {
            0 -> tab("home") { home() }
            1 -> tab("status") { a.statusUi.status() }
            2 -> tab("calls") { calls() }
            else -> tab("settings") { a.settingsUi.settings() }
        }
    }

    fun seed() {
        val o = Store.ensure("OFFICIAL", "AS CHATS Official", "official")
        if (Store.messages("OFFICIAL").isEmpty())
            Store.add("OFFICIAL", Msg("welcome", "OFFICIAL", "Welcome to AS CHATS! 👋  Share your AS-ID with friends so they can find, chat and call you.", System.currentTimeMillis(), 3, "t"), false, "Welcome to AS CHATS! 👋")
        Store.ensure("AI", "AI Assistant", "ai")
        if (Store.messages("AI").isEmpty())
            Store.add("AI", Msg("hello", "ai", "Hello! Ask me anything about AS CHATS ✨", System.currentTimeMillis(), 3, "t"), false, "How can I help you today? ✨")
        o.unread = o.unread
    }

    fun openChat(id: String) { go("chat:$id") { chat(id) } }
    fun typeOf(c: Conv) = c.type

    // ---------- header ----------
    private fun header(): View = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(Ui.iconBtn(a, Icons.MENU, Color.WHITE, 26, 8) { menu() })
        addView(Ui.logoBar(a), lp(0, W, 1f))
        addView(Ui.iconBtn(a, Icons.PREMIUM, Ui.GREEN, 28, 6) { a.crownPage() })
        addView(AvatarView(a).apply {
            fill = 0xFF03161A.toInt(); ring = Ui.TEAL; label = "AI"; layoutParams = lp(d(40), d(40))
            setOnClickListener { openChat("AI") }
        })
    }

    private fun menu() {
        val items = arrayOf("🎬  Reels", "📤  Share my code", "⭐  Starred messages", "🐞  Report a problem", "🚪  Logout")
        AlertDialog.Builder(a).setItems(items) { _, i ->
            when (i) {
                0 -> a.reelsUi.open()
                1 -> a.shareId()
                2 -> go("starred") { a.settingsUi.starred() }
                3 -> a.reportProblem()
                else -> a.logout()
            }
        }.show()
    }

    // ---------- HOME (chats) ----------
    fun home() {
        chatRefresh = null
        val root = FrameLayout(a)
        val sv = ScrollView(a).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), d(8), d(14), d(90)) }
        col.addView(header())

        col.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL; background = Ui.card(a, 18); setPadding(d(14), d(12), d(14), d(12))
            addView(Ui.avatar(a, a, a.myId(), "", "me", 54))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, 0, 0)
                addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL
                    addView(t("AS CHATS", 20f, Color.WHITE, true)); addView(Ui.icon(a, Icons.VERIFIED, Ui.GREEN, 20).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = d(6) }) })
                addView(t(if (a.online()) "Online" else "Offline", 15f, if (a.online()) Ui.GREEN else Ui.GRAY))
            }, lp(0, W, 1f))
            addView(Ui.icon(a, Icons.CHEVRON, Ui.GRAY, 26))
            setOnClickListener { go("profile:me") { a.profileUi.profile(a.myId()) } }
        }, lp(M, W).apply { topMargin = d(12) })

        data class Tile(val title: String, val sub: String, val icon: String, val f: () -> Unit)
        val tiles = listOf(
            Tile("Nearby", "Find people", Icons.SEARCH) { a.startNearby(); openChat("NEARBY0") },
            Tile("Search ID", "Find by username", Icons.PERSON_SEARCH) { searchId() },
            Tile("AS AI", "Chat with AI", Icons.MEMORY) { openChat("AI") },
            Tile("Voice Message", "Talk & send", Icons.MIC) { voiceDialog() },
            Tile("Voice Call", "Direct call", Icons.GRAPHIC_EQ) { askId(false) },
            Tile("Video Call", "Face to face", Icons.VIDEOCAM) { askId(true) })
        for (r in 0..1) col.addView(LinearLayout(a).apply {
            for (k in 0..2) { val tl = tiles[r * 3 + k]
                addView(LinearLayout(a).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = Ui.card(a, 16); setPadding(d(4), d(12), d(4), d(12))
                    addView(Ui.icon(a, tl.icon, Ui.GREEN, 30))
                    addView(t(tl.title, 14f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, d(6), 0, 0) })
                    addView(t(tl.sub, 11f, Ui.GRAY).apply { gravity = Gravity.CENTER })
                    setOnClickListener { tl.f() }
                }, lp(0, d(98), 1f).apply { setMargins(d(5), d(6), d(5), d(6)) })
            }
        }, lp(M, W).apply { topMargin = d(8) })

        // CHATS title + filter
        col.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(Ui.icon(a, Icons.CHAT_OUTLINE, Ui.GREEN, 24))
            addView(t("CHATS", 18f, Color.WHITE, true).apply { setPadding(d(14), 0, 0, 0) }, lp(0, W, 1f))
            val names = listOf("All Chats", "Personal", "Groups", "Unread")
            addView(LinearLayout(a).apply {
                gravity = Gravity.CENTER_VERTICAL; background = Ui.shape(a, 0xFF05181A.toInt(), Ui.CARD_STROKE, 30); setPadding(d(14), d(7), d(10), d(7))
                addView(t(names[filter], 14f)); addView(Ui.icon(a, Icons.DOWN, Color.WHITE, 22))
                setOnClickListener { AlertDialog.Builder(a).setItems(names.toTypedArray()) { _, i -> filter = i; refresh() }.show() }
            })
        }, lp(M, W).apply { topMargin = d(14) })
        col.addView(Ui.divider(a), lp(M, d(1)).apply { topMargin = d(10) })

        val list = Store.all().filter {
            when (filter) { 1 -> it.type == "dm" || it.type == "nearby"; 2 -> it.type == "group"; 3 -> it.unread > 0; else -> true }
        }.filter { it.id != "NEARBY0" }
        for (c in list) col.addView(convRow(c))
        // my status row
        col.addView(LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(12), 0, d(8))
            addView(FrameLayout(a).apply {
                addView(AvatarView(a).apply { fill = 0xFF0A2B2E.toInt(); ring = 0xFF1B4C52.toInt(); setIconPath(Icons.FAVORITE, Ui.TEAL) }, FrameLayout.LayoutParams(d(54), d(54)))
            }, lp(d(54), d(54)))
            addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, 0, 0)
                addView(t("My Status", 17f, Color.WHITE, true)); addView(t("Tap to add status", 14f, Ui.GRAY)) }, lp(0, W, 1f))
            addView(Ui.icon(a, Icons.CAMERA, Ui.GREEN, 28))
            setOnClickListener { openTab(1) }
        })
        sv.addView(col)
        root.addView(sv, FrameLayout.LayoutParams(M, M))
        root.addView(Ui.greenCircle(a, Icons.CHAT, 56) { newChatMenu() }.apply {
            layoutParams = FrameLayout.LayoutParams(d(56), d(56), Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, d(18), d(18)) } })
        show(root, 0)
    }

    private fun convRow(c: Conv): View = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(10), 0, d(10))
        addView(Ui.avatar(a, a, c.id, c.name, c.type, 54))
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, d(8), 0)
            addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL
                addView(t(if (c.type == "dm") net.name(c.id) else c.name, 17f, Color.WHITE, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                if (c.type == "official") addView(Ui.icon(a, Icons.VERIFIED, Ui.GREEN, 18).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = d(6) }) })
            addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL
                if (c.lastFromMe && c.last.isNotEmpty()) addView(Ui.tickView(a, c.lastStatus).apply { (layoutParams as? LinearLayout.LayoutParams)?.rightMargin = d(4) })
                addView(t(c.last.ifEmpty { "Tap to chat" }, 14f, Ui.GRAY).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }) })
        }, lp(0, W, 1f))
        addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
            addView(t(if (c.lastTs == 0L) "" else Ui.timeText(c.lastTs), 12f, Ui.GRAY))
            if (c.unread > 0) addView(t(c.unread.toString(), 12f, Color.parseColor("#02261A"), true).apply {
                gravity = Gravity.CENTER; background = Ui.shape(a, Ui.GREEN, 0, 20); minWidth = d(22); minHeight = d(22); setPadding(d(6), d(1), d(6), d(1)) }, lp(W, W).apply { topMargin = d(6) })
        })
        setOnClickListener { openChat(c.id) }
        setOnLongClickListener {
            if (c.type == "official" || c.type == "ai") true else {
                AlertDialog.Builder(a).setTitle(c.name).setItems(arrayOf("Delete this chat")) { _, _ -> Store.remove(c.id) }.show(); true }
        }
    }

    private fun newChatMenu() {
        val items = arrayOf("💬  New chat (enter AS-ID)", "👥  New group", "📡  Nearby chat (no internet)", "🔎  Search ID", "🤖  AS AI")
        AlertDialog.Builder(a).setItems(items) { _, i ->
            when (i) { 0 -> askId(null); 1 -> go("newgroup") { createGroup() }; 2 -> { a.startNearby(); openChat("NEARBY0") }; 3 -> searchId(); else -> openChat("AI") }
        }.show()
    }

    fun askId(call: Boolean?) {
        val box = EditText(a).apply { hint = "AS-XXXXXX"; setSingleLine(); filters = arrayOf(android.text.InputFilter.AllCaps()) }
        AlertDialog.Builder(a).setTitle(when (call) { true -> "VIDEO CALL: ENTER AS-ID"; false -> "VOICE CALL: ENTER AS-ID"; else -> "NEW CHAT: ENTER AS-ID" })
            .setView(FrameLayout(a).apply { setPadding(d(20), d(8), d(20), 0); addView(box) })
            .setPositiveButton("OK") { _, _ ->
                val id = box.text.toString().trim().uppercase()
                if (id.isEmpty()) return@setPositiveButton
                when (call) { null -> { net.openDm(id); openChat(id) }; else -> { net.openDm(id); a.calls.call(id, call) } }
            }.setNegativeButton("CANCEL", null).show()
    }

    fun searchId() {
        val box = EditText(a).apply { hint = "AS-XXXXXX"; setSingleLine(); filters = arrayOf(android.text.InputFilter.AllCaps()) }
        AlertDialog.Builder(a).setTitle("SEARCH AS-ID").setView(FrameLayout(a).apply { setPadding(d(20), d(8), d(20), 0); addView(box) })
            .setPositiveButton("SEARCH") { _, _ ->
                val id = box.text.toString().trim().uppercase()
                if (!a.onlineReady() || !a.online()) { a.toast("NEEDS INTERNET + LOGIN"); return@setPositiveButton }
                a.io.execute {
                    val tok = a.token()
                    val r = if (tok == null) null else a.http("GET", "${a.db()}/profiles/$id.json?auth=$tok")
                    a.runOnUiThread {
                        if (r == null || r.trim() == "null") a.toast("ID NOT FOUND")
                        else try { val o = org.json.JSONObject(r); net.setContact(id, o.optString("name", id), o.optString("dp", "")); go("profile:$id") { a.profileUi.profile(id) } } catch (e: Exception) { a.toast("ID NOT FOUND") }
                    }
                }
            }.setNegativeButton("CANCEL", null).show()
    }

    private fun voiceDialog() {
        val v = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(20), d(12), d(20), d(4)) }
        v.addView(t("Works WITHOUT internet. Tap NEARBY first so phones connect.", 13f, Color.DKGRAY))
        fun holdBtn(label: String, down: () -> Unit, up: () -> Unit) = Button(a).apply {
            text = label; setOnTouchListener { _, e ->
                when (e.action) { MotionEvent.ACTION_DOWN -> down(); MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> up() }; true }
        }
        v.addView(holdBtn("🎙 HOLD: LIVE TALK (WALKIE-TALKIE)", { a.startWalkie() }, { a.stopWalkie() }))
        v.addView(holdBtn("🎤 HOLD: RECORD + SEND VOICE MESSAGE", { a.startVoiceMsg() }, { a.stopVoiceMsg() }))
        v.addView(Button(a).apply { text = "▶ PLAY LAST RECEIVED"; setOnClickListener { a.playLast() } })
        AlertDialog.Builder(a).setTitle("🎤 Voice Message").setView(v).setNegativeButton("CLOSE", null).show()
    }

    // ---------- CHAT ----------
    fun chat(id: String) {
        val realId = if (id == "NEARBY0") "NEARBY" else id
        val conv = if (realId == "NEARBY") Store.ensure("NEARBY", "Nearby Chat", "nearby") else (Store.get(realId) ?: net.openDm(realId))
        val cid = conv.id
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val sub = t("", 13f, Ui.GREEN)
        fun subText(): String = when (conv.type) {
            "group" -> "${conv.members.size} members"
            "ai" -> "AI Assistant"
            "official" -> "Official account"
            "nearby" -> "${a.peerCount()} phones connected"
            else -> if (System.currentTimeMillis() - (net.lastActive[cid] ?: 0L) < 3 * 60000) "Online" else cid
        }
        // header
        val head = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(4), d(8), d(8), d(8)); setBackgroundColor(0xFF020A0B.toInt())
            addView(Ui.iconBtn(a, Icons.BACK, Color.WHITE, 26, 8) { goBack() })
            addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL
                addView(Ui.avatar(a, a, cid, conv.name, conv.type, 44).apply { if (conv.type == "dm") dot = Ui.GREEN })
                addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10), 0, 0, 0)
                    addView(t(if (conv.type == "dm") net.name(cid) else conv.name, 18f, Color.WHITE, true).apply { maxLines = 1 })
                    sub.text = subText(); addView(sub) })
                setOnClickListener { if (conv.type == "dm") go("profile:$cid") { a.profileUi.profile(cid) } else if (conv.type == "group") groupInfo(conv) }
            }, lp(0, W, 1f))
            if (conv.type == "dm") {
                addView(Ui.iconBtn(a, Icons.VIDEOCAM, Ui.GREEN, 26, 8) { a.calls.call(cid, true) })
                addView(Ui.iconBtn(a, Icons.CALL, Ui.GREEN, 24, 8) { a.calls.call(cid, false) })
            }
            addView(Ui.iconBtn(a, Icons.MORE, Color.WHITE, 24, 8) { chatMenu(conv) })
        }
        root.addView(head)
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(10), d(8), d(10), d(8)) }
        val sv = ScrollView(a).apply { addView(list); isVerticalScrollBarEnabled = false }
        root.addView(sv, lp(M, 0, 1f))

        fun rebuild() {
            list.removeAllViews()
            for (m in Store.messages(cid).takeLast(150)) list.addView(bubble(conv, m))
            sv.post { sv.fullScroll(View.FOCUS_DOWN) }
            sub.text = subText()
            net.ackSeen(conv)
        }
        chatRefresh = { if (cur == "chat:$id") rebuild() }
        rebuild()

        // input
        val admin = conv.members.firstOrNull()
        val blockedSend = conv.type == "group" && (conv.perm.getOrElse(0) { '1' } == '0' || conv.perm.getOrElse(2) { '0' } == '1') && admin != a.myId()
        val officialOnly = conv.type == "official"
        if (blockedSend || officialOnly) {
            root.addView(t(if (officialOnly) "This is an official account" else "Only admins can send messages", 14f, Ui.GRAY).apply { gravity = Gravity.CENTER; setPadding(d(10), d(16), d(10), d(16)) })
        } else root.addView(inputBar(conv))
        show(root, -1)
    }

    private fun goBack() { if (!back()) openTab(0) }

    private fun inputBar(conv: Conv): View {
        val bar = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(10), d(8), d(10), d(10)); setBackgroundColor(0xFF020A0B.toInt()) }
        val input = EditText(a).apply {
            hint = "Type a message..."; setHintTextColor(Ui.GRAY); setTextColor(Color.WHITE); textSize = 15f; background = null; maxLines = 4
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val pill = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; background = Ui.shape(a, 0xFF04191C.toInt(), Ui.TEAL, 30); setPadding(d(12), d(4), d(8), d(4))
            addView(Ui.iconBtn(a, Icons.EMOJI, Color.WHITE, 26, 4) { emojiDialog(input) })
            addView(input, lp(0, W, 1f).apply { leftMargin = d(6) })
            addView(Ui.iconBtn(a, Icons.ATTACH, Ui.GRAY, 24, 6) { pickPhoto(conv) })
            addView(Ui.iconBtn(a, Icons.CAMERA, Ui.GRAY, 24, 6) { takePhoto(conv) })
        }
        bar.addView(pill, lp(0, W, 1f))
        val micIcon = ImageView(a).apply { setImageDrawable(PathIcon(Icons.MIC, 0xFF03241A.toInt())) }
        val mic = FrameLayout(a).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1DEFB0.toInt(), 0xFF00D77C.toInt())).apply { shape = android.graphics.drawable.GradientDrawable.OVAL }
            elevation = d(6).toFloat(); addView(micIcon, FrameLayout.LayoutParams(d(26), d(26), Gravity.CENTER))
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { micIcon.setImageDrawable(PathIcon(if (e.isNullOrBlank()) Icons.MIC else Icons.SEND, 0xFF03241A.toInt())) }
            override fun beforeTextChanged(t: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(t: CharSequence?, x: Int, y: Int, z: Int) {}
        })
        var recording = false
        mic.setOnTouchListener { _, e ->
            val hasText = !input.text.isNullOrBlank()
            when (e.action) {
                MotionEvent.ACTION_DOWN -> if (!hasText && conv.type != "ai") { if (a.beginRecording()) { recording = true; a.toast("🎤 RECORDING... RELEASE TO SEND") } }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (recording) { recording = false; val r = a.endRecording(); if (r != null) net.sendVoice(conv, r.first, r.second) }
                    else if (hasText && e.action == MotionEvent.ACTION_UP) { val tx = input.text.toString().trim(); input.setText(""); if (tx.isNotEmpty()) net.sendText(conv, tx) }
                }
            }
            true
        }
        bar.addView(mic, lp(d(50), d(50)).apply { leftMargin = d(8) })
        return bar
    }

    private fun emojiDialog(input: EditText) {
        val es = "😀😃😄😁😆😅😂🤣😊😇🙂😉😍🥰😘😋😎🤩🥳😏😢😭😡🤔😴🤗🙄😬😱👍👎👏🙏💪🤝👋✌️🤞❤️💚💙💜🔥✨🎉💯🌹☀️🌙⭐🍕☕⚽🎁".toList()
        val chars = ArrayList<String>(); var i = 0
        while (i < es.size) { val cp = es[i]; if (Character.isHighSurrogate(cp) && i + 1 < es.size) { chars.add("" + cp + es[i + 1]); i += 2 } else { chars.add("" + cp); i++ } }
        val g = GridView(a).apply { numColumns = 7; adapter = object : BaseAdapter() {
            override fun getCount() = chars.size; override fun getItem(p: Int) = chars[p]; override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, v: View?, parent: android.view.ViewGroup?) = t(chars[p], 26f).apply { gravity = Gravity.CENTER; setPadding(0, d(8), 0, d(8)) } } }
        val dlg = AlertDialog.Builder(a).setView(g).create()
        g.setOnItemClickListener { _, _, p, _ -> input.append(chars[p]); dlg.dismiss() }
        dlg.show()
    }

    private fun pickPhoto(conv: Conv) {
        if (conv.type == "nearby" || conv.type == "ai") { a.toast("PHOTOS CAN BE SENT IN ONLINE CHATS AND GROUPS"); return }
        if (!net.cloudReady()) { a.toast("PHOTOS NEED CLOUDINARY: FILL CLOUD_NAME + CLOUD_PRESET IN KEYS.KT"); return }
        a.pick("image/*") { uri -> a.toast("UPLOADING PHOTO..."); net.upload(uri, false) { url -> if (url != null) net.sendImage(conv, url) } }
    }
    private fun takePhoto(conv: Conv) {
        if (conv.type == "nearby" || conv.type == "ai") { a.toast("PHOTOS CAN BE SENT IN ONLINE CHATS AND GROUPS"); return }
        if (!net.cloudReady()) { a.toast("PHOTOS NEED CLOUDINARY: FILL CLOUD_NAME + CLOUD_PRESET IN KEYS.KT"); return }
        a.capturePhoto { uri -> a.toast("UPLOADING PHOTO..."); net.upload(uri, false) { url -> if (url != null) net.sendImage(conv, url) } }
    }

    private fun colorFor(id: String) = intArrayOf(0xFF4FC3F7.toInt(), 0xFFFFB74D.toInt(), 0xFFBA68C8.toInt(), 0xFF81C784.toInt(), 0xFFF06292.toInt(), 0xFFFFF176.toInt())[Ui.hash(id) % 6]

    private fun bubble(c: Conv, m: Msg): View {
        val me = a.myId()
        if (m.kind == "s") return LinearLayout(a).apply { gravity = Gravity.CENTER; setPadding(0, d(6), 0, d(6))
            addView(t(m.text, 12f, Ui.GRAY).apply { background = Ui.shape(a, 0xFF0A1C1E.toInt(), 0, 12); setPadding(d(12), d(4), d(12), d(4)) }) }
        val mine = m.from == me
        val b = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setPadding(d(12), d(8), d(12), d(6))
            background = Ui.shape(a, if (mine) Ui.SENT else Ui.RECV, if (mine) 0xFF0A7A44.toInt() else 0xFF16383B.toInt(), 16)
        }
        if (c.type == "group" && !mine) b.addView(t(net.name(m.from), 12f, colorFor(m.from), true))
        if (c.type == "nearby" && !mine) b.addView(t(m.from, 12f, colorFor(m.from), true))
        val maxW = (a.resources.displayMetrics.widthPixels * 0.72f).toInt()
        when (m.kind) {
            "v" -> {
                val wave = WaveView(a, Ui.hash(m.id))
                val dur = t(if (m.dur > 0) "0:%02d".format(m.dur) else "▶", 11f, Ui.GRAY)
                b.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL
                    addView(Ui.icon(a, Icons.PLAY, Color.WHITE, 34))
                    addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(6), 0, d(6), 0)
                        addView(wave, lp(d(130), d(26))); addView(dur) })
                    if (!mine) addView(Ui.avatar(a, a, m.from, net.name(m.from), "dm", 34))
                    setOnClickListener {
                        a.playVoice(File(m.file))
                        val an = android.animation.ValueAnimator.ofFloat(0f, 1f).apply { duration = (Math.max(m.dur, 2) * 1000).toLong()
                            addUpdateListener { wave.progress = it.animatedValue as Float; wave.invalidate() }; start() }
                    } })
            }
            "i" -> {
                val iv = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF0A1C1E.toInt())
                    clipToOutline = true; outlineProvider = object : android.view.ViewOutlineProvider() { override fun getOutline(v: View, o: android.graphics.Outline) { o.setRoundRect(0, 0, v.width, v.height, d(12).toFloat()) } }
                    setOnClickListener { imageViewer(m.file) } }
                Img.load(a, m.file, iv); b.addView(iv, lp(d(230), d(170)))
            }
            else -> b.addView(t(m.text, 15f).apply { maxWidth = maxW })
        }
        b.addView(LinearLayout(a).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(t(Ui.clock(m.ts), 11f, 0xFFB7C7C4.toInt()))
            if (mine) addView(Ui.tickView(a, m.status).apply { (layoutParams as? LinearLayout.LayoutParams)?.leftMargin = d(4) ; if (layoutParams == null) layoutParams = lp(W, W).apply { leftMargin = d(4) } })
        })
        b.setOnLongClickListener {
            val star = Store.isStar(m.id)
            AlertDialog.Builder(a).setItems(arrayOf("Copy", if (star) "Remove star" else "Star", "Delete for me")) { _, i ->
                when (i) {
                    0 -> { (a.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("m", m.text)); a.toast("COPIED") }
                    1 -> Store.toggleStar(m.id)
                    else -> Store.deleteMsg(c.id, m.id)
                }
            }.show(); true
        }
        return LinearLayout(a).apply { gravity = if (mine) Gravity.END else Gravity.START; setPadding(0, d(3), 0, d(3)); addView(b, lp(W, W)) }
    }

    fun imageViewer(url: String) {
        val iv = ImageView(a).apply { setBackgroundColor(Color.BLACK); scaleType = ImageView.ScaleType.FIT_CENTER }
        Img.load(a, url, iv)
        val dlg = android.app.Dialog(a, android.R.style.Theme_Black_NoTitleBar_Fullscreen); dlg.setContentView(iv); iv.setOnClickListener { dlg.dismiss() }; dlg.show()
    }

    private fun chatMenu(c: Conv) {
        val items = ArrayList<String>()
        when (c.type) {
            "dm" -> { items.add("View profile"); items.add(if (net.isBlocked(c.id)) "Unblock" else "Block"); items.add("Clear chat"); items.add("Delete chat") }
            "group" -> { items.add("Group info"); items.add("Clear chat"); items.add("Delete chat") }
            "nearby" -> { items.add("Find people (start Nearby)"); items.add("Clear chat") }
            else -> items.add("Clear chat")
        }
        AlertDialog.Builder(a).setItems(items.toTypedArray()) { _, i ->
            when (items[i]) {
                "View profile" -> go("profile:${c.id}") { a.profileUi.profile(c.id) }
                "Group info" -> groupInfo(c)
                "Block" -> { net.setBlocked(c.id, true); a.toast("BLOCKED. THEIR MESSAGES WILL BE IGNORED.") }
                "Unblock" -> { net.setBlocked(c.id, false); a.toast("UNBLOCKED") }
                "Clear chat" -> Store.clearChat(c.id)
                "Delete chat" -> { Store.remove(c.id); goBack() }
                else -> a.startNearby()
            }
        }.show()
    }

    private fun groupInfo(c: Conv) {
        val s = c.members.joinToString("\n") { (if (it == a.myId()) "You" else net.name(it)) + (if (it == c.members.firstOrNull()) "  (admin)" else "") + "\n   $it" }
        AlertDialog.Builder(a).setTitle(c.name + " (" + c.members.size + ")").setMessage(s).setPositiveButton("OK", null).show()
    }

    // ---------- CREATE GROUP ----------
    fun createGroup() {
        val chosen = LinkedHashSet<String>()
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val sv = ScrollView(a); val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(18), d(8), d(18), d(24)) }
        col.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL
            addView(Ui.iconBtn(a, Icons.BACK, Color.WHITE, 26, 8) { goBack() })
            addView(t("Create Group", 22f, Color.WHITE, true).apply { setPadding(d(14), 0, 0, 0) }) })
        col.addView(Ui.avatar(a, a, "", "", "group", 84).apply { layoutParams = lp(d(84), d(84)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = d(14); bottomMargin = d(14) } })
        col.addView(t("Group name", 15f, Color.WHITE, true))
        val name = EditText(a).apply { hint = "Enter group name"; setHintTextColor(Ui.GRAY); setTextColor(Color.WHITE); textSize = 15f; setSingleLine()
            background = Ui.shape(a, 0xFF05181A.toInt(), Ui.CARD_STROKE, 12); setPadding(d(14), d(12), d(14), d(12))
            filters = arrayOf(android.text.InputFilter.LengthFilter(25)) }
        col.addView(name, lp(M, W).apply { topMargin = d(6) })
        val counter = t("0/25", 13f, Ui.GRAY).apply { gravity = Gravity.END }
        name.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { counter.text = "${e?.length ?: 0}/25" }
            override fun beforeTextChanged(t: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(t: CharSequence?, x: Int, y: Int, z: Int) {}
        })
        col.addView(counter)
        col.addView(t("Add members", 15f, Color.WHITE, true).apply { setPadding(0, d(10), 0, d(6)) })
        val members = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        val hs = HorizontalScrollView(a).apply { addView(members); isHorizontalScrollBarEnabled = false }
        fun drawMembers() {
            members.removeAllViews()
            chosen.forEach { id ->
                members.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; background = Ui.shape(a, 0xFF0A1C1E.toInt(), 0, 30); setPadding(d(4), d(4), d(8), d(4))
                    addView(Ui.avatar(a, a, id, net.name(id), "dm", 38)); addView(t("✕", 16f, Color.WHITE).apply { setPadding(d(10), 0, d(4), 0) })
                    setOnClickListener { chosen.remove(id); drawMembers() } }, lp(W, W).apply { rightMargin = d(8) })
            }
            members.addView(Ui.greenCircle(a, Icons.ADD, 44) { pickMembers(chosen) { drawMembers() } }.apply { background = Ui.shape(a, 0xFF05181A.toInt(), Ui.GREEN, 30) ; (getChildAt(0) as ImageView).setImageDrawable(PathIcon(Icons.ADD, Ui.GREEN)) })
        }
        drawMembers(); col.addView(hs)
        col.addView(t("Group settings", 15f, Color.WHITE, true).apply { setPadding(0, d(16), 0, d(6)) })
        val perms = booleanArrayOf(true, true, false)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = Ui.card(a, 12) }
        listOf("Send messages", "Edit group info", "Admin only").forEachIndexed { i, s ->
            box.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(14), d(10), d(10), d(10))
                addView(t(s, 15f), lp(0, W, 1f))
                addView(Switch(a).apply { isChecked = perms[i]; setOnCheckedChangeListener { _, v -> perms[i] = v } }) })
        }
        col.addView(box)
        col.addView(LinearLayout(a).apply { gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF0BE68E.toInt(), 0xFF00F092.toInt())).apply { cornerRadius = d(30).toFloat() }
            addView(Ui.icon(a, Icons.GROUPS, 0xFF03241A.toInt(), 26)); addView(t("Create Group", 18f, 0xFF03241A.toInt(), true).apply { setPadding(d(10), 0, 0, 0) })
            setOnClickListener {
                val nm = name.text.toString().trim()
                if (nm.isEmpty()) { a.toast("ENTER A GROUP NAME"); return@setOnClickListener }
                if (chosen.isEmpty()) { a.toast("ADD AT LEAST ONE MEMBER"); return@setOnClickListener }
                if (!a.onlineReady()) { a.toast("GROUPS NEED LOGIN + INTERNET"); return@setOnClickListener }
                val g = net.createGroup(nm, chosen.toList())
                g.perm = perms.joinToString("") { if (it) "1" else "0" }; Store.update(g)
                stack.removeAt(stack.size - 1); openChat(g.id)
            } }, lp(M, d(54)).apply { topMargin = d(22) })
        sv.addView(col); root.addView(sv, lp(M, M))
        show(root, -1)
    }

    private fun pickMembers(chosen: MutableSet<String>, done: () -> Unit) {
        val ids = ArrayList<String>(); Store.all().filter { it.type == "dm" }.forEach { ids.add(it.id) }
        chosen.forEach { if (!ids.contains(it)) ids.add(it) }
        val v = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(20), d(8), d(20), 0) }
        val boxes = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun addBox(id: String) { boxes.addView(CheckBox(a).apply { text = "${net.name(id)}  ($id)"; isChecked = chosen.contains(id)
            setOnCheckedChangeListener { _, on -> if (on) chosen.add(id) else chosen.remove(id) } }) }
        ids.forEach { addBox(it) }
        v.addView(ScrollView(a).apply { addView(boxes) }, lp(M, d(200)))
        val inp = EditText(a).apply { hint = "Add by AS-ID"; setSingleLine(); filters = arrayOf(android.text.InputFilter.AllCaps()) }
        v.addView(inp)
        v.addView(Button(a).apply { text = "ADD THIS ID"; setOnClickListener {
            val id = inp.text.toString().trim().uppercase()
            if (id.isNotEmpty() && !ids.contains(id)) { ids.add(id); chosen.add(id); net.openDm(id); addBox(id); (boxes.getChildAt(boxes.childCount - 1) as CheckBox).isChecked = true; inp.setText("") } } })
        AlertDialog.Builder(a).setTitle("Add members").setView(v).setPositiveButton("DONE") { _, _ -> done() }.show()
    }

    // ---------- CALLS ----------
    private var callFilter = 0
    fun calls() {
        chatRefresh = null
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), d(8), d(14), 0) }
        root.addView(header())
        root.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL
            addView(Ui.greenCircle(a, Icons.CALL, 40) {}.apply { background = Ui.shape(a, Ui.GREEN, 0, 12); (getChildAt(0) as ImageView).setImageDrawable(PathIcon(Icons.CALL, 0xFF03241A.toInt())) })
            addView(t("Calls", 22f, Color.WHITE, true).apply { setPadding(d(14), 0, 0, 0) }, lp(0, W, 1f))
            addView(Ui.iconBtn(a, Icons.SEARCH, Color.WHITE, 24, 8) { searchId() })
            addView(Ui.iconBtn(a, Icons.MORE, Color.WHITE, 24, 8) { AlertDialog.Builder(a).setItems(arrayOf("Clear call history")) { _, _ -> Store.clearCalls() ; refresh() }.show() })
        }, lp(M, W).apply { topMargin = d(8) })
        val chips = LinearLayout(a)
        listOf("All", "Missed", "Incoming", "Outgoing").forEachIndexed { i, s -> chips.addView(Ui.chip(a, s, callFilter == i) { callFilter = i; refresh() }, lp(W, W).apply { rightMargin = d(8) }) }
        root.addView(HorizontalScrollView(a).apply { addView(chips); isHorizontalScrollBarEnabled = false }, lp(M, W).apply { topMargin = d(10); bottomMargin = d(6) })
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val all = Store.calls().filter { when (callFilter) { 1 -> it.dir == "miss"; 2 -> it.dir == "in"; 3 -> it.dir == "out"; else -> true } }
        if (all.isEmpty()) col.addView(t("No calls yet", 15f, Ui.GRAY).apply { gravity = Gravity.CENTER; setPadding(0, d(40), 0, 0) })
        for (r in all) col.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(10), 0, d(10))
            addView(Ui.avatar(a, a, r.peer, net.name(r.peer), "dm", 52))
            addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, 0, 0)
                addView(t(net.name(r.peer), 17f, Color.WHITE, true))
                addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL
                    addView(Ui.icon(a, when (r.dir) { "in" -> Icons.CALL_RECEIVED; "out" -> Icons.CALL_MADE; else -> Icons.CALL_MISSED }, if (r.dir == "miss") Ui.RED else Ui.GREEN, 16))
                    addView(t((when (r.dir) { "in" -> "Incoming"; "out" -> "Outgoing"; else -> "Missed" }) + " · " + Ui.clock(r.ts) + if (r.video) " · video" else "", 14f, if (r.dir == "miss") Ui.RED else Ui.GRAY).apply { setPadding(d(4), 0, 0, 0) }) }) }, lp(0, W, 1f))
            addView(Ui.iconBtn(a, if (r.video) Icons.VIDEOCAM else Icons.CALL, if (r.dir == "miss") Ui.RED else Ui.GREEN, 26, 8) { a.calls.call(r.peer, r.video) })
        })
        root.addView(ScrollView(a).apply { addView(col); isVerticalScrollBarEnabled = false }, lp(M, 0, 1f))
        show(root, 2)
    }
}
