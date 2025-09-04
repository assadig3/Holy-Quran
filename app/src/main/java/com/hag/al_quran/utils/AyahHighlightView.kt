package com.hag.al_quran.utils

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.graphics.ColorUtils
import kotlin.math.min

/**
 * View بسيط لعرض تظليل الآيات على صورة الصفحة.
 * - حواف دائرية ناعمة.
 * - لون مائل لزرقة الصفحة مع شفافية.
 * - لا يستخدم أوضاع مزج خطيرة (تجنّب السواد).
 * - يدعم أنيميشن بسيط بين مجموعتي مستطيلات.
 *
 * API:
 *  - setRects(list)       : يضبط المستطيلات مباشرة.
 *  - animateTo(list, ms)  : ينتقل بسلاسة إلى مواضع جديدة.
 *  - setTintColor(color)  : يغير صبغة التظليل (بدون ألفا).
 *  - setColor(color)      : (توافق) يقبل لونًا مع ألفا ويحوّله داخليًا.
 *  - setRoundedCornerRadiusDp(dp)
 *  - setFeatherPx(px)
 */
class AyahHighlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // المستطيلات المعروضة
    private var rects: List<RectF> = emptyList()

    // للرسم: طبقتان خفيفتان (تنعيم خفيف + ملء)
    private val paintFeather = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // تنعيم بسيط على الحواف (لا مبالغة حتى لا يصبح باهتًا جدًا)
        maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
    }
    private val paintFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    // اللون الأساسي (بدون ألفا) – قريب من لون الصفحة
    private var tintColor: Int = Color.parseColor("#89B7C7")  // في النهار
    private var tintColorNight: Int = Color.parseColor("#6AA8B9") // في الليل (أغمق شوي)

    // نصف قطر الحواف
    private var cornerRadiusPx: Float = dp(6f)

    // شفافية أساسية (نهار/ليل)
    private val baseAlphaDay = 0.22f
    private val baseAlphaNight = 0.28f

    // لو تم استدعاء setColor(colorWithAlpha) نستخرج ألفا ونضربه هنا
    private var overrideAlphaMult = 1f

    // ========= واجهة عامة =========

    /** يضبط المستطيلات مباشرة. */
    fun setRects(newRects: List<RectF>) {
        rects = newRects
        invalidate()
    }

    /**
     * أنيميشن مبسّط بين مجموعتي مستطيلات بنفس العدد.
     * لو الأعداد مختلفة نعرض الجديدة مباشرة.
     */
    fun animateTo(newRects: List<RectF>, durationMs: Long) {
        if (rects.isEmpty() || rects.size != newRects.size) {
            setRects(newRects)
            return
        }
        val start = rects.map { RectF(it) }
        val end = newRects.map { RectF(it) }

        val startTime = System.nanoTime()
        val d = durationMs.coerceAtLeast(60L)

        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

        // إطار/تحديث يدوي خفيف
        val runner = object : Runnable {
            override fun run() {
                val t = ((System.nanoTime() - startTime) / 1_000_000L).toFloat() / d
                val f = t.coerceIn(0f, 1f)

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

                if (f < 1f) {
                    postOnAnimation(this)
                }
            }
        }
        removeCallbacks(runner)
        post(runner)
    }

    /** يغير صبغة (Hue) التظليل بدون التعامل مع الألفا. */
    fun setTintColor(color: Int) {
        // نحفظ اللون بدون ألفا
        tintColor = color or 0xFF000000.toInt()
        // لون الليل قريب لكن أغمق قليلًا تلقائيًا
        tintColorNight = ColorUtils.blendARGB(tintColor, Color.BLACK, 0.12f)
        invalidate()
    }

    /**
     * دالة توافقية: تقبل لونًا مع قناة ألفا.
     * - الصبغة = اللون بدون ألفا.
     * - الشفافية = ألفا * الشفافية الأساسية (نهار/ليل).
     */
    fun setColor(color: Int) {
        val rgb = color and 0x00FFFFFF
        setTintColor(rgb or 0xFF000000.toInt())
        overrideAlphaMult = (Color.alpha(color) / 255f).coerceIn(0f, 1f)
    }

    /** يغير نصف قطر الحواف (dp). */
    fun setRoundedCornerRadiusDp(dp: Float) {
        cornerRadiusPx = this.dp(dp)
        invalidate()
    }

    /** يبدّل مقدار التنعيم (Blur) بالبكسل. */
    fun setFeatherPx(px: Float) {
        paintFeather.maskFilter = if (px > 0f) BlurMaskFilter(px, BlurMaskFilter.Blur.NORMAL) else null
        invalidate()
    }

    // ========= الرسم =========
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

        // نرسم المستطيلات بحواف دائرية (لا نستخدم أوضاع مزج قد تسبب سواد)
        for (r in rects) {
            // أولًا طبقة التنعيم الخفيفة
            canvas.drawRoundRect(r, cornerRadiusPx, cornerRadiusPx, paintFeather)
            // ثم الملء
            canvas.drawRoundRect(r, cornerRadiusPx, cornerRadiusPx, paintFill)
        }
    }

    // ========= أدوات مساعدة =========
    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
