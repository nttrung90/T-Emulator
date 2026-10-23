package com.temulator.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.content.res.Configuration
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.io.FileOutputStream

class EmulatorActivity : AppCompatActivity() {

    private lateinit var gameScreen: ImageView
    private lateinit var gamepadArea: View
    private lateinit var appSettings: AppSettings
    private var renderThread: Thread? = null
    @Volatile private var isRunning = false
    @Volatile private var isEmulationPaused = false
    private lateinit var gameBitmap: Bitmap

    private var currentSettingsDialog: AlertDialog? = null
    private var currentMenuDialog: AlertDialog? = null
    private var currentExitDialog: AlertDialog? = null

    private var romPath: String = ""
    private var consoleType: Int = 0
    private var emulatorGameLease: com.temulator.app.cloud.GameLease? = null

    // Mesen Key Codes
    private val BTN_UP = 1
    private val BTN_DOWN = 2
    private val BTN_LEFT = 3
    private val BTN_RIGHT = 4
    private val BTN_A = 5
    private val BTN_B = 6
    private val BTN_X = 7
    private val BTN_Y = 8
    private val BTN_L = 9
    private val BTN_R = 10
    private val BTN_SELECT = 11
    private val BTN_START = 12
    private val BTN_TURBO_A = 13
    private val BTN_TURBO_B = 14

    private val buttonViews = mutableMapOf<Int, View>()
    private val activePointerButtons = mutableMapOf<Int, Set<Int>>() // pointerId to set of btnIds
    private val currentlyPressedButtons = mutableSetOf<Int>()
    private var dpadPointerId: Int? = null
    private var dpadViewRef: View? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        appSettings = AppSettings(this)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                showEmulatorMenu()
            }
        })

        romPath = intent.getStringExtra("ROM_PATH") ?: ""
        consoleType = intent.getIntExtra("CONSOLE_TYPE", 0)

        if (romPath.isEmpty()) {
            Toast.makeText(this, R.string.error_load_rom, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        // Init dummy bitmap (256x240 for standard fallback, updated dynamically by JNI)
        gameBitmap = Bitmap.createBitmap(256, 240, Bitmap.Config.ARGB_8888)

        try {
            EmulatorNative.initCoreWithFolder(filesDir.absolutePath)
            val gameId = intent.getStringExtra("GAME_ID")
            if (!gameId.isNullOrEmpty()) {
                val lease = com.temulator.app.cloud.GameOperationCoordinator.getInstance().tryAcquire(
                    gameId,
                    com.temulator.app.cloud.GameLockType.EMULATOR_PLAYING
                )
                if (lease == null) {
                    Toast.makeText(this, "Game is currently busy with backup or restore. Please wait.", Toast.LENGTH_LONG).show()
                    finish()
                    return
                }
                emulatorGameLease = lease
                val storage = com.temulator.app.storage.GameStorageManager(this)
                val saveDir = storage.getSaveDir(gameId).absolutePath
                val stateDir = storage.getSaveStateDir(gameId).absolutePath
                EmulatorNative.setFolderOverrides(saveDir, stateDir)
            }
            val loaded = EmulatorNative.loadRom(romPath, consoleType)
            if (!loaded) {
                emulatorGameLease?.close()
                emulatorGameLease = null
                Toast.makeText(this, R.string.error_load_rom, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            // Apply all saved preferences to native core
            appSettings.applyToNative()

            val detectedConsole = EmulatorNative.getConsoleType()
            if (detectedConsole in 0..6) {
                consoleType = detectedConsole
            }
        } catch (e: UnsatisfiedLinkError) {
            emulatorGameLease?.close()
            emulatorGameLease = null
            e.printStackTrace()
            Toast.makeText(this, R.string.error_init_core, Toast.LENGTH_LONG).show()
            finish()
            return
        } catch (e: Exception) {
            emulatorGameLease?.close()
            emulatorGameLease = null
            e.printStackTrace()
            Toast.makeText(this, R.string.error_load_rom, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setContentView(R.layout.activity_emulator)
        val isLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        bindViews(isLand)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val requestedRom = intent.getStringExtra("ROM_PATH")?.takeIf { it.isNotBlank() }
            ?: return
        val sameSession = requestedRom == romPath &&
                intent.getStringExtra("GAME_ID") == this.intent.getStringExtra("GAME_ID")
        if (sameSession) return

        // One process-wide JNI core must have one Activity owner. Recreate only
        // after stopping this session; the old onDestroy flushes and releases
        // its lease before the replacement onCreate loads the new ROM.
        stopEmulationLoop()
        releasePressedInputs()
        try {
            EmulatorNative.saveBattery()
            EmulatorNative.pauseEmulation()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        setIntent(intent)
        recreate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        setContentView(R.layout.activity_emulator)
        val isLand = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        bindViews(isLand)
    }

    private fun bindViews(isLandscape: Boolean) {
        gameScreen = findViewById(R.id.gameScreen)
        gamepadArea = findViewById(R.id.gamepadArea)

        if (::gameBitmap.isInitialized) {
            gameScreen.setImageBitmap(gameBitmap)
        }
        applyScaleMode(appSettings.scaleMode)

        // Visual controls opacity & background
        if (isLandscape) {
            // Semi-transparent overlay in landscape mode so buttons don't block game content
            gamepadArea.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            val baseOpacity = appSettings.gamepadOpacity / 100f
            gamepadArea.alpha = baseOpacity.coerceIn(0.35f, 0.60f)
        } else {
            gamepadArea.setBackgroundColor(0xFF12151E.toInt())
            gamepadArea.alpha = appSettings.gamepadOpacity / 100f
        }

        val dpadView = findViewById<View>(R.id.dpad)
        val btnMenu = findViewById<View>(R.id.btnMenu)
        val btnSettings = findViewById<View>(R.id.btnSettings)
        val btnTurbo = findViewById<TextView>(R.id.btnTurbo)
        val btnRewind = findViewById<TextView>(R.id.btnRewind)
        val btnScreenshot = findViewById<View>(R.id.btnScreenshot)
        val btnPip = findViewById<View?>(R.id.btnPip)

        if (!isPipSupported()) {
            btnPip?.visibility = View.GONE
        } else {
            btnPip?.visibility = View.VISIBLE
            btnPip?.setOnClickListener {
                enterPictureInPictureModeSafely()
            }
        }

        fun updateTurboButtonUi() {
            if (appSettings.turboEnabled) {
                btnTurbo?.setTextColor(0xFF00E676.toInt())
                btnTurbo?.text = getString(R.string.btn_turbo_on)
            } else {
                btnTurbo?.setTextColor(0xFFFFCA28.toInt())
                btnTurbo?.text = getString(R.string.btn_turbo_off)
            }
        }
        updateTurboButtonUi()

        btnSettings?.setOnClickListener {
            showSettingsDialog()
        }

        btnTurbo?.setOnClickListener {
            appSettings.turboEnabled = !appSettings.turboEnabled
            EmulatorNative.setTurbo(appSettings.turboEnabled)
            updateTurboButtonUi()
        }

        btnRewind?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    btnRewind.setTextColor(0xFF00E676.toInt())
                    EmulatorNative.startRewind()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    btnRewind.setTextColor(0xFFEF5350.toInt())
                    EmulatorNative.stopRewind()
                    true
                }
                else -> false
            }
        }

        btnScreenshot?.setOnClickListener {
            captureScreenshot()
        }

        btnMenu?.setOnClickListener {
            showEmulatorMenu()
        }

        configureControlsForConsole(consoleType)
        setupTouchHandler(dpadView)
        applyWindowInsetsHandling(isLandscape)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            gamepadArea.visibility = View.GONE
            findViewById<View?>(R.id.topBarLeft)?.visibility = View.GONE
            findViewById<View?>(R.id.topBarRight)?.visibility = View.GONE
        }
    }

    private fun applyWindowInsetsHandling(isLandscape: Boolean) {
        val root = findViewById<View>(R.id.emulatorRoot) ?: return
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val topBarLeft = findViewById<View?>(R.id.topBarLeft)
            val topBarRight = findViewById<View?>(R.id.topBarRight)

            if (!isLandscape) {
                // In portrait, push floating top bars below notch / status bar
                val topSafePad = systemInsets.top.coerceAtLeast(dpToPx(8))
                topBarLeft?.translationY = topSafePad.toFloat()
                topBarRight?.translationY = topSafePad.toFloat()

                // Reset horizontal translations and landscape shifts
                findViewById<View?>(R.id.btnL)?.translationX = 0f
                findViewById<View?>(R.id.btnR)?.translationX = 0f
                findViewById<View?>(R.id.dpad)?.translationX = 0f
                findViewById<View?>(R.id.actionHub)?.translationX = 0f
                findViewById<View?>(R.id.btnSelect)?.translationY = 0f
                findViewById<View?>(R.id.btnMenu)?.translationY = 0f
                findViewById<View?>(R.id.btnStart)?.translationY = 0f
                findViewById<View?>(R.id.dpad)?.translationY = 0f
                findViewById<View?>(R.id.actionHub)?.translationY = 0f

                // Avoid 3-button navigation bar overlapping lower gamepad controls in portrait
                gamepadArea.setPadding(systemInsets.left, 0, systemInsets.right, systemInsets.bottom)
            } else {
                // In landscape, overlay spans full screen without root padding
                gamepadArea.setPadding(0, 0, 0, 0)

                // Adjust top bars below notch / status bar
                val topSafePad = systemInsets.top.coerceAtLeast(dpToPx(4))
                topBarLeft?.translationY = topSafePad.toFloat()
                topBarRight?.translationY = topSafePad.toFloat()

                val dpad = findViewById<View?>(R.id.dpad)
                val actionHub = findViewById<View?>(R.id.actionHub)

                val bottomPad = systemInsets.bottom.coerceAtLeast(0)
                val shiftY = -bottomPad.toFloat()
                findViewById<View?>(R.id.btnSelect)?.translationY = shiftY
                findViewById<View?>(R.id.btnMenu)?.translationY = shiftY
                findViewById<View?>(R.id.btnStart)?.translationY = shiftY
                dpad?.translationY = shiftY
                actionHub?.translationY = shiftY

                val leftPad = systemInsets.left.coerceAtLeast(0).toFloat()
                val rightPad = systemInsets.right.coerceAtLeast(0).toFloat()

                findViewById<View?>(R.id.btnL)?.translationX = leftPad
                dpad?.translationX = leftPad
                findViewById<View?>(R.id.btnR)?.translationX = -rightPad
                actionHub?.translationX = -rightPad
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun configureControlsForConsole(console: Int) {
        val btnL = findViewById<View>(R.id.btnL)
        val btnR = findViewById<View>(R.id.btnR)
        val btnX = findViewById<View>(R.id.btnX)
        val btnY = findViewById<View>(R.id.btnY)
        val btnA = findViewById<View>(R.id.btnA)
        val btnB = findViewById<View>(R.id.btnB)
        val btnNesB = findViewById<TextView>(R.id.btnNesB)
        val btnNesA = findViewById<TextView>(R.id.btnNesA)
        val btnNesTb = findViewById<TextView>(R.id.btnNesTb)
        val btnNesTa = findViewById<TextView>(R.id.btnNesTa)
        val tvSelect = findViewById<TextView>(R.id.btnSelect)
        val tvStart = findViewById<TextView>(R.id.btnStart)

        buttonViews.clear()
        if (tvSelect != null) buttonViews[BTN_SELECT] = tvSelect
        if (tvStart != null) buttonViews[BTN_START] = tvStart

        when (console) {
            2, 1 -> {
                // NES (2) or Game Boy / GBC (1): clean 2-button layout + Turbo B/A, hide L/R and SNES diamond
                btnL?.visibility = View.GONE
                btnR?.visibility = View.GONE
                btnX?.visibility = View.GONE
                btnY?.visibility = View.GONE
                btnA?.visibility = View.GONE
                btnB?.visibility = View.GONE

                btnNesB?.visibility = View.VISIBLE
                btnNesA?.visibility = View.VISIBLE
                btnNesTb?.visibility = View.VISIBLE
                btnNesTa?.visibility = View.VISIBLE

                btnNesB?.text = "B"
                btnNesA?.text = "A"
                btnNesTb?.text = "TB"
                btnNesTa?.text = "TA"

                tvSelect?.text = "SELECT"
                tvStart?.text = "START"

                buttonViews[BTN_B] = btnNesB
                buttonViews[BTN_A] = btnNesA
                buttonViews[BTN_TURBO_B] = btnNesTb
                buttonViews[BTN_TURBO_A] = btnNesTa
            }
            5 -> {
                // GBA (5): L & R shoulder buttons, plus B, A, and Turbo B/A
                btnL?.visibility = View.VISIBLE
                btnR?.visibility = View.VISIBLE
                btnX?.visibility = View.GONE
                btnY?.visibility = View.GONE
                btnA?.visibility = View.GONE
                btnB?.visibility = View.GONE

                btnNesB?.visibility = View.VISIBLE
                btnNesA?.visibility = View.VISIBLE
                btnNesTb?.visibility = View.VISIBLE
                btnNesTa?.visibility = View.VISIBLE

                btnNesB?.text = "B"
                btnNesA?.text = "A"
                btnNesTb?.text = "TB"
                btnNesTa?.text = "TA"

                tvSelect?.text = "SELECT"
                tvStart?.text = "START"

                buttonViews[BTN_L] = btnL
                buttonViews[BTN_R] = btnR
                buttonViews[BTN_B] = btnNesB
                buttonViews[BTN_A] = btnNesA
                buttonViews[BTN_TURBO_B] = btnNesTb
                buttonViews[BTN_TURBO_A] = btnNesTa
            }
            3 -> {
                // PC Engine / TurboGrafx-16 (3): II, I, and Turbo II/I with RUN & SELECT
                btnL?.visibility = View.GONE
                btnR?.visibility = View.GONE
                btnX?.visibility = View.GONE
                btnY?.visibility = View.GONE
                btnA?.visibility = View.GONE
                btnB?.visibility = View.GONE

                btnNesB?.visibility = View.VISIBLE
                btnNesA?.visibility = View.VISIBLE
                btnNesTb?.visibility = View.VISIBLE
                btnNesTa?.visibility = View.VISIBLE

                btnNesB?.text = "II"
                btnNesA?.text = "I"
                btnNesTb?.text = "T-II"
                btnNesTa?.text = "T-I"

                tvSelect?.text = "SELECT"
                tvStart?.text = "RUN"

                buttonViews[BTN_B] = btnNesB
                buttonViews[BTN_A] = btnNesA
                buttonViews[BTN_TURBO_B] = btnNesTb
                buttonViews[BTN_TURBO_A] = btnNesTa
            }
            4 -> {
                // Sega Master System / Game Gear (4): 1 and 2, plus Turbo 1/2 and PAUSE
                btnL?.visibility = View.GONE
                btnR?.visibility = View.GONE
                btnX?.visibility = View.GONE
                btnY?.visibility = View.GONE
                btnA?.visibility = View.GONE
                btnB?.visibility = View.GONE

                btnNesB?.visibility = View.VISIBLE
                btnNesA?.visibility = View.VISIBLE
                btnNesTb?.visibility = View.VISIBLE
                btnNesTa?.visibility = View.VISIBLE

                btnNesB?.text = "1"
                btnNesA?.text = "2"
                btnNesTb?.text = "T1"
                btnNesTa?.text = "T2"

                tvSelect?.text = "RESET"
                tvStart?.text = "PAUSE"

                buttonViews[BTN_B] = btnNesB
                buttonViews[BTN_A] = btnNesA
                buttonViews[BTN_TURBO_B] = btnNesTb
                buttonViews[BTN_TURBO_A] = btnNesTa
            }
            6 -> {
                // WonderSwan (6): B, A, TB, TA, SOUND, START
                btnL?.visibility = View.GONE
                btnR?.visibility = View.GONE
                btnX?.visibility = View.GONE
                btnY?.visibility = View.GONE
                btnA?.visibility = View.GONE
                btnB?.visibility = View.GONE

                btnNesB?.visibility = View.VISIBLE
                btnNesA?.visibility = View.VISIBLE
                btnNesTb?.visibility = View.VISIBLE
                btnNesTa?.visibility = View.VISIBLE

                btnNesB?.text = "B"
                btnNesA?.text = "A"
                btnNesTb?.text = "TB"
                btnNesTa?.text = "TA"

                tvSelect?.text = "SOUND"
                tvStart?.text = "START"

                buttonViews[BTN_B] = btnNesB
                buttonViews[BTN_A] = btnNesA
                buttonViews[BTN_TURBO_B] = btnNesTb
                buttonViews[BTN_TURBO_A] = btnNesTa
            }
            else -> {
                // SNES (0) and default: L, R, and 4-button diamond (X, Y, B, A)
                btnL?.visibility = View.VISIBLE
                btnR?.visibility = View.VISIBLE
                btnX?.visibility = View.VISIBLE
                btnY?.visibility = View.VISIBLE
                btnA?.visibility = View.VISIBLE
                btnB?.visibility = View.VISIBLE

                btnNesB?.visibility = View.GONE
                btnNesA?.visibility = View.GONE
                btnNesTb?.visibility = View.GONE
                btnNesTa?.visibility = View.GONE

                tvSelect?.text = "SELECT"
                tvStart?.text = "START"

                buttonViews[BTN_L] = btnL
                buttonViews[BTN_R] = btnR
                buttonViews[BTN_X] = btnX
                buttonViews[BTN_Y] = btnY
                buttonViews[BTN_A] = btnA
                buttonViews[BTN_B] = btnB
            }
        }
    }

    private fun applyScaleMode(mode: Int) {
        gameScreen.scaleType = when (mode) {
            1 -> ImageView.ScaleType.FIT_XY
            2 -> ImageView.ScaleType.CENTER
            else -> ImageView.ScaleType.FIT_CENTER
        }
    }

    private fun captureScreenshot() {
        try {
            val dir = File(getExternalFilesDir(null), "Screenshots")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "screenshot_${System.currentTimeMillis()}.png")
            val bmpCopy = synchronized(gameBitmap) {
                gameBitmap.copy(Bitmap.Config.ARGB_8888, false)
            }
            if (bmpCopy != null) {
                FileOutputStream(file).use { fos ->
                    bmpCopy.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    fos.flush()
                }
                Toast.makeText(this, getString(R.string.msg_screenshot_saved) + ": " + file.name, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.error_saving_screenshot, Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, R.string.error_saving_screenshot, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun showSettingsDialog() {
        isEmulationPaused = true
        updatePipParamsIfSupported()

        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        val dialog = AlertDialog.Builder(this, R.style.CustomSettingsDialogTheme)
            .setView(dialogView)
            .create()
        currentSettingsDialog = dialog

        // Tabs
        val tabSaveLoad = dialogView.findViewById<Button>(R.id.tabSaveLoad)
        val tabVideo = dialogView.findViewById<Button>(R.id.tabVideo)
        val tabSpeed = dialogView.findViewById<Button>(R.id.tabSpeed)
        val tabAudio = dialogView.findViewById<Button>(R.id.tabAudio)
        val tabSystem = dialogView.findViewById<Button>(R.id.tabSystem)
        val tabCheats = dialogView.findViewById<Button>(R.id.tabCheats)

        val panelSaveLoad = dialogView.findViewById<View>(R.id.panelSaveLoad)
        val panelVideo = dialogView.findViewById<View>(R.id.panelVideo)
        val panelSpeed = dialogView.findViewById<View>(R.id.panelSpeed)
        val panelAudio = dialogView.findViewById<View>(R.id.panelAudio)
        val panelSystem = dialogView.findViewById<View>(R.id.panelSystem)
        val panelCheats = dialogView.findViewById<View>(R.id.panelCheats)

        val tabButtons = listOf(tabSaveLoad, tabVideo, tabSpeed, tabAudio, tabSystem, tabCheats)
        val panels = listOf(panelSaveLoad, panelVideo, panelSpeed, panelAudio, panelSystem, panelCheats)

        fun selectTab(index: Int) {
            for (i in panels.indices) {
                panels[i].visibility = if (i == index) View.VISIBLE else View.GONE
                tabButtons[i].setTextColor(if (i == index) 0xFF90CAF9.toInt() else 0xFFCFD8DC.toInt())
            }
        }

        tabSaveLoad.setOnClickListener { selectTab(0) }
        tabVideo.setOnClickListener { selectTab(1) }
        tabSpeed.setOnClickListener { selectTab(2) }
        tabAudio.setOnClickListener { selectTab(3) }
        tabSystem.setOnClickListener { selectTab(4) }
        tabCheats.setOnClickListener { selectTab(5) }

        // --- TAB 1: SAVE & LOAD ---
        val slotContainer = dialogView.findViewById<LinearLayout>(R.id.slotContainer)
        val btnSaveState = dialogView.findViewById<Button>(R.id.btnSaveState)
        val btnLoadState = dialogView.findViewById<Button>(R.id.btnLoadState)
        val btnQuickSave = dialogView.findViewById<Button>(R.id.btnQuickSave)
        val btnQuickLoad = dialogView.findViewById<Button>(R.id.btnQuickLoad)
        val btnSoftReset = dialogView.findViewById<Button>(R.id.btnSoftReset)
        val btnPowerCycle = dialogView.findViewById<Button>(R.id.btnPowerCycle)

        fun refreshSlotButtons() {
            slotContainer.removeAllViews()
            for (slot in 1..10) {
                val hasSave = EmulatorNative.hasSaveState(slot)
                val isSelected = (slot == appSettings.currentSlot)
                val btn = Button(this).apply {
                    text = if (hasSave) "● $slot" else "$slot"
                    textSize = 12f
                    setTextColor(if (isSelected) 0xFF90CAF9.toInt() else if (hasSave) 0xFFA5D6A7.toInt() else 0xFFB0BEC5.toInt())
                    setBackgroundResource(if (isSelected) R.drawable.btn_settings_bg else R.drawable.pill_button)
                    setOnClickListener {
                        appSettings.currentSlot = slot
                        refreshSlotButtons()
                    }
                }
                val lp = LinearLayout.LayoutParams(dpToPx(52), dpToPx(38)).apply {
                    setMargins(dpToPx(3), 0, dpToPx(3), 0)
                }
                slotContainer.addView(btn, lp)
            }
        }
        refreshSlotButtons()

        btnSaveState.setOnClickListener {
            val slot = appSettings.currentSlot
            val ok = EmulatorNative.saveState(slot)
            if (ok) {
                Toast.makeText(this, getString(R.string.state_saved_success, slot), Toast.LENGTH_SHORT).show()
                refreshSlotButtons()
            } else {
                Toast.makeText(this, R.string.state_saved_failed, Toast.LENGTH_SHORT).show()
            }
        }

        btnLoadState.setOnClickListener {
            val slot = appSettings.currentSlot
            val ok = EmulatorNative.loadState(slot)
            if (ok) {
                Toast.makeText(this, getString(R.string.state_loaded_success, slot), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(this, getString(R.string.state_loaded_failed, slot), Toast.LENGTH_SHORT).show()
            }
        }

        btnQuickSave.setOnClickListener {
            val ok = EmulatorNative.saveState(1)
            if (ok) {
                Toast.makeText(this, getString(R.string.state_saved_success, 1), Toast.LENGTH_SHORT).show()
                refreshSlotButtons()
            } else {
                Toast.makeText(this, R.string.state_saved_failed, Toast.LENGTH_SHORT).show()
            }
        }

        btnQuickLoad.setOnClickListener {
            val ok = EmulatorNative.loadState(1)
            if (ok) {
                Toast.makeText(this, getString(R.string.state_loaded_success, 1), Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            } else {
                Toast.makeText(this, getString(R.string.state_loaded_failed, 1), Toast.LENGTH_SHORT).show()
            }
        }

        btnSoftReset.setOnClickListener {
            EmulatorNative.resetGame()
            Toast.makeText(this, R.string.msg_reset_done, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnPowerCycle.setOnClickListener {
            EmulatorNative.powerCycleGame()
            Toast.makeText(this, R.string.msg_reset_done, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // --- TAB 2: DISPLAY & SCALE ---
        val spinnerAspectRatio = dialogView.findViewById<Spinner>(R.id.spinnerAspectRatio)
        val spinnerScaleMode = dialogView.findViewById<Spinner>(R.id.spinnerScaleMode)
        val spinnerVideoFilter = dialogView.findViewById<Spinner>(R.id.spinnerVideoFilter)
        val switchBilinear = dialogView.findViewById<SwitchMaterial>(R.id.switchBilinear)
        val spinnerScanlines = dialogView.findViewById<Spinner>(R.id.spinnerScanlines)
        val spinnerRotation = dialogView.findViewById<Spinner>(R.id.spinnerRotation)
        val spinnerGbPalette = dialogView.findViewById<Spinner>(R.id.spinnerGbPalette)

        // Aspect Ratio
        val aspectItems = arrayOf(
            getString(R.string.aspect_auto),
            getString(R.string.aspect_4_3),
            getString(R.string.aspect_16_9),
            getString(R.string.aspect_1_1)
        )
        val aspectValues = intArrayOf(1, 4, 5, 0)
        val aspectAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, aspectItems)
        spinnerAspectRatio.adapter = aspectAdapter
        val currAspectIdx = aspectValues.indexOf(appSettings.aspectRatio).coerceAtLeast(0)
        spinnerAspectRatio.setSelection(currAspectIdx)
        spinnerAspectRatio.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = aspectValues[position]
                if (appSettings.aspectRatio != value) {
                    appSettings.aspectRatio = value
                    EmulatorNative.setAspectRatio(value, 0.0)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Scale Mode
        val scaleItems = arrayOf(
            getString(R.string.scale_fit),
            getString(R.string.scale_stretch),
            getString(R.string.scale_center)
        )
        val scaleAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, scaleItems)
        spinnerScaleMode.adapter = scaleAdapter
        spinnerScaleMode.setSelection(appSettings.scaleMode.coerceIn(0, 2))
        spinnerScaleMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (appSettings.scaleMode != position) {
                    appSettings.scaleMode = position
                    applyScaleMode(position)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Video Filter
        val filterItems = arrayOf(
            getString(R.string.filter_none),
            getString(R.string.filter_xbrz_2x),
            getString(R.string.filter_xbrz_4x),
            getString(R.string.filter_hq2x),
            getString(R.string.filter_scale2x),
            getString(R.string.filter_prescale_2x),
            getString(R.string.filter_prescale_4x),
            getString(R.string.filter_ntsc),
            getString(R.string.filter_lcd)
        )
        val filterValues = intArrayOf(0, 4, 6, 10, 13, 19, 21, 1, 3)
        val filterAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, filterItems)
        spinnerVideoFilter.adapter = filterAdapter
        val currFilterIdx = filterValues.indexOf(appSettings.videoFilter).coerceAtLeast(0)
        spinnerVideoFilter.setSelection(currFilterIdx)
        spinnerVideoFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = filterValues[position]
                if (appSettings.videoFilter != value) {
                    appSettings.videoFilter = value
                    EmulatorNative.setVideoFilter(value)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Bilinear Switch
        switchBilinear.isChecked = appSettings.bilinearFiltering
        switchBilinear.setOnCheckedChangeListener { _, isChecked ->
            appSettings.bilinearFiltering = isChecked
            EmulatorNative.setBilinearInterpolation(isChecked)
        }

        // Scanlines
        val scanlineItems = arrayOf("0% (Off)", "25%", "50%", "75%")
        val scanlineValues = intArrayOf(0, 25, 50, 75)
        val scanlineAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, scanlineItems)
        spinnerScanlines.adapter = scanlineAdapter
        val currScanlineIdx = scanlineValues.indexOf(appSettings.scanlineIntensity).coerceAtLeast(0)
        spinnerScanlines.setSelection(currScanlineIdx)
        spinnerScanlines.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = scanlineValues[position]
                if (appSettings.scanlineIntensity != value) {
                    appSettings.scanlineIntensity = value
                    EmulatorNative.setScanlineIntensity(value)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Rotation
        val rotItems = arrayOf(getString(R.string.rot_0), getString(R.string.rot_90), getString(R.string.rot_180), getString(R.string.rot_270))
        val rotValues = intArrayOf(0, 90, 180, 270)
        val rotAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, rotItems)
        spinnerRotation.adapter = rotAdapter
        val currRotIdx = rotValues.indexOf(appSettings.screenRotation).coerceAtLeast(0)
        spinnerRotation.setSelection(currRotIdx)
        spinnerRotation.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = rotValues[position]
                if (appSettings.screenRotation != value) {
                    appSettings.screenRotation = value
                    EmulatorNative.setScreenRotation(value)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Game Boy Palette
        val gbPalItems = arrayOf(
            getString(R.string.gb_pal_default),
            getString(R.string.gb_pal_dmg),
            getString(R.string.gb_pal_pocket),
            getString(R.string.gb_pal_light)
        )
        val gbPalAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, gbPalItems)
        spinnerGbPalette.adapter = gbPalAdapter
        spinnerGbPalette.setSelection(appSettings.gameboyPalette.coerceIn(0, 3))
        spinnerGbPalette.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (appSettings.gameboyPalette != position) {
                    appSettings.gameboyPalette = position
                    EmulatorNative.setGameboyPalette(position)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // --- TAB 3: SPEED & EMULATION ---
        val spinnerSpeed = dialogView.findViewById<Spinner>(R.id.spinnerSpeed)
        val switchTurbo = dialogView.findViewById<SwitchMaterial>(R.id.switchTurbo)
        val spinnerRegion = dialogView.findViewById<Spinner>(R.id.spinnerRegion)

        val speedItems = arrayOf("50%", "100% (Normal)", "150%", "200%", "300%")
        val speedValues = intArrayOf(50, 100, 150, 200, 300)
        val speedAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, speedItems)
        spinnerSpeed.adapter = speedAdapter
        val currSpeedIdx = speedValues.indexOf(appSettings.emulationSpeed).coerceAtLeast(1)
        spinnerSpeed.setSelection(currSpeedIdx)
        spinnerSpeed.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val value = speedValues[position]
                if (appSettings.emulationSpeed != value) {
                    appSettings.emulationSpeed = value
                    EmulatorNative.setEmulationSpeed(value)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        switchTurbo.isChecked = appSettings.turboEnabled
        switchTurbo.setOnCheckedChangeListener { _, isChecked ->
            appSettings.turboEnabled = isChecked
            EmulatorNative.setTurbo(isChecked)
        }

        val regionItems = arrayOf(getString(R.string.region_auto), getString(R.string.region_ntsc), getString(R.string.region_pal))
        val regionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, regionItems)
        spinnerRegion.adapter = regionAdapter
        spinnerRegion.setSelection(appSettings.region.coerceIn(0, 2))
        spinnerRegion.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (appSettings.region != position) {
                    appSettings.region = position
                    EmulatorNative.setRegion(position)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // --- TAB 4: AUDIO ---
        val switchAudio = dialogView.findViewById<SwitchMaterial>(R.id.switchAudio)
        val tvVolumeLabel = dialogView.findViewById<TextView>(R.id.tvVolumeLabel)
        val sliderVolume = dialogView.findViewById<Slider>(R.id.sliderVolume)

        switchAudio.isChecked = appSettings.audioEnabled
        switchAudio.setOnCheckedChangeListener { _, isChecked ->
            appSettings.audioEnabled = isChecked
            EmulatorNative.setAudioConfig(isChecked, appSettings.audioVolume, 48000, false)
        }

        tvVolumeLabel.text = getString(R.string.lbl_volume, appSettings.audioVolume)
        sliderVolume.value = appSettings.audioVolume.toFloat()
        sliderVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val vol = value.toInt()
                tvVolumeLabel.text = getString(R.string.lbl_volume, vol)
                appSettings.audioVolume = vol
                EmulatorNative.setAudioConfig(appSettings.audioEnabled, vol, 48000, false)
            }
        }

        // --- TAB 5: SYSTEM & CONTROLS ---
        val switchHaptic = dialogView.findViewById<SwitchMaterial>(R.id.switchHaptic)
        val tvOpacityLabel = dialogView.findViewById<TextView>(R.id.tvOpacityLabel)
        val sliderOpacity = dialogView.findViewById<Slider>(R.id.sliderOpacity)
        val switchRemoveSpriteLimit = dialogView.findViewById<SwitchMaterial>(R.id.switchRemoveSpriteLimit)
        val switchBlendFrames = dialogView.findViewById<SwitchMaterial>(R.id.switchBlendFrames)
        val switchGbaColor = dialogView.findViewById<SwitchMaterial>(R.id.switchGbaColor)

        switchHaptic.isChecked = appSettings.hapticFeedback
        switchHaptic.setOnCheckedChangeListener { _, isChecked ->
            appSettings.hapticFeedback = isChecked
        }

        tvOpacityLabel.text = getString(R.string.lbl_opacity, appSettings.gamepadOpacity)
        sliderOpacity.value = appSettings.gamepadOpacity.toFloat()
        sliderOpacity.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val opacity = value.toInt()
                tvOpacityLabel.text = getString(R.string.lbl_opacity, opacity)
                appSettings.gamepadOpacity = opacity
                val isLand = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                gamepadArea.alpha = if (isLand) (opacity / 100f).coerceIn(0.35f, 0.60f) else (opacity / 100f)
            }
        }

        switchRemoveSpriteLimit.isChecked = appSettings.removeSpriteLimit
        switchRemoveSpriteLimit.setOnCheckedChangeListener { _, isChecked ->
            appSettings.removeSpriteLimit = isChecked
            appSettings.applyToNative()
        }

        switchBlendFrames.isChecked = appSettings.blendFrames
        switchBlendFrames.setOnCheckedChangeListener { _, isChecked ->
            appSettings.blendFrames = isChecked
            appSettings.applyToNative()
        }

        switchGbaColor.isChecked = appSettings.gbaColorCorrection
        switchGbaColor.setOnCheckedChangeListener { _, isChecked ->
            appSettings.gbaColorCorrection = isChecked
            appSettings.applyToNative()
        }

        // --- TAB 6: CHEATS ---
        val etCheatCode = dialogView.findViewById<EditText>(R.id.etCheatCode)
        val btnAddCheat = dialogView.findViewById<Button>(R.id.btnAddCheat)
        val btnClearCheats = dialogView.findViewById<Button>(R.id.btnClearCheats)
        val tvCheatsCount = dialogView.findViewById<TextView>(R.id.tvCheatsCount)
        val cheatsContainer = dialogView.findViewById<LinearLayout>(R.id.cheatsContainer)

        fun refreshCheatsUi() {
            cheatsContainer.removeAllViews()
            val cheats = appSettings.cheatsList
            tvCheatsCount.text = "${cheats.size} active cheats"
            if (cheats.isEmpty()) {
                val tvEmpty = TextView(this).apply {
                    text = getString(R.string.no_cheats_yet)
                    setTextColor(0xFF78909C.toInt())
                    textSize = 12f
                    setPadding(8, 8, 8, 8)
                }
                cheatsContainer.addView(tvEmpty)
            } else {
                for (entry in cheats) {
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        setPadding(0, 4, 0, 4)
                    }
                    val tvCode = TextView(this).apply {
                        text = "• " + entry.substringAfter(":")
                        setTextColor(0xFFECEFF1.toInt())
                        textSize = 13f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val btnDel = TextView(this).apply {
                        text = "✕"
                        setTextColor(0xFFEF5350.toInt())
                        textSize = 14f
                        setPadding(16, 0, 16, 0)
                        setOnClickListener {
                            appSettings.removeCheat(entry)
                            refreshCheatsUi()
                        }
                    }
                    row.addView(tvCode)
                    row.addView(btnDel)
                    cheatsContainer.addView(row)
                }
            }
        }
        refreshCheatsUi()

        btnAddCheat.setOnClickListener {
            val code = etCheatCode.text.toString().trim()
            if (code.isNotEmpty()) {
                // Determine cheat type based on console
                val cheatType = when (consoleType) {
                    0 -> 5 // SnesGameGenie
                    1 -> 3 // GbGameGenie
                    2 -> 0 // NesGameGenie
                    5 -> 4 // GbGameShark (for GBA/GB)
                    else -> 0
                }
                val ok = appSettings.addCheat(cheatType, code)
                if (ok) {
                    Toast.makeText(this, R.string.cheat_added_success, Toast.LENGTH_SHORT).show()
                    etCheatCode.setText("")
                    refreshCheatsUi()
                } else {
                    Toast.makeText(this, R.string.cheat_added_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnClearCheats.setOnClickListener {
            appSettings.clearCheats()
            refreshCheatsUi()
        }

        // Close & Resume
        dialogView.findViewById<View>(R.id.btnCloseSettings).setOnClickListener { dialog.dismiss() }
        dialogView.findViewById<View>(R.id.btnCloseSettingsIcon).setOnClickListener { dialog.dismiss() }

        dialog.setOnDismissListener {
            currentSettingsDialog = null
            isEmulationPaused = false
            updatePipParamsIfSupported()
        }

        dialog.show()
    }

    private fun showEmulatorMenu() {
        isEmulationPaused = true
        updatePipParamsIfSupported()

        val pipSupported = isPipSupported()
        val menuItems = if (pipSupported) {
            arrayOf(
                getString(R.string.menu_resume),
                "⧉ " + getString(R.string.menu_pip),
                "⚙ " + getString(R.string.btn_settings),
                "📸 " + getString(R.string.btn_screenshot),
                getString(R.string.menu_restart),
                getString(R.string.menu_exit)
            )
        } else {
            arrayOf(
                getString(R.string.menu_resume),
                "⚙ " + getString(R.string.btn_settings),
                "📸 " + getString(R.string.btn_screenshot),
                getString(R.string.menu_restart),
                getString(R.string.menu_exit)
            )
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.menu_title)
            .setItems(menuItems) { dialog, which ->
                val action = if (pipSupported) {
                    when (which) {
                        0 -> "resume"
                        1 -> "pip"
                        2 -> "settings"
                        3 -> "screenshot"
                        4 -> "restart"
                        5 -> "exit"
                        else -> "resume"
                    }
                } else {
                    when (which) {
                        0 -> "resume"
                        1 -> "settings"
                        2 -> "screenshot"
                        3 -> "restart"
                        4 -> "exit"
                        else -> "resume"
                    }
                }

                when (action) {
                    "resume" -> {
                        isEmulationPaused = false
                        updatePipParamsIfSupported()
                        dialog.dismiss()
                    }
                    "pip" -> {
                        dialog.dismiss()
                        enterPictureInPictureModeSafely()
                    }
                    "settings" -> {
                        dialog.dismiss()
                        showSettingsDialog()
                    }
                    "screenshot" -> {
                        dialog.dismiss()
                        captureScreenshot()
                        isEmulationPaused = false
                        updatePipParamsIfSupported()
                    }
                    "restart" -> {
                        try {
                            EmulatorNative.loadRom(romPath, consoleType)
                            appSettings.applyToNative()
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        isEmulationPaused = false
                        updatePipParamsIfSupported()
                        dialog.dismiss()
                    }
                    "exit" -> {
                        dialog.dismiss()
                        showExitConfirmDialog()
                    }
                }
            }
            .setOnCancelListener {
                isEmulationPaused = false
                updatePipParamsIfSupported()
            }
            .create()

        currentMenuDialog = dialog
        dialog.setOnDismissListener {
            currentMenuDialog = null
            isEmulationPaused = false
            updatePipParamsIfSupported()
        }
        dialog.show()
    }

    private fun showExitConfirmDialog() {
        isEmulationPaused = true
        updatePipParamsIfSupported()

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.dialog_exit_title)
            .setMessage(R.string.dialog_exit_message)
            .setPositiveButton(R.string.action_exit) { _, _ ->
                finish()
            }
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                isEmulationPaused = false
                updatePipParamsIfSupported()
            }
            .setOnCancelListener {
                isEmulationPaused = false
                updatePipParamsIfSupported()
            }
            .create()

        currentExitDialog = dialog
        dialog.setOnDismissListener {
            currentExitDialog = null
            isEmulationPaused = false
            updatePipParamsIfSupported()
        }
        dialog.show()
    }

    private fun setupTouchHandler(dpadView: View) {
        dpadViewRef = dpadView

        gamepadArea.setOnTouchListener { _, event ->
            val actionIndex = event.actionIndex
            val actionId = event.getPointerId(actionIndex)
            val action = event.actionMasked

            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    updatePointer(actionId, event, dpadView)
                    syncButtonStates()
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val pid = event.getPointerId(i)
                        updatePointer(pid, event, dpadView)
                    }
                    syncButtonStates()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    releasePointer(actionId)
                    syncButtonStates()
                }
                MotionEvent.ACTION_CANCEL -> {
                    dpadPointerId = null
                    activePointerButtons.clear()
                    syncButtonStates()
                }
            }
            true
        }
    }

    private fun updatePointer(pointerId: Int, event: MotionEvent, dpadView: View) {
        val pointerIndex = event.findPointerIndex(pointerId)
        if (pointerIndex < 0) return
        val x = event.getX(pointerIndex).toInt()
        val y = event.getY(pointerIndex).toInt()

        val hitButtons = mutableSetOf<Int>()

        // 1. D-Pad tracking (continuous tracking while finger stays down)
        if (dpadView.visibility == View.VISIBLE) {
            val rect = Rect()
            dpadView.getHitRect(rect)
            val dpadTriggerRect = Rect(rect).apply {
                val pad = dpToPx(28)
                inset(-pad, -pad)
            }

            if (dpadPointerId == pointerId || (dpadPointerId == null && dpadTriggerRect.contains(x, y))) {
                dpadPointerId = pointerId
                hitButtons.addAll(getDPadButtons(rect, x, y))
            }
        }

        // 2. Action buttons (checked if pointer is not controlling D-Pad)
        if (pointerId != dpadPointerId) {
            val pad = dpToPx(12)
            for ((btnId, view) in buttonViews) {
                if (view.visibility == View.VISIBLE && isViewHit(view, x, y, pad)) {
                    hitButtons.add(btnId)
                }
            }
        }

        activePointerButtons[pointerId] = hitButtons
    }

    private fun releasePointer(pointerId: Int) {
        if (dpadPointerId == pointerId) {
            dpadPointerId = null
        }
        activePointerButtons.remove(pointerId)
    }

    private fun syncButtonStates() {
        val allPressed = activePointerButtons.values.flatten().toSet()
        val toRelease = currentlyPressedButtons - allPressed
        val toPress = allPressed - currentlyPressedButtons

        for (btnId in toRelease) {
            setButtonState(btnId, false)
        }
        for (btnId in toPress) {
            setButtonState(btnId, true)
        }
        currentlyPressedButtons.clear()
        currentlyPressedButtons.addAll(allPressed)
    }

    private fun isViewHit(view: View, x: Int, y: Int, padding: Int = 0): Boolean {
        val rect = Rect()
        view.getHitRect(rect)
        if (padding > 0) {
            rect.inset(-padding, -padding)
        }
        return rect.contains(x, y)
    }

    private fun getDPadButtons(rect: Rect, x: Int, y: Int): Set<Int> {
        val cx = rect.exactCenterX()
        val cy = rect.exactCenterY()

        val dx = x - cx
        val dy = y - cy
        val dist = Math.hypot(dx.toDouble(), dy.toDouble())
        val deadzone = rect.width() * 0.16
        if (dist < deadzone) return emptySet()

        val buttons = mutableSetOf<Int>()
        val angle = Math.toDegrees(Math.atan2(dy.toDouble(), dx.toDouble()))

        // 8 sectors of 45 degrees:
        if (angle in -67.5..67.5) buttons.add(BTN_RIGHT)
        if (angle in 22.5..157.5) buttons.add(BTN_DOWN)
        if (angle >= 112.5 || angle <= -112.5) buttons.add(BTN_LEFT)
        if (angle in -157.5..-22.5) buttons.add(BTN_UP)

        return buttons
    }

    private fun setButtonState(btnId: Int, pressed: Boolean) {
        try {
            EmulatorNative.setKeyState(btnId, pressed)
        } catch (e: Exception) {
            // Ignore native error
        }

        runOnUiThread {
            buttonViews[btnId]?.isPressed = pressed
            if (btnId in listOf(BTN_UP, BTN_DOWN, BTN_LEFT, BTN_RIGHT)) {
                val anyDpad = currentlyPressedButtons.any { it in listOf(BTN_UP, BTN_DOWN, BTN_LEFT, BTN_RIGHT) }
                dpadViewRef?.isPressed = anyDpad
            }
        }

        if (pressed && appSettings.hapticFeedback) {
            window.decorView.post {
                window.decorView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }

    // --- Physical Gamepad & Keyboard Support ---
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val pressed = (event.action == KeyEvent.ACTION_DOWN)
        val handled = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> {
                setButtonState(BTN_UP, pressed); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> {
                setButtonState(BTN_DOWN, pressed); true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> {
                setButtonState(BTN_LEFT, pressed); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> {
                setButtonState(BTN_RIGHT, pressed); true
            }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_K -> {
                setButtonState(BTN_A, pressed); true
            }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_J -> {
                setButtonState(BTN_B, pressed); true
            }
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_I -> {
                if (consoleType == 0) {
                    setButtonState(BTN_X, pressed)
                } else {
                    setButtonState(BTN_TURBO_A, pressed)
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_U -> {
                if (consoleType == 0) {
                    setButtonState(BTN_Y, pressed)
                } else {
                    setButtonState(BTN_TURBO_B, pressed)
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_L2 -> {
                setButtonState(BTN_L, pressed); true
            }
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_BUTTON_R2 -> {
                setButtonState(BTN_R, pressed); true
            }
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ENTER -> {
                setButtonState(BTN_START, pressed); true
            }
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_SPACE -> {
                setButtonState(BTN_SELECT, pressed); true
            }
            else -> false
        }
        return if (handled) true else super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK) {
            val x = event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y)
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)

            setButtonState(BTN_LEFT, x < -0.5f || hatX < -0.5f)
            setButtonState(BTN_RIGHT, x > 0.5f || hatX > 0.5f)
            setButtonState(BTN_UP, y < -0.5f || hatY < -0.5f)
            setButtonState(BTN_DOWN, y > 0.5f || hatY > 0.5f)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun isPipSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private fun enterPictureInPictureModeSafely(): Boolean {
        if (!isPipSupported()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                currentSettingsDialog?.dismiss()
                currentMenuDialog?.dismiss()
                currentExitDialog?.dismiss()
                releasePressedInputs()
                isEmulationPaused = false

                val params = buildPipParams()
                return enterPictureInPictureMode(params)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return false
    }

    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()

        val bmpWidth = if (::gameBitmap.isInitialized && gameBitmap.width > 0) gameBitmap.width else 4
        val bmpHeight = if (::gameBitmap.isInitialized && gameBitmap.height > 0) gameBitmap.height else 3

        val ratio = bmpWidth.toFloat() / bmpHeight.toFloat()
        val clampedRatio = ratio.coerceIn(1f / 2.39f, 2.39f)
        val rational = if (ratio in (1f / 2.39f)..2.39f) {
            Rational(bmpWidth, bmpHeight)
        } else {
            if (clampedRatio > 1f) Rational((clampedRatio * 1000).toInt(), 1000)
            else Rational(1000, (1000 / clampedRatio).toInt())
        }
        builder.setAspectRatio(rational)

        if (::gameScreen.isInitialized && gameScreen.isAttachedToWindow) {
            val rect = Rect()
            gameScreen.getGlobalVisibleRect(rect)
            if (!rect.isEmpty) {
                builder.setSourceRectHint(rect)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val canAutoEnter = isRunning && !isEmulationPaused
            builder.setAutoEnterEnabled(canAutoEnter)
            builder.setSeamlessResizeEnabled(true)
        }

        return builder.build()
    }

    private fun updatePipParamsIfSupported() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isPipSupported()) {
            try {
                setPictureInPictureParams(buildPipParams())
            } catch (e: Exception) {
                // Ignore if not in valid window state
            }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val topBarLeft = findViewById<View?>(R.id.topBarLeft)
        val topBarRight = findViewById<View?>(R.id.topBarRight)

        if (isInPictureInPictureMode) {
            gamepadArea.visibility = View.GONE
            topBarLeft?.visibility = View.GONE
            topBarRight?.visibility = View.GONE
            currentSettingsDialog?.dismiss()
            currentMenuDialog?.dismiss()
            currentExitDialog?.dismiss()
            isEmulationPaused = false
            updatePipParamsIfSupported()
        } else {
            gamepadArea.visibility = View.VISIBLE
            topBarLeft?.visibility = View.VISIBLE
            topBarRight?.visibility = View.VISIBLE
            val isLand = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
            bindViews(isLand)
            updatePipParamsIfSupported()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ owns the Home transition through autoEnterEnabled.
        // Calling enterPictureInPictureMode here as well creates competing
        // transitions, especially when restoring a landscape activity.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            isRunning && !isEmulationPaused && isPipSupported()) {
            enterPictureInPictureModeSafely()
        }
    }

    @Synchronized
    private fun startEmulationLoop() {
        if (isRunning) return
        try {
            EmulatorNative.resumeEmulation()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        isRunning = true
        isEmulationPaused = false
        renderThread = Thread {
            try {
                while (isRunning && !Thread.currentThread().isInterrupted) {
                    if (isEmulationPaused) {
                        Thread.sleep(50)
                        continue
                    }

                    val start = System.currentTimeMillis()

                    try {
                        val dimensions = EmulatorNative.runFrame(gameBitmap)
                        if (dimensions != 0) {
                            val nativeW = (dimensions shr 16) and 0xFFFF
                            val nativeH = dimensions and 0xFFFF
                            if (nativeW > 0 && nativeH > 0 && (gameBitmap.width != nativeW || gameBitmap.height != nativeH)) {
                                val newBmp = Bitmap.createBitmap(nativeW, nativeH, Bitmap.Config.ARGB_8888)
                                gameBitmap = newBmp
                                runOnUiThread {
                                    gameScreen.setImageBitmap(gameBitmap)
                                    updatePipParamsIfSupported()
                                }
                            }
                        }
                        runOnUiThread {
                            gameScreen.invalidate()
                        }
                    } catch (e: Exception) {
                    }

                    val targetSleep = if (appSettings.turboEnabled || appSettings.emulationSpeed > 100) 8 else 16
                    val frameTime = System.currentTimeMillis() - start
                    val sleepTime = targetSleep - frameTime
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime)
                    }
                }
            } catch (e: InterruptedException) {
                // Thread interrupted on stop - cleanly exit loop
            }
        }.apply {
            name = "EmulatorRenderThread"
            start()
        }
    }

    private fun releasePressedInputs() {
        activePointerButtons.clear()
        dpadPointerId = null
        currentlyPressedButtons.toList().forEach { setButtonState(it, false) }
        currentlyPressedButtons.clear()
        dpadViewRef?.isPressed = false
        try {
            EmulatorNative.stopRewind()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Synchronized
    private fun stopEmulationLoop() {
        releasePressedInputs()
        if (!isRunning) return
        isRunning = false
        isEmulationPaused = true
        try {
            renderThread?.interrupt()
            renderThread?.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            renderThread = null
        }
    }

    override fun onResume() {
        super.onResume()
        startEmulationLoop()
        updatePipParamsIfSupported()
    }

    override fun onPause() {
        super.onPause()
        val inPip = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode
        val canAutoEnter = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            isRunning && !isEmulationPaused && isPipSupported()
        if (inPip || canAutoEnter) {
            // With automatic PiP, onPause can precede the mode callback.
            // onStop owns background shutdown after the transition settles.
        } else {
            // Standard pause: stop render loop, save SRAM battery and pause emulation core
            stopEmulationLoop()
            try {
                EmulatorNative.saveBattery()
                EmulatorNative.pauseEmulation()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Activity is stopped (PiP closed, screen turned off, or navigated away)
        // Strictly stop emulation loop to prevent background battery drain or infinite execution
        stopEmulationLoop()
        try {
            EmulatorNative.saveBattery()
            EmulatorNative.pauseEmulation()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEmulationLoop()
        try {
            EmulatorNative.saveBattery()
            EmulatorNative.stopEmulation()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            emulatorGameLease?.close()
            emulatorGameLease = null
        }
    }
}
