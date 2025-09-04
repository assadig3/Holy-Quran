package com.hag.al_quran.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.*
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hag.al_quran.AyahBoundsRepo
import com.hag.al_quran.R
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
data class PageCal(var offX: Float = 0f, var offY: Float = 0f, var scaleXFix: Float = 1f, var scaleYFix: Float = 1f)
object CalibrationStore {
    private const val PREF = "ayah_cal"
    fun loadGlobal(ctx: Context): PageCal {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return PageCal(
            p.getFloat("g_offX", 0f),
            p.getFloat("g_offY", 0f),
            p.getFloat("g_sx", 1f),
            p.getFloat("g_sy", 1f),
        )
    }
    fun saveGlobal(ctx: Context, cal: PageCal) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat("g_offX", cal.offX)
            .putFloat("g_offY", cal.offY)
            .putFloat("g_sx", cal.scaleXFix)
            .putFloat("g_sy", cal.scaleYFix)
            .apply()
    }
    fun loadForPage(ctx: Context, page: Int): PageCal {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val has = p.contains("p${page}_sx") || p.contains("p${page}_sy")
                || p.contains("p${page}_offX") || p.contains("p${page}_offY")
        return if (has) {
            PageCal(
                p.getFloat("p${page}_offX", 0f),
                p.getFloat("p${page}_offY", 0f),
                p.getFloat("p${page}_sx", 1f),
                p.getFloat("p${page}_sy", 1f),
            )
        } else loadGlobal(ctx)
    }
    fun saveForPage(ctx: Context, page: Int, cal: PageCal) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat("p${page}_offX", cal.offX)
            .putFloat("p${page}_offY", cal.offY)
            .putFloat("p${page}_sx", cal.scaleXFix)
            .putFloat("p${page}_sy", cal.scaleYFix)
            .apply()
    }
    fun clearPage(ctx: Context, page: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove("p${page}_offX").remove("p${page}_offY")
            .remove("p${page}_sx").remove("p${page}_sy")
            .apply()
    }
    fun applyToAllPages(ctx: Context, cal: PageCal) {
        val ed = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        for (pg in 1..604) {
            ed.putFloat("p${pg}_offX", cal.offX)
            ed.putFloat("p${pg}_offY", cal.offY)
            ed.putFloat("p${pg}_sx", cal.scaleXFix)
            ed.putFloat("p${pg}_sy", cal.scaleYFix)
        }
        ed.apply()
    }
}
private fun Int.dp(ctx: Context): Int = (this * ctx.resources.displayMetrics.density).toInt()
object CalibrationSheet {
    fun show(
        activity: AppCompatActivity,
        page: Int,
        cal: PageCal,
        onChange: () -> Unit
    ) {
        val ctx = activity
        val bs = BottomSheetDialog(ctx)
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(ctx), 18.dp(ctx), 24.dp(ctx), 12.dp(ctx))
        }
        fun sliderRow(
            title: String, min: Float, max: Float, step: Float,
            getter: () -> Float, setter: (Float)->Unit
        ): View {
            val wrap = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8.dp(ctx), 0, 4.dp(ctx))
            }
            val tv = TextView(ctx).apply {
                textSize = 14f
                text = "$title: ${"%.4f".format(getter())}"
            }
            val sb = SeekBar(ctx)
            val count = ((max - min) / step).toInt().coerceAtLeast(1)
            sb.max = count
            fun valueFromProgress(p: Int) = min + p * step
            fun progressFromValue(x: Float) = ((x - min) / step).toInt().coerceIn(0, count)
            sb.progress = progressFromValue(getter())
            sb.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = valueFromProgress(progress)
                    setter(v)
                    tv.text = "$title: ${"%.4f".format(v)}"
                    onChange()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            wrap.addView(tv); wrap.addView(sb)
            return wrap
        }
        box.addView(TextView(ctx).apply {
            text = "معايرة الصفحة $page"
            textSize = 16f
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, 6.dp(ctx))
        })
        box.addView(sliderRow("offX", -40f, 40f, 0.5f, { cal.offX }, { cal.offX = it }))
        box.addView(sliderRow("offY", -40f, 40f, 0.5f, { cal.offY }, { cal.offY = it }))
        box.addView(sliderRow("scaleXFix", 0.990f, 1.010f, 0.001f, { cal.scaleXFix }, { cal.scaleXFix = it }))
        box.addView(sliderRow("scaleYFix", 0.990f, 1.010f, 0.001f, { cal.scaleYFix }, { cal.scaleYFix = it }))
        val btnSaveGlobal = Button(ctx).apply {
            text = "حفظ كافتراضي لكل الصفحات"
            setOnClickListener {
                CalibrationStore.saveGlobal(ctx, cal)
                Toast.makeText(ctx, "تم حفظ الافتراضي العام.", Toast.LENGTH_SHORT).show()
                onChange()
            }
        }
        val btnApplyAll = Button(ctx).apply {
            text = "تطبيق على جميع الصفحات (1..604)"
            setOnClickListener {
                CalibrationStore.applyToAllPages(ctx, cal)
                Toast.makeText(ctx, "تم التطبيق على جميع الصفحات.", Toast.LENGTH_LONG).show()
                onChange()
            }
        }
        val btnClearPage = Button(ctx).apply {
            text = "إزالة ضبط هذه الصفحة (الرجوع للافتراضي)"
            setOnClickListener {
                CalibrationStore.clearPage(ctx, page)
                Toast.makeText(ctx, "تمت إزالة ضبط الصفحة.", Toast.LENGTH_SHORT).show()
                onChange()
            }
        }
        box.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 6.dp(ctx)) })
        box.addView(btnSaveGlobal); box.addView(btnApplyAll); box.addView(btnClearPage)
        bs.setContentView(box)
        bs.show()
    }
}
data class Seg(val x: Int, val y: Int, val w: Int, val h: Int)
data class AyahBounds(val sura_id: Int, val aya_id: Int, val segs: List<Seg>)
object BoundsRepo {
    private var cacheBounds: Map<Int, List<AyahBounds>>? = null
    private var cacheQuranTextObj: JSONObject? = null
    private var cacheQuranTextArr: JSONArray? = null
    fun loadBoundsMap(context: Context): Map<Int, List<AyahBounds>> {
        cacheBounds?.let { return it }
        val out = try {
            val jsonStr = context.assets.open("pages/ayah_bounds_all.json").bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)
            val map = HashMap<Int, MutableList<AyahBounds>>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val pageKey = keys.next()
                val page = pageKey.toIntOrNull() ?: continue
                val arr = root.getJSONArray(pageKey)
                val list = mutableListOf<AyahBounds>()
                val counters = HashMap<Int, Int>() // sura -> aya_id if missing
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val suraId = obj.getInt("sura_id")
                    val ayaId = if (obj.has("aya_id")) obj.getInt("aya_id") else {
                        val n = (counters[suraId] ?: 0) + 1
                        counters[suraId] = n
                        n
                    }
                    val segsArr = obj.getJSONArray("segs")
                    val segs = ArrayList<Seg>()
                    for (j in 0 until segsArr.length()) {
                        val s = segsArr.getJSONObject(j)
                        segs.add(Seg(s.getDouble("x").toInt(), s.getDouble("y").toInt(), s.getDouble("w").toInt(), s.getDouble("h").toInt()))
                    }
                    list.add(AyahBounds(suraId, ayaId, segs))
                }
                map[page] = list
            }
            map
        } catch (_: Exception) { emptyMap() }
        cacheBounds = out
        return out
    }
}
object NightMode {
    fun applyInvert(imageView: ImageView, context: Context) {
        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (isNight) {
            val cm = ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            val bright = ColorMatrix().apply { setScale(0.92f, 0.92f, 0.92f, 1f) }
            cm.postConcat(bright)
            imageView.colorFilter = ColorMatrixColorFilter(cm)
            imageView.setBackgroundColor(Color.BLACK)
        } else {
            imageView.colorFilter = null
            imageView.setBackgroundColor(Color.TRANSPARENT)
        }
    }
}
