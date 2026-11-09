package com.asapp

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Cartoon buddies at the top of the main screens: girl S (left) and boy A (right).
 * They play 16 games, change colour every 10 seconds, and tap on the boy starts a duel.
 */
class Buddies(private val a: MainActivity) {
    private val h = a.ui
    private var strip: FrameLayout? = null
    private lateinit var boy: ImageView
    private lateinit var girl: ImageView
    private val props = ArrayList<View>()
    private var running: AnimatorSet? = null
    private var busy = false
    private var girlDown = false
    private var hue = 0f
    private var started = false
    private val rnd = java.util.Random()

    fun enabled() = a.prefsPublic().getBoolean("cartoonOn", true)
    fun sizeDp() = a.prefsPublic().getInt("cartoonSize", 80)
    private fun d(v: Int) = Ui.dp(a, v)
    private fun dpf(v: Float) = v * a.resources.displayMetrics.density

    /** the strip that goes on top of the main screens */
    fun stripView(): View {
        val sz = d(sizeDp())
        val f = strip ?: FrameLayout(a).also { strip = it; build(it) }
        (f.parent as? ViewGroup)?.removeView(f)
        f.layoutParams = LinearLayout.LayoutParams(Ui.MATCH, sz + d(6))
        val gB = Art.girl(); val bB = Art.boy()
        girl.layoutParams = FrameLayout.LayoutParams((sz * gB.width / gB.height.toFloat()).toInt(), sz, Gravity.START or Gravity.BOTTOM).apply { leftMargin = d(14); bottomMargin = d(3) }
        boy.layoutParams = FrameLayout.LayoutParams((sz * bB.width / bB.height.toFloat()).toInt(), sz, Gravity.END or Gravity.BOTTOM).apply { rightMargin = d(14); bottomMargin = d(3) }
        startLoops()
        return f
    }

    private fun build(f: FrameLayout) {
        f.clipChildren = false; f.clipToPadding = false
        girl = ImageView(a).apply { setImageBitmap(Art.girl()); scaleType = ImageView.ScaleType.FIT_CENTER; setOnClickListener { onGirl() } }
        boy = ImageView(a).apply { setImageBitmap(Art.boy()); scaleType = ImageView.ScaleType.FIT_CENTER; setOnClickListener { duel() } }
        f.addView(girl); f.addView(boy)
        f.alpha = 0f
    }

    // ---------- colours ----------
    private fun hueMatrix(deg: Float): ColorMatrix {
        val r = Math.toRadians(deg.toDouble()); val c = Math.cos(r).toFloat(); val s = Math.sin(r).toFloat()
        return ColorMatrix(floatArrayOf(
            0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0f, 0f,
            0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0f, 0f,
            0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f))
    }
    private fun applyColor() {
        boy.colorFilter = ColorMatrixColorFilter(hueMatrix(hue))
        girl.colorFilter = ColorMatrixColorFilter(if (girlDown) ColorMatrix().apply { setSaturation(0f) } else hueMatrix(hue + 120f))
    }

    private fun startLoops() {
        if (started) return
        started = true
        applyColor()
        h.postDelayed({ strip?.animate()?.alpha(1f)?.setDuration(900)?.start() }, 500)
        h.postDelayed(object : Runnable { override fun run() {
            if (enabled()) { hue = (hue + 50f) % 360f; try { applyColor() } catch (e: Exception) {} }
            h.postDelayed(this, 10000) } }, 10000)
        h.postDelayed(object : Runnable { override fun run() {
            val s = strip
            if (!busy && !girlDown && enabled() && s != null && s.isAttachedToWindow && s.width > 0) { try { runGame(rnd.nextInt(16)) } catch (e: Exception) { reset() } }
            h.postDelayed(this, 13000) } }, 4000)
    }

    // ---------- helpers ----------
    private fun Animator.onEnd(f: () -> Unit): Animator { addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(an: Animator) { f() } }); return this }
    private fun cx(v: View) = v.left + v.translationX + v.width / 2f
    private fun prop(text: String, size: Float = 24f): TextView {
        val t = TextView(a).apply { this.text = text; textSize = size; setTextColor(Color.WHITE) }
        strip!!.addView(t, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        t.measure(0, 0); props.add(t); return t
    }
    private fun place(p: View, x: Float, y: Float) { p.x = x - p.measuredWidth / 2f; p.y = y }
    private fun clearProps() { props.forEach { (it.parent as? ViewGroup)?.removeView(it) }; props.clear() }
    private fun reset() {
        for (v in listOf<View>(girl, boy)) { v.translationX = 0f; v.translationY = 0f; v.rotation = 0f; v.rotationY = 0f; v.scaleX = 1f; v.scaleY = 1f; v.alpha = 1f }
        clearProps(); busy = false
    }
    private fun hop(v: View, n: Int, hgt: Float, dur: Long): Animator =
        ObjectAnimator.ofFloat(v, View.TRANSLATION_Y, 0f, -hgt).apply { duration = dur; repeatMode = ValueAnimator.REVERSE; repeatCount = n * 2 - 1 }
    private fun wiggle(v: View, deg: Float, dur: Long, n: Int): Animator =
        ObjectAnimator.ofFloat(v, View.ROTATION, -deg, deg).apply { duration = dur; repeatMode = ValueAnimator.REVERSE; repeatCount = n }
    private fun slide(v: View, dx: Float, dur: Long): Animator =
        ObjectAnimator.ofFloat(v, View.TRANSLATION_X, 0f, dx).apply { duration = dur; repeatMode = ValueAnimator.REVERSE; repeatCount = 1 }
    private fun arc(emoji: String, x0: Float, x1: Float, y0: Float, high: Float, dur: Long): Animator {
        val p = prop(emoji, 22f); p.visibility = View.INVISIBLE; place(p, x0, y0)
        return ValueAnimator.ofFloat(0f, 1f).apply { duration = dur; interpolator = LinearInterpolator()
            addUpdateListener { val t = it.animatedValue as Float; p.visibility = View.VISIBLE; p.x = x0 + (x1 - x0) * t - p.measuredWidth / 2f; p.y = y0 - high * 4f * t * (1f - t) } }
    }
    private fun seq(vararg an: Animator) = AnimatorSet().apply { playSequentially(*an) }
    private fun together(vararg an: Animator) = AnimatorSet().apply { playTogether(*an) }

    // ---------- the 16 games ----------
    private fun runGame(i: Int) {
        val s = strip ?: return
        busy = true
        val gx = cx(girl); val bx = cx(boy); val y = s.height * 0.42f; val dist = (bx - gx)
        val set: AnimatorSet = when (i) {
            0 -> together(hop(girl, 3, dpf(26f), 260), hop(boy, 3, dpf(26f), 260).apply { startDelay = 260 })                       // jump
            1 -> together(wiggle(girl, 12f, 220, 9), wiggle(boy, -12f, 220, 9),
                    ObjectAnimator.ofFloat(girl, View.SCALE_X, 1f, 1.12f).apply { duration = 220; repeatMode = ValueAnimator.REVERSE; repeatCount = 9 })  // dance
            2 -> { place(prop("🏁", 24f), (gx + bx) / 2, y - d(10)); together(slide(girl, dist * 0.8f, 1100), slide(boy, -dist * 0.8f, 1100)) } // race
            3 -> { place(prop("👋", 22f), gx, 0f); place(prop("👋", 22f), bx, 0f); together(wiggle(girl, 9f, 380, 5), wiggle(boy, -9f, 380, 5)) } // wave
            4 -> seq(arc("⚽", gx, bx, y, dpf(26f), 650), arc("⚽", bx, gx, y, dpf(26f), 650), arc("⚽", gx, bx, y, dpf(26f), 650))  // football
            5 -> { place(prop("🏏", 24f), bx - d(26), y); seq(arc("⚾", gx, bx, y, dpf(10f), 600),
                    together(ObjectAnimator.ofFloat(boy, View.ROTATION, 0f, -22f).apply { duration = 160; repeatMode = ValueAnimator.REVERSE; repeatCount = 1 }, arc("⚾", bx, gx, y, dpf(70f), 900))) } // cricket
            6 -> seq(arc("🏀", bx, gx, y, dpf(60f), 800), arc("🏀", gx, bx, y, dpf(60f), 800))                                         // basketball
            7 -> seq(arc("🏐", gx, bx, y, dpf(50f), 700), arc("🏐", bx, gx, y, dpf(50f), 700), arc("🏐", gx, bx, y, dpf(50f), 700), arc("🏐", bx, gx, y, dpf(50f), 700)) // volleyball
            8 -> { val p = prop("🤝", 26f); p.visibility = View.INVISIBLE; place(p, (gx + bx) / 2, y - d(8))
                   seq(together(ObjectAnimator.ofFloat(girl, View.TRANSLATION_X, 0f, dist / 2 - d(16)), ObjectAnimator.ofFloat(boy, View.TRANSLATION_X, 0f, -(dist / 2 - d(16)))).apply { duration = 900 },
                       ObjectAnimator.ofFloat(p, View.ALPHA, 1f, 1f).apply { duration = 900; addListener(object : AnimatorListenerAdapter() { override fun onAnimationStart(an: Animator) { p.visibility = View.VISIBLE } }) },
                       together(ObjectAnimator.ofFloat(girl, View.TRANSLATION_X, dist / 2 - d(16), 0f), ObjectAnimator.ofFloat(boy, View.TRANSLATION_X, -(dist / 2 - d(16)), 0f)).apply { duration = 900 }) } // handshake
            9 -> { place(prop("🪢", 24f), (gx + bx) / 2, y); together(
                    ObjectAnimator.ofFloat(girl, View.TRANSLATION_X, 0f, -dpf(10f)).apply { duration = 300; repeatMode = ValueAnimator.REVERSE; repeatCount = 9 },
                    ObjectAnimator.ofFloat(boy, View.TRANSLATION_X, 0f, dpf(10f)).apply { duration = 300; repeatMode = ValueAnimator.REVERSE; repeatCount = 9 },
                    wiggle(girl, 6f, 300, 9), wiggle(boy, -6f, 300, 9)) }                                                                   // tug of war
            10 -> seq(arc("🏸", gx, bx, y, dpf(34f), 450), arc("🏸", bx, gx, y, dpf(34f), 450), arc("🏸", gx, bx, y, dpf(34f), 450), arc("🏸", bx, gx, y, dpf(34f), 450)) // badminton
            11 -> seq(ObjectAnimator.ofFloat(girl, View.ROTATION_Y, 0f, 360f).apply { duration = 900 }, ObjectAnimator.ofFloat(boy, View.ROTATION_Y, 0f, 360f).apply { duration = 900 }) // spin
            12 -> { place(prop("🎉", 24f), gx, 0f); place(prop("🎊", 24f), bx, 0f); together(hop(girl, 3, dpf(22f), 240), hop(boy, 3, dpf(22f), 240)) } // celebrate
            13 -> { place(prop("💤", 22f), gx, 0f); place(prop("💤", 22f), bx, 0f); together(
                    ObjectAnimator.ofFloat(girl, View.ROTATION, 0f, -6f).apply { duration = 1200; repeatMode = ValueAnimator.REVERSE; repeatCount = 3 },
                    ObjectAnimator.ofFloat(boy, View.ROTATION, 0f, 6f).apply { duration = 1200; repeatMode = ValueAnimator.REVERSE; repeatCount = 3 }) } // sleep
            14 -> together(ObjectAnimator.ofFloat(girl, View.SCALE_X, 1f, 0.4f).apply { duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = 1 },
                    ObjectAnimator.ofFloat(girl, View.SCALE_Y, 1f, 0.4f).apply { duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = 1 },
                    ObjectAnimator.ofFloat(boy, View.ALPHA, 1f, 0.25f).apply { duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = 1 }) // hide and seek
            else -> { place(prop("💨", 22f), gx, y); together(slide(girl, dist * 0.7f, 1000), slide(boy, dist * 0.55f, 1000).apply { startDelay = 250 }) } // chase
        }
        set.onEnd { reset() }
        running = set; set.start()
    }

    // ---------- tap on the girl ----------
    private fun onGirl() { if (busy || girlDown) return; busy = true; val an = hop(girl, 1, dpf(22f), 220); an.addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(x: Animator) { reset() } }); an.start() }

    // ---------- tap on the boy: he goes left, she goes right, he shoots ----------
    private fun duel() {
        if (busy || girlDown || strip == null || strip!!.width == 0) return
        running?.let { it.removeAllListeners(); it.cancel() }
        reset(); busy = true
        val dx = (girl.left - boy.left).toFloat()
        val mv = together(ObjectAnimator.ofFloat(boy, View.TRANSLATION_X, 0f, dx), ObjectAnimator.ofFloat(girl, View.TRANSLATION_X, 0f, -dx)).apply { duration = 750 }
        mv.onEnd { shoot() }
        running = mv; mv.start()
    }
    private fun shoot() {
        val s = strip ?: return
        val y = s.height * 0.42f
        val bRight = boy.left + boy.translationX + boy.width
        val gLeft = girl.left + girl.translationX
        val gun = prop("🔫", 26f); place(gun, bRight + d(8), y)
        h.postDelayed({
            val bullet = prop("●", 14f).apply { setTextColor(Color.YELLOW) }
            val x0 = bRight + d(22); val x1 = gLeft + d(6)
            place(bullet, x0, y + d(6))
            ValueAnimator.ofFloat(0f, 1f).apply { duration = 380; interpolator = LinearInterpolator()
                addUpdateListener { val t = it.animatedValue as Float; bullet.x = x0 + (x1 - x0) * t - bullet.measuredWidth / 2f }
                onEnd { hit() } }.start()
        }, 450)
    }
    private fun hit() {
        val s = strip ?: return
        clearProps()
        val gc = girl.left + girl.translationX + girl.width / 2f
        val boom = prop("💥", 30f); place(boom, gc, s.height * 0.35f)
        h.postDelayed({ (boom.parent as? ViewGroup)?.removeView(boom) }, 450)
        girlDown = true; applyColor()
        val dizzy = prop("💫", 24f); place(dizzy, gc, 0f)
        ObjectAnimator.ofFloat(girl, View.ROTATION, -8f, 8f).apply { duration = 160; repeatMode = ValueAnimator.REVERSE; repeatCount = 30 }.start()
        h.postDelayed({
            girl.animate().cancel(); girl.rotation = 0f
            girlDown = false; applyColor(); (dizzy.parent as? ViewGroup)?.removeView(dizzy)
            val back = together(ObjectAnimator.ofFloat(boy, View.TRANSLATION_X, boy.translationX, 0f), ObjectAnimator.ofFloat(girl, View.TRANSLATION_X, girl.translationX, 0f)).apply { duration = 750 }
            back.onEnd { reset() }
            running = back; back.start()
        }, 5000)
    }
}
