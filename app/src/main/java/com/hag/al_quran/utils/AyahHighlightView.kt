package com.hag.al_quran.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.graphics.ColorUtils

/**
 * عرض تظليل الآيات:
 * - حواف دائرية ناعمة + طبقة feather خفيفة.
 * - يدعم أنيميشن بين مجموعتي مستطيلات.
 * - يتجنب سواد/Artifacts عبر تبديل طبقة الرسم عند استخدام الـBlur.
 */
class AyahHighlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ============= الحالة =============
    private var rects: List<RectF> = emptyList()
    private var animRunner: Runnable? = null
    private val interpolator = DecelerateInterpolator()

    // ألوان النهار/الليل (بدون ألفا)
    private var tintColor: Int = Color.parseColor("#89B7C7")
    private var tintColorNight: Int = Color.parseColor("#6AA8B9")

    // نصف قطر الحواف
    private var cornerRadiusPx: Float = dp(6f)

    // شفافية أساسية
    private val baseAlphaDay = 0.22f
    private val baseAlphaNight = 0.28f
    private var overrideAlphaMult = 1f

    // Debug إطار
    private var debugEnabled = false
    private val paintDebug = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
        color = Color.argb(140, 30, 144, 255) // أزرق فاتح
    }

    // ============= أدوات الرسم =============
    private val paintFeather = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // قيمة افتراضية – قد تتغير عبر setFeatherPx
        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
    }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // ============= واجهة عامة =============
    fun setRects(newRects: List<RectF>) {
        animRunner?.let { removeCallbacks(it) ; animRunner = null }
        rects = newRects
        invalidate()
    }

    fun animateTo(newRects: List<RectF>, durationMs: Long) {
        val d = durationMs.coerceAtLeast(60L)

        if (rects.isEmpty() || rects.size != newRects.size) {
            setRects(newRects)
            return
        }
        val start = rects.map { RectF(it) }
        val end = newRects.map { RectF(it) }

        animRunner?.let { removeCallbacks(it) } // ألغِ السابق
        val startTime = System.nanoTime()

        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        val r = object : Runnable {
            override fun run() {
                val tMs = (System.nanoTime() - startTime) / 1_000_000L
                val raw = (tMs.toFloat() / d).coerceIn(0f, 1f)
                val f = interpolator.getInterpolation(raw)

                val cur = ArrayList<RectF>(start.size)
                for (i in start.indices) {
                    val s = start[i]; val e = end[i]
                    cur.add(
                        RectF(
                            lerp(s.left,   e.left,   f),
                            lerp(s.top,    e.top,    f),
                            lerp(s.right,  e.right,  f),
                            lerp(s.bottom, e.bottom, f),
                        )
                    )
                }
                rects = cur
                invalidate()

                if (raw < 1f) postOnAnimation(this) else animRunner = null
            }
        }
        animRunner = r
        post(r)
    }

    /** يغير الصبغة (RGB) فقط. */
    fun setTintColor(color: Int) {
        tintColor = color or 0xFF000000.toInt()
        tintColorNight = ColorUtils.blendARGB(tintColor, Color.BLACK, 0.12f)
        invalidate()
    }

    /** توافق: يقبل لونًا مع ألفا، ونستخلص منه الشفافية كمضاعِف. */
    fun setColor(color: Int) {
        val rgb = color and 0x00FFFFFF
        setTintColor(rgb or 0xFF000000.toInt())
        overrideAlphaMult = (Color.alpha(color) / 255f).coerceIn(0f, 1f)
        invalidate()
    }

    /** تحكم يدوي بمضاعِف الألفا (0..1) بدون تغيير اللون. */
    fun setAlphaMultiplier(mult: Float) {
        overrideAlphaMult = mult.coerceIn(0f, 1f)
        invalidate()
    }

    fun setRoundedCornerRadiusDp(dp: Float) {
        cornerRadiusPx = this.dp(dp)
        invalidate()
    }

    /**
     * يبدّل مقدار التنعيم (Blur) بالبكسل.
     * - عند px>0 نفعل طبقة الرسم Software لتجنب سواد/Artifacts.
     * - عند px<=0 نعود للوضع الافتراضي (Hardware).
     */
    fun setFeatherPx(px: Float) {
        paintFeather.maskFilter =
            if (px > 0f) BlurMaskFilter(px, BlurMaskFilter.Blur.NORMAL) else null

        if (px > 0f) {
            if (layerType != LAYER_TYPE_SOFTWARE) setLayerType(LAYER_TYPE_SOFTWARE, null)
        } else {
            if (layerType != LAYER_TYPE_NONE) setLayerType(LAYER_TYPE_NONE, null)
        }
        invalidate()
    }

    /** تفعيل إطار تصحيح حول المستطيلات. */
    fun setDebugEnabled(enabled: Boolean) {
        debugEnabled = enabled
        invalidate()
    }

    // ============= الرسم =============
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rects.isEmpty()) return

        val isNight = (resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        val baseAlpha = if (isNight) baseAlphaNight else baseAlphaDay
        val a = (baseAlpha * overrideAlphaMult).coerceIn(0f, 1f)

        val color = if (isNight) tintColorNight else tintColor
        val fillColor = ColorUtils.setAlphaComponent(color, (a * 255).toInt())
        val featherColor = ColorUtils.setAlphaComponent(color, (a * 0.75f * 255).toInt())

        paintFill.color = fillColor
        paintFeather.color = featherColor

        for (r in rects) {
            canvas.drawRoundRect(r, cornerRadiusPx, cornerRadiusPx, paintFeather)
            canvas.drawRoundRect(r, cornerRadiusPx, cornerRadiusPx, paintFill)
            if (debugEnabled) canvas.drawRoundRect(r, cornerRadiusPx, cornerRadiusPx, paintDebug)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animRunner?.let { removeCallbacks(it) ; animRunner = null }
    }

    // ============= أدوات مساعدة =============
    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
