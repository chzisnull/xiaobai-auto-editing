package icu.yuqiuyijiaren.xiaobai.domain

import icu.yuqiuyijiaren.xiaobai.device.DeviceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisConfigTest {

    private fun device(
        ramMb: Long,
        cores: Int = 4,
        score: Int = 50,
        recommended: AnalysisTier = AnalysisTier.Standard,
    ) = DeviceProfile(
        displayName = "Test",
        model = "Test",
        manufacturer = "Unit",
        totalRamMb = ramMb,
        cpuCores = cores,
        sdkInt = 34,
        isEmulator = false,
        performanceScore = score,
        classLabel = "测试",
        recommendedTier = recommended,
    )

    @Test
    fun preciseDowngradesOnLowRam() {
        val midLow = AnalysisConfig.forTier(AnalysisTier.Precise, device(ramMb = 2048))
        assertEquals(AnalysisTier.Standard, midLow.tier)
        val veryLow = AnalysisConfig.forTier(AnalysisTier.Precise, device(ramMb = 1536))
        assertEquals(AnalysisTier.Fast, veryLow.tier)
    }

    @Test
    fun standardHasHigherFpsThanFast() {
        val d = device(ramMb = 6144, cores = 8)
        val fast = AnalysisConfig.forTier(AnalysisTier.Fast, d)
        val standard = AnalysisConfig.forTier(AnalysisTier.Standard, d)
        val precise = AnalysisConfig.forTier(AnalysisTier.Precise, d)
        assertTrue(standard.visualFps > fast.visualFps)
        assertTrue(precise.visualFps >= standard.visualFps)
        assertTrue(precise.motionDiffThreshold <= standard.motionDiffThreshold)
        // High RAM must not unlock crazy sample counts
        assertTrue(precise.motionMaxSamples <= 2000)
    }

    @Test
    fun longVideoReducesEffectiveFpsAndSamples() {
        val cfg = AnalysisConfig.forTier(AnalysisTier.Precise, device(ramMb = 16384, cores = 8))
        assertTrue(cfg.effectiveMotionFps(12 * 60.0) <= 3)
        assertTrue(cfg.effectiveMaxSamples(12 * 60.0) <= 1200)
        assertTrue(cfg.effectiveMotionFps(60.0) >= cfg.effectiveMotionFps(12 * 60.0))
    }

    @Test
    fun highEndDoesNotInflateFpsVsMid() {
        val high = AnalysisConfig.forTier(AnalysisTier.Standard, device(ramMb = 16384, cores = 8))
        val mid = AnalysisConfig.forTier(AnalysisTier.Standard, device(ramMb = 4096, cores = 4))
        assertEquals(mid.visualFps, high.visualFps)
    }
}
