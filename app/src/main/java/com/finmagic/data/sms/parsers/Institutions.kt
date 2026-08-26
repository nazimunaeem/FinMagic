package com.finmagic.data.sms.parsers

/**
 * Canonical institution labels used for Account identity (plan §6 alias map).
 * One Account row per (institution, accountMask).
 */
object Institutions {
    const val CITY = "City Bank"
    const val BRAC = "BRAC Bank"
    const val PUBALI = "Pubali Bank"
    const val EBL = "EBL"
    const val DBBL = "Dutch-Bangla (NexusPay)"
    const val IBBL = "Islami Bank"
    const val BBKSRSL = "BBKSRSL Savings"
    const val BKASH = "bKash"
    const val NAGAD = "Nagad"
    const val ROCKET = "Rocket"
    const val AIBL = "Al-Arafah Bank"
    const val UNKNOWN_BANK = "Bank"

    fun accountName(institution: String): String = when (institution) {
        // Displayed without the internal suffix ("BBKSRSL", not "BBKSRSL Savings A/C").
        BBKSRSL -> "BBKSRSL"
        UNKNOWN_BANK -> "Unknown Bank"
        AIBL -> "AIBL"
        DBBL -> "DBBL"
        IBBL -> "IBBL"
        else -> institution
    }
}
