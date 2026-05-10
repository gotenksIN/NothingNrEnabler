/*
 * SPDX-FileCopyrightText: 2024 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.nothing.nrenabler

import android.content.Context
import android.util.Log
import com.qti.extphone.Client
import com.qti.extphone.ExtPhoneCallbackBase
import com.qti.extphone.ExtTelephonyManager
import com.qti.extphone.NrConfig
import com.qti.extphone.ServiceCallback
import com.qti.extphone.Status
import com.qti.extphone.Token
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class QcomNothingExtTelephonyService(private val context: Context) {
    private val extTelephonyManager = ExtTelephonyManager.getInstance(context)
    private var client: Client? = null
    private var pendingSetNrConfig: PendingSetNrConfig? = null

    private val serviceCallback =
        object : ServiceCallback {
            override fun onConnected() {
                Log.d(TAG, "ExtTelephony service connected")
                registerCallback()
            }

            override fun onDisconnected() {
                Log.d(TAG, "ExtTelephony service disconnected")
                client = null
            }
        }

    private val extPhoneCallback =
        object : ExtPhoneCallbackBase() {
            override fun onSetNrConfig(slotId: Int, token: Token?, status: Status?) {
                Log.d(TAG, "onSetNrConfig: slotId=$slotId token=$token status=$status")
                val pending = pendingSetNrConfig ?: return
                if (pending.token == token?.get()) {
                    pending.future.complete(status ?: Status(Status.FAILURE))
                }
            }
        }

    init {
        extTelephonyManager.connectService(serviceCallback)
    }

    fun setNrConfig(phoneId: Int): Boolean {
        if (!ensureClient()) {
            Log.e(TAG, "setNrConfig: ExtTelephony service not ready")
            return false
        }

        val extPhoneClient = client ?: return false
        val token =
            extTelephonyManager.setNrConfig(
                phoneId,
                NrConfig(NrConfig.NR_CONFIG_COMBINED_SA_NSA),
                extPhoneClient,
            )
                ?: run {
                    Log.e(TAG, "setNrConfig: null token for phone $phoneId")
                    return false
                }

        val pending = PendingSetNrConfig(token.get(), CompletableFuture())
        pendingSetNrConfig = pending
        Log.d(TAG, "setNrConfig: token=$token phoneId=$phoneId")

        return try {
            val status = pending.future.get(SET_NR_CONFIG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            Log.d(TAG, "setNrConfig: status=$status")
            status.get() == Status.SUCCESS
        } catch (e: Exception) {
            Log.e(TAG, "setNrConfig failed", e)
            false
        } finally {
            if (pendingSetNrConfig === pending) {
                pendingSetNrConfig = null
            }
        }
    }

    fun destroy() {
        if (client != null) {
            extTelephonyManager.unRegisterCallback(extPhoneCallback)
            client = null
        }
        extTelephonyManager.disconnectService(serviceCallback)
    }

    private fun ensureClient(): Boolean {
        if (!extTelephonyManager.isServiceConnected) {
            extTelephonyManager.connectService(serviceCallback)
            return false
        }
        if (client != null) {
            return true
        }
        registerCallback()
        return client != null
    }

    private fun registerCallback() {
        if (client != null) return
        client = extTelephonyManager.registerCallback(context.packageName, extPhoneCallback)
        Log.d(TAG, "ExtTelephony client=$client")
    }

    private data class PendingSetNrConfig(
        val token: Int,
        val future: CompletableFuture<Status>,
    )

    companion object {
        private const val TAG = "NothingNrEnabler: QcomNothingExtTelephonyService"
        private const val SET_NR_CONFIG_TIMEOUT_MS = 2000L
    }
}
