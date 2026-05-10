/*
 * SPDX-FileCopyrightText: 2024 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.nothing.nrenabler

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.android.internal.telephony.GlobalSettingsHelper
import com.qti.extphone.NrConfig

class NrEnablerService : Service() {
    private lateinit var nothingExtService: QcomNothingExtTelephonyService
    private lateinit var workerThread: HandlerThread
    private lateinit var handler: Handler
    private var requestGeneration = 0

    override fun onCreate() {
        workerThread = HandlerThread(TAG)
        workerThread.start()
        handler = Handler(workerThread.looper)
        nothingExtService = QcomNothingExtTelephonyService(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val targetSubId = intent?.getIntExtra(
            CarrierConfigManager.EXTRA_SUBSCRIPTION_INDEX,
            SubscriptionManager.INVALID_SUBSCRIPTION_ID,
        ) ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val generation = ++requestGeneration
        handler.removeCallbacksAndMessages(null)
        handler.post { runSetNrConfig(startId, generation, targetSubId, 0) }
        return START_NOT_STICKY
    }

    private fun runSetNrConfig(
        startId: Int,
        generation: Int,
        targetSubId: Int,
        retryCount: Int,
    ) {
        if (generation != requestGeneration) return

        val success = setNrConfig(targetSubId)
        if (generation != requestGeneration) return

        if (success) {
            Log.d(TAG, "setNrConfig completed")
            stopSelf(startId)
            return
        }

        val nextRetry = retryCount + 1
        if (nextRetry >= MAX_RETRIES) {
            Log.e(TAG, "setNrConfig failed after $nextRetry attempts")
            stopSelf(startId)
            return
        }

        Log.v(TAG, "setNrConfig failed, retry ${nextRetry + 1}/$MAX_RETRIES after 5s")
        handler.postDelayed(
            { runSetNrConfig(startId, generation, targetSubId, nextRetry) },
            RETRY_DELAY_MS,
        )
    }

    private fun setNrConfig(targetSubId: Int): Boolean {
        val activeSubs = getSystemService(SubscriptionManager::class.java)
            ?.activeSubscriptionInfoList
            ?.filter {
                targetSubId == SubscriptionManager.INVALID_SUBSCRIPTION_ID ||
                    it.subscriptionId == targetSubId
            }
        if (activeSubs.isNullOrEmpty()) {
            Log.v(TAG, "setNrConfig: no active sub.")
            return false
        }
        for (aSubInfo in activeSubs) {
            val phoneId = SubscriptionManager.getPhoneId(aSubInfo.subscriptionId)
            if (!validatePhoneId(phoneId)) {
                Log.e(TAG, "Invalid phoneId: $phoneId")
                return false
            }

            val mode = get5GMode(aSubInfo.subscriptionId)
            Log.v(
                TAG,
                "setNrConfig: mode=$mode phoneId=$phoneId subId=${aSubInfo.subscriptionId}",
            )
            if (!nothingExtService.setNrConfig(phoneId, mode)) {
                return false
            }
        }
        return true
    }

    private fun get5GMode(subId: Int): Int {
        var mode = GlobalSettingsHelper.getInt(this, CONFIG_5G_MODE, subId, NR_CONFIG_UNKNOWN)
        val config = getSystemService(CarrierConfigManager::class.java)?.getConfigForSubId(
            subId,
            NT_HIDE_5G_STANDALONE_BOOL,
            NT_CARRIER_DEFAULT_SA_ENABLED_BOOL,
            CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY,
        )

        if (config == null) {
            if (mode == NR_CONFIG_UNKNOWN) {
                Log.d(TAG, "get5GMode: no carrier config, default SA disabled")
                mode = NrConfig.NR_CONFIG_NSA
            }
        } else {
            val supportsSa = config.getIntArray(
                CarrierConfigManager.KEY_CARRIER_NR_AVAILABILITIES_INT_ARRAY,
            )?.contains(CarrierConfigManager.CARRIER_NR_AVAILABILITY_SA) == true
            val hideSa = config.getBoolean(NT_HIDE_5G_STANDALONE_BOOL, false)
            val defaultSaEnabled = config.getBoolean(NT_CARRIER_DEFAULT_SA_ENABLED_BOOL, false)

            Log.d(
                TAG,
                "get5GMode: subId=$subId supportSa=$supportsSa hideSa=$hideSa " +
                    "defaultSaEnabled=$defaultSaEnabled mode=$mode",
            )

            mode = when {
                !supportsSa -> NrConfig.NR_CONFIG_NSA
                hideSa || mode == NR_CONFIG_UNKNOWN -> {
                    if (defaultSaEnabled) {
                        NrConfig.NR_CONFIG_COMBINED_SA_NSA
                    } else {
                        NrConfig.NR_CONFIG_NSA
                    }
                }
                else -> mode
            }
        }

        if (mode != NrConfig.NR_CONFIG_COMBINED_SA_NSA && mode != NrConfig.NR_CONFIG_NSA) {
            Log.d(TAG, "get5GMode: unexpected mode=$mode, default SA disabled")
            mode = NrConfig.NR_CONFIG_NSA
        }
        return mode
    }

    private fun validatePhoneId(phoneId: Int): Boolean {
        val phoneCount =
            getSystemService(TelephonyManager::class.java)?.activeModemCount ?: return false
        return phoneId in 0 until phoneCount
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        nothingExtService.destroy()
        workerThread.quitSafely()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NothingNrEnabler"
        private const val CONFIG_5G_MODE = "config_5g_mode"
        private const val NT_CARRIER_DEFAULT_SA_ENABLED_BOOL = "nt_carrier_default_sa_enabled_bool"
        private const val NT_HIDE_5G_STANDALONE_BOOL = "nt_hide_5g_standalone_bool"
        private const val NR_CONFIG_UNKNOWN = -1
        private const val MAX_RETRIES = 6
        private const val RETRY_DELAY_MS = 5000L
    }
}
