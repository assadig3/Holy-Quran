package com.hag.al_quran.tafsir

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

object TafsirUtils {

    // تحميل تفسير آية من ملف مخزن محليًا
    fun getAyahTafsir(context: Context, surah: Int, ayah: Int, tafsirFile: String): String? {
        return try {
            val file = File(context.filesDir, tafsirFile)
            if (!file.exists()) {
                Log.e("TafsirUtils", "File not found: $tafsirFile")
                return null
            }

            val tafsirJson = file.readText()
            val tafsirObj = JSONObject(tafsirJson)
            val surahObj = tafsirObj.optJSONObject(surah.toString()) ?: return null

            // الطريقة الأولى: مباشرة (1 -> "النص")
            val direct = surahObj.optString(ayah.toString(), null)
            if (!direct.isNullOrEmpty()) return direct

            // الطريقة الثانية: داخل مصفوفة ayahs
            if (surahObj.has("ayahs")) {
                val ayahsArr = surahObj.getJSONArray("ayahs")
                for (i in 0 until ayahsArr.length()) {
                    val ayahObj = ayahsArr.getJSONObject(i)
                    if (ayahObj.optInt("ayah") == ayah) {
                        return ayahObj.optString("text", null)
                    }
                }
            }

            null
        } catch (e: Exception) {
            Log.e("TafsirUtils", "Error loading tafsir: ${e.message}")
            null
        }
    }

    // تحميل ملف التفسير من الإنترنت وتخزينه في FilesDir
    fun downloadTafsirIfNeeded(
        context: Context,
        fileName: String,
        url: String,
        forceDownload: Boolean = false,
        callback: (Boolean, File?) -> Unit
    ) {
        val file = File(context.filesDir, fileName)
        if (file.exists() && !forceDownload) {
            callback(true, file)
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    callback(false, null)
                    return@launch
                }

                val body = response.body ?: return@launch callback(false, null)
                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(file)
                inputStream.copyTo(outputStream)
                outputStream.close()
                inputStream.close()

                callback(true, file)
            } catch (e: Exception) {
                Log.e("TafsirDownload", "Download failed: ${e.message}", e)
                callback(false, null)
            }
        }
    }
}
