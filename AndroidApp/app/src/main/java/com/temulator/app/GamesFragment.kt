package com.temulator.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.temulator.app.auth.AuthRepository
import com.temulator.app.billing.BillingRepository
import com.temulator.app.billing.EntitlementRepository
import com.temulator.app.billing.VipDialogHelper
import com.temulator.app.cloud.CloudBackupDialogHelper
import com.temulator.app.cloud.DriveAuthorizationManager
import com.temulator.app.data.db.GameRecord
import com.temulator.app.storage.GameStorageManager
import kotlinx.coroutines.launch
import java.io.File

class GamesFragment : Fragment() {

    private val viewModel: GamesViewModel by activityViewModels()

    private lateinit var rvGames: RecyclerView
    private lateinit var gameAdapter: GameListAdapter
    private lateinit var etSearchGames: TextInputEditText
    private lateinit var tvEmptyState: TextView
    private lateinit var tvScanPath: TextView
    private lateinit var tvResultCount: TextView
    private lateinit var progressScanning: ProgressBar
    private lateinit var btnSelectFolder: MaterialButton
    private lateinit var btnRescan: MaterialButton
    private lateinit var btnBackupVip: MaterialButton
    private lateinit var btnViewBackups: MaterialButton

    private var tvGamesHeaderSubtitle: TextView? = null
    private var cardFolderInfo: View? = null
    private var layoutEmptyState: View? = null
    private var layoutNoResults: View? = null
    private var btnEmptySelectFolder: View? = null
    private var btnEmptyRestoreCloud: View? = null
    private var btnClearSearch: View? = null
    private var layoutScanningIndicator: View? = null

    private lateinit var authRepository: AuthRepository
    private lateinit var entitlementRepository: EntitlementRepository
    private lateinit var billingRepository: BillingRepository
    private lateinit var driveAuthManager: DriveAuthorizationManager
    private lateinit var storageManager: GameStorageManager
    enum class DriveActionIntent {
        BACKUP,
        RESTORE
    }

    private data class PendingDriveAction(
        val intent: DriveActionIntent,
        val appUid: String?,
        val appGeneration: Long,
        val game: GameRecord? = null
    )

    private var pendingDriveAction: PendingDriveAction? = null

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        treeUri?.let { handleFolderSelected(it) }
    }

    private val driveAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.result
                if (driveAuthManager.handleSignInResult(account)) {
                    Toast.makeText(requireContext(), R.string.account_drive_connected, Toast.LENGTH_SHORT).show()
                    val currentSession = authRepository.captureSession()
                    val action = pendingDriveAction
                    pendingDriveAction = null
                    if (action != null && action.appUid == currentSession.uid && action.appGeneration == currentSession.generation) {
                        when (action.intent) {
                            DriveActionIntent.BACKUP -> {
                                if (action.game != null) triggerCloudBackup(action.game)
                            }
                            DriveActionIntent.RESTORE -> {
                                CloudBackupDialogHelper.showRestoreBackupsDialog(
                                    activity = requireActivity(),
                                    lifecycleOwner = viewLifecycleOwner,
                                    authRepo = authRepository,
                                    driveAuthManager = driveAuthManager,
                                    onDriveAuthRequired = { requestDriveAuthorization(DriveActionIntent.RESTORE) }
                                )
                            }
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), R.string.error_drive_permission_required, Toast.LENGTH_SHORT).show()
                    pendingDriveAction = null
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Drive authorization error: ${e.message}", Toast.LENGTH_SHORT).show()
                pendingDriveAction = null
            }
        } else {
            pendingDriveAction = null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_games, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireContext()
        authRepository = AuthRepository.getInstance(context)
        entitlementRepository = EntitlementRepository.getInstance(context)
        billingRepository = BillingRepository.getInstance(context, entitlementRepository)
        driveAuthManager = DriveAuthorizationManager.getInstance(context)
        storageManager = GameStorageManager(context)

        rvGames = view.findViewById(R.id.rvGames)
        etSearchGames = view.findViewById(R.id.etSearchGames)
        tvEmptyState = view.findViewById(R.id.tvEmptyState)
        tvScanPath = view.findViewById(R.id.tvScanPath)
        tvResultCount = view.findViewById(R.id.tvResultCount)
        progressScanning = view.findViewById(R.id.progressScanning)
        btnSelectFolder = view.findViewById(R.id.btnSelectFolder)
        btnRescan = view.findViewById(R.id.btnRescan)
        btnBackupVip = view.findViewById(R.id.btnBackupVip)
        btnViewBackups = view.findViewById(R.id.btnViewBackups)

        tvGamesHeaderSubtitle = view.findViewById(R.id.tvGamesHeaderSubtitle)
        cardFolderInfo = view.findViewById(R.id.cardFolderInfo)
        layoutEmptyState = view.findViewById(R.id.layoutEmptyState)
        layoutNoResults = view.findViewById(R.id.layoutNoResults)
        btnEmptySelectFolder = view.findViewById(R.id.btnEmptySelectFolder)
        btnEmptyRestoreCloud = view.findViewById(R.id.btnEmptyRestoreCloud)
        btnClearSearch = view.findViewById(R.id.btnClearSearch)
        layoutScanningIndicator = view.findViewById(R.id.layoutScanningIndicator)

        setupRecyclerView()
        setupSearch()
        setupFolderControls()
        setupCloudButtons()
        observeUiState()

        updateScanFolderLabel()
    }

    private fun setupRecyclerView() {
        gameAdapter = GameListAdapter(
            onGameClick = { game -> launchGame(game) },
            onGameLongClick = { game -> showGameOptionsDialog(game) },
            onBackupClick = { game -> triggerCloudBackup(game) }
        )
        rvGames.layoutManager = LinearLayoutManager(requireContext())
        rvGames.adapter = gameAdapter
    }

    private fun setupSearch() {
        val currentQuery = viewModel.searchQuery.value
        if (currentQuery.isNotEmpty() && etSearchGames.text.toString() != currentQuery) {
            etSearchGames.setText(currentQuery)
            etSearchGames.setSelection(currentQuery.length)
        }

        etSearchGames.doAfterTextChanged { text ->
            viewModel.setSearchQuery(text?.toString() ?: "")
        }
    }

    private fun setupFolderControls() {
        btnSelectFolder.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        cardFolderInfo?.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        btnEmptySelectFolder?.setOnClickListener {
            folderPickerLauncher.launch(null)
        }

        btnEmptyRestoreCloud?.setOnClickListener {
            btnViewBackups.performClick()
        }

        btnClearSearch?.setOnClickListener {
            etSearchGames.setText("")
        }

        btnRescan.setOnClickListener {
            val uri = viewModel.getScanFolderUri()
            if (uri != null) {
                viewModel.scanFolder(uri)
            } else {
                folderPickerLauncher.launch(null)
            }
        }
    }

    private fun setupCloudButtons() {
        btnBackupVip.setOnClickListener {
            if (!authRepository.isUserLoggedIn()) {
                Toast.makeText(requireContext(), R.string.error_login_required, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!entitlementRepository.isVipActive.value) {
                VipDialogHelper.showVipPaywall(requireActivity(), billingRepository, authRepository)
            } else if (!driveAuthManager.isDriveAuthorized()) {
                requestDriveAuthorization(DriveActionIntent.RESTORE)
            } else {
                Toast.makeText(requireContext(), R.string.account_vip_status_active, Toast.LENGTH_SHORT).show()
            }
        }

        btnViewBackups.setOnClickListener {
            CloudBackupDialogHelper.showRestoreBackupsDialog(
                activity = requireActivity(),
                lifecycleOwner = viewLifecycleOwner,
                authRepo = authRepository,
                driveAuthManager = driveAuthManager,
                onDriveAuthRequired = { requestDriveAuthorization(DriveActionIntent.RESTORE) }
            )
        }
    }

    private fun observeUiState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    gameAdapter.submitList(state.games)

                    tvGamesHeaderSubtitle?.text = getString(R.string.games_count_format, state.totalCount)

                    if (state.isLibraryEmpty) {
                        rvGames.visibility = View.GONE
                        layoutEmptyState?.visibility = View.VISIBLE
                        layoutNoResults?.visibility = View.GONE
                        tvEmptyState.visibility = View.VISIBLE
                        tvEmptyState.setText(R.string.games_empty_desc)
                        tvResultCount.visibility = View.GONE
                    } else if (state.isNoSearchResult) {
                        rvGames.visibility = View.GONE
                        layoutEmptyState?.visibility = View.GONE
                        layoutNoResults?.visibility = View.VISIBLE
                        tvEmptyState.visibility = View.GONE
                        tvResultCount.visibility = View.VISIBLE
                        tvResultCount.text = getString(R.string.games_search_count, 0, state.totalCount)
                    } else {
                        rvGames.visibility = View.VISIBLE
                        layoutEmptyState?.visibility = View.GONE
                        layoutNoResults?.visibility = View.GONE
                        tvEmptyState.visibility = View.GONE
                        if (state.searchQuery.isNotBlank()) {
                            tvResultCount.visibility = View.VISIBLE
                            tvResultCount.text = getString(R.string.games_search_count, state.filteredCount, state.totalCount)
                        } else {
                            tvResultCount.visibility = View.GONE
                        }
                    }

                    when (val scan = state.scanState) {
                        is ScanUiState.Scanning -> {
                            progressScanning.visibility = View.VISIBLE
                            layoutScanningIndicator?.visibility = View.VISIBLE
                            btnRescan.isEnabled = false
                            btnSelectFolder.isEnabled = false
                        }
                        is ScanUiState.Success -> {
                            progressScanning.visibility = View.GONE
                            layoutScanningIndicator?.visibility = View.GONE
                            btnRescan.isEnabled = true
                            btnSelectFolder.isEnabled = true
                            Toast.makeText(requireContext(), getString(R.string.games_found_count, scan.foundCount), Toast.LENGTH_SHORT).show()
                            viewModel.resetScanState()
                        }
                        is ScanUiState.Error -> {
                            progressScanning.visibility = View.GONE
                            layoutScanningIndicator?.visibility = View.GONE
                            btnRescan.isEnabled = true
                            btnSelectFolder.isEnabled = true
                            Toast.makeText(requireContext(), "Scan error: ${scan.message}", Toast.LENGTH_LONG).show()
                            viewModel.resetScanState()
                        }
                        is ScanUiState.Idle -> {
                            progressScanning.visibility = View.GONE
                            layoutScanningIndicator?.visibility = View.GONE
                            btnRescan.isEnabled = true
                            btnSelectFolder.isEnabled = true
                        }
                    }
                }
            }
        }
    }

    private fun handleFolderSelected(treeUri: Uri) {
        try {
            requireContext().contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        viewModel.setScanFolderUri(treeUri)
        updateScanFolderLabel()
        viewModel.scanFolder(treeUri)
    }

    private fun updateScanFolderLabel() {
        val uri = viewModel.getScanFolderUri()
        if (uri != null) {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val displayName = docId.substringAfterLast(":", docId)
            tvScanPath.text = getString(R.string.games_scan_folder, displayName)
        } else {
            tvScanPath.text = getString(R.string.games_folder_not_selected)
        }
    }

    private fun launchGame(game: GameRecord) {
        val coordinator = com.temulator.app.cloud.GameOperationCoordinator.getInstance()
        if (coordinator.isGameBusy(game.gameId)) {
            Toast.makeText(requireContext(), "Game is currently busy with backup or restore. Please wait.", Toast.LENGTH_LONG).show()
            return
        }

        val targetPath = if (!game.localRomPath.isNullOrEmpty() && File(game.localRomPath).exists()) {
            game.localRomPath
        } else {
            // Prepare ROM safely into isolated game directory
            val prep = storageManager.prepareRomFile(game.sourceUri, game.originalFileName, game.gameId)
            prep?.first
        }

        if (targetPath != null) {
            Toast.makeText(requireContext(), R.string.loading_rom, Toast.LENGTH_SHORT).show()
            val intent = Intent(requireContext(), EmulatorActivity::class.java).apply {
                putExtra("ROM_PATH", targetPath)
                putExtra("CONSOLE_TYPE", game.consoleType)
                putExtra("GAME_ID", game.gameId)
            }
            startActivity(intent)
        } else {
            Toast.makeText(requireContext(), R.string.error_load_rom, Toast.LENGTH_LONG).show()
        }
    }

    private fun showGameOptionsDialog(game: GameRecord) {
        val items = arrayOf(
            getString(R.string.action_backup_game),
            getString(R.string.action_delete)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(game.displayName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> triggerCloudBackup(game)
                    1 -> confirmDeleteGame(game)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun triggerCloudBackup(game: GameRecord) {
        val currentSession = authRepository.captureSession()
        pendingDriveAction = PendingDriveAction(
            intent = DriveActionIntent.BACKUP,
            appUid = currentSession.uid,
            appGeneration = currentSession.generation,
            game = game
        )
        CloudBackupDialogHelper.initiateGameBackup(
            activity = requireActivity(),
            lifecycleOwner = viewLifecycleOwner,
            game = game,
            authRepo = authRepository,
            entitlementRepo = entitlementRepository,
            billingRepo = billingRepository,
            driveAuthManager = driveAuthManager,
            onDriveAuthRequired = { requestDriveAuthorization(DriveActionIntent.BACKUP, game) },
            onComplete = { pendingDriveAction = null }
        )
    }

    private fun requestDriveAuthorization(intent: DriveActionIntent, game: GameRecord? = null) {
        val currentSession = authRepository.captureSession()
        pendingDriveAction = PendingDriveAction(
            intent = intent,
            appUid = currentSession.uid,
            appGeneration = currentSession.generation,
            game = game
        )
        val client = driveAuthManager.getGoogleSignInClient(requireActivity())
        driveAuthLauncher.launch(client.signInIntent)
    }

    private fun confirmDeleteGame(game: GameRecord) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(game.displayName)
            .setMessage(R.string.games_delete_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.deleteGame(game)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }
}
