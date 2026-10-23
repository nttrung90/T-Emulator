package com.temulator.app.util

import android.os.Build
import com.temulator.app.BuildConfig

object EmulatorDetector {

    /**
     * Checks whether the current runtime environment is an Android emulator.
     * Safe against null properties in JVM mock/test environments.
     */
    fun isEmulator(): Boolean {
        val fingerprint = Build.FINGERPRINT ?: ""
        val model = Build.MODEL ?: ""
        val manufacturer = Build.MANUFACTURER ?: ""
        val brand = Build.BRAND ?: ""
        val device = Build.DEVICE ?: ""
        val product = Build.PRODUCT ?: ""
        val hardware = Build.HARDWARE ?: ""

        return (fingerprint.startsWith("generic")
                || fingerprint.startsWith("unknown")
                || model.contains("google_sdk")
                || model.contains("Emulator")
                || model.contains("Android SDK built for x86")
                || model.contains("sdk_gphone")
                || manufacturer.contains("Genymotion")
                || (brand.startsWith("generic") && device.startsWith("generic"))
                || "google_sdk" == product
                || hardware.contains("goldfish")
                || hardware.contains("ranchu"))
    }

    /**
     * Dev tools and simulator features are strictly restricted to DEBUG builds.
     * In release builds, this is ALWAYS false, even if running on an emulator (F01).
     */
    fun isDevMode(): Boolean {
        return BuildConfig.DEBUG
    }

    @Deprecated("Use isDevMode() for features requiring debug build", ReplaceWith("isDevMode()"))
    fun isDevOrEmulator(): Boolean {
        return BuildConfig.DEBUG
    }
}
