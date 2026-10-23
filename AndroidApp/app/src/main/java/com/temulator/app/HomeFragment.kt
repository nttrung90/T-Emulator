package com.temulator.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import java.io.File
import java.io.FileOutputStream

class HomeFragment : Fragment() {

    private var selectedConsoleType = 0
    private lateinit var gameRepository: GameRepository

    private data class ConsoleItem(val nameResId: Int, val type: Int)

    // Play Store compliant safe technical labels
    private val consoleItems = listOf(
        ConsoleItem(R.string.console_nes, 2),
        ConsoleItem(R.string.console_gba, 5),
        ConsoleItem(R.string.console_snes, 0),
        ConsoleItem(R.string.console_gb, 1),
        ConsoleItem(R.string.console_pce, 3),
        ConsoleItem(R.string.console_sms, 4),
        ConsoleItem(R.string.console_ws, 6)
    )

    private val romPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { handleRomSelected(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        gameRepository = GameRepository(requireContext())
        setupConsoleList(view)
    }

    private fun setupConsoleList(rootView: View) {
        val container = rootView.findViewById<LinearLayout>(R.id.consoleListContainer)
        container.removeAllViews()

        for (item in consoleItems) {
            val btn = Button(requireContext()).apply {
                text = getString(item.nameResId)
                textSize = 16f
                setOnClickListener {
                    selectedConsoleType = item.type
                    openRomPicker()
                }
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 16)
            }
            container.addView(btn, params)
        }
    }

    private fun openRomPicker() {
        romPickerLauncher.launch("*/*")
    }

    private fun handleRomSelected(uri: Uri) {
        val filePath = copyFileToInternalStorage(uri)
        if (filePath != null) {
            val fileName = File(filePath).name
            // Save to GameRepository so it appears in the "Games" tab
            gameRepository.addGame(
                GameEntry(
                    filePath = filePath,
                    fileName = fileName,
                    consoleType = selectedConsoleType,
                    addedTimestamp = System.currentTimeMillis(),
                    source = "manual"
                )
            )

            Toast.makeText(requireContext(), R.string.loading_rom, Toast.LENGTH_SHORT).show()
            val gameId = GameRepository.generateGameId(filePath)
            val intent = Intent(requireContext(), EmulatorActivity::class.java).apply {
                putExtra("ROM_PATH", filePath)
                putExtra("CONSOLE_TYPE", selectedConsoleType)
                putExtra("GAME_ID", gameId)
            }
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), R.string.error_load_rom, Toast.LENGTH_LONG).show()
        }
    }

    private fun getDefaultExtensionForConsole(consoleType: Int): String {
        return when (consoleType) {
            0 -> ".sfc"
            1 -> ".gb"
            2 -> ".nes"
            3 -> ".pce"
            4 -> ".sms"
            5 -> ".gba"
            6 -> ".ws"
            else -> ".bin"
        }
    }

    private fun copyFileToInternalStorage(uri: Uri): String? {
        val context = context ?: return null
        return try {
            var fileName = "temp_rom"
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    fileName = cursor.getString(nameIndex)
                }
            }

            if (!fileName.contains(".") || fileName.endsWith(".bin", ignoreCase = true)) {
                val base = if (fileName.contains(".")) fileName.substringBeforeLast(".") else fileName
                fileName = base + getDefaultExtensionForConsole(selectedConsoleType)
            }

            val targetFile = File(context.filesDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(targetFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            targetFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
