package com.asapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One message. status (my own messages): 0 waiting, 1 sent, 2 delivered, 3 seen */
class Msg(
    val id: String, val from: String, val text: String, val ts: Long,
    var status: Int = 0, val kind: String = "t", val file: String = "", val dur: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("from", from).put("text", text).put("ts", ts)
        .put("st", status).put("k", kind).put("f", file).put("du", dur)
    companion object {
        fun from(o: JSONObject) = Msg(o.getString("id"), o.getString("from"), o.optString("text"), o.optLong("ts"),
            o.optInt("st"), o.optString("k", "t"), o.optString("f"), o.optInt("du"))
    }
}

/** One chat in the list. type: dm, group, ai, official, nearby */
class Conv(
    val id: String, var name: String, val type: String,
    var last: String = "", var lastTs: Long = 0L, var unread: Int = 0,
    var members: List<String> = emptyList(), var lastFromMe: Boolean = false, var lastStatus: Int = 0, var seenAt: Long = 0L,
    var perm: String = "110"
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("type", type).put("last", last)
        .put("lastTs", lastTs).put("unread", unread).put("members", JSONArray(members))
        .put("lfm", lastFromMe).put("ls", lastStatus).put("seen", seenAt).put("perm", perm)
    companion object {
        fun from(o: JSONObject): Conv {
            val m = ArrayList<String>(); val a = o.optJSONArray("members")
            if (a != null) for (i in 0 until a.length()) m.add(a.getString(i))
            return Conv(o.getString("id"), o.optString("name"), o.optString("type", "dm"), o.optString("last"),
                o.optLong("lastTs"), o.optInt("unread"), m, o.optBoolean("lfm"), o.optInt("ls"), o.optLong("seen"), o.optString("perm", "110"))
        }
    }
}

class CallRec(val peer: String, val dir: String, val ts: Long, val video: Boolean)

/** Everything the app remembers about chats, saved on the phone. */
object Store {
    private lateinit var ctx: Context
    private val convs = LinkedHashMap<String, Conv>()
    private val cache = HashMap<String, ArrayList<Msg>>()
    private val calls = ArrayList<CallRec>()
    private var loaded = false
    var listener: (() -> Unit)? = null

    fun init(c: Context) {
        if (loaded) return
        ctx = c.applicationContext
        try {
            val a = JSONArray(ctx.getSharedPreferences("chats", 0).getString("convs", "[]"))
            for (i in 0 until a.length()) { val c2 = Conv.from(a.getJSONObject(i)); convs[c2.id] = c2 }
            val b = JSONArray(ctx.getSharedPreferences("chats", 0).getString("calls", "[]"))
            for (i in 0 until b.length()) { val o = b.getJSONObject(i); calls.add(CallRec(o.getString("p"), o.getString("d"), o.getLong("t"), o.optBoolean("v"))) }
        } catch (e: Exception) {}
        try { starSet.addAll(ctx.getSharedPreferences("chats", 0).getStringSet("stars", emptySet())!!) } catch (e: Exception) {}
        loaded = true
    }

    private fun dir() = File(ctx.filesDir, "chats").apply { mkdirs() }
    private fun file(id: String) = File(dir(), id.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json")
    fun voiceDir() = File(ctx.filesDir, "voice").apply { mkdirs() }

    @Synchronized private fun saveConvs() {
        val a = JSONArray(); convs.values.forEach { a.put(it.toJson()) }
        ctx.getSharedPreferences("chats", 0).edit().putString("convs", a.toString()).apply()
    }
    @Synchronized private fun saveMsgs(id: String) {
        val a = JSONArray(); (cache[id] ?: return).forEach { a.put(it.toJson()) }
        file(id).writeText(a.toString())
    }
    private fun changed() { listener?.let { l -> android.os.Handler(android.os.Looper.getMainLooper()).post { l() } } }

    @Synchronized fun all(): List<Conv> = convs.values.sortedByDescending { it.lastTs }
    @Synchronized fun get(id: String): Conv? = convs[id]
    @Synchronized fun ensure(id: String, name: String, type: String, members: List<String> = emptyList()): Conv {
        val c = convs[id] ?: Conv(id, name, type, members = members).also { convs[id] = it; saveConvs() }
        return c
    }
    @Synchronized fun update(c: Conv) { convs[c.id] = c; saveConvs(); changed() }
    @Synchronized fun rename(id: String, name: String) { convs[id]?.let { it.name = name; saveConvs(); changed() } }
    @Synchronized fun remove(id: String) { convs.remove(id); cache.remove(id); file(id).delete(); saveConvs(); changed() }

    @Synchronized fun messages(id: String): List<Msg> {
        val l = cache.getOrPut(id) {
            val out = ArrayList<Msg>()
            try { val f = file(id); if (f.exists()) { val a = JSONArray(f.readText()); for (i in 0 until a.length()) out.add(Msg.from(a.getJSONObject(i))) } } catch (e: Exception) {}
            out
        }
        return ArrayList(l)
    }

    /** add a message. mine = true if I wrote it. */
    @Synchronized fun add(convId: String, m: Msg, mine: Boolean, preview: String) {
        val c = convs[convId] ?: return
        val l = cache.getOrPut(convId) { ArrayList(messages(convId)) }
        if (l.any { it.id == m.id }) return
        l.add(m); if (l.size > 400) l.removeAt(0)
        c.last = preview; c.lastTs = m.ts; c.lastFromMe = mine; c.lastStatus = m.status
        if (!mine) c.unread++
        saveMsgs(convId); saveConvs(); changed()
    }
    @Synchronized fun setStatus(convId: String, msgId: String, s: Int) {
        val l = cache.getOrPut(convId) { ArrayList(messages(convId)) }
        val m = l.firstOrNull { it.id == msgId } ?: return
        if (s != -1 && s <= m.status) return
        m.status = s
        convs[convId]?.let { c -> if (l.lastOrNull()?.id == msgId) c.lastStatus = s }
        saveMsgs(convId); saveConvs(); changed()
    }
    @Synchronized fun has(convId: String, msgId: String) = messages(convId).any { it.id == msgId }
    @Synchronized fun starred(me: String): List<Pair<Conv, Msg>> {
        val out = ArrayList<Pair<Conv, Msg>>()
        for (c in convs.values) for (m in messages(c.id)) if (starSet.contains(m.id)) out.add(Pair(c, m))
        return out
    }
    private val starSet = HashSet<String>()
    fun isStar(id: String) = starSet.contains(id)
    fun toggleStar(id: String) {
        if (!starSet.remove(id)) starSet.add(id)
        ctx.getSharedPreferences("chats", 0).edit().putStringSet("stars", HashSet(starSet)).apply()
    }
    @Synchronized fun media(): List<Pair<Conv, Msg>> {
        val out = ArrayList<Pair<Conv, Msg>>()
        for (c in convs.values) for (m in messages(c.id)) if (m.kind == "i") out.add(Pair(c, m))
        return out
    }
    @Synchronized fun deleteMsg(convId: String, id: String) {
        val l = cache.getOrPut(convId) { ArrayList(messages(convId)) }
        l.removeAll { it.id == id }; saveMsgs(convId); changed()
    }
    @Synchronized fun clearChat(convId: String) {
        cache[convId] = ArrayList(); saveMsgs(convId)
        convs[convId]?.let { it.last = ""; it.unread = 0; saveConvs() }; changed()
    }
    @Synchronized fun markRead(convId: String) { convs[convId]?.let { if (it.unread != 0) { it.unread = 0; saveConvs(); changed() } } }
    @Synchronized fun unseenFrom(convId: String, me: String): List<Msg> = messages(convId).filter { it.from != me && it.from != "sys" && it.status < 3 }
    @Synchronized fun markSeenLocal(convId: String, me: String) {
        val l = cache.getOrPut(convId) { ArrayList(messages(convId)) }
        var ch = false
        l.forEach { if (it.from != me && it.status < 3) { it.status = 3; ch = true } }
        if (ch) saveMsgs(convId)
    }

    // ---- call history ----
    @Synchronized fun addCall(peer: String, dir: String, video: Boolean) {
        calls.add(0, CallRec(peer, dir, System.currentTimeMillis(), video)); while (calls.size > 100) calls.removeAt(calls.size - 1)
        val a = JSONArray(); calls.forEach { a.put(JSONObject().put("p", it.peer).put("d", it.dir).put("t", it.ts).put("v", it.video)) }
        ctx.getSharedPreferences("chats", 0).edit().putString("calls", a.toString()).apply(); changed()
    }
    @Synchronized fun calls(): List<CallRec> = ArrayList(calls)
    @Synchronized fun clearCalls() { calls.clear(); ctx.getSharedPreferences("chats", 0).edit().putString("calls", "[]").apply(); changed() }

    @Synchronized fun clearAll() {
        convs.clear(); cache.clear(); calls.clear()
        dir().listFiles()?.forEach { it.delete() }
        ctx.getSharedPreferences("chats", 0).edit().clear().apply()
    }
}
