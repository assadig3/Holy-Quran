package com.hag.al_quran.audio

import android.app.*
import android.content.*
import android.media.*
import android.net.wifi.WifiManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hag.al_quran.QuranPageActivity
import com.hag.al_quran.R
import java.util.concurrent.CopyOnWriteArraySet
import java.util.regex.Pattern

/**
 * AudioPlaybackManager
 * - يدعم التشغيل بالخلفية عبر Foreground Service.
 * - API متوافق مع استدعاءاتك في QuranPageActivity:
 *   listener, setTotalPages(...), autoContinue, onAdvanceToPage,
 *   setQari(...), setRepeat(...), startPagePlayback(page),
 *   playSingle(surah, ayah), playSingleUrl(url), release().
 */
class AudioPlaybackManager private constructor(private val appCtx: Context) {

    // ========= Listener =========
    interface Listener {
        fun onPlayStateChanged(isPlaying: Boolean) {}
        fun onAyahChanged(surah: Int, ayah: Int, title: String) {}
        fun onQueueEnded() {}
        fun onError(message: String) {}
        // توسيعًا لتوافق كودك:
        fun onAyahStart(surah: Int, ayah: Int, text: String) {}
        fun onAyahComplete(surah: Int, ayah: Int) {}
    }

    // مستمع واحد (لتوافق كودك) + قائمة لمن يريد الإضافة مستقبلًا
    var listener: Listener? = null
    private val listeners = CopyOnWriteArraySet<Listener>()
    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    // ========= Singleton =========
    companion object {
        @Volatile private var INSTANCE: AudioPlaybackManager? = null
        fun get(context: Context): AudioPlaybackManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: AudioPlaybackManager(context.applicationContext).also { INSTANCE = it }
            }

        internal const val CHANNEL_ID = "quran_playback_channel"
        internal const val NOTIF_ID = 7701

        internal const val ACTION_START  = "com.hag.al_quran.action.START"
        internal const val ACTION_PLAY   = "com.hag.al_quran.action.PLAY"
        internal const val ACTION_PAUSE  = "com.hag.al_quran.action.PAUSE"
        internal const val ACTION_STOP   = "com.hag.al_quran.action.STOP"
        internal const val ACTION_UPDATE = "com.hag.al_quran.action.UPDATE"
    }

    // ========= Dependencies supplied by Activity =========
    // نضبطهم من الخارج لتوليد الروابط
    private var provider: MadaniPageProvider? = null
    private var qariId: String = "fares"

    fun setProvider(p: MadaniPageProvider) { provider = p }
    fun setQari(id: String) { qariId = id }

    // ========= Paging / Auto-continue =========
    private var totalPages: Int = 604
    fun setTotalPages(n: Int) { totalPages = n.coerceAtLeast(1) }

    var autoContinue: Boolean = true
    var onAdvanceToPage: ((Int) -> Unit)? = null
    private var currentPageForQueue: Int = 1

    // ========= Repeat (للتوافق مع قائمتك) =========
    private var repeatMode: String = "none"  // "ayah" | "page" | "none"
    private var repeatCount: Int = 1
    fun setRepeat(mode: String, count: Int) {
        repeatMode = mode
        repeatCount = count.coerceAtLeast(1)
    }

    // ========= Android services =========
    private val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val wifiManager  = appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var mediaPlayer: MediaPlayer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // ========= Queue =========
    private var queue: MutableList<String> = mutableListOf()
    private var index = 0
    private var prepared = false

    // استخراج (سورة/آية) من اسم الملف: .../SSSAAA.mp3
    private val urlRegex = Pattern.compile(".*/(\\d{3})(\\d{3})\\.mp3$")

    // ========= Audio Focus =========
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pauseInternal(true)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duck(true)
            AudioManager.AUDIOFOCUS_GAIN -> unduckAndResume()
        }
    }

    // ========= Public state =========
    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true
    fun hasQueue(): Boolean = queue.isNotEmpty()
    fun currentIndex(): Int = index
    fun currentUrl(): String? = queue.getOrNull(index)

    // ========= API: التوافق مع استدعاءاتك =========

    /** يبدأ تشغيل الصفحة الحالية، ويواصل تلقائيًا للصفحة التالية إذا autoContinue=true */
    fun startPagePlayback(page: Int) {
        val p = provider ?: run {
            listener?.onError("Provider not set")
            return
        }
        currentPageForQueue = page.coerceIn(1, totalPages)
        val urls = p.getUrlsForPage(qariId, currentPageForQueue)
        if (urls.isEmpty()) {
            listener?.onError("No ayah URLs for page $currentPageForQueue")
            return
        }
        // تكرار الصفحة عند الحاجة
        val finalList = when (repeatMode) {
            "page" -> MutableList(repeatCount) { urls }.flatten()
            else -> urls
        }
        setQueueAndPlay(finalList, 0)
    }

    /** تشغيل آية واحدة (مع تكرار إن تم اختيار repeatMode="ayah") */
    fun playSingle(surah: Int, ayah: Int) {
        val p = provider ?: run {
            listener?.onError("Provider not set")
            return
        }
        val base = p.getAyahUrl(qariId, surah, ayah) ?: run {
            listener?.onError("Ayah URL not found for $surah:$ayah")
            return
        }
        val list = when (repeatMode) {
            "ayah" -> MutableList(repeatCount) { base }
            else -> listOf(base)
        }
        setQueueAndPlay(list, 0)
    }

    /** متاح أيضًا إن رغبت بتمرير URL جاهز */
    fun playSingleUrl(url: String) {
        setQueueAndPlay(listOf(url), 0)
    }

    /** إيقاف كامل وإلغاء كل شيء */
    fun release() {
        stop()
    }

    /** تشغيل/إيقاف مؤقت */
    fun playOrPause() = if (isPlaying()) pause() else play()

    fun setQueueAndPlay(urls: List<String>, startIndex: Int = 0) {
        queue.clear()
        queue.addAll(urls)
        index = startIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        startServiceIfNeeded()
        playIndex(index)
    }

    fun play() {
        if (queue.isEmpty()) return
        startServiceIfNeeded()
        if (prepared) {
            requestFocus()
            mediaPlayer?.start()
            notifyState()
        } else {
            playIndex(index)
        }
    }

    fun pause() = pauseInternal(false)

    fun stop() {
        mediaPlayer?.setOnCompletionListener(null)
        mediaPlayer?.reset()
        mediaPlayer?.release()
        mediaPlayer = null
        prepared = false
        abandonFocus()
        releaseLocks()
        notifyState()
        ForegroundPlaybackService.stop(appCtx)
        listener?.onQueueEnded()
        listeners.forEach { it.onQueueEnded() }
    }

    fun next() {
        val n = index + 1
        if (n < queue.size) playIndex(n) else onQueueCompleted()
    }

    fun previous() {
        val pIdx = index - 1
        if (pIdx >= 0) playIndex(pIdx) else playIndex(0)
    }

    // ========= تشغيل عنصر =========
    private fun playIndex(i: Int) {
        if (queue.isEmpty()) return
        index = i.coerceIn(0, queue.size - 1)
        val url = queue[index]

        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer().apply {
                setWakeMode(appCtx, PowerManager.PARTIAL_WAKE_LOCK)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setOnPreparedListener {
                    prepared = true
                    requestFocus()
                    it.start()
                    acquireLocks()
                    notifyAyahStart()
                    notifyState()
                }
                setOnCompletionListener {
                    notifyAyahComplete()
                    val n = index + 1
                    if (n < queue.size) {
                        playIndex(n)
                    } else {
                        onQueueCompleted()
                    }
                }
                setOnErrorListener { _, what, extra ->
                    val msg = "Audio error: $what/$extra"
                    listener?.onError(msg)
                    listeners.forEach { it.onError(msg) }
                    next()
                    true
                }
            }
        } else {
            mediaPlayer?.reset()
        }

        prepared = false
        try {
            mediaPlayer?.setDataSource(url)
            mediaPlayer?.prepareAsync()
        } catch (e: Exception) {
            val msg = e.message ?: "setDataSource failed"
            listener?.onError(msg)
            listeners.forEach { it.onError(msg) }
            next()
        }

        startServiceIfNeeded()
        ForegroundPlaybackService.update(appCtx)
    }

    private fun onQueueCompleted() {
        if (repeatMode == "page") {
            // أعد تشغيل نفس الصفحة (تكرار الصفحة تم بالفعل في القائمة، نوقف الآن)
            stop()
            return
        }
        if (autoContinue) {
            val nextPage = (currentPageForQueue + 1).coerceAtMost(totalPages)
            if (nextPage != currentPageForQueue) {
                onAdvanceToPage?.invoke(nextPage) // يخبر الـActivity لينتقل في الـViewPager

                startPagePlayback(nextPage)      // يبني طابور الصفحة التالية ويكمل تلقائيًا
                return
            }
        }
        stop()
    }

    // ========= Focus / Locks =========
    private fun requestFocus() {
        audioManager.requestAudioFocus(
            focusListener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN
        )
    }

    private fun abandonFocus() {
        audioManager.abandonAudioFocus(focusListener)
    }

    private fun duck(enable: Boolean) {
        mediaPlayer?.setVolume(if (enable) 0.2f else 1f, if (enable) 0.2f else 1f)
    }

    private fun unduckAndResume() {
        duck(false)
        if (!isPlaying()) mediaPlayer?.start()
        notifyState()
    }

    private fun pauseInternal(fromFocusLoss: Boolean) {
        if (isPlaying()) {
            mediaPlayer?.pause()
            if (fromFocusLoss) duck(false)
            notifyState()
        }
    }

    private fun acquireLocks() {
        if (wifiLock?.isHeld != true) {
            wifiLock = wifiManager.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "QuranApp:WifiLock"
            ).apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
    }

    // ========= إشعارات للمستمع =========
    private fun notifyState() {
        val playing = isPlaying()
        listener?.onPlayStateChanged(playing)
        listeners.forEach { it.onPlayStateChanged(playing) }
        ForegroundPlaybackService.update(appCtx)
    }

    private fun notifyAyahStart() {
        val (s, a) = parseSurahAyah(currentUrl())
        val title = buildTitle(s, a)
        // النص الفعلي للآية غير متاح هنا؛ لو أردته من JSON يمكنك تمريره من الخارج
        listener?.onAyahChanged(s, a, title)
        listeners.forEach { it.onAyahChanged(s, a, title) }
        listener?.onAyahStart(s, a, title)
        listeners.forEach { it.onAyahStart(s, a, title) }
    }

    private fun notifyAyahComplete() {
        val (s, a) = parseSurahAyah(currentUrl())
        listener?.onAyahComplete(s, a)
        listeners.forEach { it.onAyahComplete(s, a) }
    }

    // ========= Helpers =========
    fun parseSurahAyah(url: String?): Pair<Int, Int> {
        if (url.isNullOrEmpty()) return 0 to 0
        val m = urlRegex.matcher(url)
        return if (m.find()) (m.group(1)?.toIntOrNull() ?: 0) to (m.group(2)?.toIntOrNull() ?: 0)
        else 0 to 0
    }

    fun buildTitle(surah: Int, ayah: Int): String {
        if (surah <= 0 || ayah <= 0) return "جاري التلاوة"
        return "سورة ${surah.toString().padStart(3, '0')} - آية ${ayah.toString().padStart(3, '0')}"
    }

    private fun startServiceIfNeeded() {
        ForegroundPlaybackService.start(appCtx)
    }

    // ========= Foreground Service (داخل نفس الملف) =========
    class ForegroundPlaybackService : Service() {

        private lateinit var noisyReceiver: BroadcastReceiver

        override fun onCreate() {
            super.onCreate()
            createChannel()
            registerBecomingNoisyReceiver()
        }

        override fun onDestroy() {
            unregisterReceiver(noisyReceiver)
            super.onDestroy()
        }

        override fun onBind(intent: Intent?) = null

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            when (intent?.action) {
                ACTION_PLAY  -> AudioPlaybackManager.get(this).play()
                ACTION_PAUSE -> AudioPlaybackManager.get(this).pause()
                ACTION_STOP  -> {
                    AudioPlaybackManager.get(this).stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                ACTION_UPDATE, ACTION_START -> { /* تحديث/بدء الإشعار */ }
            }
            val notification = buildNotification()
            startForeground(NOTIF_ID, notification)
            return START_STICKY
        }

        private fun registerBecomingNoisyReceiver() {
            noisyReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (AudioManager.ACTION_AUDIO_BECOMING_NOISY == intent.action) {
                        AudioPlaybackManager.get(context).pause()
                    }
                }
            }
            registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        }

        private fun buildNotification(): Notification {
            val mgr = AudioPlaybackManager.get(this)
            val isPlaying = mgr.isPlaying()
            val (s, a) = mgr.parseSurahAyah(mgr.currentUrl())
            val title = if (s > 0) "القرآن الكريم" else "جاري التلاوة"
            val text  = mgr.buildTitle(s, a)

            val openAppIntent = Intent(this, QuranPageActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val piFlags = if (Build.VERSION.SDK_INT >= 23)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val contentPI = PendingIntent.getActivity(this, 100, openAppIntent, piFlags)

            val playPI  = PendingIntent.getService(this, 101, Intent(this, ForegroundPlaybackService::class.java).setAction(ACTION_PLAY),  piFlags)
            val pausePI = PendingIntent.getService(this, 102, Intent(this, ForegroundPlaybackService::class.java).setAction(ACTION_PAUSE), piFlags)
            val stopPI  = PendingIntent.getService(this, 103, Intent(this, ForegroundPlaybackService::class.java).setAction(ACTION_STOP),  piFlags)

            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(contentPI)
                .setOnlyAlertOnce(true)
                .setOngoing(isPlaying)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            if (isPlaying) builder.addAction(R.drawable.ic_pause, "إيقاف مؤقت", pausePI)
            else builder.addAction(R.drawable.ic_play, "تشغيل", playPI)

            builder.addAction(R.drawable.ic_stop, "إيقاف", stopPI)
            return builder.build()
        }

        private fun createChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID, "تشغيل التلاوة", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "إشعار تشغيل تلاوة القرآن"; setShowBadge(false) }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.createNotificationChannel(channel)
            }
        }

        companion object {
            fun start(ctx: Context) {
                val i = Intent(ctx, ForegroundPlaybackService::class.java).setAction(ACTION_START)
                ContextCompat.startForegroundService(ctx, i)
            }
            fun update(ctx: Context) {
                val i = Intent(ctx, ForegroundPlaybackService::class.java).setAction(ACTION_UPDATE)
                ContextCompat.startForegroundService(ctx, i)
            }
            fun stop(ctx: Context) {
                try { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID) } catch (_: Exception) {}
                ctx.stopService(Intent(ctx, ForegroundPlaybackService::class.java))
            }
        }
    }
}
