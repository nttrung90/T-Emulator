package com.temulator.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.temulator.app.auth.AuthRepository
import com.temulator.app.auth.AuthState
import com.temulator.app.billing.BillingRepository
import com.temulator.app.billing.EntitlementRepository
import com.temulator.app.billing.VipDialogHelper
import com.temulator.app.cloud.DriveAuthorizationManager
import kotlinx.coroutines.launch

class MoreFragment : Fragment() {

    private lateinit var tvAccountName: TextView
    private lateinit var tvAccountEmail: TextView
    private lateinit var tvVipBadge: TextView
    private lateinit var btnAuthAction: MaterialButton
    private lateinit var tvDriveStatus: TextView
    private lateinit var btnDriveAction: MaterialButton
    private lateinit var btnRestoreVip: MaterialButton
    private lateinit var btnDeleteAccount: MaterialButton

    private lateinit var tvLanguageSubtitle: TextView
    private lateinit var tvCheckUpdateSubtitle: TextView
    private lateinit var tvAboutSubtitle: TextView
    private lateinit var tvAboutLink: TextView

    private lateinit var authRepository: AuthRepository
    private lateinit var entitlementRepository: EntitlementRepository
    private lateinit var billingRepository: BillingRepository
    private lateinit var driveAuthManager: DriveAuthorizationManager

    private val driveAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.result
                if (driveAuthManager.handleSignInResult(account)) {
                    Toast.makeText(requireContext(), R.string.account_drive_connected, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), R.string.error_drive_permission_required, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Drive authorization error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_more, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val context = requireContext()
        authRepository = AuthRepository.getInstance(context)
        entitlementRepository = EntitlementRepository.getInstance(context)
        billingRepository = BillingRepository.getInstance(context, entitlementRepository)
        driveAuthManager = DriveAuthorizationManager.getInstance(context)

        // Account Views
        tvAccountName = view.findViewById(R.id.tvAccountName)
        tvAccountEmail = view.findViewById(R.id.tvAccountEmail)
        tvVipBadge = view.findViewById(R.id.tvVipBadge)
        btnAuthAction = view.findViewById(R.id.btnAuthAction)
        tvDriveStatus = view.findViewById(R.id.tvDriveStatus)
        btnDriveAction = view.findViewById(R.id.btnDriveAction)
        btnRestoreVip = view.findViewById(R.id.btnRestoreVip)
        btnDeleteAccount = view.findViewById(R.id.btnDeleteAccount)

        // General Menu Views
        tvLanguageSubtitle = view.findViewById(R.id.tvLanguageSubtitle)
        tvCheckUpdateSubtitle = view.findViewById(R.id.tvCheckUpdateSubtitle)
        tvAboutSubtitle = view.findViewById(R.id.tvAboutSubtitle)
        tvAboutLink = view.findViewById(R.id.tvAboutLink)

        val menuLanguage = view.findViewById<LinearLayout>(R.id.menuLanguage)
        val menuCheckUpdate = view.findViewById<LinearLayout>(R.id.menuCheckUpdate)
        val menuAbout = view.findViewById<LinearLayout>(R.id.menuAbout)

        setupAccountSection()
        setupEmulatorTools(view)
        setupGeneralMenu(menuLanguage, menuCheckUpdate, menuAbout)
        observeState()
    }

    private fun setupAccountSection() {
        btnAuthAction.setOnClickListener {
            val user = authRepository.getCurrentUser()
            if (user == null) {
                viewLifecycleOwner.lifecycleScope.launch {
                    val result = authRepository.signInWithGoogle(requireActivity())
                    if (result.isSuccess) {
                        Toast.makeText(requireContext(), "Signed in successfully!", Toast.LENGTH_SHORT).show()
                        entitlementRepository.onUserChanged(result.getOrNull()?.uid)
                        entitlementRepository.refreshEntitlement(authRepository)
                    } else {
                        val err = result.exceptionOrNull()?.localizedMessage ?: "Sign in failed"
                        Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                viewLifecycleOwner.lifecycleScope.launch {
                    authRepository.signOut()
                    driveAuthManager.disconnectDrive()
                    entitlementRepository.onUserChanged(null)
                    Toast.makeText(requireContext(), "Signed out", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnDriveAction.setOnClickListener {
            if (!authRepository.isUserLoggedIn()) {
                Toast.makeText(requireContext(), R.string.error_login_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (driveAuthManager.isDriveAuthorized()) {
                driveAuthManager.disconnectDrive()
                Toast.makeText(requireContext(), "Google Drive disconnected", Toast.LENGTH_SHORT).show()
            } else {
                val client = driveAuthManager.getGoogleSignInClient(requireActivity())
                driveAuthLauncher.launch(client.signInIntent)
            }
        }

        btnRestoreVip.setOnClickListener {
            Toast.makeText(requireContext(), "Checking Play Store purchases and verifying…", Toast.LENGTH_SHORT).show()
            billingRepository.queryPurchases { result ->
                activity?.runOnUiThread {
                    when (result) {
                        is com.temulator.app.billing.BillingQueryResult.Verified -> {
                            Toast.makeText(requireContext(), R.string.account_vip_status_active, Toast.LENGTH_LONG).show()
                        }
                        is com.temulator.app.billing.BillingQueryResult.NotOwned -> {
                            Toast.makeText(requireContext(), "No verified VIP purchases found for this account.", Toast.LENGTH_SHORT).show()
                        }
                        is com.temulator.app.billing.BillingQueryResult.Pending -> {
                            Toast.makeText(requireContext(), "Your purchase is currently pending payment confirmation.", Toast.LENGTH_SHORT).show()
                        }
                        is com.temulator.app.billing.BillingQueryResult.Error -> {
                            Toast.makeText(requireContext(), "Error querying purchases: ${result.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        btnDeleteAccount.setOnClickListener {
            val pinnedSession = authRepository.captureSession()
            if (pinnedSession.uid == null) {
                Toast.makeText(requireContext(), R.string.error_login_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_account_dialog_title)
                .setMessage(R.string.delete_account_dialog_message)
                .setPositiveButton(R.string.account_btn_delete) { _, _ ->
                    if (!authRepository.isCurrent(pinnedSession)) {
                        Toast.makeText(requireContext(), "Account session changed. Operation canceled.", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    viewLifecycleOwner.lifecycleScope.launch {
                        if (!authRepository.isCurrent(pinnedSession)) {
                            Toast.makeText(requireContext(), "Account session changed. Operation canceled.", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val idToken = authRepository.getIdToken()
                        if (!authRepository.isCurrent(pinnedSession)) {
                            Toast.makeText(requireContext(), "Account session changed. Operation canceled.", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        val backendUrl = entitlementRepository.getBackendUrl()

                        if (!backendUrl.isNullOrEmpty() && !idToken.isNullOrEmpty()) {
                            val deleteSuccess = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                try {
                                    val request = okhttp3.Request.Builder()
                                        .url("$backendUrl/v1/account")
                                        .header("Authorization", "Bearer $idToken")
                                        .delete()
                                        .build()
                                    okhttp3.OkHttpClient().newCall(request).execute().use { resp ->
                                        resp.isSuccessful
                                    }
                                } catch (e: Exception) {
                                    false
                                }
                            }
                            if (!deleteSuccess) {
                                Toast.makeText(requireContext(), "Failed to delete account on server. Please check internet connection.", Toast.LENGTH_LONG).show()
                                return@launch
                            }
                        } else if (!BuildConfig.DEBUG) {
                            Toast.makeText(requireContext(), "Backend URL or session token is missing. Cannot verify server deletion.", Toast.LENGTH_LONG).show()
                            return@launch
                        }

                        // Always revoke target user's entitlement cache
                        entitlementRepository.revokeEntitlement(pinnedSession.uid)

                        // Only sign out / disconnect drive if the session is still strictly current
                        if (authRepository.isCurrent(pinnedSession)) {
                            driveAuthManager.disconnectDrive()
                            authRepository.signOut()
                            Toast.makeText(requireContext(), "App account session and server data deleted.", Toast.LENGTH_SHORT).show()
                        } else {
                            android.util.Log.w("MoreFragment", "User session changed after server deletion; suppressing signout of current user.")
                        }
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        tvVipBadge.setOnClickListener {
            if (!entitlementRepository.isVipActive.value) {
                VipDialogHelper.showVipPaywall(requireActivity(), billingRepository, authRepository)
            }
        }
    }

    private fun setupEmulatorTools(view: View) {
        val cardEmulatorTools = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardEmulatorTools)
        val btnDevToggleVip = view.findViewById<MaterialButton>(R.id.btnDevToggleVip)
        val btnDevTestPaywall = view.findViewById<MaterialButton>(R.id.btnDevTestPaywall)
        val btnDevTestLogin = view.findViewById<MaterialButton>(R.id.btnDevTestLogin)
        val btnDevToggleDrive = view.findViewById<MaterialButton>(R.id.btnDevToggleDrive)
        val btnDevResetTest = view.findViewById<MaterialButton>(R.id.btnDevResetTest)
        val tvMoreTitle = view.findViewById<TextView>(R.id.tvMoreTitle)

        // F01: Dev tools strictly unavailable in release builds
        if (!BuildConfig.DEBUG) {
            cardEmulatorTools.visibility = View.GONE
            tvMoreTitle?.setOnClickListener(null)
            return
        }

        cardEmulatorTools.visibility = View.VISIBLE

        var tapCount = 0
        tvMoreTitle?.setOnClickListener {
            tapCount++
            if (tapCount >= 5) {
                tapCount = 0
                val newVisibility = if (cardEmulatorTools.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                cardEmulatorTools.visibility = newVisibility
                val stateMsg = if (newVisibility == View.VISIBLE) "Emulator Tools: Enabled" else "Emulator Tools: Hidden"
                Toast.makeText(requireContext(), stateMsg, Toast.LENGTH_SHORT).show()
            }
        }

        btnDevToggleVip.setOnClickListener {
            val uid = authRepository.getCurrentUser()?.uid ?: "test_user"
            if (entitlementRepository.isVipActive.value) {
                entitlementRepository.revokeEntitlement(uid)
                Toast.makeText(requireContext(), R.string.dev_vip_revoked, Toast.LENGTH_SHORT).show()
            } else {
                entitlementRepository.activateTestVip(uid)
                Toast.makeText(requireContext(), R.string.dev_vip_activated, Toast.LENGTH_SHORT).show()
            }
        }

        btnDevTestPaywall.setOnClickListener {
            VipDialogHelper.showVipPaywall(requireActivity(), billingRepository, authRepository)
        }

        btnDevTestLogin.setOnClickListener {
            authRepository.signInWithTestAccount()
            driveAuthManager.enableMockDrive()
            Toast.makeText(requireContext(), R.string.dev_login_success, Toast.LENGTH_SHORT).show()
        }

        btnDevToggleDrive.setOnClickListener {
            if (driveAuthManager.isMockDriveEnabled()) {
                driveAuthManager.disconnectDrive()
                Toast.makeText(requireContext(), "Mock Google Drive: Disabled", Toast.LENGTH_SHORT).show()
            } else {
                driveAuthManager.enableMockDrive()
                Toast.makeText(requireContext(), "Mock Google Drive: Connected", Toast.LENGTH_SHORT).show()
            }
        }

        btnDevResetTest.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dev_btn_reset_test)
                .setMessage("Reset all test auth sessions, mock drive connections, and VIP entitlement?")
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        val uid = authRepository.getCurrentUser()?.uid
                        authRepository.signOut()
                        driveAuthManager.disconnectDrive()
                        entitlementRepository.revokeEntitlement(uid)
                        Toast.makeText(requireContext(), R.string.dev_reset_complete, Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
    }

    private fun setupGeneralMenu(
        menuLanguage: LinearLayout,
        menuCheckUpdate: LinearLayout,
        menuAbout: LinearLayout
    ) {
        updateLanguageSubtitle()

        val versionName = getAppVersionName()
        tvCheckUpdateSubtitle.text = getString(R.string.more_check_update_desc, versionName)
        tvAboutSubtitle.text = getString(R.string.more_about_desc)
        tvAboutLink.text = getString(R.string.more_about_url)

        menuLanguage.setOnClickListener {
            showLanguageDialog()
        }

        menuCheckUpdate.setOnClickListener {
            openPlayStoreForUpdate()
        }

        menuAbout.setOnClickListener {
            openAboutUrl()
        }

        menuAbout.setOnLongClickListener {
            showAboutDialog(versionName)
            true
        }

        tvAboutLink.setOnClickListener {
            openAboutUrl()
        }
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    authRepository.authState.collect { authState ->
                        when (authState) {
                            is AuthState.Authenticated -> {
                                val user = authState.user
                                tvAccountName.text = user.displayName ?: "Google User"
                                tvAccountEmail.text = user.email ?: ""
                                btnAuthAction.setText(R.string.account_btn_logout)
                                btnAuthAction.setBackgroundColor(android.graphics.Color.parseColor("#455A64"))
                                btnDeleteAccount.visibility = View.VISIBLE
                            }
                            AuthState.Loading -> {
                                tvAccountName.text = "Signing in…"
                                tvAccountEmail.text = "Please wait…"
                            }
                            is AuthState.Error -> {
                                tvAccountName.setText(R.string.account_section_title)
                                tvAccountEmail.text = authState.message
                                btnAuthAction.setText(R.string.account_btn_login)
                                btnAuthAction.setBackgroundColor(android.graphics.Color.parseColor("#4285F4"))
                                btnDeleteAccount.visibility = View.GONE
                            }
                            AuthState.Unauthenticated -> {
                                tvAccountName.setText(R.string.account_section_title)
                                tvAccountEmail.setText(R.string.account_not_logged_in)
                                btnAuthAction.setText(R.string.account_btn_login)
                                btnAuthAction.setBackgroundColor(android.graphics.Color.parseColor("#4285F4"))
                                btnDeleteAccount.visibility = View.GONE
                            }
                        }
                    }
                }

                launch {
                    entitlementRepository.isVipActive.collect { isVip ->
                        if (isVip) {
                            tvVipBadge.setText(R.string.account_vip_status_active)
                            tvVipBadge.setTextColor(android.graphics.Color.parseColor("#FFD54F"))
                            tvVipBadge.setBackgroundColor(android.graphics.Color.parseColor("#3E3218"))
                        } else {
                            tvVipBadge.setText(R.string.account_vip_status_free)
                            tvVipBadge.setTextColor(android.graphics.Color.parseColor("#90A4AE"))
                            tvVipBadge.setBackgroundColor(android.graphics.Color.parseColor("#263238"))
                        }
                    }
                }

                launch {
                    driveAuthManager.driveAccountEmail.collect { email ->
                        if (!email.isNullOrEmpty()) {
                            tvDriveStatus.text = getString(R.string.account_drive_status_connected, email)
                            btnDriveAction.setText(R.string.account_btn_disconnect_drive)
                        } else {
                            tvDriveStatus.setText(R.string.account_drive_status_disconnected)
                            btnDriveAction.setText(R.string.account_btn_connect_drive)
                        }
                    }
                }
            }
        }
    }

    private fun getAppVersionName(): String {
        return try {
            val pInfo = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            pInfo.versionName ?: "1.1.0"
        } catch (e: Exception) {
            "1.1.0"
        }
    }

    private fun updateLanguageSubtitle() {
        val prefs = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val savedLang = prefs.getString("selected_language", "SYSTEM") ?: "SYSTEM"

        tvLanguageSubtitle.text = when (savedLang) {
            "vi" -> getString(R.string.lang_vi)
            "en" -> getString(R.string.lang_en)
            else -> getString(R.string.lang_system)
        }
    }

    private fun showLanguageDialog() {
        val prefs = requireContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val savedLang = prefs.getString("selected_language", "SYSTEM") ?: "SYSTEM"

        val options = arrayOf(
            getString(R.string.lang_system),
            getString(R.string.lang_vi),
            getString(R.string.lang_en)
        )

        val checkedItem = when (savedLang) {
            "vi" -> 1
            "en" -> 2
            else -> 0
        }

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.select_language_title)
            .setSingleChoiceItems(options, checkedItem) { dialog, which ->
                val (langTag, prefValue) = when (which) {
                    1 -> Pair("vi", "vi")
                    2 -> Pair("en", "en")
                    else -> Pair("", "SYSTEM")
                }

                prefs.edit().putString("selected_language", prefValue).apply()

                val appLocale = if (langTag.isNotEmpty()) {
                    LocaleListCompat.forLanguageTags(langTag)
                } else {
                    LocaleListCompat.getEmptyLocaleList()
                }
                AppCompatDelegate.setApplicationLocales(appLocale)

                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun openPlayStoreForUpdate() {
        val packageName = requireContext().packageName
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=$packageName")
                )
            )
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                    )
                )
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    private fun openAboutUrl() {
        val url = getString(R.string.more_about_url)
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
                )
            )
        } catch (e: Exception) {
            Toast.makeText(requireContext(), url, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAboutDialog(versionName: String) {
        val url = getString(R.string.more_about_url)
        val message = getString(R.string.about_dialog_message, versionName, url)

        AlertDialog.Builder(requireContext())
            .setTitle(R.string.about_dialog_title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setNeutralButton(R.string.about_privacy_policy) { _, _ ->
                openAboutUrl()
            }
            .show()
    }

    private fun showPrivacyPolicyDialog() {
        openAboutUrl()
    }
}
