/*  Copyright (c) MediaArea.net SARL. All Rights Reserved.
 *
 *  Use of this source code is governed by a BSD-style license that can
 *  be found in the License.html file in the root of the source tree.
 */

package net.mediaarea.mediainfo

import java.util.concurrent.atomic.AtomicInteger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.math.pow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.OnLifecycleEvent
import android.util.Log
import android.app.Activity
import android.app.Application
import com.android.billingclient.api.*

class SubscriptionManager private constructor(private val application: Application) : PurchasesUpdatedListener, BillingClientStateListener, LifecycleObserver {
    val ready = MutableLiveData<Boolean>()
    val subscribed = MutableLiveData<Boolean>()
    val isLifetime = MutableLiveData<Boolean>()
    val details = MutableLiveData<SkuDetails>()
    val lifetimeDetails = MutableLiveData<SkuDetails>()

    private lateinit var billingClient: BillingClient

    @OnLifecycleEvent(Lifecycle.Event.ON_CREATE)
    fun create() {
        billingClient = BillingClient.newBuilder(application.applicationContext)
                .setListener(this)
                .build()

        updateState(false)
        updateSubscribedState(false)
        updateLifetimeState(false)

        if (billingClient.isReady) {
            updateState(isSubscriptionSupported())
        }
        else {
            billingClient.startConnection(this)
        }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun destroy() {
        if (billingClient.isReady) {
            billingClient.endConnection()
        }
    }

    private fun updateState(newState: Boolean) {
        if (ready.value!=newState) {
            ready.value = newState
        }
    }

    private fun updateSubscribedState(newState: Boolean) {
        if (subscribed.value!=newState) {
            subscribed.value = newState
        }
    }

    private fun updateLifetimeState(newState: Boolean) {
        if (isLifetime.value!=newState) {
            isLifetime.value = newState
        }
    }

    private fun isSubscriptionSupported(): Boolean {
        val response = billingClient.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
        return response==BillingClient.BillingResponse.OK
    }

    fun launchBillingFlow(activity: Activity, params: BillingFlowParams): Int {
        if (!billingClient.isReady) {
            Log.e(LOG_TAG, "BillingClient is not ready to start billing flow")
        }
        val response = billingClient.launchBillingFlow(activity, params)
        Log.i(LOG_TAG, "Launch Billing Flow Response Code: $response")
        return response
    }

    override fun onPurchasesUpdated(response: Int, purchases: MutableList<Purchase>?) {
        if (response==BillingClient.BillingResponse.OK) {
            handlePurchases(purchases)
        } else if (response==BillingClient.BillingResponse.DEVELOPER_ERROR) {
            Log.e(LOG_TAG, "Your app's configuration is incorrect. Review in the Google Play Console. Possible causes of this error include: APK is not signed with release key; SKU productId mismatch.")
        }
    }

    override fun onBillingSetupFinished(response: Int) {
        if (response==BillingClient.BillingResponse.OK) {
            RetryPolicies.resetConnectionRetryPolicyCounter()

            fun updatePurchasesTask() {
                handlePurchases(billingClient.queryPurchases(BillingClient.SkuType.SUBS)?.purchasesList)
                handlePurchases(billingClient.queryPurchases(BillingClient.SkuType.INAPP)?.purchasesList)
            }
            RetryPolicies.taskExecutionRetryPolicy(billingClient, this) { updatePurchasesTask() }

            if (isSubscriptionSupported()) {
                val params = SkuDetailsParams
                        .newBuilder()
                        .setSkusList(listOf(application.getString(R.string.subscription_sku)))
                        .setType(BillingClient.SkuType.SUBS)
                        .build()

                billingClient.querySkuDetailsAsync(params) { result: Int, list: List<SkuDetails>? ->
                    if (result == BillingClient.BillingResponse.OK) {
                        list?.forEach {
                            if (it.sku == application.getString(R.string.subscription_sku)) {
                                details.value = it
                            }
                        }

                        if (details.value != null && lifetimeDetails.value != null) {
                            updateState(true)
                        }
                    }
                }
            }

            val params = SkuDetailsParams
               .newBuilder()
               .setSkusList(listOf(application.getString(R.string.lifetime_subscription_sku)))
               .setType(BillingClient.SkuType.INAPP)
               .build()

            billingClient.querySkuDetailsAsync(params) { result: Int, list: List<SkuDetails>? ->
                if (result == BillingClient.BillingResponse.OK) {
                    list?.forEach {
                        if (it.sku == application.getString(R.string.lifetime_subscription_sku)) {
                            lifetimeDetails.value = it
                        }
                    }

                    if (details.value != null && lifetimeDetails.value != null) {
                        updateState(true)
                    }
                }
            }

            // Trigger cache update
            billingClient.queryPurchaseHistoryAsync(BillingClient.SkuType.SUBS) { _, _ ->
                val result = billingClient.queryPurchases(BillingClient.SkuType.SUBS)
                handlePurchases(result?.purchasesList)
            }

            billingClient.queryPurchaseHistoryAsync(BillingClient.SkuType.INAPP) { _, _ ->
                val result = billingClient.queryPurchases(BillingClient.SkuType.INAPP)
                handlePurchases(result?.purchasesList)
            }
        } else  {
            updateState(false)
            Log.d(LOG_TAG, "onBillingSetupFinished with failure response code: $response")
        }
    }

    // Disconnected from the Play BillingClient
    override fun onBillingServiceDisconnected() {
        updateState(false)
        RetryPolicies.connectionRetryPolicy { billingClient.startConnection(this) }
    }

    private fun handlePurchases(purchasesList: List<Purchase>?) {
        if (purchasesList==null)
            return

        purchasesList.forEach {
            when (it.sku) {
                application.getString(R.string.subscription_sku) -> {
                    updateSubscribedState(true)
                }
                application.getString(R.string.lifetime_subscription_sku) -> {
                    updateLifetimeState(true)
                    updateSubscribedState(true)
                }
            }
        }
    }

    // Retries handler
    private object RetryPolicies {
        private const val maxRetry = 5
        private const val taskDelay = 2000L
        private const val baseDelayMillis = 500
        private var retryCounter = AtomicInteger(1)

        fun resetConnectionRetryPolicyCounter() {
            retryCounter.set(1)
        }

        fun connectionRetryPolicy(block: () -> Unit) {
            val scope = CoroutineScope(Job() + Dispatchers.Main)
            scope.launch {
                val counter = retryCounter.getAndIncrement()
                if (counter < maxRetry) {
                    val waitTime: Long = (2f.pow(counter) * baseDelayMillis).toLong()
                    delay(waitTime)
                    block()
                }
            }
        }

        fun taskExecutionRetryPolicy(billingClient: BillingClient, listener: SubscriptionManager, task: () -> Unit) {
            val scope = CoroutineScope(Job() + Dispatchers.Main)
            scope.launch {
                if (!billingClient.isReady) {
                    billingClient.startConnection(listener)
                    delay(taskDelay)
                }
                task()
            }
        }
    }

    companion object {
        private const val LOG_TAG = "MediaInfo/Billing"

        @Volatile
        private var INSTANCE: SubscriptionManager? = null
        fun getInstance(app: Application): SubscriptionManager = INSTANCE?: synchronized(this) {
            INSTANCE ?: SubscriptionManager(app).also { INSTANCE = it }
        }
    }
}
