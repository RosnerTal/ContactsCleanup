package com.rosnertal.unusedcontactscleaner.data

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.*

class BillingManager(
    private val context: Context,
    private val repository: ContactsCleanerRepository,
    private val onUpgradeSuccess: () -> Unit,
    private val onError: (String) -> Unit
) : PurchasesUpdatedListener {

    private val billingClient: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    private var productDetails: ProductDetails? = null

    init {
        startConnection()
    }

    fun startConnection() {
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPremiumProduct()
                } else {
                    Log.e("BillingManager", "Billing setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                // Connection retry logic can be added here
                Log.w("BillingManager", "Billing client disconnected")
            }
        })
    }

    private fun queryPremiumProduct() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId("premium_lifetime")
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        billingClient.queryProductDetailsAsync(params, object : ProductDetailsResponseListener {
            override fun onProductDetailsResponse(billingResult: BillingResult, queryProductDetailsResult: QueryProductDetailsResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    productDetails = null
                    val list = queryProductDetailsResult.productDetailsList
                    if (list != null) {
                        for (details in list) {
                            if (details.productId == "premium_lifetime") {
                                productDetails = details
                                break
                            }
                        }
                    }
                    // Check if user already owns premium
                    queryPurchases()
                } else {
                    Log.e("BillingManager", "Query products failed: ${billingResult.debugMessage}")
                }
            }
        })
    }

    fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()

        billingClient.queryPurchasesAsync(params, object : PurchasesResponseListener {
            override fun onQueryPurchasesResponse(billingResult: BillingResult, purchases: List<Purchase>) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    var premiumOwned = false
                    for (purchase in purchases) {
                        if (purchase.products.contains("premium_lifetime") &&
                            purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                            premiumOwned = true
                            break
                        }
                    }
                    if (premiumOwned) {
                        repository.setPremium(true)
                        onUpgradeSuccess()
                    }
                }
            }
        })
    }

    fun launchBillingFlow(activity: Activity) {
        val details = productDetails
        if (details == null) {
            onError("Premium product details are not loaded yet. Check your connection.")
            // Try reconnecting/querying
            startConnection()
            return
        }

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        val billingResult = billingClient.launchBillingFlow(activity, billingFlowParams)
        if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            onError("Unable to launch purchase flow: ${billingResult.debugMessage}")
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: List<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                handlePurchase(purchase)
            }
        } else if (billingResult.responseCode == BillingClient.BillingResponseCode.USER_CANCELED) {
            onError("Purchase cancelled by user")
        } else {
            onError("Purchase error: ${billingResult.debugMessage}")
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.products.contains("premium_lifetime") && 
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            
            if (!purchase.isAcknowledged) {
                val acknowledgeParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                    .build()
                
                billingClient.acknowledgePurchase(acknowledgeParams) { billingResult ->
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        repository.setPremium(true)
                        onUpgradeSuccess()
                    } else {
                        onError("Acknowledgement failed: ${billingResult.debugMessage}")
                    }
                }
            } else {
                repository.setPremium(true)
                onUpgradeSuccess()
            }
        }
    }
}
