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
        // <3GB: Precise → Standard (still usable quality)
        val midLow = AnalysisConfig.forTier(AnalysisTier.Precise, device(ramMb = 2048))
        assertEquals(AnalysisTier.Standard, midLow.tier)
        // <2GB: force Fast
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
    }
}
