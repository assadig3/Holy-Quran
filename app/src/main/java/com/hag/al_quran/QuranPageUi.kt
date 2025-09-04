import android.content.SharedPreferences
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hag.al_quran.pages.PagesDownloader

fun showDownloadAllPagesDialogIfNeeded(
    activity: AppCompatActivity,
    prefs: SharedPreferences,
    pagesDownloader: PagesDownloader,
    keyCached: String = "pages_cached_ok",
    keyPromptOnce: String = "pages_prompt_shown"
) {
    if (activity.isFinishing || activity.isDestroyed) return
    if (
        prefs.getBoolean(keyCached, false) ||
        prefs.getBoolean("pages_downloaded", false) ||
        pagesDownloader.isCached()
    ) {
        prefs.edit().putBoolean(keyCached, true).apply()
        return
    }
    if (prefs.getBoolean(keyPromptOnce, false)) return
    prefs.edit().putBoolean(keyPromptOnce, true).apply()

    // واجهة التحميل
    val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = false
        max = 604
    }
    val text = TextView(activity).apply {
        setPadding(32, 24, 32, 16)
        text = "Starting download..."
    }
    val box = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(32, 32, 32, 8)
        addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    var canceled = false
    val dlg = AlertDialog.Builder(activity)
        .setTitle("Downloading Quran Pages")
        .setView(box)
        .setCancelable(false)
        .setNegativeButton("Cancel") { d, _ ->
            canceled = true
            pagesDownloader.cancel()
            d.dismiss()
        }
        .create()
    dlg.show()

    // ابدأ التحميل مع الكولباك
    pagesDownloader.start(object : PagesDownloader.Listener {
        override fun onProgress(current: Int, total: Int) {
            activity.runOnUiThread {
                progress.max = total
                progress.progress = current
                text.text = "Downloading page $current of $total"
            }
        }

        override fun onComplete(success: Boolean, errorMessage: String?) {
            activity.runOnUiThread {
                if (dlg.isShowing) dlg.dismiss()
                if (canceled) {
                    Toast.makeText(activity, "Download canceled.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                if (success) {
                    prefs.edit()
                        .putBoolean(keyCached, true)
                        .putBoolean("pages_downloaded", true)
                        .apply()
                    Toast.makeText(activity, "Download completed.", Toast.LENGTH_SHORT).show()
                } else {
                    val msg = errorMessage?.takeIf { it.isNotBlank() } ?: "Download failed."
                    Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    })
}
