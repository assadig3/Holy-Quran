package com.hag.al_quran.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hag.al_quran.R

class AyahBannerController(private val activity: AppCompatActivity) {
    private var root: ViewGroup? = null
    private var view: View? = null
    private var text: TextView? = null

    fun attachToRoot() {
        root = activity.findViewById(android.R.id.content)
        view = LayoutInflater.from(activity).inflate(R.layout.ayah_now_playing, root, false)
        text = view!!.findViewById(R.id.ayahText)
        view!!.findViewById<ImageButton>(R.id.btnCloseBanner).setOnClickListener { hide() }
        view!!.visibility = View.GONE
        root!!.addView(view)
    }

    fun show(t: String) {
        text?.text = t
        text?.isSelected = true
        val anim = AnimationUtils.loadAnimation(activity, R.anim.slide_in_top)
        view?.visibility = View.VISIBLE
        view?.startAnimation(anim)
    }

    fun hide() {
        val out = AnimationUtils.loadAnimation(activity, R.anim.slide_out_top)
        out.setAnimationListener(object: android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(p0: android.view.animation.Animation?) {}
            override fun onAnimationRepeat(p0: android.view.animation.Animation?) {}
            override fun onAnimationEnd(p0: android.view.animation.Animation?) { view?.visibility = View.GONE }
        })
        view?.startAnimation(out)
    }

    fun detach() { root?.removeView(view); view = null; text = null }
}
