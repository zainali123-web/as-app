package com.asapp

import android.app.AlertDialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*

/** Profile screen (mine and other people's). */
class ProfileUi(private val a: MainActivity, private val s: Screens) {
    private val net get() = a.net
    private fun d(v: Int) = Ui.dp(a, v)
    private fun t(x: CharSequence, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) = Ui.tv(a, x, size, color, bold)
    private val M = Ui.MATCH; private val W = Ui.WRAP

    fun profile(id: String) {
        s.chatRefresh = null
        val me = id == a.myId()
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(8), d(8), d(8), 0)
            addView(Ui.iconBtn(a, Icons.BACK, Color.WHITE, 26, 8) { if (!s.back()) s.openTab(0) })
            addView(Ui.logoBar(a).apply { gravity = Gravity.CENTER }, Screens.lp0(0, W, 1f))
            addView(Ui.iconBtn(a, Icons.MORE, Color.WHITE, 24, 8) { menu(id, me) }) })
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(d(16), d(6), d(16), d(24)) }
        root.addView(ScrollView(a).apply { addView(col); isVerticalScrollBarEnabled = false }, Screens.lp0(M, 0, 1f))
        val name = if (me) net.myName() else net.name(id)
        col.addView(FrameLayout(a).apply {
            addView(Ui.avatar(a, a, id, name, if (me) "me" else "dm", 124).apply { ring = Ui.GREEN; ringDp = 3f; if (!me && bmp == null) textSize0() }, FrameLayout.LayoutParams(d(124), d(124)))
            if (me) addView(Ui.greenCircle(a, Icons.CAMERA_ADD, 38) { a.pickDp { s.refreshProfile() } }, FrameLayout.LayoutParams(d(38), d(38), Gravity.BOTTOM or Gravity.END))
        }, Screens.lp0(d(124), d(124)).apply { topMargin = d(8) })
        col.addView(LinearLayout(a).apply { gravity = Gravity.CENTER; setPadding(0, d(12), 0, 0)
            addView(t(name, 26f, Color.WHITE, true))
            if (me) addView(Ui.iconBtn(a, Icons.EDIT, Ui.GREEN, 20, 6) { a.settingsUi.prompt("Your name", "Nickname", a.prefsPublic().getString("nick", "") ?: "") { v -> a.setNick(v); s.refreshProfile() } }) })
        col.addView(t(if (me) "Online" else id, 15f, if (me) Ui.GREEN else Ui.GRAY).apply { gravity = Gravity.CENTER })
        val posts = t("0", 20f, Color.WHITE, true); val fol = t("0", 20f, Color.WHITE, true); val fing = t(if (me) net.following().size.toString() else "—", 20f, Color.WHITE, true)
        fun stat(v: TextView, l: String) = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; addView(v); addView(t(l, 13f, Ui.GRAY)) }
        col.addView(LinearLayout(a).apply { addView(stat(posts, "Posts"), Screens.lp0(0, W, 1f)); addView(stat(fol, "Followers"), Screens.lp0(0, W, 1f)); addView(stat(fing, "Following"), Screens.lp0(0, W, 1f)) },
            Screens.lp0(M, W).apply { topMargin = d(14); bottomMargin = d(14) })
        net.postsCount(id) { posts.text = it.toString() }; net.followers(id) { fol.text = it.toString() }
        if (!me) net.fetchContact(id) { s.refreshProfile() }

        val bio = if (me) (a.prefsPublic().getString("bio", "") ?: "") else net.bio(id)
        col.addView(row(Icons.INFO, "Bio", bio.ifEmpty { if (me) "Tap to write your bio" else "—" }, "", me) {
            a.settingsUi.prompt("Your bio", "Write something about you", bio) { v -> a.setBio(v); s.refreshProfile() } })
        if (!me) {
            col.addView(LinearLayout(a).apply {
                fun b(label: String, ic: String, f: () -> Unit) = LinearLayout(a).apply { gravity = Gravity.CENTER; background = Ui.shape(a, 0xFF05181A.toInt(), Ui.GREEN, 30); setPadding(d(6), d(12), d(6), d(12))
                    addView(Ui.icon(a, ic, Ui.GREEN, 20)); addView(t(label, 13f, Ui.GREEN, true).apply { setPadding(d(4), 0, 0, 0) }); setOnClickListener { f() } }
                addView(b("Message", Icons.CHAT) { net.openDm(id); s.openChat(id) }, Screens.lp0(0, W, 1f).apply { rightMargin = d(6) })
                addView(b("Voice", Icons.CALL) { net.openDm(id); a.calls.call(id, false) }, Screens.lp0(0, W, 1f).apply { rightMargin = d(6) })
                addView(b("Video", Icons.VIDEOCAM) { net.openDm(id); a.calls.call(id, true) }, Screens.lp0(0, W, 1f)) }, Screens.lp0(M, W).apply { topMargin = d(10) })
            val fl = t(if (net.following().contains(id)) "Following" else "Follow", 16f, 0xFF03241A.toInt(), true).apply {
                gravity = Gravity.CENTER; background = Ui.shape(a, Ui.GREEN, 0, 30); setPadding(0, d(12), 0, d(12))
                setOnClickListener { val on = !net.following().contains(id); net.setFollow(id, on); text = if (on) "Following" else "Follow" } }
            col.addView(fl, Screens.lp0(M, W).apply { topMargin = d(10) })
        }
        val media = if (me) Store.media().size else Store.media().count { it.first.id == id }
        val stars = Store.starred(a.myId()).size
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = Ui.card(a, 14) }
        fun item(ic: String, title: String, cnt: String, f: () -> Unit) {
            box.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(14), d(14), d(12), d(14))
                addView(Ui.icon(a, ic, Ui.GREEN, 24)); addView(t(title, 16f).apply { setPadding(d(14), 0, 0, 0) }, Screens.lp0(0, W, 1f))
                if (cnt.isNotEmpty()) addView(t(cnt, 15f, Ui.GRAY)); addView(Ui.icon(a, Icons.CHEVRON, Ui.GRAY, 22)); setOnClickListener { f() } })
        }
        item(Icons.CAMERA, "Media, Links and Docs", media.toString()) { s.go("media") { mediaPage(if (me) null else id) } }
        item(Icons.FAVORITE, "Starred Messages", stars.toString()) { s.go("starred") { a.settingsUi.starred() } }
        item(Icons.NOTIF_OFF, "Notifications", "") { s.go("settings-n") { a.settingsUi.notifications() } }
        item(Icons.LOCK, "Privacy", "") { privacy() }
        col.addView(box, Screens.lp0(M, W).apply { topMargin = d(14) })
        s.show(root, -1)
    }

    private fun AvatarView.textSize0() {}

    private fun row(ic: String, title: String, sub: String, cnt: String, chevron: Boolean, f: () -> Unit): View = LinearLayout(a).apply {
        gravity = Gravity.CENTER_VERTICAL; background = Ui.card(a, 14); setPadding(d(14), d(12), d(12), d(12))
        addView(LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; addView(t(title, 16f, Color.WHITE, true)); addView(t(sub, 14f, Ui.GRAY)) }, Screens.lp0(0, W, 1f))
        if (chevron) addView(Ui.icon(a, Icons.CHEVRON, Ui.GRAY, 22)); setOnClickListener { f() }
        layoutParams = Screens.lp0(M, W).apply { topMargin = d(4) }
    }

    private fun menu(id: String, me: Boolean) {
        val items = if (me) arrayOf("Share my code", "Settings") else arrayOf(if (net.isBlocked(id)) "Unblock" else "Block", "Share this code")
        AlertDialog.Builder(a).setItems(items) { _, i ->
            if (me) { if (i == 0) a.shareId() else s.openTab(3) }
            else if (i == 0) { net.setBlocked(id, !net.isBlocked(id)); a.toast(if (net.isBlocked(id)) "BLOCKED" else "UNBLOCKED") } else a.shareText("AS CHATS code: $id")
        }.show()
    }

    private fun privacy() {
        val bl = net.blocked().toList()
        val msg = "• Your email is never shown to other people.\n• Chats are end-to-end encrypted.\n• Only your AS-ID, name and picture are visible.\n\nBlocked: " + (if (bl.isEmpty()) "nobody" else bl.joinToString { net.name(it) })
        AlertDialog.Builder(a).setTitle("Privacy").setMessage(msg).setPositiveButton("OK", null)
            .also { b -> if (bl.isNotEmpty()) b.setNeutralButton("UNBLOCK ALL") { _, _ -> bl.forEach { net.setBlocked(it, false) }; a.toast("DONE") } }.show()
    }

    private fun mediaPage(only: String?) {
        val root = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(d(8), d(10), d(8), d(6))
            addView(Ui.iconBtn(a, Icons.BACK, Color.WHITE, 26, 8) { s.back() }); addView(t("Media, Links and Docs", 20f, Color.WHITE, true)) })
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(d(8), 0, d(8), d(20)) }
        val list = Store.media().filter { only == null || it.first.id == only }
        if (list.isEmpty()) col.addView(t("No photos shared yet.", 15f, Ui.GRAY).apply { setPadding(d(10), d(20), 0, 0) })
        list.chunked(3).forEach { rowL -> col.addView(LinearLayout(a).apply {
            rowL.forEach { p -> addView(ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF0A1C1E.toInt()); Img.load(a, p.second.file, this); setOnClickListener { s.imageViewer(p.second.file) } },
                Screens.lp0(0, d(120), 1f).apply { setMargins(d(2), d(2), d(2), d(2)) }) } }) }
        root.addView(ScrollView(a).apply { addView(col) }, Screens.lp0(M, 0, 1f))
        s.show(root, -1)
    }
}
