package com.finmagic.data.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PaymentSmsParserTest {

    @Test
    fun cityBankShortCardForm() {
        val body = "05-DEC-24 \nTk. 3,713.00 payment received \nUS\$. 0.00 payment received \n" +
            "CARD NO: 376***945 \nClient ID: 2233552"
        val payment = PaymentSmsParser.parse(body)
        assertNotNull(payment)
        assertEquals("945", payment!!.cardTail)
        assertEquals(3713.00, payment.amountBdt, 0.001)
        assertEquals(0.0, payment.amountUsd, 0.001)
        assertEquals("05/12/2024", payment.paymentDate)
    }

    @Test
    fun cityBankLongCardFormResolvesSameTail() {
        val body = "02-SEP-25 \n \nTk. 5,000.00 payment received \n \nUS\$. 0.00 payment received \n \n" +
            "CARD NO: 376948*****2945 \n \nClient ID: 2233552"
        val payment = PaymentSmsParser.parse(body)
        assertNotNull(payment)
        // Long mask "…2945" and short mask "…945" must resolve to the same tail "945"
        assertEquals("945", payment!!.cardTail)
        assertEquals(5000.00, payment.amountBdt, 0.001)
        assertEquals("02/09/2025", payment.paymentDate)
    }

    @Test
    fun bracTransferToCardWithoutDate() {
        val body = "Tk 7,304.00 has been transferred from your BBL A/C: 1**0001 to Card: " +
            "3769-48XX-XXX2-945. Available balance is Tk. 3,787.78. Queries: call 16221."
        val payment = PaymentSmsParser.parse(body)
        assertNotNull(payment)
        assertEquals("945", payment!!.cardTail)
        assertEquals(7304.00, payment.amountBdt, 0.001)
        assertNull(payment.paymentDate)
    }

    @Test
    fun usdComponentIsCaptured() {
        val body = "05-JAN-25 \nTk. 0.00 payment received \nUS\$. 42.50 payment received \n" +
            "CARD NO: 423800******5428"
        val payment = PaymentSmsParser.parse(body)
        assertNotNull(payment)
        assertEquals("428", payment!!.cardTail)
        assertEquals(0.0, payment.amountBdt, 0.001)
        assertEquals(42.50, payment.amountUsd, 0.001)
    }

    @Test
    fun billStatementIsNotAPayment() {
        val body = "AMEX Bill Nov'24\nTotal Due\nTk 3712.73\nMin Due\nTk 1398.35\n" +
            "CARD: 376***945\nClient ID: 2233552\nPay by 08-12-24\neStatement:t.ly/NbH5"
        assertNull(PaymentSmsParser.parse(body))
    }

    @Test
    fun otpIsNotAPayment() {
        assertNull(
            PaymentSmsParser.parse(
                "6543 is your one time password (OTP). This OTP is valid for 2 minutes."
            )
        )
    }

    @Test
    fun accountLedgerSmsIsNotAPayment() {
        assertNull(
            PaymentSmsParser.parse(
                "Your BBKSRSL Account 1**88 has been Debited by BDT2,000.00 on " +
                    "25-11-2024 03:14:00PM. Current Balance is BDT29,126.60"
            )
        )
    }

    @Test
    fun paymentDateToEpochHandlesNullAndValid() {
        assertNull(PaymentSmsParser.paymentDateToEpoch(null))
        assertNotNull(PaymentSmsParser.paymentDateToEpoch("05/12/2024"))
    }

    @Test
    fun eblCardPaymentCreditedFormat() {
        val body = "EBL CARDS: BDT 5000 is credited to Card 423800**5428 on 15-JAN-26. Available balance BDT 45000"
        val payment = PaymentSmsParser.parse(body)
        assertNotNull(payment)
        assertEquals("5428", payment!!.cardTail)
        assertEquals(5000.0, payment.amountBdt, 0.001)
        assertEquals("EBL", payment.bankName)
    }

    @Test
    fun eblCardPaymentWithTkCurrency() {
        val body = "EBL CARDS: Tk 2500 is credited to Card 423800**5428 on 01-FEB-26"
        val payment = PaymentSmsParser.parse(body)
        assertNotNull(payment)
        assertEquals("5428", payment!!.cardTail)
        assertEquals(2500.0, payment.amountBdt, 0.001)
        assertEquals("EBL", payment.bankName)
    }

    @Test
    fun bracTransferSetsBankName() {
        val body = "Tk 7,304.00 has been transferred from your BBL A/C: 1**0001 to Card: 3769-48XX-XXX2-945. Available balance is Tk. 3,787.78."
        val payment = PaymentSmsParser.parse(body)
        assertNotNull(payment)
        assertEquals("BRAC Bank", payment!!.bankName)
    }

    @Test
    fun cityPaymentReceivedSetsBankName() {
        val body = "05-DEC-24 \nTk. 3,713.00 payment received \nUS\$. 0.00 payment received \nCARD NO: 376***945 \nClient ID: 2233552"
        val payment = PaymentSmsParser.parse(body)
        assertNotNull(payment)
        assertEquals("City Bank", payment!!.bankName)
    }
}
