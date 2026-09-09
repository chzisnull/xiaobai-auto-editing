package icu.yuqiuyijiaren.xiaobai.domain

import icu.yuqiuyijiaren.xiaobai.device.DeviceProfile
import kotlin.math.max
import kotlin.math.min

/**
 * Recognition intensity tiers. User may override; defaults come from [DeviceProfile].
 *
 * Speed design: high-end RAM must NOT auto-inflate motion FPS. Long videos are capped
 * by total sample budget; motion uses sequential MediaCodec, not random seeks.
 */
enum class AnalysisTier(
    val label: String,
    val hint: String,
) {
    Fast(
        label = "快速",
        hint = "省电 · 长片优先",
    ),
    Standard(
        label = "标准",
        hint = "均衡 · 默认推荐",
    ),
    Precise(
        label = "精确",
        hint = "更密采样 · 仍封顶预算",
    ),
}

/**
 * Concrete pipeline knobs derived from tier × device headroom.
 */
data class AnalysisConfig(
    val tier: AnalysisTier,
    /** Base motion sample rate before duration scaling. */
    val visualFps: Int,
    val motionDiffThreshold: Int,
    /** Legacy flag; sequential codec path ignores CLOSEST seeks. */
    val useClosestFrame: Boolean,
    val maxAudioMinutes: Int,
    /** Hard cap on motion energy samples for whole video. */
    val motionMaxSamples: Int,
    val doubleHighlightPass: Boolean,
    val softProminence: Float,
    val applyMotionBlur: Boolean,
    /** Audio hit MAD k. Higher rejects adjacent-court / shoe noise. */
    val hitMadK: Float,
) {
    val summary: String
        get() = "${tier.label} · 运动≤${visualFps}fps · 最多${motionMaxSamples}点 · 音轨≤${maxAudioMinutes}分钟"

    /**
     * Duration-aware FPS so a 12-minute match does not run thousands of samples.
     */
    fun effectiveMotionFps(durationSec: Double): Int {
        val d = durationSec.coerceAtLeast(1.0)
        val byLength = when {
            d > 15 * 60 -> 2
            d > 8 * 60 -> 3
            d > 4 * 60 -> min(visualFps, 4)
            d > 2 * 60 -> min(visualFps, max(3, visualFps - 1))
            else -> visualFps
        }
        return byLength.coerceIn(2, visualFps)
    }

    fun effectiveMaxSamples(durationSec: Double): Int {
        val d = durationSec.coerceAtLeast(1.0)
        val lengthCap = when {
            d > 15 * 60 -> 1000
            d > 10 * 60 -> 1200
            d > 6 * 60 -> 1400
            d > 3 * 60 -> motionMaxSamples
            else -> motionMaxSamples
        }
        return min(motionMaxSamples, lengthCap).coerceAtLeast(400)
    }

    companion object {
        fun forTier(tier: AnalysisTier, device: DeviceProfile): AnalysisConfig {
            val effective = clampTierToDevice(tier, device)
            // Do NOT raise FPS with more RAM — that made flagships slower on long videos.
            return when (effective) {
                AnalysisTier.Fast -> AnalysisConfig(
                    tier = effective,
                    visualFps = 3,
                    motionDiffThreshold = 22,
                    useClosestFrame = false,
                    maxAudioMinutes = if (device.totalRamMb < 3072) 12 else 20,
                    motionMaxSamples = 900,
                    doubleHighlightPass = false,
                    softProminence = 1.02f,
                    applyMotionBlur = false,
                    hitMadK = 4.2f,
                )
                AnalysisTier.Standard -> AnalysisConfig(
                    tier = effective,
                    visualFps = 4,
                    motionDiffThreshold = 18,
                    useClosestFrame = false,
                    maxAudioMinutes = if (device.totalRamMb >= 4096) 25 else 18,
                    motionMaxSamples = 1200,
                    doubleHighlightPass = false,
                    softProminence = 1.02f,
                    applyMotionBlur = false,
                    hitMadK = 4.2f,
                )
                AnalysisTier.Precise -> AnalysisConfig(
                    tier = effective,
                    visualFps = 5,
                    motionDiffThreshold = 16,
                    useClosestFrame = false,
                    maxAudioMinutes = if (device.totalRamMb >= 6144) 30 else 22,
                    motionMaxSamples = 1800,
                    doubleHighlightPass = false,
                    softProminence = 1.02f,
                    applyMotionBlur = true,
                    hitMadK = 4.0f,
                )
            }
        }

        fun clampTierToDevice(requested: AnalysisTier, device: DeviceProfile): AnalysisTier {
            if (device.totalRamMb < 2048) {
                return AnalysisTier.Fast
            }
            if (device.totalRamMb < 3072 && requested == AnalysisTier.Precise) {
                return AnalysisTier.Standard
            }
            return requested
        }
    }
}
