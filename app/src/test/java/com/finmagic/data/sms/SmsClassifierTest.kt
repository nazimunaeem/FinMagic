package com.finmagic.data.sms

import com.finmagic.data.sms.parsers.LedgerDirection
import com.finmagic.data.sms.parsers.SmsClassification
import com.finmagic.data.sms.parsers.TxnCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Corpus-grounded classification tests (plan §1 exact formats).
 */
class SmsClassifierTest {

    private fun classify(sender: String?, body: String) =
        SmsClassifier.classify(sender, body, 0L)

    private fun classify(sender: String?, body: String, subId: Int?) =
        SmsClassifier.classify(sender, body, 0L, subId)

    // ---------------------------------------------------------- bill statements

    @Test
    fun amexBillStatement() {
        val result = classify(
            "CITY_AMEX",
            "AMEX Bill Nov'24 \nTotal Due \nTk 3712.73 \nMin Due \nTk 1398.35 \nCARD: 376***945 \nClient ID: 2233552 \nPay by 08-12-24"
        )
        assertTrue(result is SmsClassification.BillStatement)
        val bill = (result as SmsClassification.BillStatement).bankSms
        assertEquals(3712.73, bill.totalDue, 0.01)
        assertEquals("945", bill.cardLast4)
    }

    @Test
    fun visaBillStatement_gapFixed() {
        val result = classify(
            "CITY AMEX.",
            "VISA Bill Dec'24 \nTotal Due \nTk 43614.61 \nMin Due \nTk 1308.44 \nCARD: 474***267 \nPay by 12-01-25"
        )
        assertTrue(result is SmsClassification.BillStatement)
        assertEquals(43614.61, (result as SmsClassification.BillStatement).bankSms.totalDue, 0.01)
    }

    @Test
    fun eblMonthlyBill() {
        val result = classify(
            "EBL",
            "Monthly bill 423800******5428 MAY2025; Total Due: BDT 50.18, Min Due: BDT 50.18, Last Pmt Date: 20-MAY-25"
        )
        assertTrue(result is SmsClassification.BillStatement)
    }

    @Test
    fun financialLookingUnknownSms_isUnparsedForTeaching() {
        val result = classify("16216", "Your account was updated. Amount Tk 125.00. Balance Tk 500.00.")
        assertTrue(result is SmsClassification.Unparsed)
    }

    @Test
    fun bracCardPurchaseMentioningRocket_isCardTransaction() {
        val result = classify(
            "BRAC-BANK",
            "aTK 200.00 transacted at ROCKET APP OFFUS >DHAKA BD on 02-11-25 @10:06 PM using card#***8044. Balance TK 44,328.83. BRAC Bank."
        )
        assertTrue(result is SmsClassification.CardTxn)
        val txn = result as SmsClassification.CardTxn
        assertEquals("8044", txn.cardTail)
        assertEquals(200.0, txn.amount, 0.01)
        assertEquals("BRAC Bank", txn.bankName)
    }

    @Test
    fun rocketWalletFromSharedSender_usesWalletTemplate() {
        val result = classify(
            "16216",
            "Cash Out Tk 500.00 to 01817030269 successful. Fee Tk 5.00. Balance Tk 35.06. TrxID 8D3E21"
        )
        assertTrue(result is SmsClassification.WalletTxn)
        assertEquals("Rocket", (result as SmsClassification.WalletTxn).entry.institution)
    }

    // ------------------------------------------------------------ card payments

    @Test
    fun cityPaymentReceived_shortCardForm() {
        val result = classify(
            "+8801944400032",
            "05-DEC-24 \nTk. 3,713.00 payment received \nUS$. 0.00 payment received \nCARD NO: 376***945 \nClient ID: 2233552"
        )
        assertTrue(result is SmsClassification.CardPayment)
        val payment = (result as SmsClassification.CardPayment).payment
        assertEquals("945", payment.cardTail)
        assertEquals(3713.00, payment.amountBdt, 0.01)
        assertEquals(0.0, payment.amountUsd, 0.01)
    }

    @Test
    fun cityPaymentReceived_longCardForm() {
        val result = classify(
            "+8801944400032",
            "02-SEP-25 \n\nTk. 5,000.00 payment received \nUS$. 0.00 payment received \nCARD NO: 376948*****2945"
        )
        assertTrue(result is SmsClassification.CardPayment)
        assertEquals("945", (result as SmsClassification.CardPayment).payment.cardTail)
    }

    @Test
    fun bracTransferToCard_isPayment() {
        val result = classify(
            "BRAC-BANK",
            "Tk 7,304.00 has been transferred from your BBL A/C: 1**0001 to Card: 3769-48XX-XXX2-945. Available balance is Tk. 3,787.78."
        )
        assertTrue(result is SmsClassification.CardPayment)
        val payment = (result as SmsClassification.CardPayment).payment
        assertEquals("945", payment.cardTail)
        assertEquals(7304.00, payment.amountBdt, 0.01)
    }

    @Test
    fun bracNewBillFormat() {
        val result = classify(
            "BRAC-BANK",
            "Credit card#**8043 & Card ID: 00792714, Aug 26 bill - Total BDT 179591; Min: BDT 8979.55. pay by: 06-09-26. Statement: https://s.bracbank.com/3HkgOC"
        )
        assertTrue(result is SmsClassification.BillStatement)
        val bill = (result as SmsClassification.BillStatement).bankSms
        assertEquals("8043", bill.cardLast4)
        assertEquals(179591.0, bill.totalDue, 0.01)
        assertEquals(8979.55, bill.minDue, 0.01)
        assertEquals("06/09/2026", bill.dueDate)
    }

    // ------------------------------------------------------------- account txns

    @Test
    fun bbksrslDebit_withRunningBalance() {
        val result = classify(
            "+8801886400500",
            "Your BBKSRSL Account 1**88 has been Debited by BDT2,000.00 on 25-11-2024 03:14:00PM. Current Balance is BDT29,126.60"
        )
        assertTrue(result is SmsClassification.AtmWithdraw)
        val entry = (result as SmsClassification.AtmWithdraw).entry
        assertEquals(LedgerDirection.OUT, entry.direction)
        assertEquals(2000.00, entry.amount, 0.01)
        assertEquals("1**88", entry.accountMask)
        assertEquals(29126.60, entry.balanceAfter!!, 0.01)
        assertNotNull(entry.occurredAt)
    }

    @Test
    fun bracCredit_fromOtherBank() {
        val result = classify(
            "BRAC-BANK",
            "TK 11,091.00 credited to A/C#10551**0001 on 30-04-25 @11:55 PM from OTHER BANK. Balance TK 11,091.78."
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(LedgerDirection.IN, entry.direction)
        assertEquals(11091.00, entry.amount, 0.01)
        assertEquals(11091.78, entry.balanceAfter!!, 0.01)
    }

    @Test
    fun bracAtmWithdrawal_becomesCashTransferSource() {
        val result = classify(
            "BRAC-BANK",
            "TK 6,000.00 withdrawn from A/C#10551**0001 on 18-05-25 @03:16 PM from OTHER BANK ATM. Balance TK 3,772.78."
        )
        assertTrue(result is SmsClassification.AtmWithdraw)
        val entry = (result as SmsClassification.AtmWithdraw).entry
        assertEquals(TxnCategory.ATM, entry.category)
        assertEquals(6000.00, entry.amount, 0.01)
        assertEquals(3772.78, entry.balanceAfter!!, 0.01)
    }

    @Test
    fun nexusPayCbStyle_atmDebit() {
        val result = classify(
            "16216",
            "Dear Sir, your A/C ***3657 debited (ATM Cash Withdrawal) by Tk20,000.00 on 17-07-2025 11:30:28 AM C/B Tk500.95. NexusPay"
        )
        assertTrue(result is SmsClassification.AtmWithdraw)
        val entry = (result as SmsClassification.AtmWithdraw).entry
        assertEquals("***3657", entry.accountMask)
        assertEquals(20000.00, entry.amount, 0.01)
        assertEquals(500.95, entry.balanceAfter!!, 0.01)
    }

    @Test
    fun nexusPayCbStyle_interestCredit() {
        val result = classify(
            "16216",
            "your A/C ***3657 credited (Interest Credit) by Tk1.04 on 01-07-2025 04:42:45 AM C/B Tk501.05"
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(TxnCategory.INTEREST, entry.category)
        assertEquals(LedgerDirection.IN, entry.direction)
    }

    @Test
    fun dpsInstallment() {
        val result = classify(
            "BRAC-BANK",
            "Your DPS installment of TK 1,000.00 has been successfully paid from A/C# 10551**0001. Your A/C balance is TK 48,199.30."
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(TxnCategory.DPS, entry.category)
        assertEquals(LedgerDirection.OUT, entry.direction)
        assertEquals(48199.30, entry.balanceAfter!!, 0.01)
    }

    @Test
    fun ibblTransferWithBalance() {
        val result = classify(
            "IBBL",
            "Dear Customer Tk 1000 has been transferred to A/C777**01530111 Balance Tk.990 Islami Bank"
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(LedgerDirection.OUT, entry.direction)
        assertEquals(1000.00, entry.amount, 0.01)
        assertEquals(990.0, entry.balanceAfter!!, 0.01)
    }

    @Test
    fun ibblPeriodicStatement_refreshesBalanceOnly() {
        val result = classify(
            "IBBL .",
            "Dear Customer, your A/C 777**01530111 periodic statement. Balance Tk 12,345.67 Islami Bank"
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(0.0, entry.amount, 0.001)
        assertEquals(TxnCategory.STATEMENT, entry.category)
        assertEquals(12345.67, entry.balanceAfter!!, 0.01)
    }

    @Test
    fun ibblAtmCredit_newFormat() {
        val result = classify(
            "IBB",
            "Dear Customer ATM Tk 145 has been credited to A/C777**01530111 Balance Tk.2055.24 Islami Bank Help:8331090,16259"
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(LedgerDirection.IN, entry.direction)
        assertEquals(145.0, entry.amount, 0.01)
        assertEquals(2055.24, entry.balanceAfter!!, 0.01)
        assertEquals(TxnCategory.ATM, entry.category)
        assertEquals("ATM Deposit", entry.counterparty)
    }

    // ------------------------------------------------------------------ wallets

    @Test
    fun bkashCashIn() {
        val result = classify(
            "bKash",
            "bKash: Cash In Tk 1,540.00 from 01817030269 successful. Fee Tk 0.00. Balance Tk 1,540.06. TrxID 9F7A6B at 26/12/2024 11:51"
        )
        assertTrue(result is SmsClassification.WalletTxn)
        val entry = (result as SmsClassification.WalletTxn).entry
        assertEquals(LedgerDirection.IN, entry.direction)
        assertEquals(1540.00, entry.amount, 0.01)
        assertEquals(1540.06, entry.balanceAfter!!, 0.01)
    }

    @Test
    fun bkashPayment() {
        val result = classify(
            "bKash",
            "bKash: Payment of Tk 406.00 to Sonali Bank Limited-RM4513 is successful. Balance Tk 79.06. TrxID 8D3E21 at 26/12/2024 12:05"
        )
        assertTrue(result is SmsClassification.WalletTxn)
        val entry = (result as SmsClassification.WalletTxn).entry
        assertEquals(LedgerDirection.OUT, entry.direction)
        assertEquals(79.06, entry.balanceAfter!!, 0.01)
    }

    @Test
    fun nagadMoneyReceived_pipeFormat() {
        val result = classify(
            "NAGAD",
            "Nagad: Money Received. | Amount: Tk 2500.00 | Sender: 01315024062 | Ref: N/A | TxnID: 750TFZM0 | Balance: Tk 2500.69 | 05/03/2026 11:52"
        )
        assertTrue(result is SmsClassification.WalletTxn)
        val entry = (result as SmsClassification.WalletTxn).entry
        assertEquals(LedgerDirection.IN, entry.direction)
        assertEquals(2500.00, entry.amount, 0.01)
        assertEquals(2500.69, entry.balanceAfter!!, 0.01)
    }

    @Test
    fun nagadAddMoneyFromBank_isCredit() {
        val result = classify(
            "NAGAD",
            "Add Money from Bank is Successful. From: Pubali Bank Limited Amount: Tk 50 TxnID: 75V2COIT Balance: Tk 53.79 22/08/2026 17:05"
        )
        assertTrue(result is SmsClassification.WalletTxn)
        val entry = (result as SmsClassification.WalletTxn).entry
        assertEquals(LedgerDirection.IN, entry.direction)
        assertEquals(50.0, entry.amount, 0.01)
        assertEquals(53.79, entry.balanceAfter!!, 0.01)
        assertEquals("Pubali Bank Limited", entry.counterparty)
    }

    @Test
    fun rocketSharedSenderNetBalance_isWalletTransaction() {
        val result = classify(
            "16216",
            "Tk200.00 received from Pubali Bank A/C:***934 Fee:Tk.00 NetBal:Tk206.85 TxnId:6729923361 Date:11-JUL-26 11:05:07 pm."
        )
        assertTrue(result is SmsClassification.WalletTxn)
        val entry = (result as SmsClassification.WalletTxn).entry
        assertEquals("Rocket", entry.institution)
        assertEquals(LedgerDirection.IN, entry.direction)
        assertEquals(200.0, entry.amount, 0.01)
        assertEquals(206.85, entry.balanceAfter!!, 0.01)
    }

    // --------------------------------------------------------- bank → wallet

    @Test
    fun pubaliWalletFundingDebit() {
        val result = classify(
            "PUBALI BANK",
            "Tk 2000 has been debited from your A/C ***934 and credited to Nagad Wallet 01315024062 on 24-FEB-26 through PI Banking. A/C Bal:12711.7"
        )
        // Single debit on the bank account — the recipient wallet must NOT become
        // a tracked account (wallets are SIM-owned).
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(LedgerDirection.OUT, entry.direction)
        assertEquals(TxnCategory.FUND_TRANSFER, entry.category)
        assertEquals(12711.7, entry.balanceAfter!!, 0.01)
        // Mask normalized: "***934" == "***0934" == "*******240934" → "0934".
        assertEquals("0934", entry.accountMask)
        assertTrue(entry.counterparty!!.contains("Nagad"))
        assertTrue(entry.counterparty!!.contains("01315024062"))
    }

    @Test
    fun pubaliEftCredit_longMaskSameAccount() {
        val result = classify(
            "PUBALI BANK",
            "BDT 5000 has been credited to your a/c ***0934 through EFT SONALI BANK,LR,NOT APPLICABLE on 08-JAN-26. A/C Bal:12320.7"
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(LedgerDirection.IN, entry.direction)
        assertEquals("0934", entry.accountMask)
        assertTrue(entry.counterparty!!.contains("SONALI"))
    }

    @Test
    fun pubaliCrmDeposit_fullNumberCollapsesToMainAccount() {
        val result = classify(
            "PUBALI BANK",
            "You deposited BDT 20000.00 through CRM to A/C Number *******240934 on 09-APR-26 .A/C Bal:Cr 57886.35."
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(LedgerDirection.IN, entry.direction)
        assertEquals(TxnCategory.CASH_DEPOSIT, entry.category)
        assertEquals("0934", entry.accountMask)
        assertEquals(57886.35, entry.balanceAfter!!, 0.01)
    }

    @Test
    fun pubaliTbsdBranchDeposit_distinctAccount() {
        val result = classify(
            "PUBALI BANK",
            "BDT 1000 has been credited to your account ***463 through MOTIJHEEL CORPORATE branch  on 06-01-2026. A/C Bal: Cr 2000"
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(TxnCategory.CASH_DEPOSIT, entry.category)
        assertEquals("0463", entry.accountMask)
    }

    @Test
    fun pubaliEmiAccountCredit_distinctAccount() {
        val result = classify(
            "PUBALI BANK",
            "BDT 1554 has been credited to your a/c ***674 from MD NAZIM UDDIN on 29-APR-26 through PI Banking.A/C Bal:-23539.4"
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(LedgerDirection.IN, entry.direction)
        assertEquals(TxnCategory.RECEIVED, entry.category)
        assertEquals("0674", entry.accountMask)
        // Pubali writes the EMI-settlement account balance with a leading hyphen;
        // the negative sign is real and must be preserved.
        assertEquals(-23539.4, entry.balanceAfter!!, 0.01)
    }

    @Test
    fun pubaliDebitCardAtmWithdrawal_primaryAccount() {
        val result = classify(
            "PUBALI BANK",
            "BDT 20000.00 debited for cash withdrawal at ATM PUBALI BANK PLC on 05/MAR/26 at 02:49:58 PM.A/C Bal:BDT Cr 12076.7.Cust ID:30062088.Card*7557."
        )
        assertTrue(result is SmsClassification.AtmWithdraw)
        val entry = (result as SmsClassification.AtmWithdraw).entry
        assertEquals(LedgerDirection.OUT, entry.direction)
        assertEquals(TxnCategory.ATM, entry.category)
        assertEquals("0934", entry.accountMask)
        assertEquals(12076.7, entry.balanceAfter!!, 0.01)
    }

    @Test
    fun pubaliCreditCardPurchase_staysCardTxn() {
        val result = classify(
            "PUBALI BANK",
            "BDT 23839.40 debited for purchase at PICKABOO.COM on 26/APR/26 at 03:34:02 PM.A/C Bal:BDT 224606.6.Cust ID:30062088.Card*2402."
        )
        assertTrue(result is SmsClassification.CardTxn)
        val txn = result as SmsClassification.CardTxn
        assertEquals("2402", txn.cardTail)
        assertEquals(23839.40, txn.amount, 0.01)
    }

    @Test
    fun pubaliTransferToOtherBank_isFundTransferOut() {
        val result = classify(
            "PUBALI BANK",
            "BDT 5000 has been debited from your a/c ***934 and credited to MD NAZIM UDDIN, CITY BANK on 04-JAN-26 through PI. A/C Bal:14880"
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(LedgerDirection.OUT, entry.direction)
        assertEquals(TxnCategory.FUND_TRANSFER, entry.category)
        assertTrue(entry.counterparty!!.contains("CITY BANK"))
    }

    @Test
    fun pubaliMobileRecharge_isPayment() {
        val result = classify(
            "PUBALI BANK",
            "BDT 100 has been debited from your a/c ***934 and credited to MOBILE RECHARGE on 12-JUN-26 through PI Banking.A/C Bal:5321"
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(TxnCategory.PAYMENT, entry.category)
        assertEquals("Mobile Recharge", entry.counterparty)
    }

    @Test
    fun pubaliUtilityBill_isBillPay() {
        val result = classify(
            "PUBALI BANK",
            "BDT 500 has been debited from your a/c ***934 and credited to DPDC Prepaid on 19-AUG-26 through PI Banking.A/C Bal:21749.56"
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(TxnCategory.BILL_PAY, entry.category)
    }

    @Test
    fun pubaliFdFunding_internalTransfer() {
        val result = classify(
            "PUBALI BANK",
            "BDT 50000 has been debited from your A/C ***934 and credited to your FD A/C 0340104152521 on 05-FEB-26 through PI Banking."
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(TxnCategory.FUND_TRANSFER, entry.category)
        assertTrue(entry.counterparty!!.contains("FD A/C"))
    }

    @Test
    fun pubaliInboundFundTransfer_noMaskFallsBackToPrimary() {
        val result = classify(
            "PUBALI BANK",
            "BDT 18980.00 has been credited to your account using FUND TRANSFER from DUTCH-BANGLA BANK LIMITED on 04-JAN-26. A/C Bal:Cr 19880."
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(LedgerDirection.IN, entry.direction)
        assertEquals("0934", entry.accountMask)
        assertEquals(19880.0, entry.balanceAfter!!, 0.01)
    }

    @Test
    fun pubaliReverseTransaction_isCredit() {
        val result = classify(
            "PUBALI BANK",
            "REVERSE TRANSACTION: BDT 460 HAS BEEN CREDITED TO YOUR A/C ***0934 ON 05-MAY-26 THROUGH PI BANKING. A/C BAL:10555.41"
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(LedgerDirection.IN, entry.direction)
        assertEquals("0934", entry.accountMask)
        assertEquals(10555.41, entry.balanceAfter!!, 0.01)
    }

    @Test
    fun pubaliBranchCashWithdrawal_maskWithSpace() {
        val result = classify(
            "PUBALI BANK",
            "BDT 6000 has been debited from your account *** 934 through KALAROA branch  on 10-03-2026. A/C Bal: Cr 32916.35"
        )
        assertTrue(result is SmsClassification.AtmWithdraw)
        val entry = (result as SmsClassification.AtmWithdraw).entry
        assertEquals("0934", entry.accountMask)
        assertEquals(TxnCategory.ATM, entry.category)
    }

    @Test
    fun pubaliInstantEmiNoticeIsNoise() {
        val result = classify(
            "PUBALI BANK",
            "Your PBL Credit Card txn. of TK.     23839.40 at PURCHASE on 29-04-2026 has been converted to InstantEMI. Details: 16253"
        )
        assertTrue(result is SmsClassification.Noise)
    }

    @Test
    fun genericWalletFundingDebit_unknownSender() {
        val result = classify(
            "+8809612345678",
            "BDT 1040 has been debited from your A/C ***934 and credited to Nagad Wallet 01723890379 on 01-AUG-26 through PI Banking. A/C Bal:10788.56"
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertTrue(entry.counterparty!!.contains("01723890379"))
    }

    // -------------------------------------------------------- wallet SIM identity

    @Test
    fun bkashEntryCarriesSubscriptionId() {
        val result = classify(
            "bKash",
            "bKash: Cash In Tk 1,540.00 from 01817030269 successful. Fee Tk 0.00. Balance Tk 1,540.06. TrxID 9F7A6B at 26/12/2024 11:51",
            subId = 5
        )
        assertTrue(result is SmsClassification.WalletTxn)
        assertEquals(5, (result as SmsClassification.WalletTxn).entry.subscriptionId)
    }

    @Test
    fun bkashCashOutTransferCarriesSubscriptionId() {
        val result = classify(
            "bKash",
            "bKash: Cash Out Tk 500.00 to 01817030269 successful. Fee Tk 5.00. Balance Tk 35.06. TrxID 8D3E21 at 26/12/2024 12:05",
            subId = 2
        )
        assertTrue(result is SmsClassification.WalletToCash)
        assertEquals(2, (result as SmsClassification.WalletToCash).from.subscriptionId)
    }

    // -------------------------------------------------------------- card spend

    @Test
    fun cityCardPurchase() {
        val result = classify(
            "+8801944400032",
            "22:17 24-Jan-25 \nTk. 2,127.29 purchased at SPG.COM.BD/CITY/EDUCATION \nCARD NO: 474***267 \nBal Tk. 55,858.10 \nCITY BANK"
        )
        assertTrue(result is SmsClassification.CardTxn)
        val txn = result as SmsClassification.CardTxn
        assertEquals(2127.29, txn.amount, 0.01)
        assertEquals("267", txn.cardTail)
    }

    @Test
    fun transactedAtCardPurchase_generic() {
        val result = classify(
            "BRAC-BANK",
            "TK 200.00 transacted at ROCKET APP OFFUS >DHAKA BD on 02-11-25 @10:06 PM using card#***8044. Balance TK 44,328.83."
        )
        assertTrue(result is SmsClassification.CardTxn)
        val txn = result as SmsClassification.CardTxn
        assertEquals(200.00, txn.amount, 0.01)
        assertEquals("8044", txn.cardTail)
    }

    // -------------------------------------------------------------------- noise

    @Test
    fun otpIsNoise() {
        val result = classify(
            "BRAC-BANK",
            "Your one time password (OTP) is 482913. Do not share with anyone."
        )
        assertTrue(result is SmsClassification.Noise)
    }

    @Test
    fun banglaServiceNoticeIsNoise() {
        val result = classify(
            "EBL",
            "সম্মানিত গ্রাহক, আপনার ATM কার্ড শীঘ্রই প্রদান করা হবে। - EBL"
        )
        assertTrue(result is SmsClassification.Noise)
    }

    @Test
    fun marketingIsNoise() {
        val result = classify(
            "PUBALI CARD",
            "Congratulations! You have won a discount offer on your PUBALI CARD this month. T&C apply."
        )
        assertTrue(result is SmsClassification.Noise)
    }

    // --------------------------------------------------------------------- AIBL

    @Test
    fun aibl_creditByAmount_noCurrencySymbol() {
        val result = classify(
            "+8801944400032",
            "Dear sir, Your  account has been Credited by amount 100000 on Account  002131****868 at date : 19-OCT-25 . Thanks for banking with AIBL."
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(LedgerDirection.IN, entry.direction)
        assertEquals(100000.0, entry.amount, 0.01)
        assertEquals("002131****868", entry.accountMask)
    }

    @Test
    fun aibl_mtdrOpenedNoticeIsNoise() {
        val result = classify(
            "+8801944400032",
            "Dear sir, Your MTDR account (AC No: 002131****857) has been  opened on date :19-OCT-25 . Thanks for banking with AIBL."
        )
        assertTrue(result is SmsClassification.Noise)
    }

    @Test
    fun aibl_profitCredit() {
        val result = classify(
            "+8801944400032",
            "Dear Customer, profit of BDT 2521 has been credited to your account 002131****868. Thank you for banking with us.-AIBPLC."
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(LedgerDirection.IN, entry.direction)
        assertEquals(2521.0, entry.amount, 0.01)
        assertEquals(TxnCategory.INTEREST, entry.category)
    }

    @Test
    fun aibl_debitWithAiblplcSenderVariant() {
        val result = classify(
            "AIBLPLC",
            "Dear Sir/Madam, Your A/C 002131****857 has been Debited by BDT 252.20 on 16-APR-26, 09:39:18 PM.Ref: 26041975246313.-AIBLPLC"
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(LedgerDirection.OUT, entry.direction)
        assertEquals(252.20, entry.amount, 0.01)
        assertNotNull(entry.occurredAt)
    }

    @Test
    fun aibl_creditBodyAliasOnly() {
        val result = classify(
            "+8801944400032",
            "Dear Customer, Your A/C 002131****868 has been Credited by BDT 2,607.00 on 18-JUL-26, 11:22:12 PM.Ref: 260719178625185. AIBPLC"
        )
        assertTrue(result is SmsClassification.AcctTxn)
        val entry = (result as SmsClassification.AcctTxn).entry
        assertEquals(LedgerDirection.IN, entry.direction)
        assertEquals(2607.0, entry.amount, 0.01)
    }

    @Test
    fun eblCardPurchase_isCardTransaction() {
        val result = classify(
            "+8801944400032",
            "EBL CARDS: Purchase txn BDT 1500 from SHADHIPAY on 05-JAN-26 at 10:30 AM. Card 423800**5428. Bal: BDT 25000"
        )
        assertTrue(result is SmsClassification.CardTxn)
        val txn = result as SmsClassification.CardTxn
        assertEquals("EBL", txn.bankName)
        assertEquals(1500.0, txn.amount, 0.01)
        assertEquals("SHADHIPAY", txn.merchant)
    }

    @Test
    fun cityBankVisaBill_isBillStatement() {
        val result = classify(
            "01944-400032",
            "VISA Bill Dec'24\nTotal Due\nTk 43614.61\nMin Due\nTk 1308.44\nCARD: 474***267\nPay by 12-01-25"
        )
        assertTrue(result is SmsClassification.BillStatement)
        val bill = (result as SmsClassification.BillStatement).bankSms
        assertEquals("City Bank (Visa)", bill.bankName)
        assertEquals("267", bill.cardLast4)
        assertEquals(43614.61, bill.totalDue, 0.01)
    }

    @Test
    fun cityBankZeroDueAmexBill_isBillStatement() {
        val result = classify(
            "01944-400032",
            "AMEX Bill Mar'25\nTotal Due\nTk 0.00\nMin Due\nTk 0.00\nCARD: 376***945\nClient ID: 2233552"
        )
        assertTrue(result is SmsClassification.BillStatement)
        val bill = (result as SmsClassification.BillStatement).bankSms
        assertEquals("City Bank (Amex)", bill.bankName)
        assertEquals("945", bill.cardLast4)
        assertEquals(0.0, bill.totalDue, 0.01)
    }

    @Test
    fun eblCardPayment_isCardPayment() {
        val result = classify(
            "+8801944400032",
            "EBL CARDS: BDT 5000 is credited to Card 423800**5428 on 15-JAN-26"
        )
        assertTrue(result is SmsClassification.CardPayment)
        val payment = (result as SmsClassification.CardPayment).payment
        assertEquals("5428", payment.cardTail)
        assertEquals(5000.0, payment.amountBdt, 0.01)
        assertEquals("EBL", payment.bankName)
    }
}
