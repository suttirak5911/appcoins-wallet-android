package com.asfoundation.wallet.topup

import android.content.Intent
import android.os.Bundle
import androidx.annotation.StringRes
import com.asf.wallet.R
import com.asfoundation.wallet.topup.TopUpActivity.Companion.WALLET_VALIDATION_REQUEST_CODE
import com.asfoundation.wallet.ui.iab.IabActivity
import com.asfoundation.wallet.ui.iab.WebViewActivity
import io.reactivex.Scheduler
import io.reactivex.disposables.CompositeDisposable
import java.util.concurrent.TimeUnit

class TopUpActivityPresenter(private val view: TopUpActivityView,
                             private val topUpInteractor: TopUpInteractor,
                             private val viewScheduler: Scheduler,
                             private val networkScheduler: Scheduler,
                             private val disposables: CompositeDisposable) {
  fun present(isCreating: Boolean) {
    if (isCreating) {
      view.showTopUpScreen()
    }
    handleSupportClicks()
    handleTryAgainClicks()
  }

  private fun handleSupportClicks() {
    disposables.add(view.getSupportClicks()
        .throttleFirst(50, TimeUnit.MILLISECONDS)
        .observeOn(viewScheduler)
        .flatMapCompletable { topUpInteractor.showSupport() }
        .subscribe({}, { handleError(it) })
    )
  }

  private fun handleTryAgainClicks() {
    disposables.add(view.getTryAgainClicks()
        .throttleFirst(50, TimeUnit.MILLISECONDS)
        .observeOn(viewScheduler)
        .doOnNext { view.showTopUpScreen() }
        .subscribe({}, { handleError(it) })
    )
  }

  fun processActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    if (requestCode == TopUpActivity.WEB_VIEW_REQUEST_CODE) {
      if (resultCode == WebViewActivity.SUCCESS && data != null) {
        data.data?.let { view.acceptResult(it) } ?: view.cancelPayment()
      } else if (resultCode == WebViewActivity.FAIL) {
        view.cancelPayment()
      }
    } else if (requestCode == WALLET_VALIDATION_REQUEST_CODE) {
      var errorMessage = data?.getIntExtra(IabActivity.ERROR_MESSAGE, 0)
      if (errorMessage == null || errorMessage == 0) {
        errorMessage = R.string.unknown_error
      }
      handleWalletBlockedCheck(errorMessage)
    }
  }

  private fun handleWalletBlockedCheck(@StringRes error: Int) {
    disposables.add(
        topUpInteractor.isWalletBlocked()
            .subscribeOn(networkScheduler)
            .observeOn(viewScheduler)
            .doOnSuccess {
              view.popBackStack()
              if (it) view.showError(error)
              else view.showTopUpScreen()
            }
            .subscribe({}, { handleError(it) })
    )
  }

  private fun handleError(throwable: Throwable) {
    throwable.printStackTrace()
    view.showError(R.string.unknown_error)
  }

  fun handlePerkNotifications(bundle: Bundle) {
    disposables.add(topUpInteractor.getWalletAddress()
        .subscribeOn(networkScheduler)
        .observeOn(viewScheduler)
        .doOnSuccess {
          view.launchPerkBonusService(it)
          view.finishActivity(bundle)
        }
        .doOnError { view.finishActivity(bundle) }
        .subscribe({}, { it.printStackTrace() }))
  }


  fun handleBackupNotifications(bundle: Bundle) {
    disposables.add(topUpInteractor.incrementAndValidateNotificationNeeded()
        .subscribeOn(networkScheduler)
        .observeOn(viewScheduler)
        .doOnSuccess { notificationNeeded ->
          if (notificationNeeded.isNeeded) {
            view.showBackupNotification(notificationNeeded.walletAddress)
          }
          view.finishActivity(bundle)
        }
        .doOnError { view.finish(bundle) }
        .subscribe({ }, { it.printStackTrace() })
    )
  }
}
