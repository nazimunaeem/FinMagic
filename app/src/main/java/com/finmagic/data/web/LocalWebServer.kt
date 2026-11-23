package com.finmagic.data.web

import android.util.Log
import com.finmagic.data.local.PreferenceManager
import com.finmagic.data.local.dao.AccountDao
import com.finmagic.data.local.dao.AccountTransactionDao
import com.finmagic.data.local.dao.BillDao
import com.finmagic.data.local.dao.CardTransactionDao
import com.finmagic.data.local.dao.CreditCardDao
import com.finmagic.data.local.entity.Account
import com.finmagic.data.local.entity.AccountTransaction
import com.finmagic.data.local.entity.Bill
import com.finmagic.data.local.entity.CardTransaction
import com.finmagic.data.local.entity.CreditCard
import com.finmagic.data.repository.AccountRepository
import com.finmagic.data.repository.BillRepository
import com.finmagic.data.repository.CardRepository
import com.finmagic.ui.creditcards.billHistoryTimestamp
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class WebServerState(
    val isRunning: Boolean = false,
    val port: Int = 8080,
    val urls: List<String> = emptyList(),
    val error: String? = null
)

/**
 * Lightweight embedded HTTP server allowing devices on the same Wi-Fi or mobile hotspot
 * to view, input, and edit FinMagic accounts, transactions, transfers, cards, and bills via browser.
 */
@Singleton
class LocalWebServer @Inject constructor(
    private val accountDao: AccountDao,
    private val txnDao: AccountTransactionDao,
    private val creditCardDao: CreditCardDao,
    private val billDao: BillDao,
    private val cardTransactionDao: CardTransactionDao,
    private val accountRepository: AccountRepository,
    private val billRepository: BillRepository,
    private val cardRepository: CardRepository,
    private val preferenceManager: PreferenceManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = GsonBuilder().serializeNulls().create()

    private val _state = MutableStateFlow(WebServerState())
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    fun start(port: Int) {
        val safePort = port.coerceIn(1024, 65535)
        if (_state.value.isRunning && _state.value.port == safePort && serverSocket?.isClosed == false) {
            refreshUrls(safePort)
            return
        }
        stop()
        try {
            val socket = ServerSocket(safePort)
            serverSocket = socket
            val urls = discoverLocalUrls(safePort)
            _state.value = WebServerState(
                isRunning = true,
                port = safePort,
                urls = urls,
                error = null
            )
            acceptJob = scope.launch {
                while (isActive && !socket.isClosed) {
                    try {
                        val client = socket.accept()
                        launch { handleClient(client) }
                    } catch (_: SocketException) {
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Accept error", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start web server on port $safePort", e)
            _state.value = WebServerState(
                isRunning = false,
                port = safePort,
                urls = emptyList(),
                error = e.message ?: "Unable to start server on port $safePort"
            )
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        _state.value = _state.value.copy(isRunning = false, urls = emptyList())
    }

    fun refreshUrls(port: Int = _state.value.port) {
        if (_state.value.isRunning) {
            _state.value = _state.value.copy(urls = discoverLocalUrls(port))
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 15_000
            val input = client.getInputStream()
            val rawRequest = readHttpRequest(input) ?: return
            val method = rawRequest.method.uppercase()
            val path = rawRequest.path.substringBefore('?')

            val response: HttpResponse = try {
                if (method == "OPTIONS") {
                    HttpResponse(204, "text/plain", "")
                } else {
                    routeRequest(method, path, rawRequest.body)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Request error: $method $path", e)
                jsonError(500, e.message ?: "Internal server error")
            }

            val bodyBytes = response.body.toByteArray(StandardCharsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 ").append(response.status).append(' ').append(statusText(response.status)).append("\r\n")
                append("Content-Type: ").append(response.contentType).append("; charset=utf-8\r\n")
                append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                append("Access-Control-Allow-Origin: *\r\n")
                append("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Content-Type\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = client.getOutputStream()
            out.write(headers.toByteArray(StandardCharsets.US_ASCII))
            out.write(bodyBytes)
            out.flush()
        }
    }

    private suspend fun routeRequest(method: String, path: String, body: String): HttpResponse {
        return when {
            method == "GET" && (path == "/" || path == "/index.html") -> {
                HttpResponse(200, "text/html", buildPortalHtml())
            }
            method == "GET" && path == "/api/overview" -> {
                jsonOk(buildOverviewPayload())
            }
            method == "POST" && path == "/api/accounts" -> {
                val obj = JsonParser.parseString(body).asJsonObject
                val name = obj.get("name")?.asString?.trim().orEmpty()
                val accountType = obj.get("accountType")?.asString?.trim().orEmpty().ifBlank { Account.TYPE_SAVINGS }
                val isLiability = obj.get("isLiability")?.asBoolean ?: false
                val bankName = obj.get("bankName")?.asString?.trim().orEmpty()
                val accountMask = obj.get("accountMask")?.asString?.trim().orEmpty()
                val currency = obj.get("currency")?.asString?.trim().orEmpty().ifBlank { "BDT" }
                val initialBalance = obj.get("initialBalance")?.asDouble ?: 0.0
                val includeInTotal = obj.get("includeInTotal")?.asBoolean ?: true
                if (name.isBlank()) return jsonError(400, "Account name is required")
                val id = accountRepository.createManualAccount(
                    name = name,
                    accountType = accountType,
                    isLiability = isLiability,
                    bankName = bankName,
                    accountMask = accountMask,
                    currency = currency,
                    initialBalance = initialBalance,
                    includeInTotal = includeInTotal
                ) ?: return jsonError(400, "Could not create account (duplicate mask or invalid input)")
                jsonOk(mapOf("ok" to true, "id" to id))
            }
            method == "PUT" && path.startsWith("/api/accounts/") -> {
                val id = path.removePrefix("/api/accounts/").toLongOrNull()
                    ?: return jsonError(400, "Invalid account ID")
                val obj = JsonParser.parseString(body).asJsonObject
                val existing = accountRepository.getAccountOnce(id) ?: return jsonError(404, "Account not found")
                val name = obj.get("name")?.asString?.trim()?.takeIf { it.isNotBlank() } ?: existing.name
                val accountType = obj.get("accountType")?.asString?.trim()?.takeIf { it.isNotBlank() } ?: existing.effectiveType
                val isLiability = obj.get("isLiability")?.asBoolean ?: existing.isLiability
                val includeInTotal = obj.get("includeInTotal")?.asBoolean ?: existing.includeInTotal
                accountRepository.updateAccountDetails(id, name, accountType, isLiability, includeInTotal)
                if (obj.has("targetBalance") && !obj.get("targetBalance").isJsonNull) {
                    val targetBalance = obj.get("targetBalance").asDouble
                    accountRepository.setCashBalanceDirect(targetBalance, id)
                }
                jsonOk(mapOf("ok" to true))
            }
            method == "DELETE" && path.startsWith("/api/accounts/") -> {
                val id = path.removePrefix("/api/accounts/").toLongOrNull()
                    ?: return jsonError(400, "Invalid account ID")
                val deleted = accountRepository.deleteAccount(id)
                if (!deleted) return jsonError(400, "Cannot delete built-in Cash or missing account")
                jsonOk(mapOf("ok" to true))
            }
            method == "POST" && path == "/api/account-types" -> {
                val obj = JsonParser.parseString(body).asJsonObject
                val name = obj.get("name")?.asString?.trim().orEmpty()
                val isLiability = obj.get("isLiability")?.asBoolean ?: false
                if (name.isBlank()) return jsonError(400, "Type name is required")
                preferenceManager.addCustomAccountType(name, isLiability)
                jsonOk(mapOf("ok" to true))
            }
            method == "DELETE" && path.startsWith("/api/account-types/") -> {
                val name = java.net.URLDecoder.decode(path.removePrefix("/api/account-types/"), "UTF-8")
                preferenceManager.removeCustomAccountType(name)
                jsonOk(mapOf("ok" to true))
            }
            method == "POST" && path == "/api/transactions" -> {
                val obj = JsonParser.parseString(body).asJsonObject
                val accountId = obj.get("accountId")?.asLong ?: return jsonError(400, "accountId is required")
                val direction = obj.get("direction")?.asString?.uppercase()?.takeIf {
                    it == AccountTransaction.DIRECTION_IN || it == AccountTransaction.DIRECTION_OUT
                } ?: AccountTransaction.DIRECTION_OUT
                val amount = obj.get("amount")?.asDouble ?: 0.0
                if (amount <= 0.0) return jsonError(400, "Amount must be positive")
                val category = obj.get("category")?.asString?.trim().orEmpty().ifBlank { "Manual" }
                val counterparty = obj.get("counterparty")?.asString?.trim()?.takeIf { it.isNotBlank() }
                val occurredAt = obj.get("occurredAt")?.takeIf { !it.isJsonNull }?.asLong ?: System.currentTimeMillis()
                val id = accountRepository.storeManualToAccount(
                    accountId = accountId,
                    direction = direction,
                    amount = amount,
                    occurredAt = occurredAt,
                    category = category,
                    counterparty = counterparty,
                    smsBody = "web:${UUID.randomUUID()}",
                    smsRecordId = null
                ) ?: return jsonError(400, "Failed to insert transaction")
                jsonOk(mapOf("ok" to true, "id" to id))
            }
            method == "POST" && path == "/api/transfers" -> {
                val obj = JsonParser.parseString(body).asJsonObject
                val fromId = obj.get("fromAccountId")?.asLong ?: return jsonError(400, "fromAccountId is required")
                val toId = obj.get("toAccountId")?.asLong ?: return jsonError(400, "toAccountId is required")
                val amount = obj.get("amount")?.asDouble ?: 0.0
                val occurredAt = obj.get("occurredAt")?.takeIf { !it.isJsonNull }?.asLong ?: System.currentTimeMillis()
                if (fromId == toId) return jsonError(400, "Source and destination accounts must differ")
                if (amount <= 0.0) return jsonError(400, "Amount must be positive")
                val ok = accountRepository.transferBetweenAccounts(fromId, toId, amount, occurredAt)
                if (!ok) return jsonError(400, "Could not complete transfer")
                jsonOk(mapOf("ok" to true))
            }
            method == "PUT" && path.startsWith("/api/transactions/") -> {
                val id = path.removePrefix("/api/transactions/").toLongOrNull()
                    ?: return jsonError(400, "Invalid transaction ID")
                val obj = JsonParser.parseString(body).asJsonObject
                val counterparty = obj.get("counterparty")?.asString?.trim()
                val category = obj.get("category")?.asString?.trim()
                val amount = obj.get("amount")?.asDouble ?: 0.0
                val ok = accountRepository.updateTransactionDetails(id, counterparty, category, amount)
                if (!ok) return jsonError(400, "Failed to update transaction")
                jsonOk(mapOf("ok" to true))
            }
            method == "DELETE" && path.startsWith("/api/transactions/") -> {
                val id = path.removePrefix("/api/transactions/").toLongOrNull()
                    ?: return jsonError(400, "Invalid transaction ID")
                val ok = accountRepository.deleteEntry(id)
                if (!ok) return jsonError(404, "Transaction not found")
                jsonOk(mapOf("ok" to true))
            }
            method == "POST" && path == "/api/cards" -> {
                val obj = JsonParser.parseString(body).asJsonObject
                val bankName = obj.get("bankName")?.asString?.trim().orEmpty()
                val last4 = obj.get("cardLast4")?.asString?.filter { it.isDigit() }?.takeLast(4).orEmpty()
                val nickname = obj.get("cardNickname")?.asString?.trim().orEmpty()
                val creditLimit = obj.get("creditLimit")?.asDouble ?: 0.0
                val currency = obj.get("currency")?.asString?.trim().orEmpty().ifBlank { "BDT" }
                if (bankName.isBlank() || last4.length != 4) {
                    return jsonError(400, "Bank name and 4 card digits are required")
                }
                val id = cardRepository.addCard(
                    CreditCard(
                        bankName = bankName,
                        cardLast4 = last4,
                        cardNickname = nickname,
                        creditLimit = creditLimit,
                        creditLimitCurrency = currency
                    )
                )
                jsonOk(mapOf("ok" to true, "id" to id))
            }
            method == "POST" && path == "/api/cards/transactions" -> {
                val obj = JsonParser.parseString(body).asJsonObject
                val cardId = obj.get("cardId")?.asLong ?: return jsonError(400, "cardId is required")
                val card = cardRepository.getCardById(cardId) ?: return jsonError(404, "Card not found")
                val merchant = obj.get("merchant")?.asString?.trim().orEmpty().ifBlank { "Card Spend" }
                val amount = obj.get("amount")?.asDouble ?: 0.0
                if (amount <= 0.0) return jsonError(400, "Amount must be positive")
                val category = obj.get("category")?.asString?.trim()?.takeIf { it.isNotBlank() }
                val occurredAt = obj.get("occurredAt")?.takeIf { !it.isJsonNull }?.asLong ?: System.currentTimeMillis()
                val id = cardTransactionDao.insert(
                    CardTransaction(
                        cardTail = card.cardLast4,
                        bankName = card.bankName,
                        merchant = merchant,
                        amount = amount,
                        occurredAt = occurredAt,
                        category = category,
                        smsBody = "manual:${UUID.randomUUID()}"
                    )
                )
                jsonOk(mapOf("ok" to true, "id" to id))
            }
            method == "DELETE" && path.startsWith("/api/cards/transactions/") -> {
                val id = path.removePrefix("/api/cards/transactions/").toLongOrNull()
                    ?: return jsonError(400, "Invalid card transaction ID")
                cardTransactionDao.deleteById(id)
                jsonOk(mapOf("ok" to true))
            }
            method == "POST" && path == "/api/bills" -> {
                val obj = JsonParser.parseString(body).asJsonObject
                val cardId = obj.get("cardId")?.asLong ?: return jsonError(400, "cardId is required")
                val period = obj.get("billPeriod")?.asString?.trim().orEmpty().ifBlank { "Current" }
                val totalDue = obj.get("totalDue")?.asDouble ?: 0.0
                val minDue = obj.get("minDue")?.asDouble ?: 0.0
                val dueDate = obj.get("dueDate")?.asString?.trim().orEmpty()
                val currency = obj.get("currency")?.asString?.trim().orEmpty().ifBlank { "BDT" }
                if (totalDue <= 0.0 || dueDate.isBlank()) {
                    return jsonError(400, "totalDue > 0 and dueDate (dd/MM/yyyy) are required")
                }
                val id = billRepository.addBill(
                    Bill(
                        cardId = cardId,
                        billPeriod = period,
                        totalDue = totalDue,
                        minDue = minDue,
                        dueDate = dueDate,
                        currency = currency
                    )
                )
                jsonOk(mapOf("ok" to true, "id" to id))
            }
            method == "POST" && path.matches(Regex("^/api/bills/\\d+/pay$")) -> {
                val id = path.removePrefix("/api/bills/").removeSuffix("/pay").toLongOrNull()
                    ?: return jsonError(400, "Invalid bill ID")
                val obj = if (body.isBlank()) null else JsonParser.parseString(body).asJsonObject
                val amount = obj?.get("amount")?.takeIf { !it.isJsonNull }?.asDouble
                if (amount != null && amount >= 0.0) {
                    billRepository.updatePaidAmount(id, amount)
                } else {
                    billRepository.markAsPaid(id)
                }
                jsonOk(mapOf("ok" to true))
            }
            method == "POST" && path.matches(Regex("^/api/bills/\\d+/unpay$")) -> {
                val id = path.removePrefix("/api/bills/").removeSuffix("/unpay").toLongOrNull()
                    ?: return jsonError(400, "Invalid bill ID")
                billRepository.markAsUnpaid(id)
                jsonOk(mapOf("ok" to true))
            }
            method == "PUT" && path.startsWith("/api/bills/") -> {
                val id = path.removePrefix("/api/bills/").toLongOrNull()
                    ?: return jsonError(400, "Invalid bill ID")
                val obj = JsonParser.parseString(body).asJsonObject
                val totalDue = obj.get("totalDue")?.asDouble ?: return jsonError(400, "totalDue required")
                val minDue = obj.get("minDue")?.asDouble ?: 0.0
                val dueDate = obj.get("dueDate")?.asString?.trim().orEmpty()
                if (dueDate.isBlank()) return jsonError(400, "dueDate required")
                billRepository.updateBillDetails(id, totalDue, minDue, dueDate)
                jsonOk(mapOf("ok" to true))
            }
            else -> jsonError(404, "Endpoint not found")
        }
    }

    private suspend fun buildOverviewPayload(): Map<String, Any?> {
        val accounts = accountDao.getAllOnce()
        val accountTypes = preferenceManager.allAccountTypes.first()
        val customTypes = preferenceManager.customAccountTypes.first().map { it.name }.toSet()
        val cards = creditCardDao.getAllCardsOnce()
        val bills = billDao.getAllBillsWithCards().first()
        val txns = txnDao.observeAllWithAccount().first().take(300)
        val allTxnsByAccount = txnDao.observeAllIncludingDirectSet().first().groupBy { it.accountId }
        val cardTxns = cardTransactionDao.observeAll().first().take(200)
        val expenseCategories = preferenceManager.cashExpenseCategories.first()
        val incomeCategories = preferenceManager.cashIncomeCategories.first()

        val trailsByAccount = accounts.associate { acct ->
            val acctTxns = allTxnsByAccount[acct.id].orEmpty()
            acct.id to AccountRepository.computeBalanceTrails(acctTxns)
        }

        val accountDtos = accounts.map { acct ->
            val acctTxns = allTxnsByAccount[acct.id].orEmpty()
            val bal = if (acct.kind == Account.KIND_CASH) {
                AccountRepository.effectiveBalance(acctTxns)
            } else {
                AccountRepository.computeAccountBalance(acctTxns)
            }
            mapOf(
                "id" to acct.id,
                "name" to acct.displayName,
                "kind" to acct.kind,
                "bankName" to acct.bankName,
                "accountMask" to acct.accountMask,
                "currency" to acct.currency,
                "accountType" to acct.effectiveType,
                "isLiability" to acct.isLiability,
                "includeInTotal" to acct.includeInTotal,
                "balance" to bal
            )
        }

        val unpaidBills = bills.filter { !it.isPaid && !it.isSuperseded }
            .sortedBy { billHistoryTimestamp(it) }
        val paidBills = bills.filter { !it.isSuperseded && it.isPaid }
            .sortedByDescending { billHistoryTimestamp(it) }
        val cardLiabilityTotal = unpaidBills.sumOf { maxOf(0.0, it.totalDue - it.paidAmount) }
        val assetsTotal = accountDtos.filter {
            (it["includeInTotal"] as Boolean) && !(it["isLiability"] as Boolean)
        }.sumOf { it["balance"] as Double }
        val accountLiabilitiesTotal = accountDtos.filter {
            (it["includeInTotal"] as Boolean) && (it["isLiability"] as Boolean)
        }.sumOf { kotlin.math.abs(it["balance"] as Double) }
        val netWorth = assetsTotal - accountLiabilitiesTotal - cardLiabilityTotal

        val cardDtos = cards.map { c ->
            val cardUnpaid = unpaidBills.filter { it.cardId == c.id }
                .sumOf { maxOf(0.0, it.totalDue - it.paidAmount) }
            val cardUnpaidCount = unpaidBills.count { it.cardId == c.id }
            mapOf(
                "id" to c.id,
                "displayName" to c.displayName,
                "bankName" to c.bankName,
                "cardLast4" to c.cardLast4,
                "cardNickname" to c.cardNickname,
                "creditLimit" to c.creditLimit,
                "currency" to c.creditLimitCurrency,
                "outstanding" to cardUnpaid,
                "unpaidCount" to cardUnpaidCount
            )
        }

        val billDtos = (unpaidBills + paidBills).map { b ->
            mapOf(
                "billId" to b.billId,
                "cardId" to b.cardId,
                "displayName" to b.displayName,
                "billPeriod" to b.billPeriod,
                "totalDue" to b.totalDue,
                "minDue" to b.minDue,
                "paidAmount" to b.paidAmount,
                "outstanding" to maxOf(0.0, b.totalDue - b.paidAmount),
                "dueDate" to b.dueDate,
                "formattedDueDate" to b.formattedDueDate,
                "dueStatus" to b.dueStatus.name,
                "isPaid" to b.isPaid,
                "currency" to b.currency,
                "sortTs" to billHistoryTimestamp(b)
            )
        }

        val txnDtos = txns.map { t ->
            val trail = trailsByAccount[t.accountId]?.get(t.id)
            mapOf(
                "id" to t.id,
                "accountId" to t.accountId,
                "accountName" to t.accountName,
                "direction" to t.direction,
                "amount" to t.amount,
                "currency" to t.currency,
                "occurredAt" to t.occurredAt,
                "category" to t.category,
                "counterparty" to t.counterparty,
                "isManual" to t.isManual,
                "beforeBalance" to trail?.before,
                "afterBalance" to trail?.after
            )
        }

        val cardTxnDtos = cardTxns.map { ct ->
            mapOf(
                "id" to ct.id,
                "cardTail" to ct.cardTail,
                "bankName" to ct.bankName,
                "merchant" to ct.merchant,
                "amount" to ct.amount,
                "occurredAt" to ct.occurredAt,
                "category" to ct.category
            )
        }

        return mapOf(
            "summary" to mapOf(
                "netWorth" to netWorth,
                "assetsTotal" to assetsTotal,
                "accountLiabilitiesTotal" to accountLiabilitiesTotal,
                "cardLiabilityTotal" to cardLiabilityTotal,
                "unpaidBillCount" to unpaidBills.size
            ),
            "accountTypes" to accountTypes.map {
                mapOf(
                    "name" to it.name,
                    "isLiability" to it.isLiability,
                    "isCustom" to (it.name in customTypes)
                )
            },
            "accounts" to accountDtos,
            "cards" to cardDtos,
            "bills" to billDtos,
            "transactions" to txnDtos,
            "cardTransactions" to cardTxnDtos,
            "expenseCategories" to expenseCategories,
            "incomeCategories" to incomeCategories
        )
    }

    private fun jsonOk(payload: Any): HttpResponse =
        HttpResponse(200, "application/json", gson.toJson(payload))

    private fun jsonError(status: Int, message: String): HttpResponse =
        HttpResponse(status, "application/json", gson.toJson(mapOf("ok" to false, "error" to message)))

    private data class HttpResponse(val status: Int, val contentType: String, val body: String)
    private data class RawHttpRequest(val method: String, val path: String, val body: String)

    private fun readHttpRequest(input: InputStream): RawHttpRequest? {
        val headerStream = ByteArrayOutputStream()
        var prev1 = -1
        var prev2 = -1
        var prev3 = -1
        var b: Int
        while (true) {
            b = input.read()
            if (b == -1) {
                if (headerStream.size() == 0) return null
                break
            }
            headerStream.write(b)
            if (prev3 == '\r'.code && prev2 == '\n'.code && prev1 == '\r'.code && b == '\n'.code) {
                break
            }
            if (headerStream.size() > 32 * 1024) return null
            prev3 = prev2
            prev2 = prev1
            prev1 = b
        }

        val headerText = headerStream.toString(StandardCharsets.ISO_8859_1.name())
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val requestLine = lines[0].split(" ")
        if (requestLine.size < 2) return null

        var contentLength = 0
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: 0
            }
        }

        val bodyBytes = if (contentLength in 1..(2 * 1024 * 1024)) {
            val buf = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val read = input.read(buf, offset, contentLength - offset)
                if (read <= 0) break
                offset += read
            }
            String(buf, 0, offset, StandardCharsets.UTF_8)
        } else ""

        return RawHttpRequest(requestLine[0], requestLine[1], bodyBytes)
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        404 -> "Not Found"
        else -> "Internal Server Error"
    }

    companion object {
        private const val TAG = "LocalWebServer"

        fun discoverLocalUrls(port: Int): List<String> {
            val addresses = mutableListOf<Pair<Int, String>>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
                for (iface in interfaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    val name = iface.name.lowercase()
                    val priority = when {
                        name.startsWith("wlan") -> 0
                        name.startsWith("ap") || name.startsWith("swlan") -> 1
                        name.startsWith("rndis") || name.startsWith("usb") -> 2
                        name.startsWith("eth") -> 3
                        else -> 4
                    }
                    for (addr in iface.inetAddresses) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress ?: continue
                            if (!host.startsWith("127.")) {
                                addresses.add(priority to "http://$host:$port")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enumerate network interfaces", e)
            }
            return addresses.sortedBy { it.first }.map { it.second }.distinct()
        }
    }

    private fun buildPortalHtml(): String = PORTAL_HTML
}

private val PORTAL_HTML: String = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1" />
  <title>FinMagic</title>
  <style>
    :root {
      --bg:#f8fafc; --surface:#ffffff; --surface-2:#f1f5f9;
      --text:#0f172a; --muted:#64748b; --border:#e2e8f0;
      --primary:#0f766e; --primary-soft:#ccfbf1; --primary-fg:#ffffff;
      --danger:#dc2626; --danger-soft:#fee2e2;
      --ok:#16a34a; --ok-soft:#dcfce7;
      --warn:#d97706; --warn-soft:#fef3c7;
      --shadow:0 1px 2px rgba(15,23,42,.06);
    }
    html.dark {
      --bg:#0b1120; --surface:#111827; --surface-2:#1e293b;
      --text:#f1f5f9; --muted:#94a3b8; --border:#273244;
      --primary:#2dd4bf; --primary-soft:rgba(45,212,191,.16); --primary-fg:#042f2e;
      --danger:#f87171; --danger-soft:rgba(248,113,113,.16);
      --ok:#4ade80; --ok-soft:rgba(74,222,128,.16);
      --warn:#fbbf24; --warn-soft:rgba(251,191,36,.16);
      --shadow:0 1px 3px rgba(0,0,0,.35);
    }
    * { box-sizing:border-box; }
    body { margin:0; font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif; background:var(--bg); color:var(--text); line-height:1.45; }
    header { position:sticky; top:0; z-index:20; background:var(--surface); border-bottom:1px solid var(--border); padding:10px 16px; }
    .head-inner { max-width:1080px; margin:0 auto; display:flex; align-items:center; justify-content:space-between; gap:10px; flex-wrap:wrap; }
    .brand { display:flex; align-items:center; gap:10px; }
    .logo { width:32px; height:32px; border-radius:9px; background:var(--primary); color:var(--primary-fg); display:grid; place-items:center; font-weight:800; font-size:16px; }
    .brand-title { font-weight:700; font-size:17px; letter-spacing:-.01em; }
    .brand-sub { font-size:12px; color:var(--muted); }
    .nav { display:flex; align-items:center; gap:6px; flex-wrap:wrap; }
    .tab { padding:7px 13px; border-radius:9px; border:1px solid transparent; background:transparent; color:var(--muted); cursor:pointer; font-weight:600; font-size:14px; }
    .tab:hover { background:var(--surface-2); color:var(--text); }
    .tab.active { background:var(--primary); color:var(--primary-fg); }
    .icon-btn { padding:7px 10px; border-radius:9px; border:1px solid var(--border); background:var(--surface); color:var(--text); cursor:pointer; font-size:13px; font-weight:600; }
    .icon-btn:hover:not(:disabled) { background:var(--surface-2); }
    .icon-btn:disabled { opacity:.4; cursor:not-allowed; }
    .stepper { display:inline-flex; align-items:center; gap:4px; }
    .pager { display:flex; align-items:center; justify-content:space-between; gap:8px; padding-top:10px; margin-top:6px; border-top:1px solid var(--border); flex-wrap:wrap; }

    main { max-width:1080px; margin:0 auto; padding:14px 14px 48px; display:grid; gap:12px; }
    .kpis { display:grid; grid-template-columns:repeat(auto-fit,minmax(200px,1fr)); gap:10px; }
    .kpi { background:var(--surface); border:1px solid var(--border); border-radius:14px; padding:14px; box-shadow:var(--shadow); }
    .kpi .lbl { font-size:12px; color:var(--muted); font-weight:600; text-transform:uppercase; letter-spacing:.04em; }
    .kpi .num { font-size:24px; font-weight:800; margin-top:4px; letter-spacing:-.02em; }

    .card { background:var(--surface); border:1px solid var(--border); border-radius:14px; padding:14px; box-shadow:var(--shadow); }
    .card-head { display:flex; align-items:center; justify-content:space-between; gap:8px; flex-wrap:wrap; margin-bottom:10px; }
    .card-title { margin:0; font-size:16px; font-weight:700; }
    .muted { color:var(--muted); font-size:13px; }
    .ok { color:var(--ok); }
    .danger { color:var(--danger); }

    .seg { display:inline-flex; background:var(--surface-2); padding:3px; border-radius:10px; border:1px solid var(--border); gap:2px; }
    .seg button { border:none; background:transparent; color:var(--muted); padding:6px 12px; border-radius:7px; font-weight:600; font-size:13px; cursor:pointer; }
    .seg button.active { background:var(--surface); color:var(--text); box-shadow:var(--shadow); }

    .form-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(145px,1fr)); gap:8px; align-items:end; }
    .field { display:flex; flex-direction:column; gap:4px; }
    .field label { font-size:12px; font-weight:600; color:var(--muted); }
    input, select { width:100%; padding:9px 11px; border-radius:9px; border:1px solid var(--border); background:var(--bg); color:var(--text); font-size:14px; outline:none; }
    input:focus, select:focus { border-color:var(--primary); }

    .btn { padding:9px 14px; border-radius:9px; border:none; font-weight:600; font-size:14px; cursor:pointer; background:var(--primary); color:var(--primary-fg); display:inline-flex; align-items:center; justify-content:center; gap:6px; }
    .btn.sm { padding:6px 10px; font-size:13px; border-radius:8px; }
    .btn.sec { background:var(--surface-2); color:var(--text); border:1px solid var(--border); }
    .btn.red { background:var(--danger); color:#fff; }
    .btn.ghost { background:transparent; color:var(--muted); border:1px solid var(--border); }
    .btn:disabled { opacity:.5; cursor:not-allowed; }

    .list { display:flex; flex-direction:column; }
    .item { display:flex; justify-content:space-between; align-items:center; gap:10px; padding:10px 4px; border-bottom:1px solid var(--border); flex-wrap:wrap; }
    .item:last-child { border-bottom:none; }
    .item-main { min-width:200px; flex:1; }
    .item-title { font-weight:600; font-size:15px; display:flex; align-items:center; gap:6px; flex-wrap:wrap; }
    .item-sub { font-size:12px; color:var(--muted); margin-top:2px; }
    .item-right { display:flex; align-items:center; gap:8px; flex-wrap:wrap; }
    .amt { font-weight:700; font-size:15px; font-variant-numeric:tabular-nums; }
    .trail { font-size:11px; color:var(--muted); font-variant-numeric:tabular-nums; text-align:right; }

    .badge { display:inline-block; font-size:11px; font-weight:700; padding:2px 8px; border-radius:999px; background:var(--surface-2); color:var(--muted); }
    .badge.ok { background:var(--ok-soft); color:var(--ok); }
    .badge.danger { background:var(--danger-soft); color:var(--danger); }
    .badge.warn { background:var(--warn-soft); color:var(--warn); }

    .acct-group { margin-top:12px; }
    .acct-group:first-child { margin-top:0; }
    .acct-group-head { display:flex; justify-content:space-between; align-items:center; padding:6px 8px; background:var(--surface-2); border-radius:9px; font-size:13px; font-weight:700; margin-bottom:4px; }

    .cards-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(260px,1fr)); gap:10px; }
    .cc-tile { border:1px solid var(--border); border-radius:12px; padding:12px; background:var(--bg); display:grid; gap:8px; }
    .bar-bg { height:6px; background:var(--surface-2); border-radius:99px; overflow:hidden; }
    .bar-fill { height:100%; background:var(--primary); border-radius:99px; }
    .bar-fill.danger { background:var(--danger); }

    .toolbar { display:flex; gap:8px; flex-wrap:wrap; align-items:center; margin-bottom:10px; }
    .toolbar input { flex:1; min-width:170px; }
    .toolbar select { width:auto; min-width:130px; }

    .hidden { display:none !important; }

    .modal-backdrop { position:fixed; inset:0; background:rgba(2,6,23,.55); display:flex; align-items:center; justify-content:center; padding:16px; z-index:50; }
    .modal { width:100%; max-width:440px; background:var(--surface); border:1px solid var(--border); border-radius:16px; padding:16px; box-shadow:0 20px 50px rgba(0,0,0,.25); display:grid; gap:12px; }
    .modal-head { display:flex; justify-content:space-between; align-items:center; }
    .modal-head h3 { margin:0; font-size:17px; }
    .modal-actions { display:flex; justify-content:flex-end; gap:8px; margin-top:4px; }

    #toast { position:fixed; bottom:18px; right:18px; background:var(--text); color:var(--bg); padding:10px 14px; border-radius:10px; font-size:13px; font-weight:600; z-index:60; box-shadow:0 8px 24px rgba(0,0,0,.22); display:none; }
    @media (max-width:600px) {
      .kpi .num { font-size:20px; }
      .item { padding:10px 0; }
    }
  </style>
</head>
<body>
<header>
  <div class="head-inner">
    <div class="brand">
      <div class="logo">F</div>
      <div>
        <div class="brand-title">FinMagic</div>
        <div class="brand-sub" id="sync-status">Connecting…</div>
      </div>
    </div>
    <nav class="nav">
      <div class="stepper">
        <button class="icon-btn" id="nav-back-btn" onclick="stepNavBack()" title="Step Backward" disabled>‹ Back</button>
        <button class="icon-btn" id="nav-fwd-btn" onclick="stepNavForward()" title="Step Forward" disabled>Fwd ›</button>
      </div>
      <button class="tab active" data-tab="overview" onclick="showTab('overview')">Overview</button>
      <button class="tab" data-tab="accounts" onclick="showTab('accounts')">Accounts</button>
      <button class="tab" data-tab="history" onclick="showTab('history')">History</button>
      <button class="tab" data-tab="cards" onclick="showTab('cards')">Cards &amp; Bills</button>
      <button class="icon-btn" onclick="loadData()" title="Refresh">Refresh</button>
      <button class="icon-btn" id="theme-btn" onclick="toggleTheme()">Dark</button>
    </nav>
  </div>
</header>

<main>
  <section id="tab-overview">
    <div class="kpis" id="kpi-grid"></div>

    <div class="card" style="margin-top:12px">
      <div class="card-head">
        <h2 class="card-title">Quick Entry</h2>
        <div class="seg" id="quick-mode-seg">
          <button type="button" class="active" onclick="setQuickMode('OUT', this)">Expense</button>
          <button type="button" onclick="setQuickMode('IN', this)">Income</button>
          <button type="button" onclick="setQuickMode('TRANSFER', this)">Transfer</button>
        </div>
      </div>

      <form id="quick-txn-form" class="form-grid" onsubmit="submitQuickTxn(event)">
        <div class="field">
          <label>Account</label>
          <select id="q-acct" required></select>
        </div>
        <div class="field">
          <label>Amount (Tk)</label>
          <input id="q-amt" type="number" step="0.01" min="0.01" placeholder="0.00" required />
        </div>
        <div class="field">
          <label>Category</label>
          <input id="q-cat" list="q-cat-list" placeholder="Food, Salary…" />
          <datalist id="q-cat-list"></datalist>
        </div>
        <div class="field">
          <label>Date</label>
          <input id="q-date" type="datetime-local" />
        </div>
        <div class="field">
          <label>Note / Merchant</label>
          <input id="q-note" placeholder="Optional" />
        </div>
        <button class="btn" type="submit" id="q-submit-btn">Save Expense</button>
      </form>

      <form id="quick-transfer-form" class="form-grid hidden" onsubmit="submitQuickTransfer(event)">
        <div class="field">
          <label>From Account</label>
          <select id="t-from" required></select>
        </div>
        <div class="field">
          <label>To Account</label>
          <select id="t-to" required></select>
        </div>
        <div class="field">
          <label>Amount (Tk)</label>
          <input id="t-amt" type="number" step="0.01" min="0.01" placeholder="0.00" required />
        </div>
        <div class="field">
          <label>Date</label>
          <input id="t-date" type="datetime-local" />
        </div>
        <button class="btn" type="submit">Transfer Funds</button>
      </form>
    </div>

    <div class="card" style="margin-top:12px">
      <div class="card-head">
        <h2 class="card-title">Unpaid Card Bills</h2>
        <span class="badge danger" id="overview-unpaid-badge">0 unpaid</span>
      </div>
      <div class="list" id="overview-bills"></div>
    </div>

    <div class="card" style="margin-top:12px">
      <div class="card-head">
        <h2 class="card-title">Recent Activity</h2>
        <button class="btn sm sec" onclick="showTabByName('history')">View All</button>
      </div>
      <div class="list" id="overview-recent"></div>
    </div>
  </section>

  <section id="tab-accounts" class="hidden">
    <div class="card">
      <div class="card-head">
        <div>
          <h2 class="card-title">Accounts</h2>
          <div class="muted" id="accounts-summary-line"></div>
        </div>
        <div style="display:flex;gap:8px;flex-wrap:wrap">
          <button class="btn sm sec" onclick="openTypesModal()">Account Types</button>
          <button class="btn sm" onclick="openAccountModal(null)">+ New Account</button>
        </div>
      </div>
      <div id="accounts-grouped"></div>
    </div>
  </section>

  <section id="tab-history" class="hidden">
    <div class="card">
      <div class="card-head">
        <h2 class="card-title">Transaction History</h2>
        <div style="display:flex;gap:8px;flex-wrap:wrap">
          <button class="btn sm sec" onclick="openCardSpendModal()">+ Card Spend</button>
          <button class="btn sm" onclick="showTabByName('overview')">+ Account Entry</button>
        </div>
      </div>
      <div class="toolbar">
        <input id="h-search" placeholder="Search merchant, category, account…" oninput="onHistoryFilterChange()" />
        <select id="h-acct" onchange="onHistoryFilterChange()"></select>
        <select id="h-kind" onchange="onHistoryFilterChange()">
          <option value="ALL">All Types</option>
          <option value="OUT">Expenses</option>
          <option value="IN">Income</option>
          <option value="CARD">Card Spend</option>
        </select>
        <div class="stepper">
          <button class="icon-btn" type="button" id="h-month-prev" onclick="stepMonth(-1)" title="Older month">‹</button>
          <select id="h-month" onchange="onHistoryFilterChange()" style="min-width:130px">
            <option value="ALL">All Months</option>
          </select>
          <button class="icon-btn" type="button" id="h-month-next" onclick="stepMonth(1)" title="Newer month">›</button>
        </div>
      </div>
      <div class="list" id="history-list"></div>
      <div class="pager hidden" id="history-pager">
        <button class="btn sm sec" type="button" id="h-page-prev" onclick="stepHistoryPage(-1)">‹ Newer</button>
        <span class="muted" id="h-page-info"></span>
        <button class="btn sm sec" type="button" id="h-page-next" onclick="stepHistoryPage(1)">Older ›</button>
      </div>
    </div>
  </section>

  <section id="tab-cards" class="hidden">
    <div class="card">
      <div class="card-head">
        <div>
          <h2 class="card-title">My Credit Cards</h2>
          <div class="muted">Total Outstanding: <strong class="danger" id="cards-total">৳0.00</strong></div>
        </div>
        <div style="display:flex;gap:8px;flex-wrap:wrap">
          <button class="btn sm sec" onclick="openCardSpendModal()">+ Card Spend</button>
          <button class="btn sm sec" onclick="openAddBillModal()">+ Add Bill</button>
          <button class="btn sm" onclick="openAddCardModal()">+ Add Card</button>
        </div>
      </div>
      <div class="cards-grid" id="cards-grid"></div>
    </div>

    <div class="card" style="margin-top:12px">
      <div class="card-head">
        <h2 class="card-title">Unpaid Bills</h2>
        <span class="badge danger" id="cards-unpaid-badge">0</span>
      </div>
      <div class="list" id="bills-unpaid"></div>
    </div>

    <div class="card" style="margin-top:12px">
      <div class="card-head">
        <h2 class="card-title">Paid Bills</h2>
        <span class="badge ok" id="cards-paid-badge">0</span>
      </div>
      <div class="list" id="bills-paid"></div>
    </div>
  </section>
</main>

<div id="modal-root" class="modal-backdrop hidden" onclick="if(event.target===this) closeModal()">
  <div class="modal" id="modal-box"></div>
</div>
<div id="toast"></div>

<script>
var DATA = null;
var quickDir = 'OUT';

function fmt(n, cur) {
  var sym = (cur && String(cur).toUpperCase() === 'USD') ? '$' : '৳';
  return sym + Number(n || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
function esc(s) {
  return String(s == null ? '' : s).replace(/[&<>"']/g, function(c) {
    return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];
  });
}
function fmtDate(ms) {
  if (!ms) return '';
  return new Date(ms).toLocaleString(undefined, { day:'2-digit', month:'short', year:'numeric', hour:'2-digit', minute:'2-digit' });
}
function nowLocalValue() {
  var d = new Date();
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 16);
}
function parseLocalDate(val) {
  if (!val) return Date.now();
  var t = new Date(val).getTime();
  return isNaN(t) ? Date.now() : t;
}
function toast(msg, isErr) {
  var el = document.getElementById('toast');
  el.textContent = msg;
  el.style.background = isErr ? 'var(--danger)' : 'var(--text)';
  el.style.color = isErr ? '#fff' : 'var(--bg)';
  el.style.display = 'block';
  clearTimeout(window._toastTimer);
  window._toastTimer = setTimeout(function(){ el.style.display = 'none'; }, 2800);
}

function applyTheme(dark) {
  document.documentElement.classList.toggle('dark', dark);
  document.getElementById('theme-btn').textContent = dark ? 'Light' : 'Dark';
  try { localStorage.setItem('finmagic_theme', dark ? 'dark' : 'light'); } catch(e) {}
}
function toggleTheme() {
  applyTheme(!document.documentElement.classList.contains('dark'));
}
(function initTheme(){
  try {
    var saved = localStorage.getItem('finmagic_theme');
    if (saved === 'dark') applyTheme(true);
  } catch(e) {}
})();

var VALID_TABS = ['overview','accounts','history','cards'];
var NAV_STACK = [];
var NAV_INDEX = -1;
var HIST_PAGE = 0;
var HIST_PAGE_SIZE = 25;
var HIST_MONTHS = [];

function currentNavSnapshot(tabOverride) {
  return {
    tab: tabOverride || (document.querySelector('.nav .tab.active') ? document.querySelector('.nav .tab.active').getAttribute('data-tab') : 'overview'),
    histAcct: document.getElementById('h-acct') ? (document.getElementById('h-acct').value || 'ALL') : 'ALL',
    histKind: document.getElementById('h-kind') ? (document.getElementById('h-kind').value || 'ALL') : 'ALL',
    histMonth: document.getElementById('h-month') ? (document.getElementById('h-month').value || 'ALL') : 'ALL',
    histPage: HIST_PAGE
  };
}
function sameSnapshot(a, b) {
  return a && b && a.tab === b.tab && a.histAcct === b.histAcct && a.histKind === b.histKind && a.histMonth === b.histMonth && a.histPage === b.histPage;
}
function updateNavButtons() {
  var backBtn = document.getElementById('nav-back-btn');
  var fwdBtn = document.getElementById('nav-fwd-btn');
  if (backBtn) backBtn.disabled = NAV_INDEX <= 0;
  if (fwdBtn) fwdBtn.disabled = NAV_INDEX < 0 || NAV_INDEX >= NAV_STACK.length - 1;
}
function applyNavSnapshot(snap) {
  if (!snap) return;
  var id = VALID_TABS.indexOf(snap.tab) !== -1 ? snap.tab : 'overview';
  document.querySelectorAll('main section').forEach(function(s){ s.classList.add('hidden'); });
  document.getElementById('tab-' + id).classList.remove('hidden');
  document.querySelectorAll('.nav .tab').forEach(function(b){
    b.classList.toggle('active', b.getAttribute('data-tab') === id);
  });
  if (snap.histAcct && document.getElementById('h-acct')) {
    document.getElementById('h-acct').value = snap.histAcct;
    if (!document.getElementById('h-acct').value) document.getElementById('h-acct').value = 'ALL';
  }
  if (snap.histKind && document.getElementById('h-kind')) {
    document.getElementById('h-kind').value = snap.histKind;
  }
  if (snap.histMonth && document.getElementById('h-month')) {
    document.getElementById('h-month').value = snap.histMonth;
    if (!document.getElementById('h-month').value) document.getElementById('h-month').value = 'ALL';
  }
  HIST_PAGE = snap.histPage || 0;
  if (DATA) renderHistory();
  updateNavButtons();
}
function pushNavSnapshot(snap) {
  if (NAV_INDEX >= 0 && sameSnapshot(NAV_STACK[NAV_INDEX], snap)) return;
  NAV_STACK = NAV_STACK.slice(0, NAV_INDEX + 1);
  NAV_STACK.push(snap);
  NAV_INDEX = NAV_STACK.length - 1;
  try { window.history.pushState({ navIdx: NAV_INDEX }, '', '#' + snap.tab); } catch(e) {}
  applyNavSnapshot(snap);
}
function showTab(id) {
  var snap = currentNavSnapshot(id);
  if (id !== 'history') snap.histPage = 0;
  pushNavSnapshot(snap);
}
function showTabByName(id) {
  showTab(id);
}
function openAccountHistory(accountId) {
  var snap = currentNavSnapshot('history');
  snap.histAcct = String(accountId);
  snap.histKind = 'ALL';
  snap.histMonth = 'ALL';
  snap.histPage = 0;
  pushNavSnapshot(snap);
}
function stepNavBack() {
  if (NAV_INDEX > 0) window.history.back();
}
function stepNavForward() {
  if (NAV_INDEX >= 0 && NAV_INDEX < NAV_STACK.length - 1) window.history.forward();
}
window.addEventListener('popstate', function(e) {
  closeModal();
  if (e.state && typeof e.state.navIdx === 'number' && NAV_STACK[e.state.navIdx]) {
    NAV_INDEX = e.state.navIdx;
    applyNavSnapshot(NAV_STACK[NAV_INDEX]);
  } else {
    var hashTab = (window.location.hash || '').replace('#', '');
    var tab = VALID_TABS.indexOf(hashTab) !== -1 ? hashTab : 'overview';
    applyNavSnapshot({ tab: tab, histAcct: 'ALL', histKind: 'ALL', histMonth: 'ALL', histPage: 0 });
  }
});
(function initNav(){
  var hashTab = (window.location.hash || '').replace('#', '');
  var startTab = VALID_TABS.indexOf(hashTab) !== -1 ? hashTab : 'overview';
  var initial = { tab: startTab, histAcct: 'ALL', histKind: 'ALL', histMonth: 'ALL', histPage: 0 };
  NAV_STACK = [initial];
  NAV_INDEX = 0;
  try { window.history.replaceState({ navIdx: 0 }, '', '#' + startTab); } catch(e) {}
  applyNavSnapshot(initial);
})();

async function api(url, opts) {
  opts = opts || {};
  var r = await fetch(url, Object.assign({ headers: {'Content-Type':'application/json'} }, opts));
  var j = await r.json();
  if (!r.ok || j.ok === false) throw new Error(j.error || 'Request failed');
  return j;
}

async function loadData() {
  try {
    DATA = await api('/api/overview');
    document.getElementById('sync-status').textContent = 'Synced • ' + new Date().toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'});
    renderAll();
  } catch (err) {
    document.getElementById('sync-status').textContent = 'Connection error';
    toast(err.message, true);
  }
}

function setQuickMode(mode, btn) {
  quickDir = mode;
  document.querySelectorAll('#quick-mode-seg button').forEach(function(b){ b.classList.remove('active'); });
  if (btn) btn.classList.add('active');
  var isTransfer = mode === 'TRANSFER';
  document.getElementById('quick-txn-form').classList.toggle('hidden', isTransfer);
  document.getElementById('quick-transfer-form').classList.toggle('hidden', !isTransfer);
  if (!isTransfer) {
    document.getElementById('q-submit-btn').textContent = mode === 'IN' ? 'Save Income' : 'Save Expense';
    updateCategoryDatalist();
  }
}

function updateCategoryDatalist() {
  if (!DATA) return;
  var cats = quickDir === 'IN' ? (DATA.incomeCategories || []) : (DATA.expenseCategories || []);
  document.getElementById('q-cat-list').innerHTML = cats.map(function(c){
    return '<option value="' + esc(c) + '"></option>';
  }).join('');
}

function renderAll() {
  if (!DATA) return;
  var s = DATA.summary;
  document.getElementById('kpi-grid').innerHTML =
    '<div class="kpi"><div class="lbl">Net Asset</div><div class="num">' + fmt(s.netWorth) + '</div></div>' +
    '<div class="kpi"><div class="lbl">Total Assets</div><div class="num ok">' + fmt(s.assetsTotal) + '</div></div>' +
    '<div class="kpi"><div class="lbl">Account Liabilities</div><div class="num danger">' + fmt(s.accountLiabilitiesTotal) + '</div></div>' +
    '<div class="kpi"><div class="lbl">Card Outstanding</div><div class="num danger">' + fmt(s.cardLiabilityTotal) + '</div></div>';

  document.getElementById('cards-total').textContent = fmt(s.cardLiabilityTotal);

  var acctOpts = DATA.accounts.map(function(a){
    return '<option value="' + a.id + '">' + esc(a.name) + ' (' + fmt(a.balance, a.currency) + ')</option>';
  }).join('');
  document.getElementById('q-acct').innerHTML = acctOpts;
  document.getElementById('t-from').innerHTML = acctOpts;
  document.getElementById('t-to').innerHTML = acctOpts;
  if (DATA.accounts.length > 1) {
    document.getElementById('t-to').selectedIndex = 1;
  }
  if (!document.getElementById('q-date').value) document.getElementById('q-date').value = nowLocalValue();
  if (!document.getElementById('t-date').value) document.getElementById('t-date').value = nowLocalValue();
  updateCategoryDatalist();

  var histAcctSel = document.getElementById('h-acct');
  var prevHistAcct = histAcctSel.value || 'ALL';
  histAcctSel.innerHTML = '<option value="ALL">All Accounts</option>' + DATA.accounts.map(function(a){
    return '<option value="' + a.id + '">' + esc(a.name) + '</option>';
  }).join('');
  histAcctSel.value = prevHistAcct;
  if (!histAcctSel.value) histAcctSel.value = 'ALL';

  buildHistoryMonthOptions();
  renderOverviewBillsAndRecent();
  renderAccounts();
  renderHistory();
  renderCardsAndBills();
}

function monthKey(ms) {
  var d = new Date(ms);
  var m = d.getMonth() + 1;
  return d.getFullYear() + '-' + (m < 10 ? '0' + m : '' + m);
}
function formatMonthKey(k) {
  var parts = k.split('-');
  var d = new Date(Number(parts[0]), Number(parts[1]) - 1, 1);
  return d.toLocaleDateString(undefined, { month: 'short', year: 'numeric' });
}
function buildHistoryMonthOptions() {
  var seen = {};
  DATA.transactions.forEach(function(t){ if (t.occurredAt) seen[monthKey(t.occurredAt)] = true; });
  DATA.cardTransactions.forEach(function(ct){ if (ct.occurredAt) seen[monthKey(ct.occurredAt)] = true; });
  HIST_MONTHS = Object.keys(seen).sort().reverse();
  var sel = document.getElementById('h-month');
  var prev = sel.value || 'ALL';
  sel.innerHTML = '<option value="ALL">All Months</option>' + HIST_MONTHS.map(function(k){
    return '<option value="' + k + '">' + esc(formatMonthKey(k)) + '</option>';
  }).join('');
  sel.value = prev;
  if (!sel.value) sel.value = 'ALL';
}
function onHistoryFilterChange() {
  HIST_PAGE = 0;
  pushNavSnapshot(currentNavSnapshot('history'));
}
function stepMonth(delta) {
  // delta -1 = step backward to older month, +1 = step forward to newer month (or ALL)
  var order = ['ALL'].concat(HIST_MONTHS); // ['ALL', newest, ..., oldest]
  var sel = document.getElementById('h-month');
  var idx = order.indexOf(sel.value || 'ALL');
  if (idx === -1) idx = 0;
  var nextIdx = delta < 0 ? Math.min(order.length - 1, idx + 1) : Math.max(0, idx - 1);
  if (nextIdx === idx) return;
  sel.value = order[nextIdx];
  HIST_PAGE = 0;
  pushNavSnapshot(currentNavSnapshot('history'));
}
function stepHistoryPage(delta) {
  HIST_PAGE = Math.max(0, HIST_PAGE + delta);
  pushNavSnapshot(currentNavSnapshot('history'));
}

function billStatusBadge(b) {
  if (b.isPaid) return '<span class="badge ok">Paid</span>';
  if (b.dueStatus === 'OVERDUE') return '<span class="badge danger">Overdue</span>';
  if (b.dueStatus === 'DUE_SOON') return '<span class="badge warn">Due Soon</span>';
  return '<span class="badge">' + esc(b.dueStatus) + '</span>';
}

function renderBillRow(b) {
  return '<div class="item">' +
    '<div class="item-main">' +
      '<div class="item-title">' + esc(b.displayName) + ' <span class="muted">(' + esc(b.billPeriod) + ')</span> ' + billStatusBadge(b) + '</div>' +
      '<div class="item-sub">Bill ' + fmt(b.totalDue, b.currency) + ' • Min ' + fmt(b.minDue, b.currency) + ' • Due ' + esc(b.formattedDueDate) + '</div>' +
    '</div>' +
    '<div class="item-right">' +
      '<div style="text-align:right">' +
        '<div class="amt ' + (b.isPaid ? 'ok' : 'danger') + '">' + (b.isPaid ? '৳0.00' : fmt(b.outstanding, b.currency)) + '</div>' +
        '<div class="muted" style="font-size:11px">' + (b.isPaid ? 'Cleared' : 'Outstanding') + '</div>' +
      '</div>' +
      (!b.isPaid
        ? '<button class="btn sm red" onclick="openPayBillModal(' + b.billId + ')">Pay Bill</button>'
        : '<button class="btn sm sec" onclick="unpayBill(' + b.billId + ')">Mark Unpaid</button>') +
      '<button class="btn sm ghost" onclick="openEditBillModal(' + b.billId + ')">Edit</button>' +
    '</div>' +
  '</div>';
}

function renderTxnRow(t, compact) {
  var isIn = t.direction === 'IN';
  var title = t.counterparty || t.category || (isIn ? 'Income' : 'Expense');
  var trailHtml = (t.beforeBalance != null && t.afterBalance != null)
    ? '<div class="trail">' + fmt(t.beforeBalance, t.currency) + ' → ' + fmt(t.afterBalance, t.currency) + '</div>'
    : '';
  return '<div class="item">' +
    '<div class="item-main">' +
      '<div class="item-title">' + esc(title) + (t.category ? ' <span class="badge">' + esc(t.category) + '</span>' : '') + '</div>' +
      '<div class="item-sub">' + esc(t.accountName) + ' • ' + fmtDate(t.occurredAt) + '</div>' +
    '</div>' +
    '<div class="item-right">' +
      '<div>' +
        '<div class="amt ' + (isIn ? 'ok' : 'danger') + '" style="text-align:right">' + (isIn ? '+' : '-') + fmt(t.amount, t.currency) + '</div>' +
        trailHtml +
      '</div>' +
      (compact ? '' :
        '<button class="btn sm ghost" onclick="openEditTxnModal(' + t.id + ')">Edit</button>' +
        '<button class="btn sm red" onclick="confirmDeleteTxn(' + t.id + ')">Del</button>') +
    '</div>' +
  '</div>';
}

function renderCardTxnRow(ct, compact) {
  return '<div class="item">' +
    '<div class="item-main">' +
      '<div class="item-title">' + esc(ct.merchant) + (ct.category ? ' <span class="badge">' + esc(ct.category) + '</span>' : '') + ' <span class="badge warn">Card</span></div>' +
      '<div class="item-sub">' + esc(ct.bankName) + ' •••• ' + esc(ct.cardTail) + ' • ' + fmtDate(ct.occurredAt) + '</div>' +
    '</div>' +
    '<div class="item-right">' +
      '<div class="amt danger">-' + fmt(ct.amount) + '</div>' +
      (compact ? '' : '<button class="btn sm red" onclick="confirmDeleteCardTxn(' + ct.id + ')">Del</button>') +
    '</div>' +
  '</div>';
}

function renderOverviewBillsAndRecent() {
  var unpaid = DATA.bills.filter(function(b){ return !b.isPaid; });
  document.getElementById('overview-unpaid-badge').textContent = unpaid.length + ' unpaid';
  document.getElementById('overview-bills').innerHTML = unpaid.length
    ? unpaid.map(renderBillRow).join('')
    : '<div class="ok" style="padding:8px 0;font-weight:600">All credit card bills are paid.</div>';

  var combined = [];
  DATA.transactions.forEach(function(t){ combined.push({ type:'acct', ts:t.occurredAt, data:t }); });
  DATA.cardTransactions.forEach(function(ct){ combined.push({ type:'card', ts:ct.occurredAt, data:ct }); });
  combined.sort(function(a,b){ return b.ts - a.ts; });
  var top10 = combined.slice(0, 10);
  document.getElementById('overview-recent').innerHTML = top10.length
    ? top10.map(function(row){ return row.type === 'acct' ? renderTxnRow(row.data, true) : renderCardTxnRow(row.data, true); }).join('')
    : '<div class="muted" style="padding:8px 0">No transactions yet.</div>';
}

function renderAccounts() {
  var grouped = {};
  DATA.accounts.forEach(function(a){
    var key = a.accountType || 'Savings';
    (grouped[key] = grouped[key] || []).push(a);
  });
  var totalCount = DATA.accounts.length;
  document.getElementById('accounts-summary-line').textContent = totalCount + ' accounts across ' + Object.keys(grouped).length + ' types';

  var html = Object.keys(grouped).map(function(type){
    var list = grouped[type];
    var tot = list.filter(function(x){ return x.includeInTotal; })
      .reduce(function(acc, x){ return acc + (x.isLiability ? -Math.abs(x.balance) : x.balance); }, 0);
    var rows = list.map(function(a){
      return '<div class="item">' +
        '<div class="item-main">' +
          '<div class="item-title">' + esc(a.name) +
            ' <span class="badge ' + (a.isLiability ? 'danger' : 'ok') + '">' + (a.isLiability ? 'Liability' : 'Asset') + '</span>' +
            (!a.includeInTotal ? ' <span class="badge">Excluded</span>' : '') +
          '</div>' +
          '<div class="item-sub">' + esc(a.bankName || a.kind) + ' • ' + esc(a.accountMask) + '</div>' +
        '</div>' +
        '<div class="item-right">' +
          '<div class="amt ' + (a.isLiability ? 'danger' : '') + '">' + fmt(a.balance, a.currency) + '</div>' +
          '<button class="btn sm ghost" onclick="openAccountHistory(' + a.id + ')">History</button>' +
          '<button class="btn sm sec" onclick="openAccountModal(' + a.id + ')">Edit</button>' +
          (a.kind !== 'CASH' ? '<button class="btn sm red" onclick="confirmDeleteAccount(' + a.id + ')">Del</button>' : '') +
        '</div>' +
      '</div>';
    }).join('');
    return '<div class="acct-group">' +
      '<div class="acct-group-head"><span>' + esc(type) + ' (' + list.length + ')</span><span>' + fmt(tot) + '</span></div>' +
      '<div class="list">' + rows + '</div>' +
    '</div>';
  }).join('');

  document.getElementById('accounts-grouped').innerHTML = html || '<div class="muted">No accounts found.</div>';
}

function renderHistory() {
  if (!DATA) return;
  var q = (document.getElementById('h-search').value || '').trim().toLowerCase();
  var acctFilter = document.getElementById('h-acct').value || 'ALL';
  var kindFilter = document.getElementById('h-kind').value || 'ALL';
  var mFilter = document.getElementById('h-month').value || 'ALL';

  var order = ['ALL'].concat(HIST_MONTHS);
  var mIdx = order.indexOf(mFilter);
  if (mIdx === -1) mIdx = 0;
  document.getElementById('h-month-prev').disabled = mIdx >= order.length - 1;
  document.getElementById('h-month-next').disabled = mIdx <= 0;

  var rows = [];
  if (kindFilter !== 'CARD') {
    DATA.transactions.forEach(function(t){
      if (acctFilter !== 'ALL' && String(t.accountId) !== acctFilter) return;
      if (kindFilter === 'IN' && t.direction !== 'IN') return;
      if (kindFilter === 'OUT' && t.direction !== 'OUT') return;
      if (mFilter !== 'ALL' && monthKey(t.occurredAt) !== mFilter) return;
      if (q) {
        var hay = [t.counterparty, t.category, t.accountName].join(' ').toLowerCase();
        if (hay.indexOf(q) === -1) return;
      }
      rows.push({ type:'acct', ts:t.occurredAt, data:t });
    });
  }
  if ((kindFilter === 'ALL' || kindFilter === 'CARD') && acctFilter === 'ALL') {
    DATA.cardTransactions.forEach(function(ct){
      if (mFilter !== 'ALL' && monthKey(ct.occurredAt) !== mFilter) return;
      if (q) {
        var hay = [ct.merchant, ct.category, ct.bankName, ct.cardTail].join(' ').toLowerCase();
        if (hay.indexOf(q) === -1) return;
      }
      rows.push({ type:'card', ts:ct.occurredAt, data:ct });
    });
  }

  rows.sort(function(a,b){ return b.ts - a.ts; });
  var totalPages = Math.max(1, Math.ceil(rows.length / HIST_PAGE_SIZE));
  if (HIST_PAGE >= totalPages) HIST_PAGE = totalPages - 1;
  var start = HIST_PAGE * HIST_PAGE_SIZE;
  var slice = rows.slice(start, start + HIST_PAGE_SIZE);

  document.getElementById('history-list').innerHTML = slice.length
    ? slice.map(function(r){ return r.type === 'acct' ? renderTxnRow(r.data, false) : renderCardTxnRow(r.data, false); }).join('')
    : '<div class="muted" style="padding:12px 0">No matching transactions.</div>';

  var pager = document.getElementById('history-pager');
  if (rows.length > HIST_PAGE_SIZE) {
    pager.classList.remove('hidden');
    document.getElementById('h-page-prev').disabled = HIST_PAGE <= 0;
    document.getElementById('h-page-next').disabled = HIST_PAGE >= totalPages - 1;
    document.getElementById('h-page-info').textContent = 'Page ' + (HIST_PAGE + 1) + ' of ' + totalPages + ' (' + rows.length + ' items)';
  } else {
    pager.classList.add('hidden');
  }
}

function renderCardsAndBills() {
  document.getElementById('cards-grid').innerHTML = DATA.cards.length
    ? DATA.cards.map(function(c){
        var pct = c.creditLimit > 0 ? Math.min(100, Math.round((c.outstanding / c.creditLimit) * 100)) : 0;
        return '<div class="cc-tile">' +
          '<div style="display:flex;justify-content:space-between;align-items:start;gap:8px">' +
            '<div>' +
              '<div style="font-weight:700;font-size:15px">' + esc(c.displayName) + '</div>' +
              '<div class="muted">•••• ' + esc(c.cardLast4) + ' • ' + esc(c.bankName) + '</div>' +
            '</div>' +
            '<span class="badge ' + (c.unpaidCount > 0 ? 'danger' : 'ok') + '">' + (c.unpaidCount > 0 ? c.unpaidCount + ' unpaid' : 'All paid') + '</span>' +
          '</div>' +
          '<div style="display:flex;justify-content:space-between;align-items:baseline">' +
            '<span class="muted">Outstanding</span>' +
            '<strong class="amt ' + (c.outstanding > 0 ? 'danger' : 'ok') + '">' + fmt(c.outstanding, c.currency) + '</strong>' +
          '</div>' +
          (c.creditLimit > 0
            ? '<div>' +
                '<div class="bar-bg"><div class="bar-fill ' + (pct > 80 ? 'danger' : '') + '" style="width:' + pct + '%"></div></div>' +
                '<div class="muted" style="font-size:11px;margin-top:3px">Limit ' + fmt(c.creditLimit, c.currency) + ' • ' + pct + '% used</div>' +
              '</div>'
            : '') +
        '</div>';
      }).join('')
    : '<div class="muted">No credit cards yet.</div>';

  var unpaid = DATA.bills.filter(function(b){ return !b.isPaid; });
  var paid = DATA.bills.filter(function(b){ return b.isPaid; });
  document.getElementById('cards-unpaid-badge').textContent = unpaid.length;
  document.getElementById('cards-paid-badge').textContent = paid.length;

  document.getElementById('bills-unpaid').innerHTML = unpaid.length
    ? unpaid.map(renderBillRow).join('')
    : '<div class="ok" style="padding:8px 0;font-weight:600">No unpaid bills.</div>';
  document.getElementById('bills-paid').innerHTML = paid.length
    ? paid.map(renderBillRow).join('')
    : '<div class="muted" style="padding:8px 0">No paid bills yet.</div>';
}

/* ---------- Quick Submit Actions ---------- */

async function submitQuickTxn(e) {
  e.preventDefault();
  try {
    await api('/api/transactions', {
      method: 'POST',
      body: JSON.stringify({
        accountId: Number(document.getElementById('q-acct').value),
        direction: quickDir,
        amount: Number(document.getElementById('q-amt').value),
        category: document.getElementById('q-cat').value,
        counterparty: document.getElementById('q-note').value,
        occurredAt: parseLocalDate(document.getElementById('q-date').value)
      })
    });
    document.getElementById('q-amt').value = '';
    document.getElementById('q-note').value = '';
    toast(quickDir === 'IN' ? 'Income saved' : 'Expense saved');
    await loadData();
  } catch (err) {
    toast(err.message, true);
  }
}

async function submitQuickTransfer(e) {
  e.preventDefault();
  try {
    await api('/api/transfers', {
      method: 'POST',
      body: JSON.stringify({
        fromAccountId: Number(document.getElementById('t-from').value),
        toAccountId: Number(document.getElementById('t-to').value),
        amount: Number(document.getElementById('t-amt').value),
        occurredAt: parseLocalDate(document.getElementById('t-date').value)
      })
    });
    document.getElementById('t-amt').value = '';
    toast('Transfer recorded');
    await loadData();
  } catch (err) {
    toast(err.message, true);
  }
}

/* ---------- Modal System (No browser prompt/confirm) ---------- */

function openModal(html) {
  document.getElementById('modal-box').innerHTML = html;
  document.getElementById('modal-root').classList.remove('hidden');
}
function closeModal() {
  document.getElementById('modal-root').classList.add('hidden');
  document.getElementById('modal-box').innerHTML = '';
}
document.addEventListener('keydown', function(e){
  if (e.key === 'Escape') closeModal();
});

function openAccountModal(accountId) {
  var existing = accountId ? DATA.accounts.find(function(x){ return x.id === accountId; }) : null;
  var typeOpts = DATA.accountTypes.map(function(t){
    var sel = existing ? (existing.accountType === t.name) : (t.name === 'Savings');
    return '<option value="' + esc(t.name) + '"' + (sel ? ' selected' : '') + '>' + esc(t.name) + (t.isLiability ? ' (Liability)' : '') + '</option>';
  }).join('');

  openModal(
    '<div class="modal-head"><h3>' + (existing ? 'Edit Account' : 'New Account') + '</h3><button class="btn sm ghost" onclick="closeModal()">✕</button></div>' +
    '<form class="list" style="gap:10px" onsubmit="saveAccountForm(event,' + (existing ? existing.id : 'null') + ')">' +
      '<div class="field"><label>Account Name</label><input id="m-a-name" value="' + esc(existing ? existing.name : '') + '" required /></div>' +
      '<div class="field"><label>Account Type</label><select id="m-a-type" onchange="syncModalTypeLiab()">' + typeOpts + '</select></div>' +
      (existing ? '' : '<div class="field"><label>Bank / Institution (optional)</label><input id="m-a-bank" /></div>') +
      '<div class="field"><label>' + (existing ? 'Set Current Balance (leave blank to keep)' : 'Opening Balance') + '</label><input id="m-a-bal" type="number" step="0.01" value="' + (existing ? existing.balance : '0') + '" /></div>' +
      '<label style="display:flex;align-items:center;gap:8px;font-size:14px"><input id="m-a-liab" type="checkbox" style="width:auto" ' + ((existing && existing.isLiability) ? 'checked' : '') + ' /> Liability account (Loan / Debt)</label>' +
      '<label style="display:flex;align-items:center;gap:8px;font-size:14px"><input id="m-a-inc" type="checkbox" style="width:auto" ' + ((!existing || existing.includeInTotal) ? 'checked' : '') + ' /> Include in Net Asset total</label>' +
      '<div class="modal-actions"><button type="button" class="btn sec" onclick="closeModal()">Cancel</button><button type="submit" class="btn">Save</button></div>' +
    '</form>'
  );
}
function syncModalTypeLiab() {
  var val = document.getElementById('m-a-type').value;
  var t = DATA.accountTypes.find(function(x){ return x.name === val; });
  if (t) document.getElementById('m-a-liab').checked = !!t.isLiability;
}
async function saveAccountForm(e, id) {
  e.preventDefault();
  try {
    var balRaw = document.getElementById('m-a-bal').value.trim();
    if (id) {
      var payload = {
        name: document.getElementById('m-a-name').value,
        accountType: document.getElementById('m-a-type').value,
        isLiability: document.getElementById('m-a-liab').checked,
        includeInTotal: document.getElementById('m-a-inc').checked
      };
      if (balRaw !== '') payload.targetBalance = Number(balRaw);
      await api('/api/accounts/' + id, { method: 'PUT', body: JSON.stringify(payload) });
      toast('Account updated');
    } else {
      await api('/api/accounts', {
        method: 'POST',
        body: JSON.stringify({
          name: document.getElementById('m-a-name').value,
          accountType: document.getElementById('m-a-type').value,
          bankName: document.getElementById('m-a-bank').value,
          initialBalance: Number(balRaw || 0),
          isLiability: document.getElementById('m-a-liab').checked,
          includeInTotal: document.getElementById('m-a-inc').checked
        })
      });
      toast('Account created');
    }
    closeModal();
    await loadData();
  } catch (err) { toast(err.message, true); }
}
function confirmDeleteAccount(id) {
  var a = DATA.accounts.find(function(x){ return x.id === id; });
  openModal(
    '<div class="modal-head"><h3>Delete Account</h3><button class="btn sm ghost" onclick="closeModal()">✕</button></div>' +
    '<div class="muted">Archive <strong>' + esc(a ? a.name : 'account') + '</strong> and remove its transactions from totals?</div>' +
    '<div class="modal-actions"><button class="btn sec" onclick="closeModal()">Cancel</button><button class="btn red" onclick="doDeleteAccount(' + id + ')">Delete</button></div>'
  );
}
async function doDeleteAccount(id) {
  try { await api('/api/accounts/' + id, { method:'DELETE' }); closeModal(); toast('Account removed'); await loadData(); }
  catch (err) { toast(err.message, true); }
}

function openTypesModal() {
  var rows = DATA.accountTypes.map(function(t){
    return '<div class="item">' +
      '<div><strong>' + esc(t.name) + '</strong> <span class="badge ' + (t.isLiability ? 'danger' : 'ok') + '">' + (t.isLiability ? 'Liability' : 'Asset') + '</span></div>' +
      (t.isCustom ? '<button class="btn sm red" onclick="delType(\'' + encodeURIComponent(t.name) + '\')">Remove</button>' : '<span class="muted">Default</span>') +
    '</div>';
  }).join('');
  openModal(
    '<div class="modal-head"><h3>Account Types</h3><button class="btn sm ghost" onclick="closeModal()">✕</button></div>' +
    '<form class="form-grid" onsubmit="saveNewType(event)">' +
      '<div class="field"><label>New Type Name</label><input id="m-t-name" placeholder="DPS, Mortgage…" required /></div>' +
      '<label style="display:flex;align-items:center;gap:6px;font-size:13px"><input id="m-t-liab" type="checkbox" style="width:auto" /> Liability</label>' +
      '<button type="submit" class="btn sm">Add</button>' +
    '</form>' +
    '<div class="list" style="max-height:240px;overflow:auto">' + rows + '</div>'
  );
}
async function saveNewType(e) {
  e.preventDefault();
  try {
    await api('/api/account-types', { method:'POST', body: JSON.stringify({ name: document.getElementById('m-t-name').value, isLiability: document.getElementById('m-t-liab').checked }) });
    toast('Type added'); await loadData(); openTypesModal();
  } catch (err) { toast(err.message, true); }
}
async function delType(encName) {
  try { await api('/api/account-types/' + encName, { method:'DELETE' }); toast('Type removed'); await loadData(); openTypesModal(); }
  catch (err) { toast(err.message, true); }
}

function openEditTxnModal(id) {
  var t = DATA.transactions.find(function(x){ return x.id === id; });
  if (!t) return;
  openModal(
    '<div class="modal-head"><h3>Edit Transaction</h3><button class="btn sm ghost" onclick="closeModal()">✕</button></div>' +
    '<form class="list" style="gap:10px" onsubmit="saveEditTxn(event,' + id + ')">' +
      '<div class="field"><label>Amount</label><input id="m-e-amt" type="number" step="0.01" min="0.01" value="' + t.amount + '" required /></div>' +
      '<div class="field"><label>Category</label><input id="m-e-cat" value="' + esc(t.category || '') + '" /></div>' +
      '<div class="field"><label>Counterparty / Note</label><input id="m-e-note" value="' + esc(t.counterparty || '') + '" /></div>' +
      '<div class="modal-actions"><button type="button" class="btn sec" onclick="closeModal()">Cancel</button><button type="submit" class="btn">Save</button></div>' +
    '</form>'
  );
}
async function saveEditTxn(e, id) {
  e.preventDefault();
  try {
    await api('/api/transactions/' + id, {
      method: 'PUT',
      body: JSON.stringify({
        amount: Number(document.getElementById('m-e-amt').value),
        category: document.getElementById('m-e-cat').value,
        counterparty: document.getElementById('m-e-note').value
      })
    });
    closeModal(); toast('Transaction updated'); await loadData();
  } catch (err) { toast(err.message, true); }
}
function confirmDeleteTxn(id) {
  openModal(
    '<div class="modal-head"><h3>Delete Transaction</h3><button class="btn sm ghost" onclick="closeModal()">✕</button></div>' +
    '<div class="muted">Permanently delete this transaction? Linked transfers will also be removed.</div>' +
    '<div class="modal-actions"><button class="btn sec" onclick="closeModal()">Cancel</button><button class="btn red" onclick="doDeleteTxn(' + id + ')">Delete</button></div>'
  );
}
async function doDeleteTxn(id) {
  try { await api('/api/transactions/' + id, { method:'DELETE' }); closeModal(); toast('Transaction deleted'); await loadData(); }
  catch (err) { toast(err.message, true); }
}
function confirmDeleteCardTxn(id) {
  openModal(
    '<div class="modal-head"><h3>Delete Card Spend</h3><button class="btn sm ghost" onclick="closeModal()">✕</button></div>' +
    '<div class="muted">Remove this card transaction?</div>' +
    '<div class="modal-actions"><button class="btn sec" onclick="closeModal()">Cancel</button><button class="btn red" onclick="doDeleteCardTxn(' + id + ')">Delete</button></div>'
  );
}
async function doDeleteCardTxn(id) {
  try { await api('/api/cards/transactions/' + id, { method:'DELETE' }); closeModal(); toast('Card spend deleted'); await loadData(); }
  catch (err) { toast(err.message, true); }
}

function openAddCardModal() {
  openModal(
    '<div class="modal-head"><h3>Add Credit Card</h3><button class="btn sm ghost" onclick="closeModal()">✕</button></div>' +
    '<form class="list" style="gap:10px" onsubmit="saveNewCard(event)">' +
      '<div class="field"><label>Bank Name</label><input id="m-c-bank" placeholder="City Bank, EBL…" required /></div>' +
      '<div class="field"><label>Last 4 Digits</label><input id="m-c-last4" maxlength="4" placeholder="1234" required /></div>' +
      '<div class="field"><label>Nickname (optional)</label><input id="m-c-nick" placeholder="Visa Platinum" /></div>' +
      '<div class="field"><label>Credit Limit</label><input id="m-c-limit" type="number" step="0.01" value="0" /></div>' +
      '<div class="modal-actions"><button type="button" class="btn sec" onclick="closeModal()">Cancel</button><button type="submit" class="btn">Add Card</button></div>' +
    '</form>'
  );
}
async function saveNewCard(e) {
  e.preventDefault();
  try {
    await api('/api/cards', {
      method: 'POST',
      body: JSON.stringify({
        bankName: document.getElementById('m-c-bank').value,
        cardLast4: document.getElementById('m-c-last4').value,
        cardNickname: document.getElementById('m-c-nick').value,
        creditLimit: Number(document.getElementById('m-c-limit').value || 0)
      })
    });
    closeModal(); toast('Card added'); await loadData();
  } catch (err) { toast(err.message, true); }
}

function openCardSpendModal() {
  if (!DATA.cards.length) { toast('Add a card first', true); return; }
  var cardOpts = DATA.cards.map(function(c){ return '<option value="' + c.id + '">' + esc(c.displayName) + ' (••' + esc(c.cardLast4) + ')</option>'; }).join('');
  var catList = (DATA.expenseCategories || []).map(function(c){ return '<option value="' + esc(c) + '"></option>'; }).join('');
  openModal(
    '<div class="modal-head"><h3>Add Card Spend</h3><button class="btn sm ghost" onclick="closeModal()">✕</button></div>' +
    '<form class="list" style="gap:10px" onsubmit="saveCardSpend(event)">' +
      '<div class="field"><label>Card</label><select id="m-cs-card" required>' + cardOpts + '</select></div>' +
      '<div class="field"><label>Merchant</label><input id="m-cs-merch" required /></div>' +
      '<div class="field"><label>Amount</label><input id="m-cs-amt" type="number" step="0.01" min="0.01" required /></div>' +
      '<div class="field"><label>Category</label><input id="m-cs-cat" list="m-cs-cats" /><datalist id="m-cs-cats">' + catList + '</datalist></div>' +
      '<div class="field"><label>Date</label><input id="m-cs-date" type="datetime-local" value="' + nowLocalValue() + '" /></div>' +
      '<div class="modal-actions"><button type="button" class="btn sec" onclick="closeModal()">Cancel</button><button type="submit" class="btn">Add Spend</button></div>' +
    '</form>'
  );
}
async function saveCardSpend(e) {
  e.preventDefault();
  try {
    await api('/api/cards/transactions', {
      method: 'POST',
      body: JSON.stringify({
        cardId: Number(document.getElementById('m-cs-card').value),
        merchant: document.getElementById('m-cs-merch').value,
        amount: Number(document.getElementById('m-cs-amt').value),
        category: document.getElementById('m-cs-cat').value,
        occurredAt: parseLocalDate(document.getElementById('m-cs-date').value)
      })
    });
    closeModal(); toast('Card spend added'); await loadData();
  } catch (err) { toast(err.message, true); }
}

function openAddBillModal() {
  if (!DATA.cards.length) { toast('Add a card first', true); return; }
  var cardOpts = DATA.cards.map(function(c){ return '<option value="' + c.id + '">' + esc(c.displayName) + ' (••' + esc(c.cardLast4) + ')</option>'; }).join('');
  openModal(
    '<div class="modal-head"><h3>Add Credit Card Bill</h3><button class="btn sm ghost" onclick="closeModal()">✕</button></div>' +
    '<form class="list" style="gap:10px" onsubmit="saveNewBill(event)">' +
      '<div class="field"><label>Card</label><select id="m-b-card" required>' + cardOpts + '</select></div>' +
      '<div class="field"><label>Bill Period</label><input id="m-b-period" placeholder="Nov 2026" required /></div>' +
      '<div class="field"><label>Bill Amount</label><input id="m-b-total" type="number" step="0.01" min="0.01" required /></div>' +
      '<div class="field"><label>Min Due</label><input id="m-b-min" type="number" step="0.01" value="0" required /></div>' +
      '<div class="field"><label>Due Date (dd/MM/yyyy)</label><input id="m-b-due" placeholder="25/11/2026" required /></div>' +
      '<div class="modal-actions"><button type="button" class="btn sec" onclick="closeModal()">Cancel</button><button type="submit" class="btn">Add Bill</button></div>' +
    '</form>'
  );
}
async function saveNewBill(e) {
  e.preventDefault();
  try {
    await api('/api/bills', {
      method: 'POST',
      body: JSON.stringify({
        cardId: Number(document.getElementById('m-b-card').value),
        billPeriod: document.getElementById('m-b-period').value,
        totalDue: Number(document.getElementById('m-b-total').value),
        minDue: Number(document.getElementById('m-b-min').value || 0),
        dueDate: document.getElementById('m-b-due').value
      })
    });
    closeModal(); toast('Bill added'); await loadData();
  } catch (err) { toast(err.message, true); }
}

function openPayBillModal(billId) {
  var b = DATA.bills.find(function(x){ return x.billId === billId; });
  if (!b) return;
  openModal(
    '<div class="modal-head"><h3>Pay Bill</h3><button class="btn sm ghost" onclick="closeModal()">✕</button></div>' +
    '<div class="muted">' + esc(b.displayName) + ' (' + esc(b.billPeriod) + ') • Outstanding: <strong class="danger">' + fmt(b.outstanding, b.currency) + '</strong></div>' +
    '<form class="list" style="gap:10px" onsubmit="submitBillPay(event,' + billId + ')">' +
      '<div class="field"><label>Total Paid Amount (leave full for paid in full)</label><input id="m-p-amt" type="number" step="0.01" value="' + b.totalDue + '" required /></div>' +
      '<div class="modal-actions">' +
        '<button type="button" class="btn sec" onclick="closeModal()">Cancel</button>' +
        '<button type="button" class="btn red" onclick="markBillFullPaid(' + billId + ')">Mark Full Paid</button>' +
        '<button type="submit" class="btn">Save Amount</button>' +
      '</div>' +
    '</form>'
  );
}
async function submitBillPay(e, billId) {
  e.preventDefault();
  try {
    await api('/api/bills/' + billId + '/pay', { method:'POST', body: JSON.stringify({ amount: Number(document.getElementById('m-p-amt').value) }) });
    closeModal(); toast('Payment updated'); await loadData();
  } catch (err) { toast(err.message, true); }
}
async function markBillFullPaid(billId) {
  try {
    await api('/api/bills/' + billId + '/pay', { method:'POST', body: JSON.stringify({}) });
    closeModal(); toast('Bill marked paid'); await loadData();
  } catch (err) { toast(err.message, true); }
}
async function unpayBill(billId) {
  try { await api('/api/bills/' + billId + '/unpay', { method:'POST' }); toast('Marked unpaid'); await loadData(); }
  catch (err) { toast(err.message, true); }
}

function openEditBillModal(billId) {
  var b = DATA.bills.find(function(x){ return x.billId === billId; });
  if (!b) return;
  openModal(
    '<div class="modal-head"><h3>Edit Bill</h3><button class="btn sm ghost" onclick="closeModal()">✕</button></div>' +
    '<form class="list" style="gap:10px" onsubmit="saveEditBill(event,' + billId + ')">' +
      '<div class="field"><label>Bill Amount</label><input id="m-eb-total" type="number" step="0.01" value="' + b.totalDue + '" required /></div>' +
      '<div class="field"><label>Min Due</label><input id="m-eb-min" type="number" step="0.01" value="' + b.minDue + '" required /></div>' +
      '<div class="field"><label>Due Date (dd/MM/yyyy)</label><input id="m-eb-due" value="' + esc(b.dueDate) + '" required /></div>' +
      '<div class="modal-actions"><button type="button" class="btn sec" onclick="closeModal()">Cancel</button><button type="submit" class="btn">Save</button></div>' +
    '</form>'
  );
}
async function saveEditBill(e, billId) {
  e.preventDefault();
  try {
    await api('/api/bills/' + billId, {
      method: 'PUT',
      body: JSON.stringify({
        totalDue: Number(document.getElementById('m-eb-total').value),
        minDue: Number(document.getElementById('m-eb-min').value),
        dueDate: document.getElementById('m-eb-due').value
      })
    });
    closeModal(); toast('Bill updated'); await loadData();
  } catch (err) { toast(err.message, true); }
}

loadData();
</script>
</body>
</html>
""".trimIndent()
