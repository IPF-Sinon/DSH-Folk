package me.bmax.apatch.util

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

object DPIUtils {
    private const val KEY_APP_DPI = "app_dpi"
    const val DEFAULT_DPI = -1 // Follow System
    const val DPI_MIN = 160
    const val DPI_MAX = 600

    var currentDpi: Int by mutableIntStateOf(DEFAULT_DPI)
        private set

    val systemDpi: Int
        get() = android.content.res.Resources.getSystem().displayMetrics.densityDpi

    fun load(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        currentDpi = prefs.getInt(KEY_APP_DPI, DEFAULT_DPI)
    }

    fun setDpi(context: Context, dpi: Int) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (dpi == DEFAULT_DPI) {
            prefs.edit { putInt(KEY_APP_DPI, DEFAULT_DPI) }
            currentDpi = DEFAULT_DPI
        } else {
            val clamped = dpi.coerceIn(DPI_MIN, DPI_MAX)
            prefs.edit { putInt(KEY_APP_DPI, clamped) }
            currentDpi = clamped
        }
    }

    fun applyDpi(context: Context) {
        if (currentDpi == DEFAULT_DPI) return

        val res = context.resources
        val config = res.configuration
        val metrics = res.displayMetrics

        if (config.densityDpi != currentDpi) {
            config.densityDpi = currentDpi
            metrics.densityDpi = currentDpi
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, metrics)
        }
    }

    fun updateContext(context: Context): Context {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val dpi = prefs.getInt(KEY_APP_DPI, DEFAULT_DPI)

        if (dpi == DEFAULT_DPI) return context

        val config = Configuration(context.resources.configuration)
        config.densityDpi = dpi
        return context.createConfigurationContext(config)
    }

    fun getDpiFriendlyName(dpi: Int): String {
        return when {
            dpi == DEFAULT_DPI -> "System Default"
            dpi <= 240 -> "Small"
            dpi <= 360 -> "Medium"
            dpi <= 480 -> "Large"
            else -> "XLarge"
        }
    }

    data class DpiPreset(val name: String, val value: Int)

    val presets = listOf(
        DpiPreset("Small", 240),
        DpiPreset("Medium", 360),
        DpiPreset("Large", 480),
        DpiPreset("XLarge", 560),
    )
}