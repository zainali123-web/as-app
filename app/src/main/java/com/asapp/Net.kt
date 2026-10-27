package com.asapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

class StatusItem(val id: String, val type: String, val text: String, val url: String, val bg: Int, val ts: Long)
class ReelItem(val id: String, val url: String, val owner: String, val name: String, val cap: String, val tags: String, val ts: Long, val official: Boolean = false, val thumb: String = "")
class CommentItem(val id: String, val from: String, val name: String, val text: String, val ts: Long)

/** Messages, contacts, status, reels: everything that talks to Firebase / Cloudinary. */
class Net(private val a: MainActivity) {
    private val cp get() = a.getSharedPreferences("contacts", 0)
    private val bmpCache = HashMap<String, Bitmap?>()
    val lastActive = HashMap<String, Long>()
    private fun me() = a.myId()
    private fun now() = System.currentTimeMillis()
    private fun rid() = java.lang.Long.toHexString(SecureRandom().nextLong()).trimStart('-')

    // ---------------- contacts ----------------
    fun name(id: String): String = cp.getString("n_$id", null)?.takeIf { it.isNotEmpty() } ?: id
    fun dp(id: String): Bitmap? {
        if (bmpCache.containsKey(id)) return bmpCache[id]
        val s = cp.getString("d_$id", "") ?: ""
        val b = if (s.isEmpty()) null else try { val x = android.util.Base64.decode(s, android.util.Base64.DEFAULT); BitmapFactory.decodeByteArray(x, 0, x.size) } catch (e: Exception) { null }
        bmpCache[id] = b; return b
    }
    fun myDp(): Bitmap? {
        val s = a.getSharedPreferences("as", 0).getString("dp", "") ?: ""
        if (s.isEmpty()) return null
        return try { val x = android.util.Base64.decode(s, android.util.Base64.DEFAULT); BitmapFactory.decodeByteArray(x, 0, x.size) } catch (e: Exception) { null }
    }
    fun myName(): String = a.getSharedPreferences("as", 0).getString("nick", "")?.takeIf { it.isNotEmpty() } ?: me()
    fun setContact(id: String, name: String, dp: String) {
        cp.edit().putString("n_$id", name).putString("d_$id", dp).apply(); bmpCache.remove(id)
        Store.get(id)?.let { if (it.type == "dm") Store.rename(id, name) }
    }
    fun fetchContact(id: String, done: () -> Unit = {}) {
        if (!a.onlineReady() || !a.online()) return
        a.io.execute {
            val tok = a.token() ?: return@execute
            val r = a.http("GET", "${a.db()}/profiles/$id.json?auth=$tok") ?: return@execute
            if (r.trim() == "null") return@execute
            try { val o = JSONObject(r); setContact(id, o.optString("name", id), o.optString("dp", "")); cp.edit().putString("b_$id", o.optString("bio", "")).apply(); a.runOnUiThread { done() } } catch (e: Exception) {}
        }
    }
    fun bio(id: String): String = cp.getString("b_$id", "") ?: ""
    fun blocked(): Set<String> = cp.getStringSet("blocked", emptySet()) ?: emptySet()
    fun isBlocked(id: String) = blocked().contains(id)
    fun setBlocked(id: String, on: Boolean) {
        val s = HashSet(blocked()); if (on) s.add(id) else s.remove(id); cp.edit().putStringSet("blocked", s).apply()
    }
    fun following(): Set<String> = cp.getStringSet("following", emptySet()) ?: emptySet()

    // ---------------- sending ----------------
    fun openDm(id: String): Conv {
        val c = Store.ensure(id, name(id), "dm"); fetchContact(id) { a.screens.refresh() }; return c
    }

    private fun newMsg(kind: String, text: String, file: String = "", dur: Int = 0) =
        Msg(rid(), me(), text, now(), 0, kind, file, dur)

    fun sendText(c: Conv, text: String) {
        val m = newMsg("t", text)
        Store.add(c.id, m, true, text)
        deliver(c, m, null)
    }
    fun sendImage(c: Conv, url: String) {
        val m = newMsg("i", "", url)
        Store.add(c.id, m, true, "📷 Photo")
        deliver(c, m, null)
    }
    fun sendVoice(c: Conv, f: File, secs: Int) {
        val dest = File(Store.voiceDir(), "out_${rid()}.3gp"); f.copyTo(dest, true)
        val m = newMsg("v", "", dest.absolutePath, secs)
        Store.add(c.id, m, true, "🎤 Voice message")
        if (c.type == "nearby") { m.status = 1; a.sendVoiceBytes(dest.readBytes()); return }
        deliver(c, m, android.util.Base64.encodeToString(dest.readBytes(), android.util.Base64.NO_WRAP))
    }

    private fun deliver(c: Conv, m: Msg, audio: String?) {
        when (c.type) {
            "ai" -> {
                Store.setStatus(c.id, m.id, 3)
                a.ui.postDelayed({
                    val r = Msg(rid(), "ai", a.aiReply(m.text), now(), 3, "t")
                    Store.add(c.id, r, false, r.text)
                }, 700)
            }
            "official" -> Store.setStatus(c.id, m.id, 1)
            "nearby" -> {
                val ok = a.sendNearbyText(m.text)
                Store.setStatus(c.id, m.id, if (ok) 1 else -1)
            }
            else -> {
                val pkt = JSONObject().put("t", "m").put("id", m.id).put("k", m.kind).put("x", m.text).put("ts", m.ts)
                if (m.kind == "i") pkt.put("u", m.file)
                if (m.kind == "v") pkt.put("d", audio ?: "").put("du", m.dur)
                if (c.type == "group") pkt.put("g", c.id)
                val targets = if (c.type == "group") c.members.filter { it != me() } else listOf(c.id)
                if (!a.onlineReady()) { a.toast("ONLINE CHAT NEEDS LOGIN + FIREBASE KEYS"); Store.setStatus(c.id, m.id, -1); return }
                a.io.execute {
                    var allOk = true; var missing = false
                    for (t in targets) {
                        val r = if (a.online()) a.sendOnline(t, pkt.toString()) else 0
                        if (r == 0) { a.queue(t, pkt.toString(), c.id, m.id); allOk = false } else if (r == -1) missing = true
                    }
                    if (missing && targets.size == 1) { Store.setStatus(c.id, m.id, -1); a.runOnUiThread { a.toast("THIS AS-ID WAS NOT FOUND") } }
                    else if (allOk) Store.setStatus(c.id, m.id, 1)
                }
            }
        }
    }
    fun onQueuedSent(cid: String, mid: String) { if (cid.isNotEmpty() && mid.isNotEmpty()) Store.setStatus(cid, mid, 1) }

    // ---------------- receiving ----------------
    fun onPacket(from: String, s: String) {
        if (isBlocked(from)) return
        lastActive[from] = now()
        val o = try { if (s.trimStart().startsWith("{")) JSONObject(s) else null } catch (e: Exception) { null }
        if (o == null) {   // old plain text
            val c = Store.ensure(from, name(from), "dm")
            Store.add(c.id, Msg(rid(), from, s.take(2000), now(), 3, "t"), false, s.take(60)); fetchContact(from) { a.screens.refresh() }
            return
        }
        when (o.optString("t")) {
            "m" -> {
                val g = o.optString("g"); val cid = if (g.isNotEmpty()) g else from
                Store.ensure(cid, if (g.isNotEmpty()) "GROUP" else name(from), if (g.isNotEmpty()) "group" else "dm")
                if (g.isEmpty()) fetchContact(from) { a.screens.refresh() }
                val kind = o.optString("k", "t"); var file = ""
                val id = o.optString("id", rid())
                if (Store.has(cid, id)) return
                if (kind == "v") {
                    val f = File(Store.voiceDir(), "in_$id.3gp")
                    try { f.writeBytes(android.util.Base64.decode(o.optString("d"), android.util.Base64.DEFAULT)) } catch (e: Exception) {}
                    file = f.absolutePath
                } else if (kind == "i") file = o.optString("u")
                val m = Msg(id, from, o.optString("x"), o.optLong("ts", now()), 0, kind, file, o.optInt("du"))
                val prev = when (kind) { "v" -> "🎤 Voice message"; "i" -> "📷 Photo"; else -> o.optString("x").take(60) }
                Store.add(cid, m, false, prev)
                if (g.isEmpty()) ack(from, id, 2)
                a.runOnUiThread { a.noteNewMessage() }
            }
            "a" -> Store.setStatus(from, o.optString("id"), o.optInt("s"))
            "gi" -> {
                val gid = o.optString("g"); if (gid.isEmpty()) return
                val ms = ArrayList<String>(); val arr = o.optJSONArray("m"); if (arr != null) for (i in 0 until arr.length()) ms.add(arr.getString(i))
                val c = Store.ensure(gid, o.optString("n", "GROUP"), "group", ms)
                c.name = o.optString("n", c.name); c.members = ms; Store.update(c)
                ms.forEach { if (it != me()) fetchContact(it) }
            }
        }
    }
    private fun ack(to: String, id: String, s: Int) {
        a.io.execute { a.sendOnline(to, JSONObject().put("t", "a").put("id", id).put("s", s).toString()) }
    }
    fun ackSeen(c: Conv) {
        if (c.type == "dm") Store.unseenFrom(c.id, me()).forEach { ack(c.id, it.id, 3) }
        Store.markSeenLocal(c.id, me()); Store.markRead(c.id)
    }
    fun onNearbyText(raw: String) {
        val i = raw.indexOf(": "); val who = if (i > 0) raw.substring(0, i) else "NEARBY"; val t = if (i > 0) raw.substring(i + 2) else raw
        val c = Store.ensure("NEARBY", "Nearby Chat", "nearby")
        Store.add(c.id, Msg(rid(), who, t, now(), 3, "t"), false, t.take(60))
        a.runOnUiThread { a.noteNewMessage() }
    }
    fun onNearbyVoice(who: String, f: File) {
        val c = Store.ensure("NEARBY", "Nearby Chat", "nearby")
        Store.add(c.id, Msg(rid(), who, "", now(), 3, "v", f.absolutePath, 0), false, "🎤 Voice message")
    }

    fun createGroup(name: String, members: List<String>): Conv {
        val gid = "G-" + (1..6).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"[SecureRandom().nextInt(32)] }.joinToString("")
        val all = (members + me()).distinct()
        val c = Store.ensure(gid, name, "group", all)
        Store.add(gid, Msg(rid(), "sys", "You created the group \"$name\"", now(), 3, "s"), true, "You created the group")
        val pkt = JSONObject().put("t", "gi").put("g", gid).put("n", name).put("m", JSONArray(all)).toString()
        a.io.execute { all.filter { it != me() }.forEach { t -> if (a.sendOnline(t, pkt) == 0) a.queue(t, pkt, "", "") } }
        return c
    }

    // ---------------- Cloudinary upload ----------------
    fun cloudReady() = Keys.CLOUD_NAME.isNotEmpty() && Keys.CLOUD_PRESET.isNotEmpty()
    /** uploads a picture/video from the phone; done(url or null) runs on the UI thread */
    fun upload(uri: Uri, video: Boolean, done: (String?) -> Unit) {
        if (!cloudReady()) { a.toast("PHOTOS/VIDEOS NEED CLOUDINARY: FILL CLOUD_NAME AND CLOUD_PRESET IN KEYS.KT"); done(null); return }
        a.io.execute {
            var url: String? = null
            try {
                val data: ByteArray = if (video) a.contentResolver.openInputStream(uri)!!.use { it.readBytes() } else {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    a.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
                    var ss = 1; while (o.outWidth / ss > 1600 || o.outHeight / ss > 1600) ss *= 2
                    val o2 = BitmapFactory.Options().apply { inSampleSize = ss }
                    val bmp = a.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o2) }!!
                    val bo = java.io.ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 82, bo); bo.toByteArray()
                }
                if (video && data.size > 45 * 1024 * 1024) { a.runOnUiThread { a.toast("VIDEO IS TOO BIG (MAX 45 MB)") }; a.runOnUiThread { done(null) }; return@execute }
                val b = "----as" + rid()
                val c = URL("https://api.cloudinary.com/v1_1/${Keys.CLOUD_NAME}/${if (video) "video" else "image"}/upload").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 15000; c.readTimeout = 120000
                c.setChunkedStreamingMode(64 * 1024); c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$b")
                c.outputStream.use { out ->
                    out.write("--$b\r\nContent-Disposition: form-data; name=\"upload_preset\"\r\n\r\n${Keys.CLOUD_PRESET}\r\n".toByteArray())
                    out.write("--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f.${if (video) "mp4" else "jpg"}\"\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                    out.write(data); out.write("\r\n--$b--\r\n".toByteArray())
                }
                val code = c.responseCode
                val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.readText() ?: ""
                if (code in 200..299) url = JSONObject(txt).optString("secure_url").ifEmpty { null }
                else a.st("UPLOAD", "FAILED $code ${txt.take(120)}")
            } catch (e: Exception) { a.st("UPLOAD", "FAILED ${e.message}") }
            a.runOnUiThread { if (url == null) a.toast("UPLOAD FAILED. CHECK KEYS.KT AND INTERNET."); done(url) }
        }
    }
    fun thumb(url: String): String = if (url.contains("/video/upload/")) url.replace("/video/upload/", "/video/upload/so_1,w_360,h_640,c_fill/").substringBeforeLast('.') + ".jpg" else url

    // ---------------- small Firebase helpers ----------------
    private fun fb(method: String, path: String, body: String? = null): String? {
        val tok = a.token() ?: return null
        val sep = if (path.contains("?")) "&" else "?"
        return a.http(method, "${a.db()}/$path${sep}auth=$tok", body)
    }
    private fun ready() = a.onlineReady() && a.online()

    // ----- status -----
    fun postStatus(type: String, text: String, url: String, bg: Int, done: (Boolean) -> Unit) {
        if (!ready()) { a.toast("STATUS NEEDS INTERNET"); done(false); return }
        a.io.execute {
            val o = JSONObject().put("type", type).put("text", text).put("url", url).put("bg", bg).put("ts", now())
            val ok = fb("PUT", "status/${me()}/items/${rid()}.json", o.toString()) != null
            a.runOnUiThread { done(ok) }
        }
    }
    fun fetchStatus(id: String, done: (List<StatusItem>) -> Unit) {
        if (!ready()) { done(emptyList()); return }
        a.io.execute {
            val out = ArrayList<StatusItem>()
            val r = fb("GET", "status/$id/items.json")
            try {
                if (r != null && r.trim() != "null") {
                    val o = JSONObject(r)
                    for (k in o.keys()) { val x = o.getJSONObject(k)
                        if (now() - x.optLong("ts") < 24L * 3600 * 1000) out.add(StatusItem(k, x.optString("type"), x.optString("text"), x.optString("url"), x.optInt("bg"), x.optLong("ts"))) }
                }
            } catch (e: Exception) {}
            out.sortBy { it.ts }
            a.runOnUiThread { done(out) }
        }
    }
    fun markViewed(owner: String, sid: String) { if (ready()) a.io.execute { fb("PUT", "status/$owner/views/$sid/${me()}.json", now().toString()) } }
    fun viewCount(owner: String, sid: String, done: (Int) -> Unit) {
        if (!ready()) { done(0); return }
        a.io.execute {
            val r = fb("GET", "status/$owner/views/$sid.json?shallow=true"); var n = 0
            try { if (r != null && r.trim() != "null") n = JSONObject(r).length() } catch (e: Exception) {}
            a.runOnUiThread { done(n) }
        }
    }
    fun deleteStatus(sid: String, done: () -> Unit) { a.io.execute { fb("DELETE", "status/${me()}/items/$sid.json"); a.runOnUiThread { done() } } }

    // ----- reels, likes, comments, follows -----
    fun postReel(url: String, cap: String, tags: String, done: (Boolean) -> Unit) {
        if (!ready()) { done(false); return }
        a.io.execute {
            val o = JSONObject().put("url", url).put("cap", cap).put("tags", tags).put("owner", me()).put("name", myName())
                .put("uid", a.getSharedPreferences("as", 0).getString("uid", "")).put("ts", now())
            val ok = fb("PUT", "reels/${rid()}.json", o.toString()) != null
            a.runOnUiThread { done(ok) }
        }
    }
    fun fetchReels(done: (List<ReelItem>) -> Unit) {
        if (!ready()) { done(emptyList()); return }
        a.io.execute {
            val out = ArrayList<ReelItem>()
            val r = fb("GET", "reels.json?orderBy=%22ts%22&limitToLast=40")
            try {
                if (r != null && r.trim() != "null") { val o = JSONObject(r)
                    for (k in o.keys()) { val x = o.getJSONObject(k)
                        out.add(ReelItem(k, x.optString("url"), x.optString("owner"), x.optString("name"), x.optString("cap"), x.optString("tags"), x.optLong("ts"))) } }
            } catch (e: Exception) {}
            out.sortByDescending { it.ts }
            a.runOnUiThread { done(out) }
        }
    }
    fun deleteReel(id: String, done: () -> Unit) { a.io.execute { fb("DELETE", "reels/$id.json"); a.runOnUiThread { done() } } }
    private fun count(path: String): Int {
        val r = fb("GET", "$path.json?shallow=true") ?: return 0
        return try { if (r.trim() == "null") 0 else JSONObject(r).length() } catch (e: Exception) { 0 }
    }
    fun reelStats(id: String, owner: String, done: (likes: Int, comments: Int, liked: Boolean, followers: Int, following: Boolean) -> Unit) {
        if (!ready()) { done(0, 0, false, 0, false); return }
        a.io.execute {
            val l = count("likes/$id"); val c = count("comments/$id")
            val liked = fb("GET", "likes/$id/${me()}.json")?.trim().let { it != null && it != "null" }
            val f = if (owner.isEmpty()) 0 else count("follows/$owner")
            val fing = following().contains(owner)
            a.runOnUiThread { done(l, c, liked, f, fing) }
        }
    }
    fun addView(id: String) { if (ready()) a.io.execute { fb("PUT", "views/$id.json", "{\".sv\":{\"increment\":1}}") } }
    fun viewsOf(id: String, done: (Int) -> Unit) {
        if (!ready()) { done(0); return }
        a.io.execute { val r = fb("GET", "views/$id.json"); val n = r?.trim()?.toIntOrNull() ?: 0; a.runOnUiThread { done(n) } }
    }
    fun setLike(id: String, on: Boolean) { if (ready()) a.io.execute { if (on) fb("PUT", "likes/$id/${me()}.json", "true") else fb("DELETE", "likes/$id/${me()}.json") } }
    fun setFollow(owner: String, on: Boolean) {
        val s = HashSet(following()); if (on) s.add(owner) else s.remove(owner); cp.edit().putStringSet("following", s).apply()
        if (ready()) a.io.execute { if (on) fb("PUT", "follows/$owner/${me()}.json", "true") else fb("DELETE", "follows/$owner/${me()}.json") }
    }
    fun followers(owner: String, done: (Int) -> Unit) {
        if (!ready()) { done(0); return }
        a.io.execute { val n = count("follows/$owner"); a.runOnUiThread { done(n) } }
    }
    fun postsCount(owner: String, done: (Int) -> Unit) {
        if (!ready()) { done(0); return }
        a.io.execute {
            val r = fb("GET", "reels.json?orderBy=%22owner%22&equalTo=%22$owner%22&shallow=true"); var n = 0
            try { if (r != null && r.trim() != "null") n = JSONObject(r).length() } catch (e: Exception) {}
            a.runOnUiThread { done(n) }
        }
    }
    fun comments(id: String, done: (List<CommentItem>) -> Unit) {
        if (!ready()) { done(emptyList()); return }
        a.io.execute {
            val out = ArrayList<CommentItem>(); val r = fb("GET", "comments/$id.json")
            try { if (r != null && r.trim() != "null") { val o = JSONObject(r)
                for (k in o.keys()) { val x = o.getJSONObject(k); out.add(CommentItem(k, x.optString("from"), x.optString("name"), x.optString("text"), x.optLong("ts"))) } } } catch (e: Exception) {}
            out.sortBy { it.ts }; a.runOnUiThread { done(out) }
        }
    }
    fun addComment(id: String, text: String, done: () -> Unit) {
        if (!ready()) { a.toast("NEEDS INTERNET"); return }
        a.io.execute {
            val o = JSONObject().put("from", me()).put("name", myName()).put("text", text.take(300)).put("ts", now())
                .put("uid", a.getSharedPreferences("as", 0).getString("uid", ""))
            fb("PUT", "comments/$id/${rid()}.json", o.toString()); a.runOnUiThread { done() }
        }
    }
    fun myProfile(done: () -> Unit) { a.io.execute { a.pushProfileNow(); a.runOnUiThread { done() } } }
}
