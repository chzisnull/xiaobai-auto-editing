package icu.yuqiuyijiaren.xiaobai.device

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisTier
import kotlin.math.roundToInt

/**
 * Lightweight device capability probe used to recommend analysis intensity
 * and clamp maximum quality so recognition stays accurate without OOM.
 */
object DeviceProfiler {

    fun probe(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalRamMb = (memInfo.totalMem / (1024L * 1024L)).coerceAtLeast(1L)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val isEmulator = isEmulatorDevice()
        val model = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Unknown"
        val manufacturer = Build.MANUFACTURER?.takeIf { it.isNotBlank() } ?: ""
        val sdk = Build.VERSION.SDK_INT

        // Score: RAM weighs most (OOM risk), then cores, then emulator penalty.
        var score = 0
        score += when {
            totalRamMb >= 8192 -> 50
            totalRamMb >= 6144 -> 40
            totalRamMb >= 4096 -> 28
            totalRamMb >= 3072 -> 18
            else -> 8
        }
        score += when {
            cores >= 8 -> 30
            cores >= 6 -> 24
            cores >= 4 -> 16
            else -> 8
        }
        score += when {
            sdk >= 34 -> 10
            sdk >= 31 -> 8
            else -> 4
        }
        if (isEmulator) score = (score * 0.85).roundToInt()

        val classLabel = when {
            score >= 75 -> "高性能"
            score >= 50 -> "均衡"
            else -> "省电"
        }
        val recommended = when {
            score >= 75 -> AnalysisTier.Precise
            score >= 45 -> AnalysisTier.Standard
            else -> AnalysisTier.Fast
        }
        // Emulators with lots of RAM can still do Standard/Precise, but default Standard if borderline
        val recommendedFinal = if (isEmulator && totalRamMb >= 6144 && recommended == AnalysisTier.Fast) {
            AnalysisTier.Standard
        } else {
            recommended
        }

        val displayName = listOf(manufacturer, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { model }

        return DeviceProfile(
            displayName = displayName,
            model = model,
            manufacturer = manufacturer,
            totalRamMb = totalRamMb,
            cpuCores = cores,
            sdkInt = sdk,
            isEmulator = isEmulator,
            performanceScore = score,
            classLabel = classLabel,
            recommendedTier = recommendedFinal,
        )
    }

    private fun isEmulatorDevice(): Boolean {
        val fp = Build.FINGERPRINT.orEmpty()
        val model = Build.MODEL.orEmpty()
        val product = Build.PRODUCT.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()
        val brand = Build.BRAND.orEmpty()
        val device = Build.DEVICE.orEmpty()
        return fp.startsWith("generic") ||
            fp.contains("emulator") ||
            model.contains("Emulator", ignoreCase = true) ||
            model.contains("Android SDK", ignoreCase = true) ||
            product.contains("sdk") ||
            product.contains("emulator") ||
            hardware.contains("ranchu") ||
            hardware.contains("goldfish") ||
            brand.startsWith("generic") && device.startsWith("generic") ||
            "google_sdk" == product
    }
}

data class DeviceProfile(
    val displayName: String,
    val model: String,
    val manufacturer: String,
    val totalRamMb: Long,
    val cpuCores: Int,
    val sdkInt: Int,
    val isEmulator: Boolean,
    val performanceScore: Int,
    val classLabel: String,
    val recommendedTier: AnalysisTier,
) {
    val ramLabel: String
        get() = when {
            totalRamMb >= 1024 -> String.format("%.1fGB", totalRamMb / 1024.0)
            else -> "${totalRamMb}MB"
        }

    val shortSummary: String
        get() = buildString {
            append(displayName.take(22))
            append(" · ")
            append(ramLabel)
            append(" · ")
            append(cpuCores)
            append("核")
            if (isEmulator) append(" · 模拟器")
        }

    val capabilityLine: String
        get() = "$classLabel · 推荐${recommendedTier.label}"
}
