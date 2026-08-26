import re

with open('app/src/main/java/com/finmagic/ui/creditcards/CreditCardsScreen.kt', 'r') as f:
    content = f.read()

# Remove the recentPayments section from the top
recent_pattern = re.compile(r'(\s*if \(state\.recentPayments\.isNotEmpty\(\)\) \{\s*item \{\s*SectionHeader\(\s*title = "PAYMENT RECEIVED",.*?\}\s*\})', re.DOTALL)

content = recent_pattern.sub('', content)

# Now find the paidHistory section and replace it with the interleaved logic
history_pattern = re.compile(r'(\s*if \(state\.paidHistory\.isNotEmpty\(\)\) \{\s*item \{\s*SectionHeader\(\s*title = "HISTORY".*?\}\s*\})', re.DOTALL)

interleaved_code = """
                        if (state.paidHistory.isNotEmpty() || state.recentPayments.isNotEmpty()) {
                            item {
                                SectionHeader(
                                    title = "HISTORY & PAYMENTS",
                                    count = state.paidHistory.size + state.recentPayments.size,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            val maxLen = maxOf(state.recentPayments.size, state.paidHistory.size)
                            for (i in 0 until maxLen) {
                                if (i < state.recentPayments.size) {
                                    item(key = "pay_${state.recentPayments[i].id}") {
                                        PaymentReceivedRow(
                                            payment = state.recentPayments[i],
                                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
                                        )
                                    }
                                }
                                if (i < state.paidHistory.size) {
                                    item(key = "history_${state.paidHistory[i].billId}") {
                                        val bill = state.paidHistory[i]
                                        BillCard(
                                            bill = bill,
                                            onMarkPaid = { viewModel.markBillAsPaid(bill.billId) },
                                            onUpdatePayment = { viewModel.updatePartialPayment(bill.billId, it) },
                                            onMarkUnpaid = { viewModel.markBillAsUnpaid(bill.billId) },
                                            onEditBill = { total, min, due ->
                                                viewModel.editBill(bill.billId, total, min, due)
                                            },
                                            onCardClick = { onCardClick(bill.cardId) },
                                            modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp)
                                        )
                                    }
                                }
                            }
                        }
"""

content = history_pattern.sub(interleaved_code, content)

with open('app/src/main/java/com/finmagic/ui/creditcards/CreditCardsScreen.kt', 'w') as f:
    f.write(content)
