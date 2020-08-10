package com.appcoins.wallet.billing.adyen

import com.adyen.checkout.core.model.ModelObject
import com.appcoins.wallet.bdsbilling.SubscriptionBillingApi
import com.appcoins.wallet.bdsbilling.repository.BillingSupportedType
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import io.reactivex.Completable
import io.reactivex.Single
import org.json.JSONObject
import retrofit2.http.*

class AdyenPaymentRepository(private val adyenApi: AdyenApi,
                             private val subscriptionsApi: SubscriptionBillingApi,
                             private val adyenResponseMapper: AdyenResponseMapper) {

  fun loadPaymentInfo(methods: Methods, value: String,
                      currency: String, walletAddress: String): Single<PaymentInfoModel> {
    return adyenApi.loadPaymentInfo(walletAddress, value, currency, methods.transactionType)
        .map { adyenResponseMapper.map(it, methods) }
        .onErrorReturn { adyenResponseMapper.mapInfoModelError(it) }
  }

  fun makePayment(adyenPaymentMethod: ModelObject, shouldStoreMethod: Boolean, returnUrl: String,
                  value: String, currency: String, reference: String?, paymentType: String,
                  walletAddress: String, origin: String?, packageName: String?, metadata: String?,
                  sku: String?, callbackUrl: String?, transactionType: String,
                  developerWallet: String?, storeWallet: String?, oemWallet: String?,
                  userWallet: String?, autoRenewing: Boolean?): Single<PaymentModel> {
    return makePayment(adyenPaymentMethod, shouldStoreMethod, returnUrl, callbackUrl,
        packageName, metadata, paymentType, origin, sku, reference, transactionType, currency,
        value, developerWallet, storeWallet, oemWallet, userWallet, autoRenewing, walletAddress)
        .map { adyenResponseMapper.map(it) }
        .onErrorReturn { adyenResponseMapper.mapPaymentModelError(it) }
  }

  fun submitRedirect(uid: String, walletAddress: String, details: JSONObject,
                     paymentData: String?): Single<PaymentModel> {
    val json = convertToJson(details)
    return adyenApi.submitRedirect(uid, walletAddress, AdyenPayment(json, paymentData))
        .map { adyenResponseMapper.map(it) }
        .onErrorReturn { adyenResponseMapper.mapPaymentModelError(it) }
  }

  fun disablePayments(walletAddress: String): Single<Boolean> {
    return adyenApi.disablePayments(DisableWallet(walletAddress))
        .toSingleDefault(true)
        .doOnError { it.printStackTrace() }
        .onErrorReturn { false }
  }

  fun getTransaction(uid: String, walletAddress: String,
                     signedWalletAddress: String): Single<PaymentModel> {
    return adyenApi.getTransaction(uid, walletAddress, signedWalletAddress)
        .map { adyenResponseMapper.map(it) }
        .onErrorReturn { adyenResponseMapper.mapPaymentModelError(it) }
  }

  private fun makePayment(adyenPaymentMethod: ModelObject,
                          shouldStoreMethod: Boolean, returnUrl: String,
                          callbackUrl: String?, packageName: String?,
                          metadata: String?, paymentType: String,
                          origin: String?, sku: String?,
                          reference: String?, transactionType: String,
                          currency: String, value: String,
                          developerWallet: String?, storeWallet: String?,
                          oemWallet: String?, userWallet: String?,
                          autoRenewing: Boolean?,
                          walletAddress: String): Single<AdyenTransactionResponse> {
    return if (transactionType == BillingSupportedType.INAPP_SUBSCRIPTION.name) {
      subscriptionsApi.getSkuSubscriptionToken(packageName!!, sku!!, currency)
          .map {
            TokenPayment(adyenPaymentMethod, shouldStoreMethod, returnUrl, callbackUrl,
                metadata, paymentType, origin, reference, developerWallet, storeWallet, oemWallet,
                userWallet, autoRenewing, it)
          }
          .flatMap { adyenApi.makeTokenPayment(walletAddress, it) }
    } else {
      adyenApi.makePayment(walletAddress,
          Payment(adyenPaymentMethod, shouldStoreMethod, returnUrl, callbackUrl, packageName,
              metadata, paymentType, origin, sku, reference, transactionType, currency, value,
              developerWallet, storeWallet, oemWallet, userWallet))
    }
  }

  //This method is used to avoid the nameValuePairs key problem that occurs when we pass a JSONObject trough a GSON converter
  private fun convertToJson(details: JSONObject): JsonObject {
    val json = JsonObject()
    val keys = details.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      val value = details.get(key)
      if (value is String) json.addProperty(key, value)
    }
    return json
  }

  interface AdyenApi {

    @GET("payment-methods")
    fun loadPaymentInfo(@Query("wallet.address") walletAddress: String,
                        @Query("price.value") value: String,
                        @Query("price.currency") currency: String,
                        @Query("method") methods: String
    ): Single<PaymentMethodsResponse>


    @GET("transactions/{uid}")
    fun getTransaction(@Path("uid") uid: String, @Query("wallet.address") walletAddress: String,
                       @Query("wallet.signature")
                       walletSignature: String): Single<TransactionResponse>

    @POST("transactions")
    fun makePayment(@Query("wallet.address") walletAddress: String,
                    @Body payment: Payment): Single<AdyenTransactionResponse>

    @Headers("Content-Type: application/json;format=product_token")
    @POST("transactions")
    fun makeTokenPayment(@Query("wallet.address") walletAddress: String,
                         @Body payment: TokenPayment): Single<AdyenTransactionResponse>

    @PATCH("transactions/{uid}")
    fun submitRedirect(@Path("uid") uid: String,
                       @Query("wallet.address") address: String,
                       @Body payment: AdyenPayment): Single<AdyenTransactionResponse>

    @POST("disable-recurring")
    fun disablePayments(@Body wallet: DisableWallet): Completable
  }

  data class AdyenPayment(@SerializedName("payment.details") val details: Any,
                          @SerializedName("payment.data") val data: String?)

  data class DisableWallet(@SerializedName("wallet.address") val walletAddress: String)

  enum class Methods(val adyenType: String, val transactionType: String) {
    CREDIT_CARD("scheme", "credit_card"), PAYPAL("paypal", "paypal")
  }
}