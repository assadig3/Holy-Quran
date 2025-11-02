// File: app/src/main/java/com/hag/al_quran/helpers/QuranAudioHelper.kt
package com.hag.al_quran.helpers

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.widget.Toast
import androidx.core.content.edit
import com.hag.al_quran.QuranPageActivity
import com.hag.al_quran.R
import com.hag.al_quran.audio.MadaniPageProvider
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList

class QuranAudioHelper(
    private val activity: QuranPageActivity,
    private val provider: MadaniPageProvider,
    val supportHelper: QuranSupportHelper,
    private val bgHandler: Handler
) {

    // ================= Ayah Banner Gate =================
    // الـ Activity (QuranPageActivity) ستطبّق هذه الواجهة وتقرر هل فعلاً
    // تعرض بانر الآية أو لا (لو المستخدم قفله نهائياً، ترفض الإظهار)
    interface AyahBannerGate {
        fun requestShowAyahBanner(surah: Int, ayah: Int, text: String)
        fun requestHideAyahBanner()
    }

    @Volatile
    private var ayahBannerGate: AyahBannerGate? = null

    fun setAyahBannerGate(gate: AyahBannerGate?) {
        ayahBannerGate = gate
    }
    // =====================================================

    private var mediaPlayer: MediaPlayer? = null

    @Volatile var isPlaying = false
    @Volatile var isAyahPlaying = false
    @Volatile private var playToken: Long = 0
    @Volatile private var suppressAutoNext: Boolean = false

    // Queue of ayat for the current page:
    // Triple(url, surahNumber, ayahNumber)
    private val ayahQueue: MutableList<Triple<String, Int, Int>> = CopyOnWriteArrayList()
    private var currentIndex: Int = -1
    private var resumeFromMs: Int = 0

    var autoContinueToNextPage: Boolean = true

    private var singleSurahPlaying: Int? = null
    private var singleAyahPlaying: Int? = null
    @Volatile private var playGen: Int = 0

    // التكرار
    // repeatMode: "off" | "page" | "ayah"
    var repeatMode: String = "off"
    var repeatCount: Int = 1
    var pageRepeatCount: Int = 1
    private var currentRepeat = 0
    private var lastRepeatedAyah: Pair<Int, Int>? = null
    private var pageRepeatIteration = 0

    // نداء نهاية الصفحة — الـ Activity يضبطه
    var onPagePlaybackComplete: (() -> Unit)? = null

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

    // ======== مصادر الصوت ========
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

    // ======== تحكم عام ========
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
            // بدل hideAyahBanner() المباشر:
            ayahBannerGate?.requestHideAyahBanner()
        }

        singleSurahPlaying = null
        singleAyahPlaying = null
    }

    /** كانت تُنادى من الـActivity لمسح كل شيء */
    fun stopAllPlaybackAndClearQueue() {
        try { stopPagePlayback() } catch (_: Exception) {}
        try { stopSingleAyah() } catch (_: Exception) {}
        try {
            mediaPlayer?.reset()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        ayahQueue.clear()
        currentIndex = -1
        isPlaying = false
        isAyahPlaying = false
        resumeFromMs = 0
    }

    // ======== آية مفردة ========
    fun toggleSingleAyah(surah: Int, ayah: Int, qariId: String) {
        if (isAyahPlaying && singleSurahPlaying == surah && singleAyahPlaying == ayah) {
            stopSingleAyah()
        } else {
            if (isPlaying) stopPagePlayback()
            playSingleAyah(surah, ayah, qariId)
        }
    }

    fun playSingleAyah(surah: Int, ayah: Int, qariId: String) {
        if (isPlaying) stopSingleAyah()

        suppressAutoNext = false
        isAyahPlaying = false
        singleSurahPlaying = surah
        singleAyahPlaying = ayah
        val token = ++playToken

        try {
            val mp = ensurePlayer()
            clearListeners()
            mp.reset()

            when (val ds = resolveAyahDataSource(qariId, surah, ayah)) {
                is DataSource.LocalFile -> FileInputStream(ds.file).use { fis ->
                    mp.setDataSource(fis.fd)
                }
                is DataSource.Asset     -> mp.setDataSource(
                    ds.afd.fileDescriptor,
                    ds.afd.startOffset,
                    ds.afd.length
                )
                is DataSource.Remote    -> mp.setDataSource(ds.url)
            }

            mp.setOnPreparedListener {
                if (token != playToken) return@setOnPreparedListener
                it.start()
                isAyahPlaying = true
                activity.runOnUiThread {
                    runCatching { activity.btnPlayAyah.setImageResource(R.drawable.ic_pause) }

                    // نص الآية
                    val text = supportHelper.getAyahTextFromJson(surah, ayah)

                    // بدل showOrUpdateAyahBanner المباشر
                    ayahBannerGate?.requestShowAyahBanner(surah, ayah, text)
                }
                saveLastAyah(surah, ayah)
            }

            mp.setOnCompletionListener {
                if (token != playToken) return@setOnCompletionListener
                isAyahPlaying = false
                activity.runOnUiThread {
                    runCatching { activity.btnPlayAyah.setImageResource(R.drawable.ic_play) }
                    // بدل hideAyahBanner المباشر:
                    ayahBannerGate?.requestHideAyahBanner()
                }
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
            // بدل hideAyahBanner:
            ayahBannerGate?.requestHideAyahBanner()
        }

        singleSurahPlaying = null
        singleAyahPlaying = null
    }

    // ======== تجهيز طابور الصفحة ========
    fun prepareAudioQueueForPage(
        page: Int,
        qariId: String,
        fromStart: Boolean = true,
        onReady: (() -> Unit)? = null
    ) {
        val myGen = ++playGen // يبطل أي تجهيز أقدم

        bgHandler.post {
            val qari = provider.getQariById(qariId) ?: return@post
            val bounds = supportHelper.loadAyahBoundsForPage(page)

            val newQueue = ArrayList<Triple<String, Int, Int>>(bounds.size)
            for (b in bounds) {
                val url = provider.getAyahUrl(qari, b.sura_id, b.aya_id)
                newQueue.add(Triple(url, b.sura_id, b.aya_id))
            }

            activity.runOnUiThread {
                if (myGen != playGen) return@runOnUiThread
                ayahQueue.clear()
                ayahQueue.addAll(newQueue)
                currentIndex = if (fromStart) 0 else currentIndex.coerceAtLeast(0)
                resumeFromMs = 0
                onReady?.invoke()
            }
        }
    }

    // بدء الصفحة المحضرة من أول آية
    fun startPreparedPage(qariId: String) {
        if (ayahQueue.isEmpty()) return
        suppressAutoNext = false
        currentIndex = 0
        resumeFromMs = 0
        playAt(0, 0, qariId)
    }

    // واجهة متوافقة مع النداءات القديمة في الـ Activity
    fun startPagePlayback(page: Int, qariId: String, fromStart: Boolean = true) {
        // أوقف آية مفردة لو كانت تعمل
        stopSingleAyah()
        suppressAutoNext = false

        // حضّر الصف ثم شغّل أول آية بعد ما يجهز
        prepareAudioQueueForPage(page, qariId, fromStart) {
            startPreparedPage(qariId)
        }
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

        activity.runOnUiThread {
            runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
        }
    }

    /** محاولة استئناف الصفحة، تعود true لو اشتغل فعلاً */
    fun resumePagePlayback(): Boolean {
        return try {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    suppressAutoNext = false
                    if (resumeFromMs > 0) it.seekTo(resumeFromMs)
                    it.start()
                    isPlaying = true
                    activity.runOnUiThread {
                        runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_pause) }
                    }
                    true
                } else {
                    false
                }
            } ?: false
        } catch (_: Exception) {
            false
        }
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
            // بدل hideAyahBanner:
            ayahBannerGate?.requestHideAyahBanner()
        }
    }

    // ======== التشغيل الداخلي لآيات الصفحة ========
    private fun playAt(index: Int, startMs: Int, qariId: String) {
        // لو عدينا آخر آية في الصفحة
        if (index !in ayahQueue.indices) {
            isPlaying = false
            activity.runOnUiThread {
                runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
                // بدل hideAyahBanner:
                ayahBannerGate?.requestHideAyahBanner()
            }
            if (!suppressAutoNext) {
                onPagePlaybackComplete?.invoke()
            }
            return
        }

        val (_, s, a) = ayahQueue[index]

        // حدّث المؤشرات الحالية في الـ Activity
        activity.currentSurah = s
        activity.currentAyah = a
        runCatching {
            activity.adapter.highlightAyahOnPage(activity.currentPage, s, a)
        }

        val token = ++playToken
        try {
            val mp = ensurePlayer()
            clearListeners()
            mp.reset()

            when (val ds = resolveAyahDataSource(qariId, s, a)) {
                is DataSource.LocalFile -> FileInputStream(ds.file).use { fis ->
                    mp.setDataSource(fis.fd)
                }
                is DataSource.Asset     -> mp.setDataSource(
                    ds.afd.fileDescriptor,
                    ds.afd.startOffset,
                    ds.afd.length
                )
                is DataSource.Remote    -> mp.setDataSource(ds.url)
            }

            mp.setOnPreparedListener {
                if (token != playToken) return@setOnPreparedListener

                if (startMs > 0) it.seekTo(startMs)
                it.start()

                isPlaying = true
                isAyahPlaying = true

                // نص الآية الحالية
                val ayahText = supportHelper.getAyahTextFromJson(s, a)

                activity.runOnUiThread {
                    runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_pause) }

                    // بدل showOrUpdateAyahBanner:
                    ayahBannerGate?.requestShowAyahBanner(s, a, ayahText)
                }

                saveLastAyah(s, a)
            }

            mp.setOnCompletionListener {
                if (token != playToken) return@setOnCompletionListener

                // منطق تكرار آية
                if (repeatMode == "ayah") {
                    val key = s to a
                    if (lastRepeatedAyah == key) {
                        currentRepeat++
                    } else {
                        currentRepeat = 1
                        lastRepeatedAyah = key
                    }

                    if (currentRepeat < repeatCount) {
                        // أعد نفس الآية
                        resumeFromMs = 0
                        playAt(currentIndex, 0, qariId)
                        return@setOnCompletionListener
                    } else {
                        // خلص التكرار المطلوب
                        currentRepeat = 0
                        lastRepeatedAyah = null
                    }
                }

                // التالي
                resumeFromMs = 0
                currentIndex++
                playAt(currentIndex, 0, qariId)
            }

            mp.setOnErrorListener { _, _, _ ->
                // خطأ في الآية الحالية → حاول نكمل اللي بعدها
                resumeFromMs = 0
                currentIndex++
                playAt(currentIndex, 0, qariId)
                true
            }

            mp.prepareAsync()
        } catch (_: Exception) {
            // لو فشل إعداد هذه الآية، جرّب التي بعدها
            resumeFromMs = 0
            currentIndex++
            playAt(currentIndex, 0, qariId)
        }
    }

    // ======== أدوات مساعدة ========
    private fun resolveAyahDataSource(qariId: String, surah: Int, ayah: Int): DataSource {
        // 1) ملف محلي (في مجلد app الخاص بالتنزيلات)
        val f = File(
            activity.getExternalFilesDir(null),
            "recitations/${safeQari(qariId)}/${fileName(surah, ayah)}"
        )
        if (f.exists() && f.length() > 0) {
            return DataSource.LocalFile(f)
        }

        // 2) من الـ assets
        val assetRel = "quran_audio/${safeQari(qariId)}/${fileName(surah, ayah)}"
        return try {
            val afd = activity.assets.openFd(assetRel)
            DataSource.Asset(afd, assetRel)
        } catch (_: Exception) {
            // 3) رابط أونلاين
            val qari = provider.getQariById(qariId)
            DataSource.Remote(provider.getAyahUrl(qari!!, surah, ayah))
        }
    }
    // ==================== اختيار القارئ ====================
    data class QariMini(val id: String, val name: String)

    fun showQariPicker(onPicked: (QariMini) -> Unit) {
        try {
            val qaris = provider.getQaris()
            if (qaris.isEmpty()) {
                Toast.makeText(activity, "لا توجد قائمة قراء", Toast.LENGTH_SHORT).show()
                return
            }

            val names = qaris.map { it.name }.toTypedArray()

            android.app.AlertDialog.Builder(activity)
                .setTitle("اختر القارئ")
                .setItems(names) { _, which ->
                    val q = qaris[which]

                    // حفظ القارئ الجديد في prefs
                    activity.prefs.edit()
                        .putString(com.hag.al_quran.QuranPageActivity.KEY_QARI_ID, q.id.trim().lowercase())
                        .apply()

                    // تمرير القارئ المختار للـ Activity
                    onPicked(QariMini(q.id, q.name))

                    Toast.makeText(activity, "تم اختيار ${q.name}", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("إلغاء", null)
                .show()
        } catch (e: Exception) {
            Toast.makeText(activity, "تعذر تحميل قائمة القراء", Toast.LENGTH_SHORT).show()
        }
    }

    private fun safeQari(qariId: String): String =
        qariId
            .lowercase()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-z0-9_\\-]".toRegex(), "")

    private fun fileName(surah: Int, ayah: Int): String =
        "%03d%03d.mp3".format(surah, ayah)
}
