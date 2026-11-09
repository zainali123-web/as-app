package com.asapp

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject

class SeatState(val member: Boolean, val pos: Int, val waiting: Int)

/**
 * 200 seats. Member = has a seat. More people go to the waiting list (first come, first in).
 * A seat is freed when the member did not open the app for 2 months.
 * Firebase: seats/<ASID>, waiting/<ASID>, presence/<ASID> (last time the app was open).
 */
class Seats(private val a: MainActivity) {
    val CAP = 200
    private val STALE_MS = 60L * 24 * 3600 * 1000      // 2 months
    private val ONLINE_MS = 5L * 60 * 1000
    var members = a.prefsPublic().getInt("seatMembers", 0)
    var online = a.prefsPublic().getInt("seatOnline", 0)
    private var lastCount = 0L
    private var lastClean = 0L
    private var loops = false
    private var waitingTick: Runnable? = null
    private fun me() = a.myId()
    private fun d(v: Int) = Ui.dp(a, v)

    fun usable() = a.onlineReady() && a.online()

    private fun fb(method: String, path: String, body: String? = null): String? {
        val tok = a.token() ?: return null
        val sep = if (path.contains("?")) "&" else "?"
        return a.http(method, "${a.db()}/$path${sep}auth=$tok", body)
    }
    private fun serverTime() = JSONObject().put(".sv", "timestamp").toString()
    private fun keysOf(r: String?): Int = try { if (r == null || r.trim() == "null") 0 else JSONObject(r).length() } catch (e: Exception) { 0 }

    /** does this account have a seat? (done runs on the UI thread, null = could not check) */
    fun check(done: (SeatState?) -> Unit) {
        a.io.execute {
            val st = try { decide() } catch (e: Exception) { a.st("14 SEATS", "FAILED: ${e.message}"); null }
            if (st != null) a.prefsPublic().edit().putBoolean("seatWaiting", !st.member).putInt("seatPos", st.pos).putInt("seatWait", st.waiting).apply()
            a.runOnUiThread { done(st) }
        }
    }

    private fun waitingIds(): List<String>? {
        val r = fb("GET", "waiting.json") ?: return null
        if (r.trim() == "null") return emptyList()
        val o = JSONObject(r); val l = ArrayList<Pair<String, Long>>()
        for (k in o.keys()) l.add(Pair(k, o.optJSONObject(k)?.optLong("ts", Long.MAX_VALUE) ?: Long.MAX_VALUE))
        return l.sortedBy { it.second }.map { it.first }
    }

    /** VIP ids (the owner) are listed by hand in the Firebase console: config/vip/<ASID> = true */
    private fun vipIds(): List<String> {
        val r = fb("GET", "config/vip.json?shallow=true") ?: return emptyList()
        return try { if (r.trim() == "null") emptyList() else JSONObject(r).keys().asSequence().toList() } catch (e: Exception) { emptyList() }
    }

    private fun decide(): SeatState? {
        val id = me()
        val mine = fb("GET", "seats/$id.json") ?: return null
        if (mine.trim() != "null") { beat(); counts(); housekeeping(); a.st("14 SEATS", "OK"); return SeatState(true, 0, 0) }
        val uid = a.prefsPublic().getString("uid", "") ?: ""
        val vips = vipIds()
        val seatBody = JSONObject().put("uid", uid).put("ts", JSONObject().put(".sv", "timestamp")).toString()
        // reserved seat for the owner: always allowed in
        if (vips.contains(id)) {
            if (fb("PUT", "seats/$id.json", seatBody) == null) return null
            fb("DELETE", "waiting/$id.json"); beat(); counts(); a.st("14 SEATS", "OK (reserved seat)")
            return SeatState(true, 0, 0)
        }
        val sr = fb("GET", "seats.json?shallow=true") ?: return null
        val seatIds = try { if (sr.trim() == "null") emptyList() else JSONObject(sr).keys().asSequence().toList() } catch (e: Exception) { emptyList() }
        val n = seatIds.size
        val reservedFree = vips.count { !seatIds.contains(it) }          // seats kept empty for VIPs
        val regularFree = CAP - n - reservedFree
        val wait = waitingIds() ?: return null
        val myPos = wait.indexOf(id)
        val rank = if (myPos >= 0) myPos else wait.size
        if (regularFree > 0 && rank < regularFree) {
            if (fb("PUT", "seats/$id.json", seatBody) == null) return null
            val all = fb("GET", "seats.json") ?: return null
            var ok = false
            try {
                val o = JSONObject(all); val l = ArrayList<Pair<String, Long>>()
                for (k in o.keys()) if (!vips.contains(k)) l.add(Pair(k, o.optJSONObject(k)?.optLong("ts", Long.MAX_VALUE) ?: Long.MAX_VALUE))
                ok = l.sortedBy { it.second }.map { it.first }.indexOf(id) in 0 until (CAP - vips.size)
            } catch (e: Exception) {}
            if (ok) { fb("DELETE", "waiting/$id.json"); beat(); counts(); housekeeping(); a.st("14 SEATS", "OK"); return SeatState(true, 0, 0) }
            fb("DELETE", "seats/$id.json")          // somebody was faster
        }
        if (myPos < 0) fb("PUT", "waiting/$id.json", JSONObject().put("uid", uid).put("ts", JSONObject().put(".sv", "timestamp")).toString())
        val w2 = waitingIds() ?: wait
        val pos = w2.indexOf(id) + 1
        a.st("14 SEATS", "WAITING #$pos")
        return SeatState(false, if (pos <= 0) w2.size + 1 else pos, Math.max(w2.size, 1))
    }

    /** "I am using the app now" */
    private fun beat() { fb("PUT", "presence/${me()}.json", serverTime()) }

    private fun counts() {
        val m = keysOf(fb("GET", "seats.json?shallow=true"))
        val q = java.net.URLEncoder.encode("\"\$value\"", "UTF-8")
        val on = keysOf(fb("GET", "presence.json?orderBy=$q&startAt=${System.currentTimeMillis() - ONLINE_MS}"))
        val changed = m != members || on != online
        members = m; online = on
        a.prefsPublic().edit().putInt("seatMembers", m).putInt("seatOnline", on).apply()
        if (changed) a.runOnUiThread { a.screens.refresh() }
    }

    /** frees the seats of people who did not open the app for 2 months */
    private fun housekeeping() {
        if (System.currentTimeMillis() - lastClean < 30 * 60 * 1000L) return
        lastClean = System.currentTimeMillis()
        val q = java.net.URLEncoder.encode("\"\$value\"", "UTF-8")
        val r = fb("GET", "presence.json?orderBy=$q&endAt=${System.currentTimeMillis() - STALE_MS}") ?: return
        if (r.trim() == "null") return
        try {
            val o = JSONObject(r); var n = 0
            for (k in o.keys()) { if (k == me() || n >= 20) continue
                fb("DELETE", "seats/$k.json"); fb("DELETE", "presence/$k.json"); n++ }
            if (n > 0) a.st("14 SEATS", "freed $n old seat(s)")
        } catch (e: Exception) {}
    }

    /** called when the home screen opens */
    fun refreshCounts() {
        if (!usable() || System.currentTimeMillis() - lastCount < 60000L) return
        lastCount = System.currentTimeMillis()
        a.io.execute { try { counts() } catch (e: Exception) {} }
    }

    fun startLoops() {
        if (loops) return
        loops = true
        a.ui.postDelayed(object : Runnable { override fun run() {
            if (usable() && a.prefsPublic().getBoolean("in", false) && me().isNotEmpty() && !a.prefsPublic().getBoolean("seatWaiting", false))
                a.io.execute { try { beat(); counts(); housekeeping() } catch (e: Exception) {} }
            a.ui.postDelayed(this, 4 * 60 * 1000L) } }, 4 * 60 * 1000L)
    }

    // ---------- screens ----------
    private fun base(): Pair<FrameLayout, LinearLayout> {
        a.window.statusBarColor = Look.BG; a.window.navigationBarColor = Look.BG
        val root = FrameLayout(a); root.addView(GlowBg(a), FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(d(26), d(40), d(26), d(30)) }
        root.addView(ScrollView(a).apply { isFillViewport = true; addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH)) }, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        return Pair(root, col)
    }

    fun showChecking() {
        val (root, col) = base()
        col.addView(ImageView(a).apply { setImageBitmap(Art.head()) }, LinearLayout.LayoutParams(d(90), d(90)))
        col.addView(Ui.tv(a, "Checking your seat...", 18f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, d(16), 0, d(10)) })
        col.addView(ProgressBar(a).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(Ui.GREEN) })
        a.setContentView(root)
    }

    fun showWaiting(st: SeatState?) {
        waitingTick?.let { a.ui.removeCallbacks(it) }
        val (root, col) = base()
        val pos = st?.pos ?: a.prefsPublic().getInt("seatPos", 0)
        val total = st?.waiting ?: a.prefsPublic().getInt("seatWait", 0)
        col.addView(ImageView(a).apply { setImageBitmap(Art.head()) }, LinearLayout.LayoutParams(d(90), d(90)))
        col.addView(Ui.tv(a, "Waiting list", 28f, Color.WHITE, true).apply { gravity = Gravity.CENTER; setPadding(0, d(14), 0, d(4)) })
        col.addView(Ui.tv(a, "You are added to the waiting list.", 15f, 0xFFDCE8E6.toInt()).apply { gravity = Gravity.CENTER })
        col.addView(LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; background = Ui.card(a, 18); setPadding(d(20), d(18), d(20), d(18))
            addView(Ui.tv(a, "Your number", 13f, Ui.GRAY))
            addView(Ui.tv(a, if (pos > 0) "#$pos" else "#?", 54f, Ui.GREEN, true))
            addView(Ui.tv(a, if (total > 0) "$total people are waiting" else "", 13f, Ui.GRAY))
        }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = d(20) })
        col.addView(Ui.tv(a, "AS CHATS has ${CAP} seats. When a member does not open the app for 2 months, the seat is freed and the next person in the waiting list gets in. Open this screen again later to check.", 13f, Ui.GRAY).apply { gravity = Gravity.CENTER; setPadding(0, d(16), 0, d(16)) })
        val status = Ui.tv(a, "", 13f, Color.YELLOW).apply { gravity = Gravity.CENTER }
        col.addView(status)
        fun again() {
            if (!usable()) { status.text = "No internet. Connect and try again."; return }
            status.text = "Checking..."
            check { s2 ->
                if (s2 == null) status.text = "Could not check. Try again."
                else if (s2.member) { waitingTick?.let { a.ui.removeCallbacks(it) }; Dlg.success(a, "You are in!", "A seat is free for you. Welcome to AS CHATS.") { a.enter() } }
                else showWaiting(s2)
            }
        }
        col.addView(Dlg.fillBtn(a, "Check again") { again() }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = d(8) })
        col.addView(Dlg.outlineBtn(a, "Logout") { waitingTick?.let { a.ui.removeCallbacks(it) }; a.logout() }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = d(12) })
        a.setContentView(root)
        waitingTick = object : Runnable { override fun run() { if (usable()) again(); a.ui.postDelayed(this, 60000L) } }
        a.ui.postDelayed(waitingTick!!, 60000L)
    }
}
