package com.appcoins.wallet.billing.adyen

import com.adyen.checkout.base.model.PaymentMethodsApiResponse
import com.appcoins.wallet.billing.util.Error
import io.reactivex.Single
import retrofit2.http.*
import java.io.IOException

class AdyenPaymentService(private val adyenApi: AdyenApi) {

  fun loadPaymentInfo(methods: Methods, value: String,
                      currency: String): Single<PaymentInfoModel> {
    return adyenApi.loadPaymentInfo(value, currency)
        .map { map(it, methods) }
        .onErrorReturn { mapInfoModelError(it) }
  }

  fun makePayment(value: String, currency: String, reference: String?, encryptedCardNumber: String?,
                  encryptedExpiryMonth: String?, encryptedExpiryYear: String?,
                  encryptedSecurityCode: String?, type: String,
                  returnUrl: String?): Single<PaymentModel> {
    return adyenApi.makePayment(value, currency, encryptedCardNumber,
        encryptedExpiryMonth, encryptedExpiryYear, encryptedSecurityCode, null, reference,
        type, returnUrl)
        .map {
          map(it)
        }
        .onErrorReturn { mapModelError(it) }
  }

  fun submitRedirect(payload: String?, paymentData: String?): Single<PaymentModel> {
    return adyenApi.submitRedirect(payload, paymentData)
        .map { map(it) }
        .onErrorReturn { mapModelError(it) }
  }

  private fun map(response: PaymentMethodsApiResponse,
                  method: Methods): PaymentInfoModel {
    val paymentMethods = response.paymentMethods
    paymentMethods?.let {
      for (paymentMethod in it) {
        if (paymentMethod.name == method.id) return PaymentInfoModel(paymentMethod)
      }
    }
    return PaymentInfoModel(null, Error(true))
  }

  private fun map(response: MakePaymentResponse): PaymentModel {
    return PaymentModel(response.resultCode, response.refusalReason,
        response.refusalReasonCode?.toInt(),
        response.action, response.action?.url, response.action?.paymentData)
  }

  private fun mapInfoModelError(throwable: Throwable): PaymentInfoModel {
    return PaymentInfoModel(null, Error(true, throwable.isNoNetworkException()))
  }

  private fun mapModelError(throwable: Throwable): PaymentModel {
    return PaymentModel(Error(true, throwable.isNoNetworkException()))
  }

  interface AdyenApi {

    @GET("adyen/methods")
    fun loadPaymentInfo(@Query("value") value: String,
                        @Query("currency") currency: String): Single<PaymentMethodsApiResponse>

    @POST("adyen/payment")
    fun makePayment(@Query("value") value: String,
                    @Query("currency") currency: String,
                    @Query("encrypted_card_number") encryptedCardNumber: String?,
                    @Query("encrypted_expiry_month") encryptedExpiryMonth: String?,
                    @Query("encrypted_expiry_year") encryptedExpiryYear: String?,
                    @Query("encrypted_security_code") encryptedSecurityCode: String?,
                    @Query("holder_name") holderName: String?,
                    @Query("reference") reference: String?,
                    @Query("type") paymentMethod: String,
                    @Query("redirect_url") returnUrl: String?): Single<MakePaymentResponse>

    @FormUrlEncoded
    @POST("adyen/payment/details")
    fun submitRedirect(@Field("payload") payload: String?,
                       @Field("payment_data") paymentData: String?): Single<MakePaymentResponse>
  }


  enum class Methods(val id: String) {
    CREDIT_CARD("Credit Card"), PAYPAL("PayPal")
  }

  fun Throwable?.isNoNetworkException(): Boolean {
    return this != null && (this is IOException || this.cause != null && this.cause is IOException)
  }
}