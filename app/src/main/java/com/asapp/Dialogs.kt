package com.asapp

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*

/** Professional dark dialogs (same look everywhere in the app). */
object Dlg {
    val BORDER = 0xFF19B5C8.toInt()
    val FILL = 0xFF041A1E.toInt()
    private fun dp(c: Context, v: Int) = Ui.dp(c, v)

    fun make(c: Context, content: View, bottom: Boolean = false, cancelable: Boolean = true): Dialog {
        val d = Dialog(c)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = FrameLayout(c).apply {
            background = GradientDrawable().apply { setColor(FILL); cornerRadius = dp(c, 20).toFloat(); setStroke(dp(c, 1), BORDER) }
            addView(content)
        }
        d.setContentView(card)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout((c.resources.displayMetrics.widthPixels * 0.92f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.7f); addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setGravity(if (bottom) Gravity.BOTTOM else Gravity.CENTER)
            if (bottom) attributes = attributes.apply { y = dp(c, 24) }
        }
        d.setCancelable(cancelable)
        return d
    }

    fun ring(c: Context, icon: String, size: Int = 52, color: Int = 0xFF14E8C0.toInt()): View = FrameLayout(c).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF03221F.toInt()); setStroke(dp(c, 2), color) }
        addView(ImageView(c).apply { setImageDrawable(PathIcon(icon, color)) }, FrameLayout.LayoutParams(dp(c, size * 11 / 20), dp(c, size * 11 / 20), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(c, size), dp(c, size))
    }

    fun outlineBtn(c: Context, text: String, f: () -> Unit): View = TextView(c).apply {
        this.text = text; textSize = 15f; gravity = Gravity.CENTER; setTextColor(0xFF14E8C0.toInt()); typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = dp(c, 30).toFloat(); setStroke(dp(c, 2), 0xFF14E8C0.toInt()) }
        setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12)); setOnClickListener { f() }
    }
    fun fillBtn(c: Context, text: String, icon: String? = null, f: () -> Unit): View = LinearLayout(c).apply {
        gravity = Gravity.CENTER; setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12))
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xFF0BE68E.toInt(), 0xFF00F5B0.toInt())).apply { cornerRadius = dp(c, 30).toFloat() }
        if (icon != null) addView(Ui.icon(c, icon, 0xFF03241A.toInt(), 20).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(c, 8) })
        addView(TextView(c).apply { this.text = text; textSize = 15f; setTextColor(0xFF03241A.toInt()); typeface = Typeface.DEFAULT_BOLD })
        setOnClickListener { f() }
    }

    fun confirm(c: Context, icon: String, title: String, msg: String, ok: String, okIcon: String?, onOk: () -> Unit): Dialog {
        lateinit var d: Dialog
        val v = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 18), dp(c, 20), dp(c, 18), dp(c, 18)) }
        v.addView(LinearLayout(c).apply {
            addView(ring(c, icon, 54))
            addView(LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 14), 0, 0, 0)
                addView(Ui.tv(c, title, 22f, Color.WHITE, true)); addView(Ui.tv(c, msg, 14.5f, 0xFFDCE8E6.toInt()).apply { setPadding(0, dp(c, 6), 0, 0) }) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        v.addView(LinearLayout(c).apply {
            addView(outlineBtn(c, "Cancel") { d.dismiss() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(c, 8) })
            addView(fillBtn(c, ok, okIcon) { d.dismiss(); onOk() }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(c, 8) })
        }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(c, 18) })
        d = make(c, v); d.show(); return d
    }

    fun success(c: Context, title: String, msg: String, onOk: (() -> Unit)? = null): Dialog {
        lateinit var d: Dialog
        val v = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 18), dp(c, 20), dp(c, 18), dp(c, 18)) }
        v.addView(LinearLayout(c).apply {
            addView(ring(c, Icons.CHECK_CIRCLE, 54))
            addView(LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 14), 0, 0, 0)
                addView(Ui.tv(c, title, 22f, Color.WHITE, true)); addView(Ui.tv(c, msg, 14.5f, 0xFFDCE8E6.toInt()).apply { setPadding(0, dp(c, 6), 0, 0) }) },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })
        v.addView(fillBtn(c, "OK") { d.dismiss(); onOk?.invoke() }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(c, 18) })
        d = make(c, v); d.show(); return d
    }

    class Row(val icon: String, val title: String, val sub: String = "", val sepBefore: Boolean = false, val f: () -> Unit)

    /** list of actions: round icon, title, small text, arrow */
    fun sheet(c: Context, rows: List<Row>, bottom: Boolean = false): Dialog {
        lateinit var d: Dialog
        val v = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 8)) }
        rows.forEachIndexed { i, r ->
            if (i > 0) v.addView(View(c).apply { setBackgroundColor(if (r.sepBefore) 0xFF2A7A86.toInt() else 0xFF0F3A44.toInt()) },
                LinearLayout.LayoutParams(Ui.MATCH, dp(c, if (r.sepBefore) 2 else 1)).apply { if (r.sepBefore) { topMargin = dp(c, 4); bottomMargin = dp(c, 4) } })
            v.addView(LinearLayout(c).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(c, 9), dp(c, 4), dp(c, 9))
                addView(ring(c, r.icon, 46))
                addView(LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(c, 14), 0, 0, 0)
                    addView(Ui.tv(c, r.title, 17f, Color.WHITE, true)); if (r.sub.isNotEmpty()) addView(Ui.tv(c, r.sub, 13f, 0xFF9FB3B0.toInt())) },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Ui.icon(c, Icons.CHEVRON, 0xFF9FB3B0.toInt(), 24))
                setOnClickListener { d.dismiss(); r.f() }
            })
        }
        d = make(c, v, bottom); d.show(); return d
    }

    /** Report a problem (message + optional screenshot) */
    fun report(a: MainActivity) {
        lateinit var d: Dialog
        var shot: Uri? = null
        val v = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(a, 18), dp(a, 14), dp(a, 18), dp(a, 18)) }
        v.addView(Ui.iconBtn(a, Icons.CLOSE, Color.WHITE, 22, 6) { d.dismiss() }.apply { (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.END })
        v.addView(ring(a, Icons.WARNING, 60))
        v.addView(Ui.tv(a, "Report a problem", 22f, Color.WHITE, true).apply { setPadding(0, dp(a, 8), 0, dp(a, 6)) })
        v.addView(Ui.tv(a, "Tell us what's wrong or what can be improved. Your feedback helps us make AS CHATS better.", 14f, 0xFFDCE8E6.toInt()).apply { gravity = Gravity.CENTER })
        val et = EditText(a).apply { hint = "Write your message here..."; setHintTextColor(0xFF9FB3B0.toInt()); setTextColor(Color.WHITE); textSize = 14.5f; gravity = Gravity.TOP
            background = GradientDrawable().apply { setColor(0xFF03141A.toInt()); cornerRadius = dp(a, 14).toFloat(); setStroke(dp(a, 1), BORDER) }
            setPadding(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 24)); minLines = 4; maxLines = 6
            filters = arrayOf(android.text.InputFilter.LengthFilter(500)) }
        val cnt = Ui.tv(a, "0/500", 12f, 0xFF9FB3B0.toInt())
        et.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { cnt.text = "${e?.length ?: 0}/500" }
            override fun beforeTextChanged(t: CharSequence?, x: Int, y: Int, z: Int) {}
            override fun onTextChanged(t: CharSequence?, x: Int, y: Int, z: Int) {}
        })
        v.addView(FrameLayout(a).apply { addView(et, FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP)); addView(cnt, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(a, 12), dp(a, 8)) }) },
            LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(a, 14) })
        val shotTx = Ui.tv(a, "Add screenshot (optional)", 14f, 0xFF14E8C0.toInt())
        v.addView(LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(a, 12), dp(a, 12), dp(a, 12), dp(a, 12))
            background = GradientDrawable().apply { setColor(0xFF03141A.toInt()); cornerRadius = dp(a, 14).toFloat(); setStroke(dp(a, 1), BORDER) }
            addView(Ui.icon(a, Icons.ATTACH, 0xFF14E8C0.toInt(), 22)); addView(shotTx, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = dp(a, 12) }); addView(Ui.icon(a, Icons.CHEVRON, 0xFF9FB3B0.toInt(), 22))
            setOnClickListener { a.pick("image/*") { u -> shot = u; shotTx.text = "Screenshot added ✓" } } },
            LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(a, 10) })
        v.addView(LinearLayout(a).apply {
            addView(outlineBtn(a, "Cancel") { d.dismiss() }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = dp(a, 8) })
            addView(fillBtn(a, "Send", Icons.SEND) { d.dismiss(); a.sendReport(et.text.toString().trim(), shot) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1.2f).apply { leftMargin = dp(a, 8) })
        }, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = dp(a, 14) })
        d = make(a, v); d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE); d.show()
    }
}

/** Drop-in replacement for AlertDialog.Builder with the app look. */
class PDialog(private val c: Context) {
    private var title: CharSequence? = null
    private var msg: CharSequence? = null
    private var view: View? = null
    private var items: Array<String>? = null
    private var itemCb: ((DialogInterface, Int) -> Unit)? = null
    private var pos: Pair<String, ((DialogInterface, Int) -> Unit)?>? = null
    private var neg: Pair<String, ((DialogInterface, Int) -> Unit)?>? = null
    private var neu: Pair<String, ((DialogInterface, Int) -> Unit)?>? = null
    private var cancel = true
    fun setTitle(t: CharSequence) = apply { title = t }
    fun setMessage(m: CharSequence) = apply { msg = m }
    fun setView(v: View) = apply { view = v }
    fun setCancelable(b: Boolean) = apply { cancel = b }
    fun setItems(i: Array<String>, f: (DialogInterface, Int) -> Unit) = apply { items = i; itemCb = f }
    fun setPositiveButton(t: String, f: ((DialogInterface, Int) -> Unit)?) = apply { pos = Pair(t, f) }
    fun setNegativeButton(t: String, f: ((DialogInterface, Int) -> Unit)?) = apply { neg = Pair(t, f) }
    fun setNeutralButton(t: String, f: ((DialogInterface, Int) -> Unit)?) = apply { neu = Pair(t, f) }

    fun create(): Dialog {
        lateinit var d: Dialog
        val v = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(c, 18), Ui.dp(c, 18), Ui.dp(c, 18), Ui.dp(c, 14)) }
        title?.let { v.addView(Ui.tv(c, it, 20f, Color.WHITE, true).apply { setPadding(0, 0, 0, Ui.dp(c, 8)) }) }
        msg?.let { v.addView(Ui.tv(c, it, 14.5f, 0xFFDCE8E6.toInt()).apply { setPadding(0, 0, 0, Ui.dp(c, 8)) }) }
        view?.let { v.addView(it) }
        items?.forEachIndexed { i, s ->
            v.addView(View(c).apply { setBackgroundColor(0xFF0F3A44.toInt()) }, LinearLayout.LayoutParams(Ui.MATCH, if (i == 0) 0 else Ui.dp(c, 1)))
            v.addView(LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, Ui.dp(c, 13), Ui.dp(c, 4), Ui.dp(c, 13))
                addView(Ui.tv(c, s, 16.5f, Color.WHITE), LinearLayout.LayoutParams(0, Ui.WRAP, 1f)); addView(Ui.icon(c, Icons.CHEVRON, 0xFF9FB3B0.toInt(), 22))
                setOnClickListener { d.dismiss(); itemCb?.invoke(d, i) } })
        }
        if (pos != null || neg != null || neu != null) {
            val row = LinearLayout(c).apply { gravity = Gravity.CENTER }
            neu?.let { n -> row.addView(Dlg.outlineBtn(c, n.first) { d.dismiss(); n.second?.invoke(d, 0) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.dp(c, 6) }) }
            neg?.let { n -> row.addView(Dlg.outlineBtn(c, n.first) { d.dismiss(); n.second?.invoke(d, 0) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { rightMargin = Ui.dp(c, 6) }) }
            pos?.let { p -> row.addView(Dlg.fillBtn(c, p.first) { d.dismiss(); p.second?.invoke(d, 0) }, LinearLayout.LayoutParams(0, Ui.WRAP, 1f).apply { leftMargin = Ui.dp(c, 6) }) }
            v.addView(row, LinearLayout.LayoutParams(Ui.MATCH, Ui.WRAP).apply { topMargin = Ui.dp(c, 12) })
        }
        d = Dlg.make(c, v, false, cancel)
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE.and(0))
        return d
    }
    fun show(): Dialog = create().also { it.show() }
}
