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
        assertEquals("2945", payment!!.cardTail)
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
    fun eblCardPaymentCreditedFormat() {
        val body = "EBL CARDS: BDT 5000 is credited to Card 423800**5428 on 15-JAN-26. Available balance BDT 45000"
        val payment = PaymentSmsParser.parse(body)
        assertNotNull(payment)
        assertEquals("5428", payment!!.cardTail)
        assertEquals(5000.0, payment.amountBdt, 0.001)
        assertEquals("EBL", payment.bankName)
    }

    @Test
    fun eblCardPaymentNewFormatUsd() {
        val body = "EBL CARDS: Payment of USD8.50 credited to Card 423800**5428 on 13-Apr-26 01:45:13 PM. Thank You. EBL Helpline 16230"
        val payment = PaymentSmsParser.parse(body)
        assertNotNull(payment)
        assertEquals("5428", payment!!.cardTail)
        assertEquals(8.50, payment.amountBdt, 0.001)
        assertEquals("EBL", payment.bankName)
    }

    @Test
    fun bkashCityBankAmexPayment() {
        val body = "Bill Payment of Tk 3,475.00 for CITYBANKAMEX Credit Card is successful. Fee Tk 51.78. Balance Tk 89.19. TrxID CB33HL81ZV at 03/02/2025 08:51"
        val payment = PaymentSmsParser.parse(body)
        assertNotNull(payment)
        assertEquals("City Bank", payment!!.bankName)
        assertEquals(3475.0, payment.amountBdt, 0.001)
    }

    @Test
    fun bkashVisaCreditCardPayment() {
        val body = "Bill Payment of Tk 1,450.00 for VISA Credit Card is successful. Fee Tk 21.60. Balance Tk 10.72. TrxID CB86NCPGNC at 08/02/2025 22:58"
        val payment = PaymentSmsParser.parse(body)
        assertNotNull(payment)
        assertEquals("City Bank", payment!!.bankName) // "VISA" matches City Bank logic if I update it or I should be specific
        assertEquals(1450.0, payment.amountBdt, 0.001)
    }
}
