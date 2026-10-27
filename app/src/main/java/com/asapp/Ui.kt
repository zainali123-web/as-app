package com.asapp

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A round picture: photo, letter, or icon. */
class AvatarView(c: Context) : View(c) {
    var bmp: Bitmap? = null
    var label = ""
    var icon: String? = null
    var fill = 0xFF0B2A2A.toInt()
    var ring = 0
    var ringDp = 2f
    var dot = 0
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var iconD: PathIcon? = null
    fun setIconPath(path: String?, color: Int) { icon = path; iconD = if (path == null) null else PathIcon(path, color); invalidate() }
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = Math.min(w, h) / 2f
        val rw = if (ring != 0) Look.dp(context, ringDp.toInt().coerceAtLeast(1)).toFloat() else 0f
        p.style = Paint.Style.FILL; p.shader = null; p.color = fill
        cv.drawCircle(w / 2, h / 2, r - rw, p)
        val b = bmp
        if (b != null) {
            val sh = BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            val m = Matrix(); val d = (r - rw) * 2; val sc = Math.max(d / b.width, d / b.height)
            m.setScale(sc, sc); m.postTranslate(w / 2 - b.width * sc / 2, h / 2 - b.height * sc / 2); sh.setLocalMatrix(m)
            p.shader = sh; cv.drawCircle(w / 2, h / 2, r - rw, p); p.shader = null
        } else if (iconD != null) {
            val s = ((r - rw) * 1.1f).toInt(); iconD!!.setBounds((w / 2 - s / 2).toInt(), (h / 2 - s / 2).toInt(), (w / 2 + s / 2).toInt(), (h / 2 + s / 2).toInt()); iconD!!.draw(cv)
        } else if (label.isNotEmpty()) {
            p.color = Color.WHITE; p.textSize = r * 0.95f; p.typeface = Typeface.DEFAULT_BOLD; p.textAlign = Paint.Align.CENTER
            cv.drawText(label.take(2).uppercase(), w / 2, h / 2 - (p.descent() + p.ascent()) / 2, p)
        }
        if (ring != 0) { p.style = Paint.Style.STROKE; p.strokeWidth = rw; p.color = ring; cv.drawCircle(w / 2, h / 2, r - rw / 2, p) }
        if (dot != 0) { p.style = Paint.Style.FILL; p.color = Color.BLACK; cv.drawCircle(w * 0.82f, h * 0.82f, r * 0.24f, p); p.color = dot; cv.drawCircle(w * 0.82f, h * 0.82f, r * 0.17f, p) }
    }
}

/** bars that look like a voice wave; first part is green */
class WaveView(c: Context, private val seed: Int) : View(c) {
    var progress = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        val n = 26; val gap = width / n.toFloat(); val rnd = java.util.Random(seed.toLong())
        p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = gap * 0.45f
        for (i in 0 until n) {
            val hh = height * (0.25f + 0.7f * rnd.nextFloat()); val x = gap * i + gap / 2
            p.color = if (i / n.toFloat() < progress) Look.GREEN else 0xFF8A9A99.toInt()
            cv.drawLine(x, (height - hh) / 2, x, (height + hh) / 2, p)
        }
    }
}

object Ui {
    val GREEN = 0xFF00E676.toInt()
    val TEAL = 0xFF14E8C0.toInt()
    val CARD = 0xFF04191C.toInt()
    val CARD_STROKE = 0xFF0C4A42.toInt()
    val SENT = 0xFF05562E.toInt()
    val RECV = 0xFF0E2427.toInt()
    val GRAY = 0xFF9FB3B0.toInt()
    val RED = 0xFFFA0921.toInt()
    val BLUE = 0xFF29B6F6.toInt()
    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    fun dp(c: Context, v: Int) = Look.dp(c, v)
    fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

    fun tv(c: Context, s: CharSequence, size: Float = 14f, color: Int = Color.WHITE, bold: Boolean = false) = TextView(c).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    fun shape(c: Context, fill: Int, stroke: Int = 0, r: Int = 16, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(c, r).toFloat(); if (stroke != 0) setStroke(dp(c, strokeDp), stroke)
    }
    fun card(c: Context, r: Int = 16) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF072A27.toInt(), 0xFF041719.toInt())).apply {
        cornerRadius = dp(c, r).toFloat(); setStroke(dp(c, 1), CARD_STROKE)
    }
    fun icon(c: Context, path: String, color: Int, size: Int) = ImageView(c).apply {
        setImageDrawable(PathIcon(path, color)); layoutParams = LinearLayout.LayoutParams(dp(c, size), dp(c, size))
    }
    fun iconBtn(c: Context, path: String, color: Int, size: Int = 24, pad: Int = 8, f: () -> Unit) = ImageView(c).apply {
        setImageDrawable(PathIcon(path, color)); setPadding(dp(c, pad), dp(c, pad), dp(c, pad), dp(c, pad))
        layoutParams = LinearLayout.LayoutParams(dp(c, size + pad * 2), dp(c, size + pad * 2)); setOnClickListener { f() }
    }
    fun brand(c: Context, size: Float = 26f): TextView = TextView(c).apply {
        text = Look.brand(); textSize = size; typeface = Typeface.DEFAULT_BOLD
    }
    fun logoBar(c: Context): LinearLayout = LinearLayout(c).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(c).apply { setImageBitmap(Art.head()) }, lp(dp(c, 44), dp(c, 44)))
        addView(LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(c, 8), 0, 0, 0)
            addView(brand(c, 24f)); addView(tv(c, "SMART  •  FAST  •  AI POWERED", 8.5f, 0xFFD6E4E1.toInt()).apply { letterSpacing = 0.12f })
        })
    }
    fun chip(c: Context, text: String, sel: Boolean, f: () -> Unit) = TextView(c).apply {
        this.text = text; textSize = 13f; gravity = Gravity.CENTER; setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8))
        setTextColor(if (sel) Color.parseColor("#02261A") else Color.WHITE); if (sel) typeface = Typeface.DEFAULT_BOLD
        background = if (sel) GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF0BE68E.toInt(), 0xFF3AFBA3.toInt())).apply { cornerRadius = dp(c, 40).toFloat() }
        else shape(c, 0xFF05181A.toInt(), CARD_STROKE, 40)
        setOnClickListener { f() }
    }
    fun greenCircle(c: Context, iconPath: String, size: Int = 52, f: () -> Unit) = FrameLayout(c).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1DEFB0.toInt(), 0xFF00D77C.toInt())).apply { shape = GradientDrawable.OVAL }
        elevation = dp(c, 6).toFloat()
        addView(ImageView(c).apply { setImageDrawable(PathIcon(iconPath, 0xFF03241A.toInt())) },
            FrameLayout.LayoutParams(dp(c, size * 5 / 11), dp(c, size * 5 / 11), Gravity.CENTER))
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(dp(c, size), dp(c, size))
    }
    fun hash(s: String): Int { var h = 7; for (ch in s) h = h * 31 + ch.code; return Math.abs(h) }
    private val avColors = intArrayOf(0xFF1B5E20.toInt(), 0xFF00695C.toInt(), 0xFF01579B.toInt(), 0xFF4A148C.toInt(), 0xFF880E4F.toInt(), 0xFFE65100.toInt(), 0xFF37474F.toInt(), 0xFF3E2723.toInt())

    fun avatar(c: Context, a: MainActivity, id: String, name: String, type: String, size: Int): AvatarView = AvatarView(c).apply {
        layoutParams = LinearLayout.LayoutParams(dp(c, size), dp(c, size))
        when (type) {
            "official" -> { fill = 0xFF03150F.toInt(); ring = GREEN; label = "AS" }
            "group" -> { fill = 0xFF0A2B2E.toInt(); ring = 0xFF1B4C52.toInt(); setIconPath(Icons.GROUPS, TEAL) }
            "ai" -> { fill = 0xFF2A1458.toInt(); ring = 0xFF8E4DFF.toInt(); setIconPath(Icons.SMART_TOY, 0xFFB388FF.toInt()) }
            "nearby" -> { fill = 0xFF0A2B2E.toInt(); ring = 0xFF1B4C52.toInt(); setIconPath(Icons.SEARCH, TEAL) }
            "me" -> { bmp = a.net.myDp(); fill = 0xFF0B2A2A.toInt(); ring = GREEN; if (bmp == null) { bmp = Art.head() } }
            else -> {
                bmp = a.net.dp(id); fill = avColors[hash(id) % avColors.size]; label = (name.ifEmpty { id }).replace("AS-", "").take(1)
            }
        }
    }

    fun timeText(ts: Long): String {
        val d = Date(ts); val now = Date()
        val sameDay = SimpleDateFormat("yyyyMMdd", Locale.US).format(d) == SimpleDateFormat("yyyyMMdd", Locale.US).format(now)
        return if (sameDay) SimpleDateFormat("h:mm a", Locale.US).format(d) else SimpleDateFormat("d MMM", Locale.US).format(d)
    }
    fun clock(ts: Long): String = SimpleDateFormat("h:mm a", Locale.US).format(Date(ts))
    fun ago(ts: Long): String {
        val m = (System.currentTimeMillis() - ts) / 60000
        return if (m < 1) "Just now" else if (m < 60) "${m}m ago" else if (m < 1440) "${m / 60}h ago" else "${m / 1440}d ago"
    }

    fun tickView(c: Context, status: Int): View {
        return when (status) {
            -1 -> tv(c, "!", 12f, RED, true)
            0 -> icon(c, Icons.TIME, GRAY, 12)
            1 -> icon(c, Icons.DONE, GRAY, 14)
            2 -> icon(c, Icons.DONE_ALL, GRAY, 14)
            else -> icon(c, Icons.DONE_ALL, BLUE, 14)
        }
    }
    fun divider(c: Context) = View(c).apply { setBackgroundColor(0xFF0B2323.toInt()); layoutParams = LinearLayout.LayoutParams(MATCH, dp(c, 1)) }
}

/** loads pictures from the internet (cached) */
object Img {
    private val cache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(k: String, v: Bitmap) = v.byteCount
    }
    private val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
    fun load(a: MainActivity, url: String, v: ImageView) {
        cache.get(url)?.let { v.setImageBitmap(it); return }
        v.tag = url
        pool.execute {
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 15000
                val o = BitmapFactory.Options().apply { inSampleSize = 1 }
                val bytes = c.inputStream.use { it.readBytes() }
                val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                if (b != null) { cache.put(url, b); a.runOnUiThread { if (v.tag == url) v.setImageBitmap(b) } }
            } catch (e: Exception) {}
        }
    }
}

/** moving green bars used on the voice call screen */
class LiveBars(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rnd = java.util.Random()
    override fun onDraw(cv: Canvas) {
        val n = 40; val gap = width / n.toFloat(); p.strokeWidth = gap * 0.5f; p.strokeCap = Paint.Cap.ROUND; p.color = 0xFF14E8C0.toInt()
        for (i in 0 until n) {
            val centre = 1f - Math.abs(i - n / 2f) / (n / 2f)
            val hh = height * (0.15f + 0.85f * centre * rnd.nextFloat()); val x = gap * i + gap / 2
            cv.drawLine(x, (height - hh) / 2, x, (height + hh) / 2, p)
        }
        postInvalidateDelayed(110)
    }
}
