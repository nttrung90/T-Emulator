package com.temulator.app.billing

import android.app.Activity
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.temulator.app.BuildConfig
import com.temulator.app.R
import com.temulator.app.auth.AuthRepository

object VipDialogHelper {

    fun showVipPaywall(
        activity: Activity,
        billingRepository: BillingRepository,
        authRepository: AuthRepository,
        onSuccess: (() -> Unit)? = null
    ) {
        val user = authRepository.getCurrentUser()
        if (user == null) {
            if (BuildConfig.DEBUG) {
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.dialog_login_emulator_fallback_title)
                    .setMessage(R.string.dialog_login_emulator_fallback_msg)
                    .setPositiveButton(R.string.dialog_btn_use_test_account) { _, _ ->
                        authRepository.signInWithTestAccount()
                        Toast.makeText(activity, R.string.dev_login_success, Toast.LENGTH_SHORT).show()
                        showVipPaywall(activity, billingRepository, authRepository, onSuccess)
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            } else {
                Toast.makeText(activity, R.string.error_login_required, Toast.LENGTH_LONG).show()
            }
            return
        }

        val formattedPrice = billingRepository.getFormattedPrice()

        val message = buildString {
            append(activity.getString(R.string.vip_desc))
            append("\n\n")
            append("• ").append(activity.getString(R.string.vip_feature_rom)).append("\n")
            append("• ").append(activity.getString(R.string.vip_feature_save)).append("\n")
            append("• ").append(activity.getString(R.string.vip_feature_drive)).append("\n\n")
            append(activity.getString(R.string.vip_storage_notice))
        }

        val buyButtonText = if (formattedPrice != null) {
            activity.getString(R.string.vip_btn_buy, formattedPrice)
        } else {
            activity.getString(R.string.vip_btn_buy, activity.getString(R.string.vip_price_default))
        }

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.vip_title))
            .setMessage(message)
            .setPositiveButton(buyButtonText) { _, _ ->
                if (!BuildConfig.DEBUG) {
                    val preflight = billingRepository.checkBillingPreflight()
                    if (preflight is BillingPreflightResult.Unconfigured) {
                        Toast.makeText(activity, preflight.reason, Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                }
                if (formattedPrice == null && !BuildConfig.DEBUG) {
                    Toast.makeText(activity, "Product details loading from Play Store. Please retry shortly.", Toast.LENGTH_SHORT).show()
                    billingRepository.startConnection()
                    return@setPositiveButton
                }
                billingRepository.launchVipPurchase(activity, user.uid) {
                    onSuccess?.invoke()
                }
            }
            .setNeutralButton(R.string.vip_btn_restore) { _, _ ->
                Toast.makeText(activity, "Verifying purchases with Play Store & backend…", Toast.LENGTH_SHORT).show()
                billingRepository.queryPurchases { result ->
                    activity.runOnUiThread {
                        when (result) {
                            is BillingQueryResult.Verified -> {
                                Toast.makeText(activity, R.string.account_vip_status_active, Toast.LENGTH_LONG).show()
                                onSuccess?.invoke()
                            }
                            is BillingQueryResult.NotOwned -> {
                                Toast.makeText(activity, "No active VIP purchases verified for this account.", Toast.LENGTH_SHORT).show()
                            }
                            is BillingQueryResult.Pending -> {
                                Toast.makeText(activity, "Your purchase is pending payment confirmation.", Toast.LENGTH_SHORT).show()
                            }
                            is BillingQueryResult.Error -> {
                                Toast.makeText(activity, "Error: ${result.message}", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel) { _, _ ->
                billingRepository.clearPendingPurchase()
            }
            .setOnCancelListener {
                billingRepository.clearPendingPurchase()
            }
            .create()

        dialog.show()
    }
}
