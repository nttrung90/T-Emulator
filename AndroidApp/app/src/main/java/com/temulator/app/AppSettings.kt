package com.temulator.app

import android.content.Context
import android.content.SharedPreferences

class AppSettings(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("emulator_preferences", Context.MODE_PRIVATE)

    var currentSlot: Int
        get() = prefs.getInt("current_slot", 1)
        set(value) = prefs.edit().putInt("current_slot", value.coerceIn(1, 10)).apply()

    var autoSaveState: Boolean
        get() = prefs.getBoolean("auto_save_state", false)
        set(value) = prefs.edit().putBoolean("auto_save_state", value).apply()

    // Aspect Ratio: 1 = Auto, 4 = Standard 4:3, 5 = Widescreen 16:9, 0 = No Stretching (1:1), 7 = Stretch Full
    var aspectRatio: Int
        get() = prefs.getInt("aspect_ratio", 1)
        set(value) = prefs.edit().putInt("aspect_ratio", value).apply()

    // Scale Mode for Android ImageView: 0 = Fit Center, 1 = Fit XY (Stretch), 2 = Center (1x Original)
    var scaleMode: Int
        get() = prefs.getInt("scale_mode", 0)
        set(value) = prefs.edit().putInt("scale_mode", value).apply()

    // Video Filter:
    // 0 = None, 1 = NTSC Blargg, 3 = LCD Grid, 4 = xBRZ 2x, 5 = xBRZ 3x, 6 = xBRZ 4x,
    // 10 = HQ 2x, 11 = HQ 3x, 13 = Scale 2x, 19 = Prescale 2x, 21 = Prescale 4x
    var videoFilter: Int
        get() = prefs.getInt("video_filter", 0)
        set(value) = prefs.edit().putInt("video_filter", value).apply()

    var bilinearFiltering: Boolean
        get() = prefs.getBoolean("bilinear_filtering", false)
        set(value) = prefs.edit().putBoolean("bilinear_filtering", value).apply()

    var scanlineIntensity: Int
        get() = prefs.getInt("scanline_intensity", 0)
        set(value) = prefs.edit().putInt("scanline_intensity", value.coerceIn(0, 100)).apply()

    var screenRotation: Int
        get() = prefs.getInt("screen_rotation", 0)
        set(value) = prefs.edit().putInt("screen_rotation", value).apply()

    var emulationSpeed: Int
        get() = prefs.getInt("emulation_speed", 100)
        set(value) = prefs.edit().putInt("emulation_speed", value.coerceIn(25, 500)).apply()

    var turboEnabled: Boolean
        get() = prefs.getBoolean("turbo_enabled", false)
        set(value) = prefs.edit().putBoolean("turbo_enabled", value).apply()

    var rewindEnabled: Boolean
        get() = prefs.getBoolean("rewind_enabled", true)
        set(value) = prefs.edit().putBoolean("rewind_enabled", value).apply()

    var region: Int
        get() = prefs.getInt("region", 0) // 0 = Auto, 1 = NTSC, 2 = PAL
        set(value) = prefs.edit().putInt("region", value).apply()

    var audioEnabled: Boolean
        get() = prefs.getBoolean("audio_enabled", true)
        set(value) = prefs.edit().putBoolean("audio_enabled", value).apply()

    var audioVolume: Int
        get() = prefs.getInt("audio_volume", 100)
        set(value) = prefs.edit().putInt("audio_volume", value.coerceIn(0, 100)).apply()

    var hapticFeedback: Boolean
        get() = prefs.getBoolean("haptic_feedback", true)
        set(value) = prefs.edit().putBoolean("haptic_feedback", value).apply()

    var gamepadOpacity: Int
        get() = prefs.getInt("gamepad_opacity", 100)
        set(value) = prefs.edit().putInt("gamepad_opacity", value.coerceIn(20, 100)).apply()

    var removeSpriteLimit: Boolean
        get() = prefs.getBoolean("remove_sprite_limit", false)
        set(value) = prefs.edit().putBoolean("remove_sprite_limit", value).apply()

    var blendFrames: Boolean
        get() = prefs.getBoolean("blend_frames", true)
        set(value) = prefs.edit().putBoolean("blend_frames", value).apply()

    var gbaColorCorrection: Boolean
        get() = prefs.getBoolean("gba_color_correction", true)
        set(value) = prefs.edit().putBoolean("gba_color_correction", value).apply()

    var gameboyModel: Int
        get() = prefs.getInt("gameboy_model", 0) // 0 = Auto, 3 = DMG, 4 = GBC, 5 = SGB
        set(value) = prefs.edit().putInt("gameboy_model", value).apply()

    var gameboyPalette: Int
        get() = prefs.getInt("gameboy_palette", 0) // 0 = Original, 1 = DMG Green, 2 = Pocket Gray, 3 = Light Teal
        set(value) = prefs.edit().putInt("gameboy_palette", value).apply()

    // Cheats: stored as "TYPE:CODE", e.g. "0:GXXZKZVI"
    var cheatsList: Set<String>
        get() = prefs.getStringSet("cheats_list", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("cheats_list", value).apply()

    fun addCheat(type: Int, code: String): Boolean {
        val entry = "$type:${code.trim()}"
        val set = cheatsList.toMutableSet()
        set.add(entry)
        cheatsList = set
        return try {
            EmulatorNative.addCheat(type, code.trim())
        } catch (e: Exception) {
            false
        }
    }

    fun removeCheat(entry: String) {
        val set = cheatsList.toMutableSet()
        set.remove(entry)
        cheatsList = set
        reloadCheats()
    }

    fun clearCheats() {
        cheatsList = emptySet()
        try {
            EmulatorNative.clearCheats()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun reloadCheats() {
        try {
            EmulatorNative.clearCheats()
            for (item in cheatsList) {
                val parts = item.split(":")
                if (parts.size == 2) {
                    val type = parts[0].toIntOrNull() ?: 0
                    val code = parts[1]
                    EmulatorNative.addCheat(type, code)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Apply all stored settings to Mesen Native Core
     */
    fun applyToNative() {
        try {
            // Video & Filter
            EmulatorNative.setVideoFilter(videoFilter)
            EmulatorNative.setAspectRatio(aspectRatio, 0.0)
            EmulatorNative.setBilinearInterpolation(bilinearFiltering)
            EmulatorNative.setScanlineIntensity(scanlineIntensity)
            EmulatorNative.setScreenRotation(screenRotation)

            // Speed
            EmulatorNative.setEmulationSpeed(emulationSpeed)
            EmulatorNative.setTurbo(turboEnabled)
            EmulatorNative.setRegion(region)

            // Rewind
            EmulatorNative.setRewind(rewindEnabled)

            // Audio
            EmulatorNative.setAudioConfig(audioEnabled, audioVolume, 48000, false)

            // System
            EmulatorNative.setSystemConfig(
                removeSpriteLimit,
                blendFrames,
                gbaColorCorrection,
                gameboyModel
            )
            EmulatorNative.setGameboyPalette(gameboyPalette)

            // Cheats
            reloadCheats()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
