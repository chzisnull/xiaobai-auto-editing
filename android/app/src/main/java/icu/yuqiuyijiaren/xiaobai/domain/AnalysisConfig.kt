package icu.yuqiuyijiaren.xiaobai.domain

import icu.yuqiuyijiaren.xiaobai.device.DeviceProfile

/**
 * Recognition intensity tiers. User may override; defaults come from [DeviceProfile].
 * Higher tiers trade CPU/RAM for better motion fidelity and boundary quality.
 */
enum class AnalysisTier(
    val label: String,
    val hint: String,
) {
    Fast(
        label = "快速",
        hint = "省电 · 适合低端机/预览",
    ),
    Standard(
        label = "标准",
        hint = "均衡 · 默认推荐",
    ),
    Precise(
        label = "精确",
        hint = "高质 · 更密运动采样",
    ),
}

/**
 * Concrete pipeline knobs derived from tier × device headroom.
 */
data class AnalysisConfig(
    val tier: AnalysisTier,
    val visualFps: Int,
    val motionDiffThreshold: Int,
    val useClosestFrame: Boolean,
    val maxAudioMinutes: Int,
    val motionMaxSamples: Int,
    val doubleHighlightPass: Boolean,
    val softProminence: Float,
) {
    val summary: String
        get() = "${tier.label} · ${visualFps}fps 运动 · 音轨≤${maxAudioMinutes}分钟"

    companion object {
        fun forTier(tier: AnalysisTier, device: DeviceProfile): AnalysisConfig {
            // Cap tier by device to avoid OOM while still allowing user choice on strong devices.
            val effective = clampTierToDevice(tier, device)
            return when (effective) {
                AnalysisTier.Fast -> AnalysisConfig(
                    tier = effective,
                    visualFps = if (device.totalRamMb < 3072) 3 else 4,
                    motionDiffThreshold = 22,
                    useClosestFrame = false, // SYNC is cheaper
                    maxAudioMinutes = if (device.totalRamMb < 3072) 12 else 18,
                    motionMaxSamples = 2400,
                    doubleHighlightPass = false,
                    softProminence = 1.06f,
                )
                AnalysisTier.Standard -> AnalysisConfig(
                    tier = effective,
                    visualFps = when {
                        device.totalRamMb >= 6144 -> 7
                        device.totalRamMb >= 4096 -> 6
                        else -> 5
                    },
                    motionDiffThreshold = 18,
                    useClosestFrame = true,
                    maxAudioMinutes = if (device.totalRamMb >= 4096) 25 else 18,
                    motionMaxSamples = 4200,
                    doubleHighlightPass = true,
                    softProminence = 1.04f,
                )
                AnalysisTier.Precise -> AnalysisConfig(
                    tier = effective,
                    visualFps = when {
                        device.totalRamMb >= 8192 && device.cpuCores >= 6 -> 9
                        device.totalRamMb >= 6144 -> 8
                        else -> 7
                    },
                    motionDiffThreshold = 16,
                    useClosestFrame = true,
                    maxAudioMinutes = if (device.totalRamMb >= 6144) 30 else 22,
                    motionMaxSamples = 5400,
                    doubleHighlightPass = true,
                    softProminence = 1.03f,
                )
            }
        }

        /**
         * On very weak devices, "Precise" is auto-downgraded to Standard to keep accuracy usable.
         * User still sees the selection; effective config is honest in summary.
         */
        fun clampTierToDevice(requested: AnalysisTier, device: DeviceProfile): AnalysisTier {
            // Check lowest tier first so extreme low-RAM never attempts Precise/Standard.
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
