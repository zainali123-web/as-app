package com.asapp

import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*

/** Settings screen and its pages. */
class SettingsUi(private val a: MainActivity, private val s: Screens) {
    private val net get() = a.net
    private fun d(v: Int) = Ui.dp(a, v)
    private fun t(x: CharSequence, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) = Ui.tv(a, x, size, color, bold)
    private val M = Ui.MATCH; private val W = Ui.WRAP
    private fun p() = a.prefsPublic()

    fun prompt(title: String, hint: String, initial: String, done: (String) -> Unit) {
        val et = EditText(a).apply { this.hint = hint; setText(initial) }
        AlertDialog.Builder(a).setTitle(title).setView(FrameLayout(a).apply { setPadding(d(20), d(8), d(20), 0); addView(et) })
            .setPositiveButton("SAVE") { _, _ -> done(et.text.toString().trim()) }.setNegativeButton("CANCEL", null).show()
    }

    private fun page(title: String, build: (LinearLayout) -> Unit) {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(8), d(10), d(8), d(6))
            addView(Ui.iconBtn(a, Icons.BACK, Color.WHITE, 26, 8) { if (!s.back()) s.openTab(3) }); addView(t(title, 22f, Color.WHITE, true)) })
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), d(6), d(14), d(24)) }
        build(col)
        root.addView(ScrollView(a).apply { addView(col); isVerticalScrollBarEnabled = false }, Screens.lp0(M, 0, 1f))
        s.show(root, -1)
    }

    private fun item(box: LinearLayout, ic: String, title: String, sub: String, f: () -> Unit) {
        box.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(12), d(12), d(10), d(12))
            addView(FrameLayout(a).apply { background = Ui.shape(a, 0xFF03161A.toInt(), Ui.GREEN, 30, 2); addView(Ui.icon(a, ic, Ui.GREEN, 22), FrameLayout.LayoutParams(d(22), d(22), Gravity.CENTER)) }, Screens.lp0(d(44), d(44)))
            addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, 0, 0)
                addView(t(title, 16f, Color.WHITE, true)); if (sub.isNotEmpty()) addView(t(sub, 12.5f, Ui.GRAY)) }, Screens.lp0(0, W, 1f))
            addView(Ui.icon(a, Icons.CHEVRON, Ui.GRAY, 22)); setOnClickListener { f() } })
    }
    private fun switchRow(box: LinearLayout, title: String, sub: String, key: String, def: Boolean, after: (Boolean) -> Unit = {}) {
        box.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(14), d(12), d(12), d(12))
            addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; addView(t(title, 16f, Color.WHITE, true)); if (sub.isNotEmpty()) addView(t(sub, 12.5f, Ui.GRAY)) }, Screens.lp0(0, W, 1f))
            addView(Switch(a).apply { isChecked = p().getBoolean(key, def); setOnCheckedChangeListener { _, v -> p().edit().putBoolean(key, v).apply(); after(v) } }) })
    }
    private fun group(): LinearLayout = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = Ui.card(a, 14); layoutParams = Screens.lp0(M, W).apply { topMargin = d(10) } }

    // ---------- main settings ----------
    fun settings() {
        s.chatRefresh = null
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), d(8), d(14), 0) }
        root.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; addView(Ui.logoBar(a), Screens.lp0(0, W, 1f)) })
        root.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, d(10), 0, d(6))
            addView(t("Settings", 24f, Color.WHITE, true), Screens.lp0(0, W, 1f))
            addView(Ui.iconBtn(a, Icons.SEARCH, Color.WHITE, 24, 8) { quick() }) })
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, d(16)) }
        root.addView(ScrollView(a).apply { addView(col); isVerticalScrollBarEnabled = false }, Screens.lp0(M, 0, 1f))
        val bio = (p().getString("bio", "") ?: "").ifEmpty { "Better Days Ahead..." }
        col.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; background = Ui.card(a, 14); setPadding(d(12), d(12), d(12), d(12))
            addView(Ui.avatar(a, a, a.myId(), "", "me", 56))
            addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12), 0, 0, 0)
                addView(t(net.myName(), 18f, Color.WHITE, true)); addView(t(bio + "  💚", 14f, Ui.GRAY).apply { maxLines = 1 }) }, Screens.lp0(0, W, 1f))
            addView(Ui.iconBtn(a, Icons.DIALPAD, Color.WHITE, 26, 8) { a.shareId() })
            setOnClickListener { s.go("profile:me") { a.profileUi.profile(a.myId()) } } })
        val box = group()
        item(box, Icons.PERSON, "Account", "Name, picture, email, password") { s.go("set-account") { account() } }
        item(box, Icons.CHAT_OUTLINE, "Chats", "Theme, background, chat history") { s.go("set-chats") { chatsPage() } }
        item(box, Icons.NOTIF_OFF, "Notifications", "Voice announcements & sounds") { s.go("set-n") { notifications() } }
        item(box, Icons.STATUS, "Storage and Data", "Reels saving, network usage") { s.go("set-storage") { storage() } }
        item(box, Icons.INFO, "Help", "AI help, report a problem, privacy") { s.go("set-help") { help() } }
        item(box, Icons.GROUP_ADD, "Invite a Friend", "") { a.shareText("Chat with me on AS CHATS! My code: ${a.myId()}") }
        col.addView(box)
        col.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(16), d(16), d(16), d(16))
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF0A3A28.toInt(), 0xFF04191C.toInt())).apply { cornerRadius = d(16).toFloat(); setStroke(d(1), Ui.CARD_STROKE) }
            addView(android.widget.ImageView(a).apply { setImageBitmap(Art.head()) }, Screens.lp0(d(54), d(54)))
            addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(12), 0, 0, 0)
                addView(Ui.brand(a, 26f)); addView(t("SMART  •  FAST  •  AI POWERED", 11f, Color.WHITE).apply { letterSpacing = 0.1f }); addView(t("v" + a.versionName(), 11f, Ui.GRAY)) }) },
            Screens.lp0(M, W).apply { topMargin = d(14) })
        s.show(root, 3)
    }

    private fun quick() {
        val names = arrayOf("Account", "Chats", "Notifications", "Storage and Data", "Help")
        AlertDialog.Builder(a).setItems(names) { _, i -> when (i) { 0 -> s.go("set-account") { account() }; 1 -> s.go("set-chats") { chatsPage() }; 2 -> s.go("set-n") { notifications() }; 3 -> s.go("set-storage") { storage() }; else -> s.go("set-help") { help() } } }.show()
    }

    // ---------- pages ----------
    private fun account(): Unit = page("Account") { c ->
        val box = group(); c.addView(box)
        item(box, Icons.EDIT, "Name", net.myName()) { prompt("Your name", "Nickname", p().getString("nick", "") ?: "") { v -> a.setNick(v); s.go("set-account") { account() } } }
        item(box, Icons.CAMERA_ADD, "Profile picture", "Choose from gallery") { a.pickDp { s.go("set-account") { account() } } }
        item(box, Icons.INFO, "Bio", (p().getString("bio", "") ?: "").ifEmpty { "Write something about you" }) { prompt("Your bio", "Bio", p().getString("bio", "") ?: "") { v -> a.setBio(v); s.go("set-account") { account() } } }
        item(box, Icons.EMAIL, "Email", p().getString("email", "") ?: "") { a.toast("YOUR EMAIL STAYS HIDDEN FROM OTHERS") }
        item(box, Icons.DIALPAD, "My code", a.myId()) { a.shareId() }
        item(box, Icons.LOCK, "Reset password", "Send reset email") {
            val e = p().getString("email", "") ?: ""
            if (e.isEmpty() || !a.online()) { a.toast("NEEDS LOGIN + INTERNET"); return@item }
            a.io.execute { val r = AuthApi.sendReset(e); a.runOnUiThread { a.toast(if (r.ok) "RESET EMAIL SENT TO $e" else AuthApi.friendly(r.err)) } } }
        item(box, Icons.PREMIUM, "Unlock / trial", "$5 unlock code") { a.crownPage() }
        item(box, Icons.CALL_END, "Logout", "") { a.logout() }
    }

    private fun chatsPage(): Unit = page("Chats") { c ->
        val box = group(); c.addView(box)
        switchRow(box, "Auto background colour", "Changes softly every 15 seconds", "bgauto", true) { a.applyGlow() }
        c.addView(t("Background colour (60)", 14f, Ui.GRAY).apply { setPadding(d(4), d(14), 0, d(6)) })
        c.addView(a.colorRowPublic("bgfix") { a.glowColor(it) })
        val box2 = group(); c.addView(box2)
        item(box2, Icons.DELETE, "Clear all chats", "Deletes chats on this phone") {
            AlertDialog.Builder(a).setMessage("Delete ALL chats on this phone?").setPositiveButton("DELETE") { _, _ -> Store.clearAll(); s.seed(); a.toast("DONE") }.setNegativeButton("CANCEL", null).show() }
        item(box2, Icons.PERSON, "Blocked contacts", "${net.blocked().size} blocked") {
            val bl = net.blocked().toList()
            if (bl.isEmpty()) a.toast("NO BLOCKED CONTACTS") else AlertDialog.Builder(a).setItems(bl.map { net.name(it) + " ($it)" }.toTypedArray()) { _, i -> net.setBlocked(bl[i], false); a.toast("UNBLOCKED") }.setTitle("Tap to unblock").show() }
    }

    fun notifications(): Unit = page("Notifications") { c ->
        val box = group(); c.addView(box)
        switchRow(box, "Voice announcements", "\"New message\", \"Chatting mode is activated\"...", "voice", true) { if (!it) a.stopTts() }
    }

    private fun storage(): Unit = page("Storage and Data") { c ->
        val box = group(); c.addView(box)
        item(box, Icons.GLOBE, "Reels language", a.langName()) { a.nextLang(); s.go("set-storage") { storage() } }
        switchRow(box, "Save reels on Wi-Fi only", "Saves reels for offline watching", "wifionly", true)
        c.addView(t("Saved reels: ${a.reelsSizeMb()} MB / 8 GB", 13f, Ui.GRAY).apply { setPadding(d(4), d(12), 0, d(6)) })
        val box2 = group(); c.addView(box2)
        item(box2, Icons.DELETE, "Clear saved reels", "") { a.clearReels(); s.go("set-storage") { storage() } }
    }

    private fun help(): Unit = page("Help") { c ->
        val box = group(); c.addView(box)
        item(box, Icons.MEMORY, "AS AI", "Ask how the app works") { s.openChat("AI") }
        item(box, Icons.INFO, "Report a problem", "Send details to the AS CHATS team") { a.reportProblem() }
        item(box, Icons.SETTINGS, "Diagnostics", "Status of every part of the app") { a.showDiagnostics() }
        item(box, Icons.LOCK, "Privacy policy", "How your data is used") {
            AlertDialog.Builder(a).setTitle("Privacy").setMessage("• We ask for your email only to create your account.\n• Your email is not shown to others.\n• Chat messages are end-to-end encrypted.\n• Photos, videos and statuses you post are stored on our free cloud servers.\n• You can delete your chats any time.").setPositiveButton("OK", null).show() }
    }

    // ---------- starred ----------
    fun starred() {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(8), d(10), d(8), d(6))
            addView(Ui.iconBtn(a, Icons.BACK, Color.WHITE, 26, 8) { if (!s.back()) s.openTab(0) }); addView(t("Starred Messages", 20f, Color.WHITE, true)) })
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(14), 0, d(14), d(20)) }
        val l = Store.starred(a.myId())
        if (l.isEmpty()) col.addView(t("Long-press a message and choose Star.", 15f, Ui.GRAY).apply { setPadding(0, d(20), 0, 0) })
        l.forEach { (c, m) -> col.addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = Ui.card(a, 12); setPadding(d(12), d(10), d(12), d(10))
            addView(t(if (c.type == "dm") net.name(c.id) else c.name, 13f, Ui.GREEN, true)); addView(t(if (m.kind == "t") m.text else if (m.kind == "v") "🎤 Voice message" else "📷 Photo", 15f))
            addView(t(Ui.clock(m.ts), 11f, Ui.GRAY)); setOnClickListener { s.openChat(c.id) } }, Screens.lp0(M, W).apply { topMargin = d(8) }) }
        root.addView(ScrollView(a).apply { addView(col) }, Screens.lp0(M, 0, 1f))
        s.show(root, -1)
    }
}
