/*
 * SPDX-FileCopyrightText: 2024 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.nothing.nrenabler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.CarrierConfigManager
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            CarrierConfigManager.ACTION_CARRIER_CONFIG_CHANGED -> {
                Log.d(TAG, "Starting for ${intent.action}")
                context.startService(Intent(context, NrEnablerService::class.java).putExtras(intent))
            }
            else -> Log.w(TAG, "Ignoring ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "NothingNrEnabler"
    }
}
