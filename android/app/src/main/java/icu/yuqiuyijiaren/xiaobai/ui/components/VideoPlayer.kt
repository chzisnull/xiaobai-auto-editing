package icu.yuqiuyijiaren.xiaobai.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import icu.yuqiuyijiaren.xiaobai.domain.PlaybackCommand
import icu.yuqiuyijiaren.xiaobai.domain.VideoSource
import kotlin.math.abs

@Composable
fun LocalVideoPlayer(
    source: VideoSource?,
    playheadSec: Double,
    onPlayheadChange: (Double) -> Unit,
    playback: PlaybackCommand? = null,
    onPlaybackConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    var lastEmittedSec by remember { mutableDoubleStateOf(0.0) }
    var playUntilSec by remember { mutableDoubleStateOf(Double.POSITIVE_INFINITY) }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
        }
    }

    LaunchedEffect(source?.uriString) {
        val uri = source?.uriString
        if (uri.isNullOrBlank()) {
            player.clearMediaItems()
            return@LaunchedEffect
        }
        player.setMediaItem(MediaItem.fromUri(uri))
        player.prepare()
        player.seekTo((playheadSec * 1000).toLong().coerceAtLeast(0L))
        lastEmittedSec = playheadSec
    }

    // One-shot playback commands (play selected segment, jump prev/next)
    LaunchedEffect(playback?.token) {
        val cmd = playback ?: return@LaunchedEffect
        val targetMs = (cmd.seekSec * 1000).toLong().coerceAtLeast(0L)
        player.seekTo(targetMs)
        lastEmittedSec = cmd.seekSec
        onPlayheadChange(cmd.seekSec)
        playUntilSec = cmd.playUntilSec ?: Double.POSITIVE_INFINITY
        player.playWhenReady = cmd.autoPlay
        if (cmd.autoPlay) player.play()
        onPlaybackConsumed()
    }

    // External seek (timeline / list) → player
    LaunchedEffect(playheadSec, source?.uriString) {
        if (source == null) return@LaunchedEffect
        val targetMs = (playheadSec * 1000).toLong().coerceAtLeast(0L)
        val currentMs = player.currentPosition
        if (abs(playheadSec - lastEmittedSec) > 0.35) {
            player.seekTo(targetMs)
            lastEmittedSec = playheadSec
        } else if (abs(currentMs - targetMs) > 450 && !player.isPlaying) {
            player.seekTo(targetMs)
            lastEmittedSec = playheadSec
        }
    }

    LaunchedEffect(player) {
        while (true) {
            if (player.playbackState == Player.STATE_READY || player.isPlaying) {
                val sec = player.currentPosition / 1000.0
                lastEmittedSec = sec
                onPlayheadChange(sec)
                if (player.isPlaying && sec >= playUntilSec - 0.04) {
                    player.pause()
                    playUntilSec = Double.POSITIVE_INFINITY
                }
            }
            kotlinx.coroutines.delay(80)
        }
    }

    val shape = Modifier
        .then(if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        .clip(RoundedCornerShape(12.dp))
        .background(Color(0xFF111113))

    Box(modifier = modifier.then(shape)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view ->
                view.player = player
            },
        )
    }
}
