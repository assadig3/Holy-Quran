// File: app/src/main/java/com/hag/al_quran/AssetPageAdapter.kt
package com.hag.al_quran

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import com.github.chrisbanes.photoview.PhotoViewAttacher
import com.hag.al_quran.ui.NightMode
import com.hag.al_quran.ui.PageImageLoader
import com.hag.al_quran.utils.AyahHighlightView
import com.hag.al_quran.utils.Seg

// ----- Types / Aliases -----
typealias AyahBounds = AyahBoundsRepo

// ----- ROI -----
data class Roi(val l: Float, val t: Float, val r: Float, val b: Float)

/** قياس الأساس للحدود (العرض 290 ثابت، الارتفاع من أكبر y+h) */
private fun baseSizeFor(boundsList: List<AyahBounds>): Pair<Float, Float> {
    if (boundsList.isEmpty()) return 290f to 428f
    var maxY = 0f
    for (ab in boundsList) for (s in ab.segs) {
        val ey = (s.y + s.h).toFloat()
        if (ey > maxY) maxY = ey
    }
    val h = if (maxY > 0f) maxY else 428f
    return 290f to h
}

/** ROI ديناميكي من حدود الآيات، مع حالات افتراضية للصفحات الزخرفية */
private fun roiFromBoundsOrDefault(
    page: Int,
    boundsList: List<AyahBounds>,
    baseW: Float,
    baseH: Float
): Roi {
    if (boundsList.isEmpty()) {
        return when (page) {
            1 -> Roi(0.060f, 0.140f, 0.940f, 0.900f)
            2 -> Roi(0.060f, 0.120f, 0.940f, 0.910f)
            else -> Roi(0.045f, 0.095f, 0.955f, 0.915f)
        }
    }

    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY

    for (ab in boundsList) {
        for (s in ab.segs) {
            if (s.x < minX) minX = s.x.toFloat()
            if (s.y < minY) minY = s.y.toFloat()
            val ex = (s.x + s.w).toFloat()
            val ey = (s.y + s.h).toFloat()
            if (ex > maxX) maxX = ex
            if (ey > maxY) maxY = ey
        }
    }

    if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) {
        return Roi(0.045f, 0.095f, 0.955f, 0.915f)
    }

    // هامش بسيط حول النص
    val padX = 0.02f * baseW
    val padY = 0.02f * baseH
    minX = (minX - padX).coerceAtLeast(0f)
    minY = (minY - padY).coerceAtLeast(0f)
    maxX = (maxX + padX).coerceAtMost(baseW)
    maxY = (maxY + padY).coerceAtMost(baseH)

    return Roi(
        l = (minX / baseW),
        t = (minY / baseH),
        r = (maxX / baseW),
        b = (maxY / baseH)
    )
}

// تحويل Seg إلى مستطيل على الشاشة
private fun mapSegToViewRect(
    attacher: PhotoViewAttacher,
    seg: Seg,
    baseW: Float,
    baseH: Float,
    roi: Roi
): RectF {
    val dr = attacher.displayRect ?: return RectF()
    val content = RectF(
        dr.left + roi.l * dr.width(),
        dr.top + roi.t * dr.height(),
        dr.left + roi.r * dr.width(),
        dr.top + roi.b * dr.height()
    )
    val sx = content.width() / baseW
    val sy = content.height() / baseH

    val left = content.left + seg.x * sx
    val top = content.top + seg.y * sy
    val right = left + seg.w * sx
    val bottom = top + seg.h * sy

    val inset = 0.015f * (bottom - top)
    return RectF(left, top + inset, right, bottom - inset)
}

class AssetPageAdapter(
    private val context: Context,
    private val pages: List<String>,
    private val realPageNumber: Int,
    private val onAyahClick: (surah: Int, ayah: Int, ayahText: String) -> Unit,
    private val onImageTap: () -> Unit,
    private val onNeedPagesDownload: () -> Unit = {},
    private val loaderHost: CenterLoaderHost? = null,
    private var topPaddingPx: Int = 0 // مسافة ثابتة أسفل الشريط العلوي (اختياري)
) : RecyclerView.Adapter<AssetPageAdapter.PageViewHolder>() {

    /** يمكن استدعاؤها من الـActivity لتحديث المسافة العلوية الثابتة */
    fun setFixedTopPadding(paddingPx: Int) {
        if (paddingPx != topPaddingPx) {
            topPaddingPx = paddingPx
            notifyDataSetChanged()
        }
    }

    init { setHasStableIds(true) }

    var selectedAyah: Pair<Int, Int>? = null
    private val ayahBoundsMap by lazy { BoundsRepo.loadBoundsMap(context) }
    private val selectionByPage = mutableMapOf<Int, Pair<Int, Int>?>()

    private fun pageToIndex(page: Int) = (page - 1).coerceIn(0, pages.size - 1)

    fun highlightAyahOnPage(page: Int, surah: Int, ayah: Int) {
        selectionByPage[page] = surah to ayah
        notifyItemChanged(pageToIndex(page))
    }

    fun clearHighlightOnPage(page: Int) {
        selectionByPage.remove(page)
        notifyItemChanged(pageToIndex(page))
    }

    class PageViewHolder(
        itemView: View,
        val photoView: PhotoView,
        val overlay: AyahHighlightView
    ) : RecyclerView.ViewHolder(itemView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val scroll = ScrollView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, topPaddingPx, 0, 0)
            clipToPadding = false
        }

        val root = FrameLayout(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }

        val photo = PhotoView(parent.context).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setZoomable(false)
            isClickable = true
        }

        val overlay = AyahHighlightView(parent.context).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            isClickable = false
            isFocusable = false
            setBackgroundColor(Color.TRANSPARENT)
            setRects(emptyList())
            setOnTouchListener { _, _ -> false }
        }

        root.addView(photo)
        root.addView(overlay)
        scroll.addView(root)
        return PageViewHolder(scroll, photo, overlay)
    }

    // مطابقة ذكية لمعالجة فرق البسملة (±1)
    private fun resolveAyahBounds(
        boundsList: List<AyahBounds>,
        surah: Int,
        ayah: Int
    ): AyahBounds? {
        boundsList.firstOrNull { it.sura_id == surah && it.aya_id == ayah }?.let { return it }
        if (surah != 9) {
            boundsList.firstOrNull { it.sura_id == surah && it.aya_id == ayah + 1 }?.let { return it }
            if (ayah > 1) {
                boundsList.firstOrNull { it.sura_id == surah && it.aya_id == ayah - 1 }?.let { return it }
            }
        }
        val sameSurah = boundsList.filter { it.sura_id == surah }
        if (sameSurah.isNotEmpty()) return sameSurah.minByOrNull { kotlin.math.abs(it.aya_id - ayah) }
        return null
    }

    // ننتظر حتى تجهز displayRect
    private fun waitForDisplayRect(
        pv: PhotoView,
        tries: Int = 12,
        delayMs: Long = 32L,
        ready: () -> Unit
    ) {
        fun check(left: Int) {
            val dr = pv.attacher.displayRect
            val ok = dr != null && dr.width() > 0f && dr.height() > 0f && pv.drawable != null
            if (ok) ready()
            else if (left > 0) pv.postDelayed({ check(left - 1) }, delayMs)
            else ready()
        }
        pv.post { check(tries) }
    }

    // إعادة ضبط التكبير بحيث تلائم الصورة العرض
    private fun resetScaleToFit(pv: PhotoView) {
        val att = pv.attacher
        att.setZoomable(true)
        val min = pv.minimumScale
        if (min > 0f) pv.setScale(min, false)
        else att.setDisplayMatrix(android.graphics.Matrix())
        att.setZoomable(false)
        pv.scaleType = ImageView.ScaleType.FIT_CENTER
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val pageNumber = if (realPageNumber == 0) position + 1 else realPageNumber

        holder.photoView.scaleType = ImageView.ScaleType.FIT_CENTER

        // تحميل الصورة
        PageImageLoader.loadWithCallbacks(
            context = context,
            pageNumber = pageNumber,
            into = holder.photoView,
            mode = PageImageLoader.Mode.NORMAL,
            onStart = { },
            onReady = { },
            onFail  = { }
        )

        NightMode.applyInvert(holder.photoView, context)

        waitForDisplayRect(holder.photoView) {
            resetScaleToFit(holder.photoView)

            val att = holder.photoView.attacher
            val boundsList = ayahBoundsMap[pageNumber] ?: emptyList()

            val (BASE_W, BASE_H) = baseSizeFor(boundsList)
            val roi = roiFromBoundsOrDefault(pageNumber, boundsList, BASE_W, BASE_H)

            val isNight = (context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val highlightColor = if (isNight) Color.argb(110, 80, 220, 140) else Color.argb(100, 52, 199, 89)
            holder.overlay.setColor(highlightColor)

            fun toScreenRects(ab: AyahBounds?): List<RectF> =
                ab?.segs?.map { seg -> mapSegToViewRect(att, toUtilsSeg(seg), BASE_W, BASE_H, roi) } ?: emptyList()

            var selected: AyahBounds? = null
            selectionByPage[pageNumber]?.let { (s, a) ->
                selected = resolveAyahBounds(boundsList, s, a)
                holder.overlay.setRects(toScreenRects(selected))
            } ?: holder.overlay.setRects(emptyList())

            // نقرة: إظهار/إخفاء الأشرطة | ضغطة مطولة: اختيار آية + Haptic
            var lastToggleAt = 0L
            val TAP_DEBOUNCE_MS = 400L

            val detector = GestureDetector(holder.photoView.context,
                object : GestureDetector.SimpleOnGestureListener() {

                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastToggleAt < TAP_DEBOUNCE_MS) return true
                        lastToggleAt = now
                        onImageTap()
                        return true
                    }

                    override fun onLongPress(e: MotionEvent) {
                        val dr = att.displayRect ?: return
                        val content = RectF(
                            dr.left + roi.l * dr.width(),
                            dr.top + roi.t * dr.height(),
                            dr.left + roi.r * dr.width(),
                            dr.top + roi.b * dr.height()
                        )
                        if (!content.contains(e.x, e.y)) return

                        val rx = (e.x - content.left) / content.width()
                        val ry = (e.y - content.top) / content.height()
                        val imgX = rx * BASE_W
                        val imgY = ry * BASE_H

                        var hit: AyahBounds? = null
                        loop@ for (ab in boundsList) {
                            for (s in ab.segs) {
                                if (imgX >= s.x && imgX <= s.x + s.w &&
                                    imgY >= s.y && imgY <= s.y + s.h
                                ) { hit = ab; break@loop }
                            }
                        }
                        if (hit != null) {
                            holder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

                            val oldR = toScreenRects(selected)
                            selected = hit
                            selectionByPage[pageNumber] = hit.sura_id to hit.aya_id
                            val newR = toScreenRects(selected)

                            if (oldR.isNotEmpty() && newR.isNotEmpty() && oldR.size == newR.size)
                                holder.overlay.animateTo(newR, 160)
                            else
                                holder.overlay.setRects(newR)

                            onAyahClick(
                                hit.sura_id,
                                hit.aya_id,
                                BoundsRepo.getAyahText(context, hit.sura_id, hit.aya_id)
                            )
                        }
                    }
                })

            // نعتمد الكاشف أعلاه فقط
            holder.photoView.setOnPhotoTapListener(null)
            holder.photoView.setOnViewTapListener(null)

            holder.photoView.setOnTouchListener { _, ev ->
                detector.onTouchEvent(ev)
                false
            }

            var firstMatrix = true
            att.setOnMatrixChangeListener {
                if (firstMatrix) {
                    firstMatrix = false
                    val cur = holder.photoView.scale
                    val min = holder.photoView.minimumScale
                    if (cur - min > 0.001f) {
                        holder.photoView.post { resetScaleToFit(holder.photoView) }
                    }
                }
                holder.overlay.setRects(toScreenRects(selected))
            }
        }
    }

    override fun onViewAttachedToWindow(holder: PageViewHolder) {
        super.onViewAttachedToWindow(holder)
        holder.photoView.post { resetScaleToFit(holder.photoView) }
    }

    override fun onViewRecycled(holder: PageViewHolder) {
        try {
            holder.overlay.setRects(emptyList())
            holder.photoView.scaleType = ImageView.ScaleType.FIT_CENTER
            resetScaleToFit(holder.photoView)
        } finally {
            super.onViewRecycled(holder)
        }
    }

    override fun getItemCount(): Int = pages.size

    override fun getItemId(position: Int): Long {
        val pageNumber = if (realPageNumber == 0) position + 1 else realPageNumber
        return pageNumber.toLong()
    }

    companion object {
        private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    // تحويل من com.hag.al_quran.Seg إلى com.hag.al_quran.utils.Seg
    private fun toUtilsSeg(s: com.hag.al_quran.Seg): com.hag.al_quran.utils.Seg =
        com.hag.al_quran.utils.Seg(s.x, s.y, s.w, s.h)
}
