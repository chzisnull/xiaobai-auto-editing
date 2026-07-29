package icu.yuqiuyijiaren.xiaobai.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import icu.yuqiuyijiaren.xiaobai.domain.PlaybackCommand
import icu.yuqiuyijiaren.xiaobai.domain.VideoSource
import icu.yuqiuyijiaren.xiaobai.ui.theme.Blue
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Minimal player: surface only + play/pause + thin progress.
 * Seeking is primarily done via the app timeline / trim tools.
 */
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
    var isPlaying by remember { mutableStateOf(false) }
    var sliderDragging by remember { mutableStateOf(false) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    val durationSec = (source?.durationSec ?: 0.0).coerceAtLeast(0.001)

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
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
        sliderValue = playheadSec.toFloat()
    }

    LaunchedEffect(playback?.token) {
        val cmd = playback ?: return@LaunchedEffect
        val targetMs = (cmd.seekSec * 1000).toLong().coerceAtLeast(0L)
        player.seekTo(targetMs)
        lastEmittedSec = cmd.seekSec
        sliderValue = cmd.seekSec.toFloat()
        onPlayheadChange(cmd.seekSec)
        playUntilSec = cmd.playUntilSec ?: Double.POSITIVE_INFINITY
        player.playWhenReady = cmd.autoPlay
        if (cmd.autoPlay) player.play()
        onPlaybackConsumed()
    }

    LaunchedEffect(playheadSec, source?.uriString) {
        if (source == null || sliderDragging) return@LaunchedEffect
        val targetMs = (playheadSec * 1000).toLong().coerceAtLeast(0L)
        val currentMs = player.currentPosition
        if (abs(playheadSec - lastEmittedSec) > 0.35) {
            player.seekTo(targetMs)
            lastEmittedSec = playheadSec
            sliderValue = playheadSec.toFloat()
        } else if (abs(currentMs - targetMs) > 450 && !player.isPlaying) {
            player.seekTo(targetMs)
            lastEmittedSec = playheadSec
            sliderValue = playheadSec.toFloat()
        }
    }

    LaunchedEffect(player) {
        while (true) {
            if (player.playbackState == Player.STATE_READY || player.isPlaying) {
                val sec = player.currentPosition / 1000.0
                lastEmittedSec = sec
                if (!sliderDragging) {
                    sliderValue = sec.toFloat()
                    onPlayheadChange(sec)
                }
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
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { view ->
                view.player = player
                view.useController = false
            },
        )

        // Center tap toggles play/pause
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    if (player.isPlaying) player.pause() else player.play()
                },
        )

        // Bottom minimal controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x99000000))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color(0x66FFFFFF))
                        .clickable {
                            if (player.isPlaying) player.pause() else player.play()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatClock(if (sliderDragging) sliderValue.toDouble() else playheadSec),
                    color = Color.White,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.width(6.dp))
                Slider(
                    value = sliderValue.coerceIn(0f, durationSec.toFloat()),
                    onValueChange = {
                        sliderDragging = true
                        sliderValue = it
                    },
                    onValueChangeFinished = {
                        sliderDragging = false
                        val sec = sliderValue.toDouble().coerceIn(0.0, durationSec)
                        player.seekTo((sec * 1000).toLong())
                        lastEmittedSec = sec
                        onPlayheadChange(sec)
                    },
                    valueRange = 0f..durationSec.toFloat(),
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Blue,
                        activeTrackColor = Blue,
                        inactiveTrackColor = Color(0x55FFFFFF),
                    ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatClock(durationSec),
                    color = Color(0xCCFFFFFF),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

private fun formatClock(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).roundToInt()
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}
