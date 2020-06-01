package com.asfoundation.wallet.ui.backup

import com.asfoundation.wallet.billing.analytics.WalletEventSender
import com.asfoundation.wallet.billing.analytics.WalletsAnalytics
import com.asfoundation.wallet.ui.balance.BalanceInteract
import io.reactivex.Scheduler
import io.reactivex.disposables.CompositeDisposable

class BackupWalletPresenter(private var balanceInteract: BalanceInteract,
                            private var walletEventSender: WalletEventSender,
                            private var view: BackupWalletFragmentView,
                            private var activityView: BackupActivityView,
                            private var disposables: CompositeDisposable,
                            private var dbScheduler: Scheduler,
                            private var viewScheduler: Scheduler) {

  fun present(walletAddress: String) {
    handleBackupClick()
    retrieveStoredBalance(walletAddress)
  }

  private fun handleBackupClick() {
    disposables.add(view.getBackupClick()
        .doOnNext {
          view.hideKeyboard()
          activityView.showBackupCreationScreen(it)
        }
        .doOnNext {
          walletEventSender.sendCreateBackupEvent(WalletsAnalytics.ACTION_CREATE,
              WalletsAnalytics.CONTEXT_SETTINGS, WalletsAnalytics.STATUS_SUCCESS)
        }
        .doOnError { t ->
          walletEventSender.sendCreateBackupEvent(WalletsAnalytics.ACTION_CREATE,
              WalletsAnalytics.CONTEXT_SETTINGS, WalletsAnalytics.STATUS_FAIL, t.message)
        }
        .subscribe())
  }

  private fun retrieveStoredBalance(walletAddress: String) {
    disposables.add(balanceInteract.getStoredOverallBalance(walletAddress)
        .subscribeOn(dbScheduler)
        .observeOn(viewScheduler)
        .map { view.showBalance(it) }
        .subscribe())
  }

  fun stop() = disposables.clear()
}
