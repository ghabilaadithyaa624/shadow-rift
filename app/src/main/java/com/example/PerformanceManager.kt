package com.example

import android.app.ActivityManager
import android.content.Context
import android.util.Log

enum class MemoryTier {
    LOW, MID, HIGH
}

object PerformanceManager {
    private const val PREFS_NAME = "performance_prefs"
    private const val KEY_OVERRIDE_TIER = "override_tier"
    
    var detectedTier: MemoryTier = MemoryTier.HIGH
        private set
        
    var selectedTier: MemoryTier = MemoryTier.HIGH
        private set

    var isOptimizingAtStartupDone = false

    fun init(context: Context) {
        val totalMemoryGb = getDeviceTotalMemoryGb(context)
        detectedTier = when {
            totalMemoryGb <= 3.5 -> MemoryTier.LOW
            totalMemoryGb <= 5.5 -> MemoryTier.MID
            else -> MemoryTier.HIGH
        }
        
        // Load override preference if any
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val overrideStr = prefs.getString(KEY_OVERRIDE_TIER, null)
        selectedTier = if (overrideStr != null) {
            try {
                MemoryTier.valueOf(overrideStr)
            } catch (e: Exception) {
                detectedTier
            }
        } else {
            detectedTier
        }
        
        Log.d("PerformanceManager", "Device RAM detected: ${String.format("%.2f", totalMemoryGb)} GB. Default Tier: $detectedTier. Selected Tier: $selectedTier")
    }

    private fun getDeviceTotalMemoryGb(context: Context): Double {
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memoryInfo = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            memoryInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        } catch (e: Exception) {
            4.0 // fallback
        }
    }

    fun setManualTier(context: Context, tier: MemoryTier?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (tier == null) {
            prefs.edit().remove(KEY_OVERRIDE_TIER).apply()
            selectedTier = detectedTier
        } else {
            prefs.edit().putString(KEY_OVERRIDE_TIER, tier.name).apply()
            selectedTier = tier
        }
        Log.d("PerformanceManager", "Manual override tier set to: $selectedTier")
    }

    fun isManualOverrideActive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.contains(KEY_OVERRIDE_TIER)
    }

    // Dynamic getters based on the active selected tier
    val maxParticles: Int
        get() = when (selectedTier) {
            MemoryTier.LOW -> 15
            MemoryTier.MID -> 30
            MemoryTier.HIGH -> 50
        }

    val resolutionScale: Float
        get() = when (selectedTier) {
            MemoryTier.LOW -> 0.75f
            MemoryTier.MID -> 0.85f
            MemoryTier.HIGH -> 1.00f
        }

    val isScreenShakeEnabled: Boolean
        get() = when (selectedTier) {
            MemoryTier.LOW -> false
            MemoryTier.MID -> true
            MemoryTier.HIGH -> true
        }

    val screenShakeIntensityMultiplier: Float
        get() = when (selectedTier) {
            MemoryTier.LOW -> 0.0f
            MemoryTier.MID -> 0.5f
            MemoryTier.HIGH -> 1.0f
        }

    val maxEnemiesPerRoom: Int
        get() = when (selectedTier) {
            MemoryTier.LOW -> 3
            MemoryTier.MID -> 5
            MemoryTier.HIGH -> 8
        }

    val spriteCacheSizeMb: Int
        get() = when (selectedTier) {
            MemoryTier.LOW -> 24
            MemoryTier.MID -> 40
            MemoryTier.HIGH -> 64
        }

    val isFloatingDamageNumbersEnabled: Boolean
        get() = when (selectedTier) {
            MemoryTier.LOW -> false
            MemoryTier.MID -> true
            MemoryTier.HIGH -> true
        }

    val isGradientsEnabled: Boolean
        get() = when (selectedTier) {
            MemoryTier.LOW -> false
            MemoryTier.MID -> true
            MemoryTier.HIGH -> true
        }

    val targetFps: Int
        get() = when (selectedTier) {
            MemoryTier.LOW -> 30
            MemoryTier.MID -> 45
            MemoryTier.HIGH -> 60
        }

    val isBackgroundParallaxEnabled: Boolean
        get() = when (selectedTier) {
            MemoryTier.LOW -> false
            MemoryTier.MID -> false
            MemoryTier.HIGH -> true
        }

    val audioSampleRateKhz: Int
        get() = when (selectedTier) {
            MemoryTier.LOW -> 22
            MemoryTier.MID -> 44
            MemoryTier.HIGH -> 44
        }

    val audioChannels: String
        get() = when (selectedTier) {
            MemoryTier.LOW -> "mono"
            else -> "stereo"
        }
}
