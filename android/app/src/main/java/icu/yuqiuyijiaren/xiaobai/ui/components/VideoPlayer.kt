package icu.yuqiuyijiaren.xiaobai.ui.components

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import icu.yuqiuyijiaren.xiaobai.domain.PlaybackCommand
import icu.yuqiuyijiaren.xiaobai.domain.Rally
import icu.yuqiuyijiaren.xiaobai.domain.VideoSource
import icu.yuqiuyijiaren.xiaobai.ui.theme.Amber
import icu.yuqiuyijiaren.xiaobai.ui.theme.Blue
import icu.yuqiuyijiaren.xiaobai.ui.theme.Green
import icu.yuqiuyijiaren.xiaobai.ui.theme.StudioElevated
import icu.yuqiuyijiaren.xiaobai.ui.theme.SurfaceGlass
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Self-developed Mobile Studio Video Player:
 * - Gestures: double-tap left (-2s), double-tap right (+2s) with animated bubble feedback
 * - Single tap to toggle HUD with auto-hide
 * - Speed control cycling (0.5x, 1.0x, 1.25x, 1.5x, 2.0x)
 * - Frame step buttons ([-1帧], [+1帧])
 * - Multi-segment mini scrubber with bright green rally blocks
 * - Smooth dynamic rally playback extension without restarting
 */
@Composable
fun LocalVideoPlayer(
    source: VideoSource?,
    playheadSec: Double,
    onPlayheadChange: (Double) -> Unit,
    onIsPlayingChange: (Boolean) -> Unit = {},
    playback: PlaybackCommand? = null,
    onPlaybackConsumed: () -> Unit = {},
    rallies: List<Rally> = emptyList(),
    selectedRally: Rally? = null,
    smartSkip: Boolean = false,
    onToggleSmartSkip: () -> Unit = {},
    loopRally: Boolean = false,
    onToggleLoop: () -> Unit = {},
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
    var isHudVisible by remember { mutableStateOf(true) }
    var doubleTapFeedback by remember { mutableIntStateOf(0) } // -2 or +2 or 0
    var playbackSpeedIndex by remember { mutableIntStateOf(1) } // index into speeds list
    val speeds = listOf(0.5f, 1.0f, 1.25f, 1.5f, 2.0f)
    val durationSec = (source?.durationSec ?: 0.0).coerceAtLeast(0.001)

    // Listen to player state
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                onIsPlayingChange(playing)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // Auto-hide HUD after 3.2s of playback
    LaunchedEffect(isPlaying, isHudVisible) {
        if (isPlaying && isHudVisible) {
            delay(3200)
            isHudVisible = false
        }
    }

    // Clear double-tap feedback after 650ms
    LaunchedEffect(doubleTapFeedback) {
        if (doubleTapFeedback != 0) {
            delay(650)
            doubleTapFeedback = 0
        }
    }

    // Load media source
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

    // Playback command from outside (e.g. PlaySelected, Start/End Nudge)
    LaunchedEffect(playback?.token) {
        val cmd = playback ?: return@LaunchedEffect
        if (cmd.seekSec != null) {
            val targetMs = (cmd.seekSec * 1000).toLong().coerceAtLeast(0L)
            player.seekTo(targetMs)
            lastEmittedSec = cmd.seekSec
            onPlayheadChange(cmd.seekSec)
        }
        playUntilSec = cmd.playUntilSec ?: Double.POSITIVE_INFINITY
        player.playWhenReady = cmd.autoPlay
        if (cmd.autoPlay) {
            if (!player.isPlaying) player.play()
        } else {
            if (player.isPlaying) player.pause()
        }
        onPlaybackConsumed()
    }

    // Keep playhead synchronized if changed externally (e.g. timeline scrub)
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

    // Dynamically extend playUntilSec when selected rally is extended while playing (no restart!)
    LaunchedEffect(selectedRally?.endSec) {
        val currentSelected = selectedRally ?: return@LaunchedEffect
        if (playUntilSec.isFinite()) {
            playUntilSec = currentSelected.endSec
        }
    }

    // Main playback loop
    LaunchedEffect(player, rallies, selectedRally, smartSkip, loopRally) {
        while (true) {
            if (player.playbackState == Player.STATE_READY || player.isPlaying) {
                val sec = player.currentPosition / 1000.0
                lastEmittedSec = sec
                onPlayheadChange(sec)

                if (player.isPlaying) {
                    if (loopRally && selectedRally != null) {
                        if (sec >= selectedRally.endSec - 0.04) {
                            val loopStartMs = (selectedRally.startSec * 1000).toLong()
                            player.seekTo(loopStartMs)
                            lastEmittedSec = selectedRally.startSec
                            onPlayheadChange(selectedRally.startSec)
                        }
                    } else if (smartSkip && rallies.isNotEmpty()) {
                        val sorted = rallies.sortedBy { it.startSec }
                        val inside = sorted.find { sec >= it.startSec - 0.05 && sec <= it.endSec - 0.04 }
                        if (inside == null) {
                            val next = sorted.find { it.startSec > sec }
                            if (next != null) {
                                player.seekTo((next.startSec * 1000).toLong())
                            } else if (sec > sorted.last().endSec) {
                                player.pause()
                            }
                        }
                    } else {
                        val effectiveEnd = if (playUntilSec.isFinite()) playUntilSec else Double.POSITIVE_INFINITY
                        if (sec >= effectiveEnd - 0.04) {
                            player.pause()
                            playUntilSec = Double.POSITIVE_INFINITY
                        }
                    }
                }
            }
            delay(60)
        }
    }

    fun seekRelative(deltaSec: Double) {
        val currentSec = player.currentPosition / 1000.0
        val targetSec = (currentSec + deltaSec).coerceIn(0.0, durationSec)
        player.seekTo((targetSec * 1000).toLong())
        lastEmittedSec = targetSec
        onPlayheadChange(targetSec)
    }

    fun stepFrame(forward: Boolean) {
        val delta = if (forward) 0.04 else -0.04
        val currentSec = player.currentPosition / 1000.0
        val targetSec = (currentSec + delta).coerceIn(0.0, durationSec)
        if (player.isPlaying) player.pause()
        player.seekTo((targetSec * 1000).toLong())
        lastEmittedSec = targetSec
        onPlayheadChange(targetSec)
    }

    val shape = Modifier
        .then(if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        .clip(RoundedCornerShape(14.dp))
        .background(Color(0xFF07070A))
        .border(1.dp, Color(0xFF22222A), RoundedCornerShape(14.dp))

    Box(modifier = modifier.then(shape)) {
        // 1. AndroidView ExoPlayer surface
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

        // 2. Gesture Detector Layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(durationSec) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            val isLeft = offset.x < size.width / 2
                            if (isLeft) {
                                seekRelative(-2.0)
                                doubleTapFeedback = -2
                            } else {
                                seekRelative(2.0)
                                doubleTapFeedback = 2
                            }
                            isHudVisible = true
                        },
                        onTap = {
                            isHudVisible = !isHudVisible
                        },
                    )
                },
        )

        // 3. Double-tap Ripple / Indicator Badges
        AnimatedVisibility(
            visible = doubleTapFeedback == -2,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 28.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Default.FastRewind, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(4.dp))
                Text("-2s", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }

        AnimatedVisibility(
            visible = doubleTapFeedback == 2,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 28.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text("+2s", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.Default.FastForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }

        // 4. Center Large Play / Pause button
        AnimatedVisibility(
            visible = !isPlaying || isHudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(Color(0x991C1C24))
                    .border(1.dp, Color(0x44FFFFFF), CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures {
                            if (player.isPlaying) player.pause() else player.play()
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
        }

        // 5. In-Player HUD Overlays (Top & Bottom)
        AnimatedVisibility(
            visible = isHudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top In-Player Controls
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color(0xDD000000), Color.Transparent),
                            ),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = source?.displayName ?: "羽毛球比赛",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    Spacer(Modifier.width(8.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Loop toggle
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (loopRally) Green.copy(alpha = 0.25f) else Color(0x55000000))
                                .border(1.dp, if (loopRally) Green else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                                .pointerInput(Unit) { detectTapGestures { onToggleLoop() } }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Repeat,
                                    contentDescription = null,
                                    tint = if (loopRally) Green else Color(0xCCFFFFFF),
                                    modifier = Modifier.size(12.dp),
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    "循环",
                                    color = if (loopRally) Green else Color(0xCCFFFFFF),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }

                        // Smart Skip toggle
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (smartSkip) Amber.copy(alpha = 0.25f) else Color(0x55000000))
                                .border(1.dp, if (smartSkip) Amber else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                                .pointerInput(Unit) { detectTapGestures { onToggleSmartSkip() } }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                "跳死球",
                                color = if (smartSkip) Amber else Color(0xCCFFFFFF),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        // Speed toggle
                        val currentSpeed = speeds[playbackSpeedIndex]
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x55000000))
                                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                                .pointerInput(Unit) {
                                    detectTapGestures {
                                        playbackSpeedIndex = (playbackSpeedIndex + 1) % speeds.size
                                        val nextSpeed = speeds[playbackSpeedIndex]
                                        player.playbackParameters = PlaybackParameters(nextSpeed)
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        ) {
                            Text(
                                "${currentSpeed}x",
                                color = if (currentSpeed != 1.0f) Blue else Color(0xCCFFFFFF),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                // Bottom In-Player Controls
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color(0xF2000000)),
                            ),
                        )
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    // Mini Segmented Progress Scrub Bar
                    MiniSegmentedScrubber(
                        durationSec = durationSec,
                        playheadSec = playheadSec,
                        rallies = rallies,
                        selectedRally = selectedRally,
                        onSeek = { target ->
                            player.seekTo((target * 1000).toLong())
                            lastEmittedSec = target
                            onPlayheadChange(target)
                        },
                    )

                    Spacer(Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Play/Pause + Time
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Color(0x55FFFFFF))
                                    .pointerInput(Unit) {
                                        detectTapGestures {
                                            if (player.isPlaying) player.pause() else player.play()
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "${formatClock(playheadSec)} / ${formatClock(durationSec)}",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        // Frame stepping & Fast Forward buttons
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0x33FFFFFF))
                                    .pointerInput(Unit) { detectTapGestures { stepFrame(forward = false) } }
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            ) {
                                Text("-1帧", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0x33FFFFFF))
                                    .pointerInput(Unit) { detectTapGestures { stepFrame(forward = true) } }
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            ) {
                                Text("+1帧", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0x3834C759))
                                    .border(1.dp, Color(0xFF34C759), RoundedCornerShape(6.dp))
                                    .pointerInput(Unit) { detectTapGestures { seekRelative(1.5) } }
                                    .padding(horizontal = 7.dp, vertical = 3.dp),
                            ) {
                                Text("+1.5s", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Mini Segmented Progress Scrub Bar:
 * Displays all rallies in bright neon green (#30D158), inactive dead time in dark gray,
 * and current playhead with a glowing scrubber handle.
 */
@Composable
private fun MiniSegmentedScrubber(
    durationSec: Double,
    playheadSec: Double,
    rallies: List<Rally>,
    selectedRally: Rally?,
    onSeek: (Double) -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val totalWidth = maxWidth

        // Gesture handler for scrubbing across the entire bar
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(durationSec) {
                    detectTapGestures { offset ->
                        val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek(ratio * durationSec)
                    }
                }
                .pointerInput(durationSec) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val ratio = (change.position.x / size.width).coerceIn(0f, 1f)
                        onSeek(ratio * durationSec)
                    }
                },
        ) {
            // Track background (dead time)
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0x55FFFFFF)),
            )

            // Overlaid rally segments
            rallies.forEach { r ->
                val startRatio = (r.startSec / durationSec).toFloat().coerceIn(0f, 1f)
                val endRatio = (r.endSec / durationSec).toFloat().coerceIn(0f, 1f)
                val isSelected = selectedRally == r
                val segmentWidth = ((endRatio - startRatio) * totalWidth.value).coerceAtLeast(3f).dp

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = totalWidth * startRatio)
                        .width(segmentWidth)
                        .height(6.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isSelected) Amber else Green),
                )
            }

            // Playhead handle
            val playRatio = (playheadSec / durationSec).toFloat().coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (totalWidth * playRatio) - 4.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .border(1.5.dp, Blue, CircleShape),
            )
        }
    }
}

private fun formatClock(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).roundToInt()
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}
