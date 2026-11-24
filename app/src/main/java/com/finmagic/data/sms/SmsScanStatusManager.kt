package com.finmagic.data.sms

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ScanPhase {
    IDLE,
    RECENT_50,
    FULL_AUTO,
    MANUAL_OR_PERIODIC
}

data class SmsScanProgress(
    val isScanning: Boolean = false,
    val phase: ScanPhase = ScanPhase.IDLE,
    val processed: Int = 0,
    val total: Int = 0,
    val parsed: Int = 0,
    val message: String = "",
    val lastCompletedAt: Long = 0L
)

@Singleton
class SmsScanStatusManager @Inject constructor() {
    private val _status = MutableStateFlow(SmsScanProgress())
    val status: StateFlow<SmsScanProgress> = _status.asStateFlow()

    fun startScan(phase: ScanPhase, message: String, total: Int = 0) {
        _status.value = SmsScanProgress(
            isScanning = true,
            phase = phase,
            processed = 0,
            total = total,
            parsed = 0,
            message = message
        )
    }

    fun updateProgress(
        phase: ScanPhase,
        processed: Int,
        total: Int,
        parsed: Int,
        message: String
    ) {
        _status.value = _status.value.copy(
            isScanning = true,
            phase = phase,
            processed = processed,
            total = total,
            parsed = parsed,
            message = message
        )
    }

    fun finishScan(parsedTotal: Int, message: String = "") {
        _status.value = SmsScanProgress(
            isScanning = false,
            phase = ScanPhase.IDLE,
            processed = 0,
            total = 0,
            parsed = parsedTotal,
            message = message,
            lastCompletedAt = System.currentTimeMillis()
        )
    }
}
