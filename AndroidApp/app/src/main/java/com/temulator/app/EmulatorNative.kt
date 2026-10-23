package com.temulator.app

import android.graphics.Bitmap

object EmulatorNative {
    init {
        System.loadLibrary("mesen-core")
    }

    external fun initCore()
    external fun initCoreWithFolder(homeFolder: String)
    external fun setFolderOverrides(saveFolder: String, saveStateFolder: String)
    external fun loadRom(path: String, consoleType: Int): Boolean
    external fun getConsoleType(): Int
    external fun setKeyState(keyCode: Int, isPressed: Boolean)
    external fun runFrame(bitmap: Bitmap): Int

    external fun getFrameWidth(): Int
    external fun getFrameHeight(): Int
    external fun getFps(): Double

    // Battery Save / Stop Emulation
    external fun pauseEmulation()
    external fun resumeEmulation()
    external fun saveBattery()
    external fun stopEmulation()

    // Save & Load State
    external fun saveState(slot: Int): Boolean
    external fun loadState(slot: Int): Boolean
    external fun hasSaveState(slot: Int): Boolean
    external fun resetGame()
    external fun powerCycleGame()

    // Video & Display
    external fun setVideoFilter(filterType: Int)
    external fun setAspectRatio(aspectRatio: Int, customRatio: Double)
    external fun setBilinearInterpolation(enabled: Boolean)
    external fun setScanlineIntensity(intensity: Int)
    external fun setColorAdjustments(brightness: Double, contrast: Double, saturation: Double, hue: Double)
    external fun setScreenRotation(rotation: Int)

    // Emulation & Speed
    external fun setEmulationSpeed(speedPercent: Int)
    external fun setTurbo(enabled: Boolean)
    external fun setRewind(enabled: Boolean)
    external fun setRegion(region: Int)

    // In-Game Rewind
    external fun startRewind()
    external fun stopRewind()
    external fun rewindSeconds(seconds: Int)

    // Cheats
    external fun addCheat(type: Int, code: String): Boolean
    external fun clearCheats()
    external fun getCheatCount(): Int

    // Audio
    external fun setAudioConfig(enabled: Boolean, volume: Int, sampleRate: Int, reverb: Boolean)

    // System Specific
    external fun setSystemConfig(removeSpriteLimit: Boolean, blendFrames: Boolean, gbaColorCorrection: Boolean, gbModel: Int)
    external fun setGameboyPalette(palType: Int)
}
