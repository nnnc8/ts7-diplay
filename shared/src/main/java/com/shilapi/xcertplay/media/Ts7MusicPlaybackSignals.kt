package com.shilapi.xcertplay.media

import java.util.concurrent.CopyOnWriteArraySet

internal interface Ts7MusicPlaybackTarget {
    fun requestSongSkip(index: Int)
    fun playbackChanged(playing: Boolean)
}

/** Routes only TS7 music controls to the renderers that opted in through Ts7HeadUnit.detected. */
internal object Ts7MusicPlaybackSignals {
    private val targets = CopyOnWriteArraySet<Ts7MusicPlaybackTarget>()

    fun register(target: Ts7MusicPlaybackTarget) { targets += target }
    fun unregister(target: Ts7MusicPlaybackTarget) { targets -= target }

    fun songSkip(index: Int) {
        targets.forEach { it.requestSongSkip(index) }
    }

    fun playbackChanged(playing: Boolean) {
        targets.forEach { it.playbackChanged(playing) }
    }
}
