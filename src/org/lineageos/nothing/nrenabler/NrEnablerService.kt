/*
 * SPDX-FileCopyrightText: 2024 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.nothing.nrenabler

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.IBinder
import android.telephony.CarrierConfigManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class NrEnablerService : Service() {
    private lateinit var nothingExtService: QcomNothingExtTelephonyService
    private val handler by lazy { Handler(mainLooper) }
    private val workingInProgress = AtomicBoolean(false)

    private val repeatSetNrConfigIfFail =
        object : Runnable {
            override fun run() {
                if (workingInProgress.getAndSet(true)) return
                if (!setNrConfig()) {
                    Log.v(TAG, "setNrConfig failed, retry after 5s")
                    handler.removeCallbacks(this)
                    handler.postDelayed(this, 5000)
                }
                workingInProgress.set(false)
            }
        }

    private val broadcastReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!workingInProgress.get()) {
                    handler.post(repeatSetNrConfigIfFail)
                }
            }
        }

    override fun onCreate() {
        nothingExtService = QcomNothingExtTelephonyService(this)
        registerReceiver(
            broadcastReceiver,
            IntentFilter(CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED),
        )
        handler.post(repeatSetNrConfigIfFail)
    }

    private fun setNrConfig(): Boolean {
        val activeSubs =
            getSystemService(SubscriptionManager::class.java)?.getActiveSubscriptionInfoList()
        if (activeSubs.isNullOrEmpty()) {
            Log.v(TAG, "setNrConfig: no active sub.")
            return true
        }
        for (aSubInfo in activeSubs) {
            val phoneId = SubscriptionManager.getPhoneId(aSubInfo.subscriptionId)
            if (!validatePhoneId(phoneId)) {
                Log.e(TAG, "Invalid phoneId: $phoneId")
                return false
            }

            Log.v(TAG, "setNrConfig: enable SA/NSA for phone $phoneId")
            if (!nothingExtService.setNrConfig(phoneId)) {
                return false
            }
        }
        return true
    }

    private fun validatePhoneId(phoneId: Int): Boolean {
        val phoneCount =
            getSystemService(TelephonyManager::class.java)?.activeModemCount ?: return false
        return phoneId in 0 until phoneCount
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(broadcastReceiver)
        nothingExtService.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NothingNrEnabler"
    }
}
