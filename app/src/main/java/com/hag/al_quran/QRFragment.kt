package com.hag.al_quran

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import net.glxn.qrgen.android.QRCode

class QRFragment : Fragment(R.layout.fragment_qr) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = requireActivity() as AppCompatActivity

        // إعداد الـ Toolbar الخاص بالفراجمنت
        val toolbar = view.findViewById<Toolbar>(R.id.fragment_toolbar)
        activity.setSupportActionBar(toolbar)

        val title = getString(R.string.share_qr)
        toolbar.title = title                         // عنوان الـToolbar نفسه
        activity.supportActionBar?.title = title      // عنوان الـActionBar
        activity.title = title                        // احتياطيًا

        toolbar.setNavigationOnClickListener {
            activity.onBackPressedDispatcher.onBackPressed()
        }

        // توليد كود QR
        val qrImage = view.findViewById<ImageView>(R.id.qrImageView)
        val qrText = "https://play.google.com/store/apps/details?id=${activity.packageName}"
        val bitmap: Bitmap = QRCode.from(qrText).withSize(500, 500).bitmap()
        qrImage.setImageBitmap(bitmap)
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // إعادة Toolbar الرئيسي + الهامبرجر
        val activity = requireActivity() as AppCompatActivity
        val mainToolbar = activity.findViewById<Toolbar>(R.id.toolbar)
        activity.setSupportActionBar(mainToolbar)

        val drawerLayout = activity.findViewById<DrawerLayout>(R.id.drawer_layout)
        val toggle = ActionBarDrawerToggle(
            activity, drawerLayout, mainToolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
    }
}
