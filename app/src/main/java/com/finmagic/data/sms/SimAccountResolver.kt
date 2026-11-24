package com.finmagic.data.sms

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** SIM identity for a wallet account: slot label ("SIM1") and/or MSISDN ("01712…"). */
data class SimIdentity(
    val label: String,
    val number: String?
)

/**
 * Resolves which physical SIM (SIM1/SIM2) received an SMS so bKash/Nagad/Rocket
 * ledger messages map to at most one wallet account per SIM slot.
 */
@Singleton
class SimAccountResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cache = mutableMapOf<Int, SimIdentity>()

    fun identityFor(subscriptionId: Int?): SimIdentity? {
        if (subscriptionId == null || subscriptionId < 0) return null
        cache[subscriptionId]?.let { return it }

        val identity = SimIdentity(label = "SIM${subscriptionId + 1}", number = null)
        cache[subscriptionId] = identity
        return identity
    }

    companion object {
        /** "+8801712345678"/"88017…" → "01712345678"; keeps anything else verbatim. */
        internal fun normalizeBdNumber(raw: String?): String? {
            val digits = raw?.filter { it.isDigit() } ?: return null
            val local = when {
                digits.startsWith("880") && digits.length >= 13 -> "0" + digits.removePrefix("880")
                digits.startsWith("88") && digits.length >= 12 -> "0" + digits.removePrefix("88")
                digits.startsWith("1") && digits.length == 10 -> "0$digits"
                else -> digits
            }
            // Bangladeshi mobile numbers only; otherwise the number is not usable.
            return if (Regex("^01[3-9]\\d{8}$").matches(local)) local else null
        }
    }
}
