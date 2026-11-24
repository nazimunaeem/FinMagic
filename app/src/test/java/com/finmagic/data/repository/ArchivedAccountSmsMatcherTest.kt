package com.finmagic.data.repository

import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.SmsRecord
import com.finmagic.data.sms.parsers.Institutions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchivedAccountSmsMatcherTest {

    @Test
    fun matchesBracBankSmsToArchivedAccountWithoutCollidingWithOtherMask() {
        val bracAccount = Account(
            id = 1L,
            name = "BRAC Bank",
            kind = Account.KIND_BANK,
            bankName = Institutions.BRAC,
            accountMask = "10551**0001",
            isArchived = true
        )
        val matchingSms = SmsRecord(
            id = 10L,
            address = "BRAC-BANK",
            body = "TK 6,000.00 withdrawn from A/C#10551**0001 on 18-05-25 @03:16 PM from OTHER BANK ATM. Balance TK 3,772.78.",
            timestamp = 1000L
        )
        val otherAccountSms = SmsRecord(
            id = 11L,
            address = "BRAC-BANK",
            body = "TK 500.00 credited to A/C#10551**0002 on 19-05-25 @10:00 AM from TEST. Balance TK 500.00.",
            timestamp = 2000L
        )

        val matched = ArchivedAccountSmsMatcher.matchingSms(
            account = bracAccount,
            allSms = listOf(matchingSms, otherAccountSms)
        )
        assertEquals(listOf(matchingSms), matched)
    }

    @Test
    fun matchesPubaliMaskVariantsUsingZeroPaddedTail() {
        val pubaliAccount = Account(
            id = 2L,
            name = "Pubali Bank •• 0934",
            kind = Account.KIND_BANK,
            bankName = Institutions.PUBALI,
            accountMask = "0934",
            isArchived = true
        )
        val smsShort = SmsRecord(
            id = 20L,
            address = "PUBALI BANK",
            body = "BDT 5000 has been debited from your a/c ***934 and credited to TEST USER, CITY BANK on 04-JAN-26 through PI. A/C Bal:14880",
            timestamp = 1000L
        )
        val smsLong = SmsRecord(
            id = 21L,
            address = "PUBALI BANK",
            body = "You deposited BDT 20000.00 through CRM to A/C Number *******240934 on 09-APR-26 .A/C Bal:Cr 57886.35.",
            timestamp = 2000L
        )
        val smsOtherSubAccount = SmsRecord(
            id = 22L,
            address = "PUBALI BANK",
            body = "BDT 1000 has been credited to your account ***463 through MOTIJHEEL CORPORATE branch  on 06-01-2026. A/C Bal: Cr 2000",
            timestamp = 3000L
        )

        val matched = ArchivedAccountSmsMatcher.matchingSms(
            account = pubaliAccount,
            allSms = listOf(smsShort, smsLong, smsOtherSubAccount)
        )
        assertEquals(listOf(smsShort, smsLong), matched)
    }

    @Test
    fun matchesAiblAccountsDistinctlyWhenSameSuffixLengthDiffersInPrefix() {
        val aiblAccount = Account(
            id = 3L,
            name = "AIBL 868",
            kind = Account.KIND_BANK,
            bankName = Institutions.AIBL,
            accountMask = "002131****868",
            isArchived = true
        )
        val ownSms = SmsRecord(
            id = 30L,
            address = "+8801944400032",
            body = "Your A/C 002131****868 has been Debited by BDT 252.20 on 16-APR-26, 09:39:18 PM.Ref: TEST-AIBLPLC",
            timestamp = 1000L
        )
        val siblingSms = SmsRecord(
            id = 31L,
            address = "+8801944400032",
            body = "Your A/C 002132****868 has been Debited by BDT 100.00 on 16-APR-26, 09:39:18 PM.Ref: TEST-AIBLPLC",
            timestamp = 2000L
        )

        assertTrue(ArchivedAccountSmsMatcher.matches(aiblAccount, ownSms))
        assertFalse(ArchivedAccountSmsMatcher.matches(aiblAccount, siblingSms))
    }

    @Test
    fun matchesWalletTxnAndWalletToCashToArchivedWalletAccount() {
        val bkashAccount = Account(
            id = 4L,
            name = "bKash 01712345678",
            kind = Account.KIND_WALLET,
            bankName = Institutions.BKASH,
            accountMask = "01712345678",
            isArchived = true
        )
        val cashInSms = SmsRecord(
            id = 40L,
            address = "bKash",
            body = "Cash In Tk 1,540.00 from 01812345678 successful. Fee Tk 0.00. Balance Tk 1,540.06. TrxID ABC123 at 26/12/2024 11:51",
            timestamp = 1000L
        )
        val cashOutSms = SmsRecord(
            id = 41L,
            address = "bKash",
            body = "Cash Out Tk 500.00 to 01812345678 successful. Fee Tk 9.25. Balance Tk 1,030.81. TrxID DEF456 at 27/12/2024 12:00",
            timestamp = 2000L
        )
        val nagadSms = SmsRecord(
            id = 42L,
            address = "Nagad",
            body = "Money Received. | Amount: Tk 2500.00 | Sender: 01312345678 | Ref: N/A | TxnID: 750TFZM0 | Balance: Tk 2500.69 | 05/03/2026 11:52",
            timestamp = 3000L
        )

        val matched = ArchivedAccountSmsMatcher.matchingSms(
            account = bkashAccount,
            allSms = listOf(cashInSms, cashOutSms, nagadSms)
        )
        assertEquals(listOf(cashInSms, cashOutSms), matched)
    }

    @Test
    fun excludesCardBillPaymentsPosAndNoise() {
        val bracAccount = Account(
            id = 5L,
            name = "BRAC Bank",
            kind = Account.KIND_BANK,
            bankName = Institutions.BRAC,
            accountMask = "10551**0001",
            isArchived = true
        )
        val cardPos = SmsRecord(
            id = 50L,
            address = "BRAC-BANK",
            body = "aTK 200.00 transacted at ROCKET APP OFFUS >DHAKA BD on 02-11-25 @10:06 PM using card#***8044. Balance TK 44,328.83. BRAC Bank.",
            timestamp = 1000L
        )
        val cardTransferPayment = SmsRecord(
            id = 51L,
            address = "BRAC-BANK",
            body = "Tk 7,304.00 has been transferred from your BBL A/C: 1**0001 to Card: 3769-48XX-XXX2-945. Available balance is Tk. 3,787.78.",
            timestamp = 2000L
        )
        val otpNoise = SmsRecord(
            id = 52L,
            address = "BRAC-BANK",
            body = "Your OTP for A/C 10551**0001 is 123456. Do not share with anyone.",
            timestamp = 3000L
        )

        assertFalse(ArchivedAccountSmsMatcher.matches(bracAccount, cardPos))
        assertFalse(ArchivedAccountSmsMatcher.matches(bracAccount, cardTransferPayment))
        assertFalse(ArchivedAccountSmsMatcher.matches(bracAccount, otpNoise))
    }

    @Test
    fun matchesRuleLinkedOrUnparsedAccountSmsAndNeverMatchesCash() {
        val customAccount = Account(
            id = 6L,
            name = "Standard Chartered",
            kind = Account.KIND_BANK,
            bankName = "SCB",
            accountMask = "8899",
            isArchived = true
        )
        val unparsedSms = SmsRecord(
            id = 60L,
            address = "SCB",
            body = "Alert: SCB account **8899 updated with Tk 1,200.00.",
            timestamp = 1000L
        )
        val ruleLinkedSms = SmsRecord(
            id = 61L,
            address = "UNKNOWN",
            body = "Custom taught notice for Tk 999.00",
            timestamp = 2000L
        )
        val cashAccount = Account(
            id = 7L,
            name = "Cash",
            kind = Account.KIND_CASH,
            bankName = "",
            accountMask = Account.CASH_MASK
        )

        assertTrue(ArchivedAccountSmsMatcher.matches(customAccount, unparsedSms))
        assertTrue(ArchivedAccountSmsMatcher.matches(customAccount, ruleLinkedSms, linkedSmsIds = setOf(61L)))
        assertFalse(ArchivedAccountSmsMatcher.matches(cashAccount, unparsedSms))
    }
}
