package com.asapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView


/** A simple icon drawn from Material icon path data. */
class PathIcon(d: String, color: Int) : Drawable() {
    private val path = androidx.core.graphics.PathParser.createPathFromPathData(d)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).also { it.style = Paint.Style.FILL; it.color = color }
    override fun draw(c: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()) / 24f
        c.save(); c.translate(b.left.toFloat(), b.top.toFloat()); c.scale(s, s)
        path?.let { c.drawPath(it, paint) }
        c.restore()
    }
    override fun setAlpha(a: Int) { paint.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Look of the new design (dark green, taken from the design pictures). */
object Look {
    val GREEN = 0xFF00E676.toInt()
    val TEAL = 0xFF14E8C0.toInt()
    var glow = 0xFF063B33.toInt()
    val BG = 0xFF020E12.toInt()
    val BG_TOP = BG
    val BG_BOT = BG
    val FIELD_FILL = 0xFF03171D.toInt()
    val STROKE = 0xFF2A434E.toInt()
    val TEXT = 0xFFF2F7F5.toInt()
    val HINT = 0xFFA9B8B4.toInt()
    val DARK_TEXT = 0xFF03241A.toInt()

    fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    fun bgGradient() = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG, BG))

    fun pill(c: Context, fill: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(c, 60).toFloat()
        if (stroke != null) setStroke(dp(c, 1), stroke)
    }

    fun icon(c: Context, path: String, color: Int, size: Int): ImageView = ImageView(c).apply {
        setImageDrawable(PathIcon(path, color))
        layoutParams = LinearLayout.LayoutParams(dp(c, size), dp(c, size))
    }

    fun brand(): SpannableString {
        val s = SpannableString("AS CHATS")
        s.setSpan(ForegroundColorSpan(GREEN), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(ForegroundColorSpan(0xFFFFFFFF.toInt()), 3, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    /** rounded field with an icon on the left (and an eye on the right for passwords) */
    fun field(c: Context, hint: String, iconPath: String, pass: Boolean): Pair<LinearLayout, EditText> {
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pill(c, FIELD_FILL, STROKE)
            setPadding(dp(c, 18), 0, dp(c, 16), 0)
        }
        box.addView(icon(c, iconPath, TEXT, 22))
        val et = EditText(c).apply {
            this.hint = hint; setHintTextColor(HINT); setTextColor(TEXT); textSize = 13f
            background = null; setSingleLine()
            setPadding(dp(c, 18), 0, 0, 0)
            inputType = InputType.TYPE_CLASS_TEXT or
                (if (pass) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        }
        box.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        if (pass) {
            var shown = false
            val eye = icon(c, Icons.EYE_OFF, HINT, 24)
            eye.setOnClickListener {
                shown = !shown
                val pos = et.selectionEnd
                et.inputType = InputType.TYPE_CLASS_TEXT or
                    (if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                et.setSelection(pos.coerceAtLeast(0))
                eye.setImageDrawable(PathIcon(if (shown) Icons.EYE else Icons.EYE_OFF, HINT))
            }
            box.addView(eye)
        }
        box.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 48)).apply { topMargin = dp(c, 14) }
        return Pair(box, et)
    }

    /** big green button: TEXT  -> */
    fun mainButton(c: Context, text: String, onClick: () -> Unit): LinearLayout {
        val b = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF09ECAA.toInt(), 0xFF09D99F.toInt(), 0xFF00D77C.toInt())).apply {
                cornerRadius = dp(c, 60).toFloat()
            }
            elevation = dp(c, 6).toFloat()
            isClickable = true
            setOnClickListener { onClick() }
        }
        b.addView(TextView(c).apply { this.text = text; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(DARK_TEXT); letterSpacing = 0.05f })
        b.addView(icon(c, Icons.ARROW, DARK_TEXT, 22).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(c, 10) })
        b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 53)).apply { topMargin = dp(c, 17) }
        return b
    }

    /** outlined button: [icon] TEXT  > */
    fun outlineButton(c: Context, iconPath: String, text: String, size: Float = 13f, onClick: () -> Unit): LinearLayout {
        val b = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = pill(c, 0xFF011317.toInt(), STROKE)
            setPadding(dp(c, 20), 0, dp(c, 16), 0)
            isClickable = true
            setOnClickListener { onClick() }
        }
        b.addView(icon(c, iconPath, TEXT, 24))
        b.addView(TextView(c).apply {
            this.text = text; textSize = size; setTextColor(TEXT); typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(c, 18), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        b.addView(icon(c, Icons.CHEVRON, HINT, 24))
        b.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 46)).apply { topMargin = dp(c, 14) }
        return b
    }

    fun orDivider(c: Context): LinearLayout {
        val r = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun line() = View(c).apply { setBackgroundColor(STROKE) }
        r.addView(line(), LinearLayout.LayoutParams(0, dp(c, 1), 1f))
        r.addView(TextView(c).apply { text = "OR"; textSize = 13f; setTextColor(HINT); setPadding(dp(c, 18), 0, dp(c, 18), 0) })
        r.addView(line(), LinearLayout.LayoutParams(0, dp(c, 1), 1f))
        r.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(c, 14) }
        return r
    }
}

/** Dark background with the two soft green corner glows of the design. */
class GlowBg(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(cv: Canvas) {
        cv.drawColor(Look.BG)
        val r = Look.dp(context, 150).toFloat()
        val g0 = Look.glow; val g1 = android.graphics.Color.argb(255, (android.graphics.Color.red(g0) * 0.55f).toInt(), (android.graphics.Color.green(g0) * 0.55f).toInt(), (android.graphics.Color.blue(g0) * 0.55f).toInt()); val g2 = g1 and 0x00FFFFFF
        p.shader = android.graphics.RadialGradient(0f, 0f, r, intArrayOf(g0, g1, g2), floatArrayOf(0f, 0.7f, 1f), android.graphics.Shader.TileMode.CLAMP)
        cv.drawCircle(0f, 0f, r, p)
        val w = width.toFloat(); val h = height.toFloat()
        p.shader = android.graphics.RadialGradient(w, h, r, intArrayOf(g0, g1, g2), floatArrayOf(0f, 0.7f, 1f), android.graphics.Shader.TileMode.CLAMP)
        cv.drawCircle(w, h, r, p)
    }
}
