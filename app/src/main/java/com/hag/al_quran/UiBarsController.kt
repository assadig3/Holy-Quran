// File: app/src/main/java/com/hag/al_quran/ui/UiBarsController.kt
package com.hag.al_quran.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.graphics.Insets
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * متحكم واجهة:
 * - نقرة واحدة: تبديل إظهار/إخفاء الأشرطة (Toolbar + BottomBar).
 * - إخفاء تلقائي بعد delayMs (افتراضي 4000ms).
 * - Immersive متوافق عبر AndroidX.
 */
class UiBarsController(
    private val activity: Activity,
    private val contentRoot: ViewGroup,          // الجذر الذي يحتوي المحتوى
    private val chromeViews: List<View>,         // الأشرطة التي نريد إظهارها/إخفاءها
    private val delayMs: Long = 4000L
) {

    private val window = activity.window
    private val insetsController by lazy {
        WindowInsetsControllerCompat(window, contentRoot).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private val gestureDetector: GestureDetectorCompat =
        GestureDetectorCompat(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                toggleBars()
                return true
            }
        })

    private var barsVisible = true
    private var lastInsets: WindowInsetsCompat? = null
    private val autoHideRunnable = Runnable { hideBars(animate = true) }

    val isBarsVisible: Boolean get() = barsVisible

    init {
        // اجعل المحتوى تحت أشرطة النظام (نستخدم insets لتعديل الـ padding)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // WindowInsetsCompat (متوافق مع minSdk 24)
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { v, insets ->
            lastInsets = insets
            val sys: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (barsVisible) v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            else v.setPadding(0, 0, 0, 0)
            insets
        }

        setTapListener(contentRoot)
    }

    fun attach() {
        showBars(animate = false)
        scheduleAutoHide()
    }

    fun onUserInteraction() {
        if (!barsVisible) showBars(animate = true)
        scheduleAutoHide()
    }

    fun onWindowFocusGained() {
        scheduleAutoHide()
    }

    fun showThenAutoHide(delay: Long? = null) {
        showBars(animate = true)
        contentRoot.removeCallbacks(autoHideRunnable)
        contentRoot.postDelayed(autoHideRunnable, delay ?: this.delayMs)
    }

    fun toggleBars() {
        if (barsVisible) hideBars(animate = true) else showBars(animate = true)
    }

    fun showBars(animate: Boolean) {
        barsVisible = true
        activity.runOnUiThread {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            lastInsets?.let {
                val sys = it.getInsets(WindowInsetsCompat.Type.systemBars())
                contentRoot.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            }
            chromeViews.forEach { v ->
                if (animate) v.animate().alpha(1f).setDuration(180).withStartAction { v.visibility = View.VISIBLE }.start()
                else { v.alpha = 1f; v.visibility = View.VISIBLE }
            }
        }
    }

    fun hideBars(animate: Boolean) {
        barsVisible = false
        activity.runOnUiThread {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            contentRoot.setPadding(0, 0, 0, 0)
            chromeViews.forEach { v ->
                if (animate) v.animate().alpha(0f).setDuration(140).withEndAction { v.visibility = View.GONE }.start()
                else { v.alpha = 0f; v.visibility = View.GONE }
            }
        }
    }

    private fun scheduleAutoHide() {
        contentRoot.removeCallbacks(autoHideRunnable)
        contentRoot.postDelayed(autoHideRunnable, delayMs)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setTapListener(target: View) {
        target.setOnTouchListener { _, ev ->
            gestureDetector.onTouchEvent(ev)
            false // لا نستهلك الحدث؛ نسمح للتمرير/الـ ViewPager
        }
    }
}
