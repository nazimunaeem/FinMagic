package com.finmagic.data.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BankSmsParserTest {

    @Test
    fun parsePrimeBankSms() {
        val sms = "Monthly bill Aug-2026 for your Credit Card# *2472 Total due: BDT 1,02,679.23 Minimum due: BDT 5,133.96. Last date of payment 27/08/2026. Client ID: 2630132. Helpline 16218"
        val parsed = BankSmsParser.parse(sms, "PRIMEBANK")

        assertNotNull(parsed)
        assertEquals("Prime Bank", parsed?.bankName)
        assertEquals("2472", parsed?.cardLast4)
        assertEquals(102679.23, parsed?.totalDue ?: 0.0, 0.01)
        assertEquals(5133.96, parsed?.minDue ?: 0.0, 0.01)
        assertEquals("27/08/2026", parsed?.dueDate)
        assertEquals("2630132", parsed?.clientId)
    }

    @Test
    fun parsePubaliBankSms() {
        val sms = "Monthly bill Aug-2026 for your Credit Card# *2472 Total due: BDT 1,02,679.23 Minimum due: BDT 5,133.96. Last date of payment 27/08/2026. Client ID: 2630132. Helpline 16218"
        val parsed = BankSmsParser.parse(sms, "PUBALI BANK")

        assertNotNull(parsed)
        assertEquals("Pubali Bank", parsed?.bankName)
        assertEquals("2472", parsed?.cardLast4)
        assertEquals(102679.23, parsed?.totalDue ?: 0.0, 0.01)
        assertEquals(5133.96, parsed?.minDue ?: 0.0, 0.01)
        assertEquals("27/08/2026", parsed?.dueDate)
        assertEquals("2630132", parsed?.clientId)
    }

    @Test
    fun parsePubaliBankNewFormatSms() {
        val sms = "PBPLC CREDIT CARD *2402,CustID: 30062088 Bill for AUG-26,Total Due: TK 1,986.00,Min Due: TK 1,986.00,Pay By:01-SEP-26.Helpline 16253"
        val parsed = BankSmsParser.parse(sms, "PBPLC")

        assertNotNull(parsed)
        assertEquals("Pubali Bank", parsed?.bankName)
        assertEquals("2402", parsed?.cardLast4)
        assertEquals("30062088", parsed?.clientId)
        assertEquals("August 2026", parsed?.billPeriod)
        assertEquals(1986.0, parsed?.totalDue ?: 0.0, 0.01)
        assertEquals(1986.0, parsed?.minDue ?: 0.0, 0.01)
        assertEquals("01/09/2026", parsed?.dueDate)
    }

    @Test
    fun parseCityBankAmexSms() {
        val sms = "AMEX Bill Jun'25\nTotal Due\nTk 15158.49\nMin Due\nTk 2926.67\nCARD: 376***945\nClient ID: 2233552\nPay by 08-07-25\neStatement:t.ly/NbH5"
        val parsed = BankSmsParser.parse(sms, "01944-400032")

        assertNotNull(parsed)
        assertEquals("City Bank (Amex)", parsed?.bankName)
        assertEquals("945", parsed?.cardLast4)
        assertEquals(15158.49, parsed?.totalDue ?: 0.0, 0.01)
        assertEquals(2926.67, parsed?.minDue ?: 0.0, 0.01)
        assertEquals("08/07/2025", parsed?.dueDate)
        assertEquals("2233552", parsed?.clientId)
    }

    @Test
    fun parseCityBankAmexLongMaskSms() {
        val sms = "AMEX Bill Jun'25\nTotal Due\nTk 15158.49\nMin Due\nTk 2926.67\nCARD: 376948*****2945\nClient ID: 2233552\nPay by 08-07-25"
        val parsed = BankSmsParser.parse(sms, "CITY AMEX")

        assertNotNull(parsed)
        assertEquals("City Bank (Amex)", parsed?.bankName)
        assertEquals("2945", parsed?.cardLast4)
    }

    @Test
    fun parseEblSms() {
        val sms = "Monthly bill 423800****5428 APR2026; Total Due: BDT 8754.72, Min Due: BDT 500.00, Last Pmt: 20-APR-26. Statement link https://onelink.to/eblskybanking"
        val parsed = BankSmsParser.parse(sms, "01944-400032")

        assertNotNull(parsed)
        assertEquals("EBL", parsed?.bankName)
        assertEquals("5428", parsed?.cardLast4)
        assertEquals(8754.72, parsed?.totalDue ?: 0.0, 0.01)
        assertEquals(500.0, parsed?.minDue ?: 0.0, 0.01)
        assertEquals("20/04/2026", parsed?.dueDate)
        assertEquals("BDT", parsed?.currency)
        assertEquals("April 2026", parsed?.billPeriod)
    }

    @Test
    fun parseEblRealUsdSms() {
        val sms = "Monthly bill 423800******5428 NOV2025; Total Due: USD 3.19, Min Due: USD 3.19, Last Pmt: 20-NOV-25. Statement link https://onelink.to/eblskybanking"
        val parsed = BankSmsParser.parse(sms, "01944-400032")

        assertNotNull(parsed)
        assertEquals("EBL", parsed?.bankName)
        assertEquals(3.19, parsed?.totalDue ?: 0.0, 0.01)
        assertEquals("USD", parsed?.currency)
    }

    @Test
    fun identifyCityBankFromSharedNumber() {
        val sms = "AMEX Bill Jun'25\nTotal Due\nTk 15158.49"
        val bankType = BankSmsParser.identifyBank("01944400032", sms)
        assertEquals(BankSmsParser.BankType.CITY_BANK, bankType)
    }
}
