package com.example.vr

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VideoPlayerState(
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    val videoWidth: Int = 1280,
    val videoHeight: Int = 720,
    val currentUrl: String = "",
    val errorMessage: String? = null
)

class VrVideoController(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var exoPlayer: ExoPlayer? = null
    private var videoSurface: Surface? = null

    private val _state = MutableStateFlow(VideoPlayerState())
    val state: StateFlow<VideoPlayerState> = _state.asStateFlow()

    fun attachSurfaceTexture(surfaceTexture: SurfaceTexture, initialUrl: String) {
        mainHandler.post {
            surfaceTexture.setDefaultBufferSize(1280, 720)
            videoSurface?.release()
            val surface = Surface(surfaceTexture)
            videoSurface = surface

            val player = exoPlayer ?: ExoPlayer.Builder(context).build().also { created ->
                created.repeatMode = Player.REPEAT_MODE_ALL
                created.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        updateSnapshot()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updateSnapshot()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        _state.value = _state.value.copy(
                            errorMessage = error.localizedMessage ?: "Playback Error"
                        )
                    }
                })
                exoPlayer = created
            }

            player.setVideoSurface(surface)
            if (_state.value.currentUrl != initialUrl || player.mediaItemCount == 0) {
                loadUrlInternal(player, initialUrl, autoPlay = false)
            }
        }
    }

    fun loadUrl(url: String, autoPlay: Boolean = false) {
        mainHandler.post {
            val player = exoPlayer ?: return@post
            loadUrlInternal(player, url, autoPlay)
        }
    }

    private fun loadUrlInternal(player: ExoPlayer, url: String, autoPlay: Boolean) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) return
        _state.value = _state.value.copy(currentUrl = cleanUrl, errorMessage = null)
        player.setMediaItem(MediaItem.fromUri(cleanUrl))
        player.prepare()
        player.playWhenReady = autoPlay
        updateSnapshot()
    }

    fun togglePlayPause() {
        mainHandler.post {
            val player = exoPlayer ?: return@post
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                    player.prepare()
                }
                player.play()
            }
            updateSnapshot()
        }
    }

    fun pause() {
        mainHandler.post {
            exoPlayer?.pause()
            updateSnapshot()
        }
    }

    fun play() {
        mainHandler.post {
            val player = exoPlayer ?: return@post
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            player.play()
            updateSnapshot()
        }
    }

    fun seekByMs(deltaMs: Long) {
        mainHandler.post {
            val player = exoPlayer ?: return@post
            val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            val target = (player.currentPosition + deltaMs).coerceIn(0L, duration)
            player.seekTo(target)
            updateSnapshot()
        }
    }

    fun pollPosition() {
        mainHandler.post {
            updateSnapshot()
        }
    }

    private fun updateSnapshot() {
        val player = exoPlayer ?: return
        val size = player.videoSize
        _state.value = _state.value.copy(
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            currentPositionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.coerceAtLeast(0L),
            videoWidth = if (size.width > 0) size.width else 1280,
            videoHeight = if (size.height > 0) size.height else 720
        )
    }

    fun release() {
        mainHandler.post {
            try {
                exoPlayer?.clearVideoSurface()
                exoPlayer?.release()
            } catch (_: Exception) {
            }
            exoPlayer = null
            videoSurface?.release()
            videoSurface = null
            _state.value = _state.value.copy(isPlaying = false)
        }
    }
}
