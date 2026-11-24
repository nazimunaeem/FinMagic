package com.finmagic.ui.home

import androidx.lifecycle.ViewModel
import com.finmagic.data.sms.SmsScanProgress
import com.finmagic.data.sms.SmsScanStatusManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SmsScanViewModel @Inject constructor(
    scanStatusManager: SmsScanStatusManager
) : ViewModel() {

    val scanStatus: StateFlow<SmsScanProgress> = scanStatusManager.status
}
