package com.hag.al_quran.player

import android.app.ProgressDialog
import android.content.Context
import android.media.MediaPlayer
import android.widget.NumberPicker
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hag.al_quran.R
import com.hag.al_quran.audio.MadaniPageProvider
import org.json.JSONObject

class PageRecitationController(
    private val activity: AppCompatActivity,
    private val provider: MadaniPageProvider
) {
    private var qariId: String = "fares"
    private var mp: MediaPlayer? = null
    private val queue: ArrayDeque<Triple<String, Int, Int>> = ArrayDeque()

    // repeat (per-ayah)
    private var repeatMode = "ayah"
    private var repeatCount = 1
    private var lastAyah: Pair<Int, Int>? = null
    private var repeatedTimes = 0

    // callbacks
    var onAyahStarted: ((Int, Int, String) -> Unit)? = null
    var onPlayStateChanged: ((Boolean) -> Unit)? = null

    fun setQari(id: String) { qariId = id }

    fun isPlaying(): Boolean = mp?.isPlaying == true

    fun release() { mp?.release(); mp = null }

    fun pause() { mp?.pause(); onPlayStateChanged?.invoke(false) }

    fun buildQueueForPage(page: Int) {
        queue.clear()
        val bounds = loadAyahBoundsForPage(activity, page)
        val q = provider.getQariById(qariId) ?: return
        for (b in bounds) {
            val url = provider.getAyahUrl(q, b.sura_id, b.aya_id)
            queue.add(Triple(url, b.sura_id, b.aya_id))
        }
    }

    fun playNext() {
        if (queue.isEmpty()) {
            onPlayStateChanged?.invoke(false); return
        }
        val (url, s, a) = queue.removeFirst()
        val text = provider.getAyahTextFromUrl(url, activity) ?: ""
        onAyahStarted?.invoke(s, a, text)

        if (mp == null) mp = MediaPlayer() else mp?.reset()
        try {
            mp?.setDataSource(url)
            mp?.setOnPreparedListener {
                it.start(); onPlayStateChanged?.invoke(true)
            }
            mp?.setOnCompletionListener {
                handleRepeat(url, s, a)
                playNext()
            }
            mp?.prepareAsync()
        } catch (_: Exception) {
            Toast.makeText(activity, "تعذر تشغيل التلاوة", Toast.LENGTH_SHORT).show()
            playNext()
        }
    }

    private fun handleRepeat(url: String, s: Int, a: Int) {
        if (repeatMode != "ayah") return
        val key = s to a
        if (lastAyah == key) repeatedTimes++ else { lastAyah = key; repeatedTimes = 1 }
        if (repeatedTimes < repeatCount) queue.addFirst(Triple(url, s, a))
        else { repeatedTimes = 0; lastAyah = null }
    }



    fun downloadPageAudio(
        page: Int,
        onProgress: (idx: Int, total: Int) -> Unit,
        onDone: (Boolean) -> Unit
    ) {
        val q = provider.getQariById(qariId) ?: return onDone(false)
        val ayat = loadAyahBoundsForPage(activity, page)
        if (ayat.isEmpty()) return onDone(false)

        val dlg = ProgressDialog(activity).apply {
            setCancelable(false)
            setMessage("بدء التحميل…"); show()
        }

        Thread {
            var okAll = true
            val total = ayat.size
            ayat.forEachIndexed { i, b ->
                val url = provider.getAyahUrl(q, b.sura_id, b.aya_id)
                val name = "s${b.sura_id}_a${b.aya_id}_${q.id}.mp3"
                okAll = okAll && downloadToAppStorage(activity, url, name)
                activity.runOnUiThread {
                    dlg.setMessage("تحميل ${i + 1} / $total")
                    onProgress(i + 1, total)
                }
            }
            activity.runOnUiThread { dlg.dismiss(); onDone(okAll) }
        }.start()
    }

    // ===== Helpers =====
    private fun downloadToAppStorage(ctx: Context, url: String, fileName: String): Boolean {
        return try {
            val dir = java.io.File(ctx.getExternalFilesDir(null), "AlQuranAudio").apply { if (!exists()) mkdirs() }
            val out = java.io.File(dir, fileName)
            if (!out.exists()) {
                val conn = java.net.URL(url).openConnection()
                conn.getInputStream().use { input ->
                    java.io.FileOutputStream(out).use { output -> input.copyTo(output) }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace(); false
        }
    }

    private fun loadAyahBoundsForPage(context: Context, page: Int): List<AyahBounds> {
        val jsonStr = context.assets.open("pages/ayah_bounds_all.json").bufferedReader().use { it.readText() }
        val json = JSONObject(jsonStr)
        val arr = json.optJSONArray(page.toString()) ?: return emptyList()
        val out = mutableListOf<AyahBounds>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val segsArr = o.getJSONArray("segs")
            val segs = mutableListOf<Seg>()
            for (j in 0 until segsArr.length()) {
                val s = segsArr.getJSONObject(j)
                segs.add(Seg(s.getInt("x"), s.getInt("y"), s.getInt("w"), s.getInt("h")))
            }
            out.add(AyahBounds(o.getInt("sura_id"), o.getInt("aya_id"), segs))
        }
        return out
    }

    data class Seg(val x: Int, val y: Int, val w: Int, val h: Int)
    data class AyahBounds(val sura_id: Int, val aya_id: Int, val segs: List<Seg>)
}

// ===== Extension used by Activity =====
fun MadaniPageProvider.getAyahTextFromUrl(url: String, context: Context): String? {
    val rx = ".*/(\\d{3})(\\d{3})\\.mp3$".toRegex()
    val m = rx.find(url) ?: return null
    val s = m.groupValues[1].toInt()
    val a = m.groupValues[2].toInt()
    val js = context.assets.open("quran.json").bufferedReader().use { it.readText() }
    val arr = org.json.JSONArray(js)
    for (i in 0 until arr.length()) {
        val so = arr.getJSONObject(i)
        if (so.getInt("id") == s) {
            val verses = so.getJSONArray("verses")
            for (j in 0 until verses.length()) {
                val v = verses.getJSONObject(j)
                if (v.getInt("id") == a) return v.getString("text")
            }
        }
    }
    return null
}
