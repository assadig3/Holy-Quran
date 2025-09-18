package com.hag.al_quran

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment

class AboutFragment : Fragment(R.layout.fragment_about) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tvVersion: TextView = view.findViewById(R.id.tv_version)

        // هذا BuildConfig خاص بالتطبيق
        val versionName = BuildConfig.VERSION_NAME
        val versionCode = BuildConfig.VERSION_CODE

        // اعرض رقم النسخة بشكل مرتب
        tvVersion.text = getString(R.string.version_format, "$versionName ($versionCode)")
    }
}
