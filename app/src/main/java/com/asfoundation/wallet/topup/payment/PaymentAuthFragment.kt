package com.asfoundation.wallet.topup.payment

import adyen.com.adyencse.encrypter.ClientSideEncrypter
import adyen.com.adyencse.encrypter.exception.EncrypterException
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.adyen.core.models.PaymentMethod
import com.adyen.core.models.paymentdetails.CreditCardPaymentDetails
import com.adyen.core.models.paymentdetails.PaymentDetails
import com.appcoins.wallet.bdsbilling.Billing
import com.asf.wallet.R
import com.asfoundation.wallet.billing.adyen.Adyen
import com.asfoundation.wallet.billing.adyen.PaymentType
import com.asfoundation.wallet.billing.authorization.AdyenAuthorization
import com.asfoundation.wallet.billing.purchase.BillingFactory
import com.asfoundation.wallet.interact.FindDefaultWalletInteract
import com.asfoundation.wallet.navigator.UriNavigator
import com.asfoundation.wallet.topup.TopUpActivityView
import com.asfoundation.wallet.topup.TopUpData
import com.asfoundation.wallet.ui.iab.InAppPurchaseInteractor
import com.asfoundation.wallet.util.KeyboardUtils
import com.asfoundation.wallet.view.rx.RxAlertDialog
import com.google.android.material.textfield.TextInputLayout
import com.jakewharton.rxbinding2.view.RxView
import com.jakewharton.rxrelay2.PublishRelay
import dagger.android.support.DaggerFragment
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import kotlinx.android.synthetic.main.fragment_top_up.*
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.util.*
import javax.inject.Inject

class PaymentAuthFragment : DaggerFragment(), PaymentAuthView {
  @Inject
  internal lateinit var inAppPurchaseInteractor: InAppPurchaseInteractor
  @Inject
  internal lateinit var defaultWalletInteract: FindDefaultWalletInteract
  @Inject
  internal lateinit var billingFactory: BillingFactory
  @Inject
  internal lateinit var adyen: Adyen
  @Inject
  internal lateinit var billing: Billing
  private var topUpView: TopUpActivityView? = null
  private lateinit var genericErrorDialog: RxAlertDialog
  private lateinit var networkErrorDialog: RxAlertDialog
  private lateinit var paymentRefusedDialog: RxAlertDialog
  private lateinit var paymentMethod: PaymentMethod
  private var cvcOnly: Boolean = false
  private lateinit var presenter: PaymentAuthPresenter
  private lateinit var keyboardTopUpRelay: PublishRelay<Boolean>
  private lateinit var navigator: PaymentFragmentNavigator
  private var publicKey: String? = null
  private var generationTime: String? = null

  val appPackage: String
    get () {
      if (activity != null) {
        return activity!!.packageName
      }
      throw IllegalArgumentException("previous app package name not found")
    }

  val data: TopUpData?
    get() {
      if (arguments!!.containsKey(PAYMENT_DATA)) {
        return arguments!!.getSerializable(PAYMENT_DATA) as TopUpData?
      }
      throw IllegalArgumentException("previous payment data not found")
    }

  val paymentType: PaymentType
    get() {
      if (arguments!!.containsKey(PAYMENT_TYPE)) {
        return PaymentType.valueOf(arguments!!.getString(PAYMENT_TYPE))
      }
      throw IllegalArgumentException("Payment Type not found")
    }

  val origin: String
    get() {
      if (arguments!!.containsKey(PAYMENT_ORIGIN)) {
        return arguments!!.getString(PAYMENT_ORIGIN)
      }
      throw IllegalArgumentException("Payment origin not found")
    }

  private val transactionType: String
    get() {
      if (arguments!!.containsKey(PAYMENT_TRANSACTION_TYPE)) {
        return arguments!!.getString(PAYMENT_TRANSACTION_TYPE)
      }
      throw IllegalArgumentException("Transaction type not found")
    }

  val currentCurrency: String
    get() {
      if (arguments!!.containsKey(PAYMENT_CURRENT_CURRENCY)) {
        return arguments!!.getString(PAYMENT_CURRENT_CURRENCY)
      }
      throw IllegalArgumentException("Payment main currency not found")
    }

  companion object {

    private val TAG = PaymentAuthFragment::class.java.simpleName

    private const val PAYMENT_TYPE = "paymentType"
    private const val PAYMENT_ORIGIN = "origin"
    private const val PAYMENT_TRANSACTION_TYPE = "transactionType"
    private const val PAYMENT_DATA = "data"
    private const val PAYMENT_CURRENT_CURRENCY = "currentCurrency"

    fun newInstance(paymentType: PaymentType, data: TopUpData, currentCurrency: String,
                    origin: String, transactionType: String): PaymentAuthFragment {
      val bundle = Bundle()
      bundle.putString(PAYMENT_TYPE, paymentType.name)
      bundle.putString(PAYMENT_ORIGIN, origin)
      bundle.putString(PAYMENT_TRANSACTION_TYPE, transactionType)
      bundle.putSerializable(PAYMENT_DATA, data)
      bundle.putString(PAYMENT_CURRENT_CURRENCY, currentCurrency)
      val fragment = PaymentAuthFragment()
      fragment.arguments = bundle
      return fragment
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    keyboardTopUpRelay = PublishRelay.create()

    navigator = PaymentFragmentNavigator((activity as UriNavigator?)!!, topUpView!!)

    presenter =
        PaymentAuthPresenter(this, appPackage, defaultWalletInteract,
            AndroidSchedulers.mainThread(), CompositeDisposable(), adyen,
            billingFactory.getBilling(appPackage), navigator,
            inAppPurchaseInteractor.billingMessagesMapper, inAppPurchaseInteractor)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    credit_card_info_container.visibility = View.INVISIBLE

    button.isEnabled = false

    button.setText(R.string.topup_home_button)

    fragment_braintree_credit_card_form.setOnCardFormValidListener { valid ->
      button.isEnabled = valid
    }
    fragment_braintree_credit_card_form.setOnCardFormSubmitListener {
      if (fragment_braintree_credit_card_form.isValid) {
        keyboardTopUpRelay.accept(true)
        if (getView() != null) {
          KeyboardUtils.hideKeyboard(getView()!!)
        }
      }
    }

    genericErrorDialog = RxAlertDialog.Builder(context).setMessage(R.string.unknown_error)
        .setPositiveButton(R.string.ok)
        .build()
    genericErrorDialog.positiveClicks()
        .subscribe({ navigator.popViewWithError() }, { it.printStackTrace() })

    networkErrorDialog =
        RxAlertDialog.Builder(context).setMessage(R.string.notification_no_network_poa)
            .setPositiveButton(R.string.ok)
            .build()
    networkErrorDialog.positiveClicks()
        .subscribe({ navigator.popViewWithError() }, { it.printStackTrace() })

    paymentRefusedDialog =
        RxAlertDialog.Builder(context).setMessage(R.string.notification_payment_refused)
            .setPositiveButton(R.string.ok)
            .build()
    paymentRefusedDialog.positiveClicks()
        .subscribe({ navigator.popViewWithError() }, { it.printStackTrace() })

    showValues()
    presenter.present(savedInstanceState, origin, data?.currency?.appcValue,
        data?.currency?.fiatCurrencyCode, transactionType, paymentType)
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                            savedInstanceState: Bundle?): View? {
    return inflater.inflate(R.layout.fragment_top_up, container, false)
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)

    presenter.onSaveInstanceState(outState)
  }

  override fun onDestroyView() {
    presenter.stop()
    super.onDestroyView()
  }

  override fun onDetach() {
    super.onDetach()
    topUpView = null
  }

  override fun onAttach(context: Context) {
    super.onAttach(context)
    if (context !is TopUpActivityView) {
      throw IllegalStateException("Regular buy fragment must be attached to IAB activity")
    }
    topUpView = context
  }

  override fun showValues() {
    val formatter = Formatter()
    val appcValue = formatter.format(Locale.getDefault(), "%(,.2f",
        BigDecimal(data?.currency?.appcValue).toDouble()).toString()
    converted_value.text = "$appcValue ${data?.currency?.appcCode}"
    val fiatValue = BigDecimal(data?.currency?.fiatValue).toDouble().toString()
    main_value.setText(fiatValue)
    main_value_currency.text = data?.currency?.fiatCurrencySymbol
    currency_code.text = data?.currency?.fiatCurrencyCode

  }

  override fun showLoading() {
    loading.visibility = View.VISIBLE
    fragment_braintree_credit_card_form.visibility = View.GONE
    credit_card_info_container.visibility = View.INVISIBLE
    button.isEnabled = false
    change_card_button.visibility = View.INVISIBLE
  }

  override fun hideLoading() {
    loading.visibility = View.GONE
    fragment_braintree_credit_card_form.visibility = View.VISIBLE
    credit_card_info_container.visibility = View.VISIBLE
  }

  override fun errorDismisses(): Observable<Any> {
    return Observable.merge<DialogInterface>(networkErrorDialog.dismisses(),
        paymentRefusedDialog.dismisses())
        .map { Any() }
  }

  override fun paymentMethodDetailsEvent(): Observable<PaymentDetails> {
    return Observable.merge(keyboardTopUpRelay, RxView.clicks(button))
        .map { getPaymentDetails(publicKey, generationTime) }
  }

  override fun changeCardMethodDetailsEvent(): Observable<PaymentMethod> {
    return RxView.clicks(change_card_button)
        .map { paymentMethod }
  }

  override fun showNetworkError() {
    if (!networkErrorDialog.isShowing) {
      networkErrorDialog.show()
    }
  }

  override fun showCvcView(paymentMethod: PaymentMethod) {
    cvcOnly = true
    fragment_braintree_credit_card_form.findViewById<View>(
        com.braintreepayments.cardform.R.id.bt_card_form_card_number_icon)
        .visibility = View.GONE
    this.paymentMethod = paymentMethod
    card_details.visibility = View.VISIBLE
    card_details.text = paymentMethod.name
    change_card_button.visibility = View.VISIBLE
    remember_card.visibility = View.GONE
    fragment_braintree_credit_card_form.cardRequired(false)
        .expirationRequired(false)
        .cvvRequired(true)
        .postalCodeRequired(false)
        .mobileNumberRequired(false)
        .actionLabel(getString(R.string.action_buy))
        .setup(activity!!)

    hideLoading()
    finishSetupView()
  }

  override fun showCreditCardView(paymentMethod: PaymentMethod, cvcStatus: Boolean,
                                  allowSave: Boolean, publicKey: String, generationTime: String) {
    this.paymentMethod = paymentMethod
    this.publicKey = publicKey
    this.generationTime = generationTime
    cvcOnly = false
    card_details.visibility = View.GONE
    change_card_button.visibility = View.GONE
    remember_card.visibility = View.VISIBLE
    fragment_braintree_credit_card_form.setCardNumberIcon(0)
    fragment_braintree_credit_card_form.cardRequired(true)
        .expirationRequired(true)
        .cvvRequired(cvcStatus)
        .postalCodeRequired(false)
        .mobileNumberRequired(false)
        .actionLabel(getString(R.string.action_buy))
        .setup(activity!!)

    hideLoading()
    finishSetupView()
  }

  override fun close() {
    topUpView?.close()
  }

  override fun showSuccess() {

  }

  override fun showPaymentRefusedError(adyenAuthorization: AdyenAuthorization) {
    if (!paymentRefusedDialog.isShowing) {
      paymentRefusedDialog.show()
    }
  }

  override fun showGenericError() {
    if (!genericErrorDialog.isShowing) {
      genericErrorDialog.show()
    }
  }

  private fun finishSetupView() {
    fragment_braintree_credit_card_form.findViewById<View>(R.id.bt_card_form_card_number_icon)
        .visibility = View.GONE
    (fragment_braintree_credit_card_form.findViewById<View>(R.id.bt_card_form_card_number)
        .parent
        .parent as TextInputLayout).setPadding(24, 50, 0, 0)
    (fragment_braintree_credit_card_form.findViewById<View>(R.id.bt_card_form_expiration)
        .parent
        .parent
        .parent as LinearLayout).setPadding(24, 0, 0, 0)
  }

  private fun getPaymentDetails(publicKey: String?, generationTime: String?): PaymentDetails {
    if (cvcOnly) {
      val paymentDetails = PaymentDetails(paymentMethod.inputDetails)
      paymentDetails.fill("cardDetails.cvc", fragment_braintree_credit_card_form.cvv)
      return paymentDetails
    }

    val creditCardPaymentDetails = CreditCardPaymentDetails(paymentMethod.inputDetails)
    try {
      val sensitiveData = JSONObject()

      sensitiveData.put("holderName", "Checkout Shopper Placeholder")
      sensitiveData.put("number", fragment_braintree_credit_card_form.cardNumber)
      sensitiveData.put("expiryMonth", fragment_braintree_credit_card_form.expirationMonth)
      sensitiveData.put("expiryYear", fragment_braintree_credit_card_form.expirationYear)
      sensitiveData.put("generationtime", generationTime)
      sensitiveData.put("cvc", fragment_braintree_credit_card_form.cvv)
      creditCardPaymentDetails.fillCardToken(
          ClientSideEncrypter(publicKey).encrypt(sensitiveData.toString()))
    } catch (e: JSONException) {
      Log.e(TAG, "JSON Exception occurred while generating token.", e)
    } catch (e: EncrypterException) {
      Log.e(TAG, "EncrypterException occurred while generating token.", e)
    }

    creditCardPaymentDetails.fillStoreDetails(remember_card.isChecked)
    return creditCardPaymentDetails
  }
}
