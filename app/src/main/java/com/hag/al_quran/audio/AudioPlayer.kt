package com.hag.al_quran.audio

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

object AudioPlayer {
    private var player: ExoPlayer? = null

    fun play(context: Context, url: String) {
        stop()

        player = ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }

    fun stop() {
        player?.release()
        player = null
    }

    fun isPlaying(): Boolean {
        return player?.isPlaying == true
    }
}
