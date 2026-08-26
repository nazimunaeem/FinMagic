package com.finmagic.data.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** SIM identity for a wallet account: slot label ("SIM1") and/or MSISDN ("01712…"). */
data class SimIdentity(
    val label: String,
    val number: String?
)

/**
 * Resolves which physical SIM (SIM1/SIM2) received an SMS and — when readable —
 * that SIM's phone number, so bKash/Nagad/Rocket ledger messages map to at most
 * one wallet account per SIM (one account per number).
 *
 * Falls back to a slot label when READ_PHONE_STATE is denied or the operator
 * does not report the line number.
 */
@Singleton
class SimAccountResolver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cache = mutableMapOf<Int, SimIdentity>()

    fun identityFor(subscriptionId: Int?): SimIdentity? {
        if (subscriptionId == null || subscriptionId < 0) return null
        cache[subscriptionId]?.let { return it }

        val identity = resolve(subscriptionId) ?: return null
        cache[subscriptionId] = identity
        return identity
    }

    private fun resolve(subscriptionId: Int): SimIdentity? {
        val baseLabel = "SIM${subscriptionId + 1}"
        if (!hasPhoneStatePermission()) return SimIdentity(label = baseLabel, number = null)
        return try {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
                as? SubscriptionManager ?: return SimIdentity(label = baseLabel, number = null)
            val info = sm.getActiveSubscriptionInfo(subscriptionId)
            val slot = (info?.simSlotIndex ?: -1) + 1
            val label = "SIM${if (slot > 0) slot else subscriptionId + 1}"
            val rawNumber = info?.number?.takeIf { it.isNotBlank() }
                ?: telephonyNumber(subscriptionId)
            SimIdentity(label = label, number = normalizeBdNumber(rawNumber))
        } catch (_: SecurityException) {
            SimIdentity(label = baseLabel, number = null)
        } catch (_: Exception) {
            SimIdentity(label = baseLabel, number = null)
        }
    }

    private fun telephonyNumber(subscriptionId: Int): String? = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.createForSubscriptionId(subscriptionId)?.line1Number?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private fun hasPhoneStatePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

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
