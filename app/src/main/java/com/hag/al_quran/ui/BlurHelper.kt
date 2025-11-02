package com.hag.al_quran.ui

import android.graphics.RenderEffect
import android.os.Build
import android.view.View
import android.graphics.Shader

object BlurHelper {
    fun applyBlurBehind(viewToBlur: View?, radius: Float = 18f) {
        if (viewToBlur == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            viewToBlur.setRenderEffect(
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
            )
        }
    }

    fun clearBlur(viewToBlur: View?) {
        if (viewToBlur == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            viewToBlur.setRenderEffect(null)
        }
    }
}
