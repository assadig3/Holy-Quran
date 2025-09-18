// File: app/src/main/java/com/hag/al_quran/helpers/QuranAudioHelper.kt
package com.hag.al_quran.helpers

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.util.Log
import android.widget.Toast
import com.hag.al_quran.QuranPageActivity
import com.hag.al_quran.R
import com.hag.al_quran.audio.MadaniPageProvider
import com.hag.al_quran.search.AyahLocator
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList
import androidx.core.content.edit

private const val TAG = "RangeRepeat"

class QuranAudioHelper(
    private val activity: QuranPageActivity,
    private val provider: MadaniPageProvider,
    val supportHelper: QuranSupportHelper,
    private val bgHandler: Handler
) {

    private var mediaPlayer: MediaPlayer? = null

    @Volatile var isPlaying = false
    @Volatile var isAyahPlaying = false
    @Volatile private var playToken: Long = 0
    @Volatile private var suppressAutoNext: Boolean = false

    private val ayahQueue: MutableList<Triple<String, Int, Int>> = CopyOnWriteArrayList()
    private var currentIndex: Int = -1
    private var resumeFromMs: Int = 0

    var autoContinueToNextPage: Boolean = true

    private var singleSurahPlaying: Int? = null
    private var singleAyahPlaying: Int? = null

    // === التكرار الأساسي ===
    // "off" | "page" | "ayah" | "range"
    var repeatMode: String = "off"
    var repeatCount: Int = 1        // عداد تكرار الآية (لنمط "ayah")
    var pageRepeatCount: Int = 1    // عداد تكرار الصفحة (لنمط "page")
    private var currentRepeat = 0
    private var lastRepeatedAyah: Pair<Int, Int>? = null
    private var pageRepeatIteration = 0

    // === تكرار النطاق (تنفيذ مستقل يضمن الترتيب التصاعدي) ===
    private data class RangeRepeatState(
        val surah: Int,
        val startAyah: Int,
        val endAyah: Int,
        var loopsLeft: Int,
        val qariId: String,
        var currentAyah: Int
    )
    @Volatile private var rangeState: RangeRepeatState? = null
    private var savedAutoContinueToNextPage: Boolean? = null

    private val prefs by lazy { activity.getSharedPreferences("quran_audio", Context.MODE_PRIVATE) }
    private fun saveLastAyah(surah: Int, ayah: Int) {
        prefs.edit {
            putInt("last_surah", surah)
            putInt("last_ayah", ayah)
        }
    }
    private fun loadLastAyah(): Pair<Int, Int>? {
        val s = prefs.getInt("last_surah", -1)
        val a = prefs.getInt("last_ayah", -1)
        return if (s > 0 && a > 0) (s to a) else null
    }

    // حفظ/استرجاع آخر نطاق
    data class LastRange(val surah: Int, val fromAyah: Int, val toAyah: Int, val times: Int)
    fun saveLastRange(surah: Int, from: Int, to: Int, times: Int) {
        prefs.edit {
            putInt("range_surah", surah)
            putInt("range_from",  from)
            putInt("range_to",    to)
            putInt("range_times", times)
        }
    }
    fun loadLastRange(): LastRange? {
        val s  = prefs.getInt("range_surah", -1)
        val f  = prefs.getInt("range_from",  -1)
        val t  = prefs.getInt("range_to",    -1)
        val tm = prefs.getInt("range_times", -1)
        return if (s > 0 && f > 0 && t > 0 && tm > 0) LastRange(s, f, t, tm) else null
    }

    // ===================== مصادر الصوت =====================
    private sealed class DataSource {
        data class LocalFile(val file: File): DataSource()
        data class Asset(val afd: AssetFileDescriptor, val debugPath: String): DataSource()
        data class Remote(val url: String): DataSource()
    }

    private fun ensurePlayer(): MediaPlayer {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                }
            }
        }
        return mediaPlayer!!
    }

    private fun clearListeners() {
        try { mediaPlayer?.setOnPreparedListener(null) } catch (_: Exception) {}
        try { mediaPlayer?.setOnCompletionListener(null) } catch (_: Exception) {}
        try { mediaPlayer?.setOnErrorListener(null) } catch (_: Exception) {}
    }

    private fun isActuallyPlaying(): Boolean =
        try { mediaPlayer?.isPlaying == true } catch (_: Exception) { false }

    fun release() {
        suppressAutoNext = true
        playToken++
        clearListeners()
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.reset() } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        isAyahPlaying = false
        cancelRangeRepeat()
    }

    fun stopAll() {
        suppressAutoNext = true
        playToken++
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.reset() } catch (_: Exception) {}
        clearListeners()
        isAyahPlaying = false
        isPlaying = false
        resumeFromMs = 0
        activity.runOnUiThread {
            runCatching { activity.btnPlayAyah.setImageResource(R.drawable.ic_play) }
            runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
            supportHelper.hideAyahBanner()
        }
        singleSurahPlaying = null
        singleAyahPlaying = null
        cancelRangeRepeat()
    }

    fun toggleSingleAyah(surah: Int, ayah: Int, qariId: String) {
        if (isAyahPlaying && singleSurahPlaying == surah && singleAyahPlaying == ayah) {
            stopSingleAyah()
        } else {
            if (isPlaying) stopPagePlayback()
            if (rangeState != null) cancelRangeRepeat()
            playSingleAyah(surah, ayah, qariId)
        }
    }

    fun playSingleAyah(surah: Int, ayah: Int, qariId: String) {
        if (isPlaying) stopSingleAyah()
        if (rangeState != null) cancelRangeRepeat()

        suppressAutoNext = false
        isAyahPlaying = false
        singleSurahPlaying = surah
        singleAyahPlaying = ayah

        val token = ++playToken

        try {
            val mp = ensurePlayer()
            clearListeners()
            mp.reset()

            // --- أوفلاين أولاً ثم أونلاين ---
            when (val ds = resolveAyahDataSource(qariId, surah, ayah)) {
                is DataSource.LocalFile -> {
                    FileInputStream(ds.file).use { fis -> mp.setDataSource(fis.fd) }
                    Log.d("QuranAudioHelper", "Playing OFFLINE(file): ${ds.file.absolutePath}")
                }
                is DataSource.Asset -> {
                    mp.setDataSource(ds.afd.fileDescriptor, ds.afd.startOffset, ds.afd.length)
                    Log.d("QuranAudioHelper", "Playing OFFLINE(asset): ${ds.debugPath}")
                }
                is DataSource.Remote -> {
                    mp.setDataSource(ds.url)
                    toastOnceOnline()
                    Log.d("QuranAudioHelper", "Playing ONLINE(url): ${ds.url}")
                }
            }

            mp.setOnPreparedListener {
                if (token != playToken) return@setOnPreparedListener
                it.start()
                isAyahPlaying = true
                activity.runOnUiThread {
                    runCatching { activity.btnPlayAyah.setImageResource(R.drawable.ic_pause) }
                    val text = supportHelper.getAyahTextFromJson(surah, ayah)
                    supportHelper.showOrUpdateAyahBanner(surah, ayah, text)
                    runCatching { activity.ayahOptionsBar.visibility = android.view.View.VISIBLE }
                    runCatching { activity.adapter.highlightAyahOnPage(activity.currentPage, surah, ayah) }
                }
                saveLastAyah(surah, ayah)

                // لو اشتغل أونلاين، نزّل الملف بصمت للمرة القادمة
                if (!existsAnyLocal(qariId, surah, ayah)) {
                    val remote = provider.getAyahUrl(provider.getQariById(qariId)!!, surah, ayah)
                    supportHelper.downloadOneSilentInBackground(remote, preferredLocalFile(qariId, surah, ayah))
                }
            }

            mp.setOnCompletionListener {
                if (token != playToken) return@setOnCompletionListener
                if (suppressAutoNext) return@setOnCompletionListener
                isAyahPlaying = false
                activity.runOnUiThread {
                    runCatching { activity.btnPlayAyah.setImageResource(R.drawable.ic_play) }
                    supportHelper.hideAyahBanner()
                }
                singleSurahPlaying = null
                singleAyahPlaying = null
            }

            mp.setOnErrorListener { _, _, _ ->
                stopSingleAyah()
                true
            }

            mp.prepareAsync()
        } catch (_: Exception) {
            Toast.makeText(activity, "تعذر تشغيل التلاوة", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopSingleAyah() {
        suppressAutoNext = true
        playToken++
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.reset() } catch (_: Exception) {}
        clearListeners()

        isAyahPlaying = false
        activity.runOnUiThread {
            runCatching { activity.btnPlayAyah.setImageResource(R.drawable.ic_play) }
            supportHelper.hideAyahBanner()
        }
        singleSurahPlaying = null
        singleAyahPlaying = null
    }

    // ====== إعداد قائمة الصفحة ======
    fun prepareAudioQueueForPage(page: Int, qariId: String, fromStart: Boolean = true) {
        bgHandler.post {
            val qari = provider.getQariById(qariId) ?: return@post
            val list = supportHelper.loadAyahBoundsForPage(page)
            ayahQueue.clear()
            for (b in list) {
                val url = provider.getAyahUrl(qari, b.sura_id, b.aya_id) // للأونلاين عند الحاجة
                ayahQueue.add(Triple(url, b.sura_id, b.aya_id))
            }

            if (fromStart) {
                currentIndex = 0
                resumeFromMs = 0
            } else {
                val saved = loadLastAyah()
                currentIndex = saved?.let { (s, a) ->
                    ayahQueue.indexOfFirst { it.second == s && it.third == a }
                }?.takeIf { it >= 0 } ?: 0
                resumeFromMs = 0
            }
        }
    }

    fun onQariChanged() {
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.reset() } catch (_: Exception) {}
        isPlaying = false
        isAyahPlaying = false
        ayahQueue.clear()
        currentIndex = -1
        resumeFromMs = 0
        activity.btnPlayPause.setImageResource(R.drawable.ic_play)
        supportHelper.hideAyahBanner()
        cancelRangeRepeat()
    }

    // ====== تشغيل صفحة كاملة ======
    fun startPagePlayback(page: Int, qariId: String, fromStart: Boolean = true) {
        if (isAyahPlaying) stopSingleAyah()
        if (rangeState != null) cancelRangeRepeat()

        currentRepeat = 0; lastRepeatedAyah = null
        pageRepeatIteration = 0
        prepareAudioQueueForPage(page, qariId, fromStart)

        activity.window.decorView.postDelayed({
            if (ayahQueue.isEmpty()) {
                Toast.makeText(activity, "لا توجد آيات على الصفحة الحالية.", Toast.LENGTH_SHORT).show()
            } else {
                if (currentIndex !in ayahQueue.indices) currentIndex = 0
                suppressAutoNext = false
                playAt(currentIndex, resumeFromMs, qariId)
            }
        }, 80)
    }

    fun pausePagePlayback() {
        suppressAutoNext = true
        try {
            mediaPlayer?.let {
                resumeFromMs = it.currentPosition
                it.pause()
            }
        } catch (_: Exception) {}
        isPlaying = false
        activity.runOnUiThread { runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) } }
    }

    fun stopPagePlayback() {
        suppressAutoNext = true
        playToken++
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.reset() } catch (_: Exception) {}
        clearListeners()

        isPlaying = false
        isAyahPlaying = false
        resumeFromMs = 0
        activity.runOnUiThread {
            runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
            supportHelper.hideAyahBanner()
        }
    }

    fun resumePagePlayback(): Boolean {
        return try {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    suppressAutoNext = false
                    it.start()
                    isPlaying = true
                    activity.runOnUiThread { runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_pause) } }
                    true
                } else false
            } ?: false
        } catch (_: Exception) { false }
    }

    fun togglePlayPause(page: Int, qariId: String): Boolean {
        if (isAyahPlaying) stopSingleAyah()
        return if (isActuallyPlaying()) {
            stopPagePlayback()
            false
        } else {
            val resumed = resumePagePlayback()
            if (!resumed) startPagePlayback(page, qariId)
            true
        }
    }

    // ====== تشغيل/إلغاء تكرار نطاق آيات (من..إلى داخل سورة واحدة) ======
    fun startRangeRepeat(surah: Int, fromAyah: Int, toAyah: Int, times: Int, qariId: String) {
        // تصحيح الحدود لضمان الترتيب التصاعدي
        val start = if (fromAyah <= toAyah) fromAyah else toAyah
        val end   = if (fromAyah <= toAyah) toAyah   else fromAyah
        val loops = times.coerceIn(1, 99)

        Log.d(TAG, "startRangeRepeat s=$surah, $start..$end x$loops, qari=$qariId")
        saveLastRange(surah, start, end, loops)

        // أوقف أي تشغيل آخر
        stopPagePlayback()
        stopSingleAyah()

        // عطّل الانتقال التلقائي بين الصفحات مؤقتًا
        savedAutoContinueToNextPage = savedAutoContinueToNextPage ?: autoContinueToNextPage
        autoContinueToNextPage = false

        // أوقف أنماط التكرار الأخرى
        repeatMode = "range"
        currentRepeat = 0
        lastRepeatedAyah = null

        // جهّز الحالة وابدأ
        rangeState = RangeRepeatState(
            surah = surah,
            startAyah = start,
            endAyah = end,
            loopsLeft = loops,
            qariId = qariId,
            currentAyah = start
        )

        suppressAutoNext = false
        playToken++
        playRangeCurrent()
    }

    fun cancelRangeRepeat() {
        Log.d(TAG, "cancelRangeRepeat()")
        if (rangeState == null && repeatMode != "range") return
        rangeState = null
        if (repeatMode == "range") repeatMode = "off"
        savedAutoContinueToNextPage?.let { autoContinueToNextPage = it }
        savedAutoContinueToNextPage = null
    }

    /** تشغيل آية واحدة ضمن حالة تكرار النطاق ثم اتخاذ القرار التالي تصاعديًا */
    private fun playRangeCurrent() {
        val st = rangeState ?: return
        val token = ++playToken

        Log.d(TAG, "playRangeCurrent ayah=${st.currentAyah}/${st.endAyah}, loopsLeft=${st.loopsLeft}")

        try {
            val mp = ensurePlayer()
            clearListeners()
            mp.reset()

            // أوفلاين أولاً ثم أونلاين
            when (val ds = resolveAyahDataSource(st.qariId, st.surah, st.currentAyah)) {
                is DataSource.LocalFile -> {
                    FileInputStream(ds.file).use { fis -> mp.setDataSource(fis.fd) }
                }
                is DataSource.Asset -> {
                    mp.setDataSource(ds.afd.fileDescriptor, ds.afd.startOffset, ds.afd.length)
                }
                is DataSource.Remote -> {
                    val q = provider.getQariById(st.qariId)
                    val remoteUrl = if (q != null) provider.getAyahUrl(q, st.surah, st.currentAyah) else ""
                    mp.setDataSource(remoteUrl)
                    toastOnceOnline()
                }
            }

            mp.setOnPreparedListener {
                if (token != playToken) return@setOnPreparedListener
                it.start()
                isPlaying = true
                isAyahPlaying = true

                // تحديث حالة الشاشة والانتقال لصفحة الآية الحالية
                activity.currentSurah = st.surah
                activity.currentAyah = st.currentAyah
                activity.runOnUiThread {
                    runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_pause) }

                    // احسب صفحة الآية وافتحها إن كانت مختلفة
                    val page = try { AyahLocator.getPageFor(activity, st.surah, st.currentAyah) } catch (_: Throwable) { -1 }
                    if (page in 1..604 && page != activity.currentPage) {
                        runCatching { activity.viewPager.setCurrentItem(page - 1, true) }
                    }
                    // بعد انتقال الصفحة أعطِ مهلة خفيفة ثم ظلّل الآية
                    activity.window.decorView.postDelayed({
                        runCatching { activity.adapter.highlightAyahOnPage(activity.currentPage, st.surah, st.currentAyah) }
                    }, 120)
                }

                val ayahText = supportHelper.getAyahTextFromJson(st.surah, st.currentAyah)
                supportHelper.showOrUpdateAyahBanner(st.surah, st.currentAyah, ayahText)
                supportHelper.showAyahOptionsBar(st.surah, st.currentAyah, ayahText)
                saveLastAyah(st.surah, st.currentAyah)

                if (!existsAnyLocal(st.qariId, st.surah, st.currentAyah)) {
                    val remote = provider.getAyahUrl(provider.getQariById(st.qariId)!!, st.surah, st.currentAyah)
                    supportHelper.downloadOneSilentInBackground(remote, preferredLocalFile(st.qariId, st.surah, st.currentAyah))
                }
            }

            mp.setOnCompletionListener {
                if (token != playToken) return@setOnCompletionListener
                if (suppressAutoNext) {
                    isPlaying = false
                    isAyahPlaying = false
                    return@setOnCompletionListener
                }

                val state = rangeState ?: return@setOnCompletionListener

                // تقدّم تصاعديًا داخل النطاق
                if (state.currentAyah < state.endAyah) {
                    Log.d(TAG, "completed ayah=${state.currentAyah}, advancing...")
                    state.currentAyah += 1
                    playRangeCurrent()
                    return@setOnCompletionListener
                }

                // انتهى مرور واحد على كامل النطاق
                state.loopsLeft -= 1
                if (state.loopsLeft > 0) {
                    Log.d(TAG, "range loop finished, loopsLeft=${state.loopsLeft}, restarting from ${state.startAyah}")
                    state.currentAyah = state.startAyah
                    playRangeCurrent()
                } else {
                    // انتهى النطاق
                    Log.d(TAG, "range finished ✓")
                    rangeState = null
                    savedAutoContinueToNextPage?.let { ac -> autoContinueToNextPage = ac }
                    savedAutoContinueToNextPage = null
                    repeatMode = "off"
                    isPlaying = false
                    isAyahPlaying = false
                    activity.runOnUiThread {
                        runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
                        supportHelper.hideAyahBanner()
                    }
                }
            }

            mp.setOnErrorListener { _, _, _ ->
                val state = rangeState
                Log.w(TAG, "error while playing ayah=${state?.currentAyah}, skipping ahead")
                if (state != null) {
                    if (state.currentAyah < state.endAyah) {
                        state.currentAyah += 1
                        playRangeCurrent()
                    } else {
                        state.loopsLeft -= 1
                        if (state.loopsLeft > 0) {
                            state.currentAyah = state.startAyah
                            playRangeCurrent()
                        } else {
                            rangeState = null
                            savedAutoContinueToNextPage?.let { ac -> autoContinueToNextPage = ac }
                            savedAutoContinueToNextPage = null
                            repeatMode = "off"
                            isPlaying = false
                            isAyahPlaying = false
                        }
                    }
                }
                true
            }

            mp.prepareAsync()
        } catch (_: Exception) {
            // أي استثناء: طبّق نفس منطق onError
            val state = rangeState
            Log.w(TAG, "exception while preparing ayah=${state?.currentAyah}, skipping ahead")
            if (state != null) {
                if (state.currentAyah < state.endAyah) {
                    state.currentAyah += 1
                    playRangeCurrent()
                } else {
                    state.loopsLeft -= 1
                    if (state.loopsLeft > 0) {
                        state.currentAyah = state.startAyah
                        playRangeCurrent()
                    } else {
                        rangeState = null
                        savedAutoContinueToNextPage?.let { ac -> autoContinueToNextPage = ac }
                        savedAutoContinueToNextPage = null
                        repeatMode = "off"
                        isPlaying = false
                        isAyahPlaying = false
                    }
                }
            }
        }
    }

    // ===================== تشغيل عام (صفحات) =====================

    private fun playAt(index: Int, startMs: Int, qariId: String) {
        if (index !in ayahQueue.indices) {
            // انتهت آيات الصفحة
            if (!suppressAutoNext && repeatMode == "page" &&
                pageRepeatCount > 1 && pageRepeatIteration < pageRepeatCount - 1) {
                pageRepeatIteration += 1
                currentIndex = 0
                resumeFromMs = 0
                playAt(0, 0, qariId)
                return
            } else {
                pageRepeatIteration = 0
            }

            activity.runOnUiThread {
                runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
                supportHelper.hideAyahBanner()
            }
            if (suppressAutoNext) {
                isPlaying = false
                return
            }
            val next = activity.currentPage + 1
            if (autoContinueToNextPage && next <= 604) {
                prepareAudioQueueForPage(next, qariId, fromStart = true)
                runCatching { activity.viewPager.setCurrentItem(next - 1, true) }
                activity.window.decorView.postDelayed({
                    currentIndex = 0
                    resumeFromMs = 0
                    if (ayahQueue.isNotEmpty()) playAt(0, 0, qariId)
                    else {
                        isPlaying = false
                        activity.runOnUiThread {
                            runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
                            supportHelper.hideAyahBanner()
                        }
                    }
                }, 180)
            } else {
                isPlaying = false
            }
            return
        }

        val (url, s, a) = ayahQueue[index]
        activity.currentSurah = s
        activity.currentAyah = a
        runCatching { activity.adapter.highlightAyahOnPage(activity.currentPage, s, a) }

        val token = ++playToken

        try {
            val mp = ensurePlayer()
            clearListeners()
            mp.reset()

            // --- أوفلاين أولاً ثم أونلاين ---
            when (val ds = resolveAyahDataSource(qariId, s, a)) {
                is DataSource.LocalFile -> {
                    FileInputStream(ds.file).use { fis -> mp.setDataSource(fis.fd) }
                    Log.d("QuranAudioHelper", "Playing OFFLINE(file): ${ds.file.absolutePath}")
                }
                is DataSource.Asset -> {
                    mp.setDataSource(ds.afd.fileDescriptor, ds.afd.startOffset, ds.afd.length)
                    Log.d("QuranAudioHelper", "Playing OFFLINE(asset): ${ds.debugPath}")
                }
                is DataSource.Remote -> {
                    val remoteUrl = if (url.isNotBlank()) url else ds.url
                    mp.setDataSource(remoteUrl)
                    toastOnceOnline()
                    Log.d("QuranAudioHelper", "Playing ONLINE(url): $remoteUrl")
                }
            }

            mp.setOnPreparedListener {
                if (token != playToken) return@setOnPreparedListener
                if (startMs > 0) it.seekTo(startMs)
                it.start()
                isPlaying = true
                isAyahPlaying = true
                activity.runOnUiThread { runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_pause) } }

                val ayahText = supportHelper.getAyahTextFromJson(s, a)
                supportHelper.showOrUpdateAyahBanner(s, a, ayahText)
                supportHelper.showAyahOptionsBar(s, a, ayahText)
                saveLastAyah(s, a)

                // تنزيل صامت إن كنا على الأونلاين
                if (!existsAnyLocal(qariId, s, a)) {
                    val remote = provider.getAyahUrl(provider.getQariById(qariId)!!, s, a)
                    supportHelper.downloadOneSilentInBackground(remote, preferredLocalFile(qariId, s, a))
                }
            }

            mp.setOnCompletionListener {
                if (token != playToken) return@setOnCompletionListener
                if (suppressAutoNext || !isPlaying) {
                    isPlaying = false
                    activity.runOnUiThread {
                        runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
                        supportHelper.hideAyahBanner()
                    }
                    return@setOnCompletionListener
                }

                // تكرار آية مفردة
                if (repeatMode == "ayah") {
                    val key = s to a
                    if (lastRepeatedAyah == key) currentRepeat++ else { currentRepeat = 1; lastRepeatedAyah = key }
                    if (currentRepeat < repeatCount) {
                        resumeFromMs = 0
                        playAt(currentIndex, 0, qariId)
                        return@setOnCompletionListener
                    } else {
                        currentRepeat = 0; lastRepeatedAyah = null
                    }
                }

                resumeFromMs = 0
                currentIndex += 1
                playAt(currentIndex, 0, qariId)
            }

            mp.setOnErrorListener { _, _, _ ->
                resumeFromMs = 0
                currentIndex += 1
                playAt(currentIndex, 0, qariId)
                true
            }

            mp.prepareAsync()
        } catch (_: Exception) {
            Toast.makeText(activity, "تعذر تشغيل التلاوة", Toast.LENGTH_SHORT).show()
            resumeFromMs = 0
            currentIndex += 1
            playAt(currentIndex, 0, qariId)
        }
    }

    // ===================== أوفلاين أولاً (مسارات + الأصول) =====================

    private fun resolveAyahDataSource(qariId: String, surah: Int, ayah: Int): DataSource {
        // 1) externalFilesDir/recitations/<qariId>/<SSS><AAA>.mp3
        val f1 = File(qariDirRecitations(qariId), fileName(surah, ayah))
        if (f1.exists() && f1.length() > 0L) return DataSource.LocalFile(f1)

        // 2) externalFilesDir/quran_audio/<qariId>/<SSS><AAA>.mp3  (دعم مسار قديم)
        val f2 = File(qariDirQuranAudio(qariId), fileName(surah, ayah))
        if (f2.exists() && f2.length() > 0L) return DataSource.LocalFile(f2)

        // 3) assets/quran_audio/<qariId>/<SSS><AAA>.mp3
        val assetRel = "quran_audio/${safeQari(qariId)}/${fileName(surah, ayah)}"
        try {
            val afd = activity.assets.openFd(assetRel)
            return DataSource.Asset(afd, assetRel)
        } catch (_: Throwable) { /* غير موجود في الأصول */ }

        // 4) أونلاين
        val qari = provider.getQariById(qariId)
        val url = if (qari != null) provider.getAyahUrl(qari, surah, ayah) else ""
        return DataSource.Remote(url)
    }

    private fun existsAnyLocal(qariId: String, surah: Int, ayah: Int): Boolean {
        val n = fileName(surah, ayah)
        return File(qariDirRecitations(qariId), n).exists() ||
                File(qariDirQuranAudio(qariId), n).exists()
    }

    private fun preferredLocalFile(qariId: String, surah: Int, ayah: Int): File =
        File(qariDirRecitations(qariId), fileName(surah, ayah))

    // ===================== مسارات التخزين =====================

    private fun qariDirRecitations(qariId: String): File {
        val dir = File(activity.getExternalFilesDir(null), "recitations/${safeQari(qariId)}")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun qariDirQuranAudio(qariId: String): File {
        val dir = File(activity.getExternalFilesDir(null), "quran_audio/${safeQari(qariId)}")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun safeQari(qariId: String): String =
        qariId.lowercase()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-z0-9_\\-]".toRegex(), "")

    private fun fileName(surah: Int, ayah: Int): String =
        "%03d%03d.mp3".format(surah, ayah)

    // ===================== رسائل + Fallback أونلاين =====================

    private fun tryOnlineFallback(
        mp: MediaPlayer,
        qariId: String,
        surah: Int,
        ayah: Int,
        token: Long
    ) {
        try {
            val qari = provider.getQariById(qariId)
            val online = if (qari != null) provider.getAyahUrl(qari, surah, ayah) else ""
            mp.reset()
            mp.setDataSource(online)
            mp.setOnPreparedListener {
                if (token != playToken) return@setOnPreparedListener
                it.start()
                isAyahPlaying = true
                isPlaying = true
                activity.runOnUiThread { runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_pause) } }
            }
            mp.prepareAsync()
            toastOnceOnline()
        } catch (e: Throwable) {
            Log.e("QuranAudioHelper", "Online fallback failed", e)
            Toast.makeText(activity, activity.getString(R.string.audio_error), Toast.LENGTH_SHORT).show()
            stopAll()
        }
    }

    fun stopAllPlaybackAndClearQueue() {
        try { pausePagePlayback() } catch (_: Exception) {}
        try { stopSingleAyah() } catch (_: Exception) {}

        try {
            mediaPlayer?.reset()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null

        clearPendingQueueInternal()
        isPlaying = false
        isAyahPlaying = false
        cancelRangeRepeat()
    }

    /** امسح أي قائمة انتظار/مؤشرات داخليّة لديك */
    private fun clearPendingQueueInternal() {
        // مثال (إن وُجدت لديك هذه البُنى):
        // pageQueue.clear()
        // currentQueueIndex = 0
    }

    @Volatile private var onlineToastShown = false
    private fun toastOnceOnline() {
        if (!onlineToastShown) {
            onlineToastShown = true
            try {
                Toast.makeText(activity, activity.getString(R.string.playing_online_fallback), Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
            bgHandler.postDelayed({ onlineToastShown = false }, 6000L)
        }
    }
}
