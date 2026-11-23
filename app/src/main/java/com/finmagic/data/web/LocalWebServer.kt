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
 * to view, input, and edit FinMagic accounts, transactions, cards, and bills via browser.
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
                val occurredAt = obj.get("occurredAt")?.asLong ?: System.currentTimeMillis()
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
                val occurredAt = obj.get("occurredAt")?.asLong ?: System.currentTimeMillis()
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
        val txns = txnDao.observeAllWithAccount().first().take(150)
        val cardTxns = cardTransactionDao.observeAll().first().take(100)
        val expenseCategories = preferenceManager.cashExpenseCategories.first()
        val incomeCategories = preferenceManager.cashIncomeCategories.first()

        val accountDtos = accounts.map { acct ->
            val acctTxns = txnDao.getAllByAccountOnce(acct.id)
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
            mapOf(
                "id" to c.id,
                "displayName" to c.displayName,
                "bankName" to c.bankName,
                "cardLast4" to c.cardLast4,
                "cardNickname" to c.cardNickname,
                "creditLimit" to c.creditLimit,
                "currency" to c.creditLimitCurrency,
                "outstanding" to cardUnpaid
            )
        }

        val billDtos = bills.filter { !it.isSuperseded }.map { b ->
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
                "currency" to b.currency
            )
        }

        val txnDtos = txns.map { t ->
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
                "isManual" to t.isManual
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

    private fun buildPortalHtml(): String = """
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width,initial-scale=1" />
  <title>FinMagic Web Portal</title>
  <style>
    :root { --bg:#0f172a; --card:#1e293b; --muted:#94a3b8; --text:#f8fafc; --primary:#38bdf8; --danger:#ef4444; --ok:#22c55e; --border:#334155; }
    * { box-sizing:border-box; }
    body { margin:0; font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif; background:var(--bg); color:var(--text); }
    header { padding:14px 18px; background:#020617; border-bottom:1px solid var(--border); display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:10px; }
    header h1 { margin:0; font-size:18px; }
    .tabs { display:flex; gap:8px; flex-wrap:wrap; }
    .tab { padding:8px 14px; border-radius:10px; border:1px solid var(--border); background:var(--card); color:var(--text); cursor:pointer; font-weight:600; }
    .tab.active { background:var(--primary); color:#020617; border-color:var(--primary); }
    main { max-width:1100px; margin:0 auto; padding:10px; display:grid; gap:8px; }
    .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(210px,1fr)); gap:8px; }
    .card { background:var(--card); border:1px solid var(--border); border-radius:12px; padding:10px; }
    .label { font-size:12px; color:var(--muted); text-transform:uppercase; letter-spacing:.05em; }
    .val { font-size:22px; font-weight:700; margin-top:2px; }
    .danger { color:var(--danger); }
    .ok { color:var(--ok); }
    h2 { margin:0 0 6px 0; font-size:15px; }
    .row { display:flex; justify-content:space-between; align-items:center; gap:8px; padding:6px 0; border-bottom:1px solid var(--border); flex-wrap:wrap; }
    .row:last-child { border-bottom:none; }
    form.inline { display:grid; grid-template-columns:repeat(auto-fit,minmax(140px,1fr)); gap:8px; margin-top:8px; }
    input, select, button { padding:8px 10px; border-radius:8px; border:1px solid var(--border); background:#0f172a; color:var(--text); font-size:14px; }
    button { cursor:pointer; font-weight:600; background:var(--primary); color:#020617; border:none; }
    button.sec { background:#334155; color:var(--text); }
    button.red { background:var(--danger); color:#fff; }
    .pill { display:inline-block; font-size:11px; padding:2px 8px; border-radius:99px; background:#334155; margin-left:6px; }
    .pill.liab { background:rgba(239,68,68,.2); color:#fca5a5; }
    .hidden { display:none; }
    #toast { position:fixed; bottom:18px; right:18px; background:#020617; border:1px solid var(--primary); padding:10px 14px; border-radius:10px; display:none; }
  </style>
</head>
<body>
<header>
  <h1>FinMagic Web Portal</h1>
  <div class="tabs">
    <button class="tab active" onclick="showTab('overview', this)">Overview</button>
    <button class="tab" onclick="showTab('accounts', this)">Accounts & Types</button>
    <button class="tab" onclick="showTab('txns', this)">Transactions</button>
    <button class="tab" onclick="showTab('cards', this)">Cards & Bills</button>
    <button class="sec" onclick="loadData()">Refresh</button>
  </div>
</header>
<main>
  <section id="tab-overview">
    <div class="grid" id="summary-cards"></div>
    <div class="card" style="margin-top:14px">
      <h2>Quick Add Account Transaction</h2>
      <form class="inline" onsubmit="addTxn(event)">
        <select id="q-acct" required></select>
        <select id="q-dir"><option value="OUT">Expense (OUT)</option><option value="IN">Income (IN)</option></select>
        <input id="q-amt" type="number" step="0.01" placeholder="Amount" required />
        <input id="q-cat" placeholder="Category (Food, Salary...)" />
        <input id="q-note" placeholder="Counterparty / Note" />
        <button type="submit">Add Entry</button>
      </form>
    </div>
    <div class="card" style="margin-top:14px">
      <h2>Unpaid Credit Card Bills</h2>
      <div id="overview-bills"></div>
    </div>
  </section>

  <section id="tab-accounts" class="hidden">
    <div class="card">
      <h2>Create Account</h2>
      <form class="inline" onsubmit="addAccount(event)">
        <input id="a-name" placeholder="Account Name" required />
        <select id="a-type"></select>
        <input id="a-bank" placeholder="Bank (optional)" />
        <input id="a-bal" type="number" step="0.01" placeholder="Initial Balance" value="0" />
        <label style="display:flex;align-items:center;gap:6px"><input id="a-liab" type="checkbox" /> Liability</label>
        <button type="submit">Add Account</button>
      </form>
    </div>
    <div class="card" style="margin-top:14px">
      <h2>Custom Account Types</h2>
      <form class="inline" onsubmit="addType(event)">
        <input id="t-name" placeholder="Type name (e.g. DPS, Mortgage)" required />
        <label style="display:flex;align-items:center;gap:6px"><input id="t-liab" type="checkbox" /> Is Liability</label>
        <button type="submit">Create Type</button>
      </form>
      <div id="types-list" style="margin-top:10px"></div>
    </div>
    <div class="card" style="margin-top:14px">
      <h2>Accounts by Type (Tap Edit to update name, type, balance)</h2>
      <div id="accounts-grouped"></div>
    </div>
  </section>

  <section id="tab-txns" class="hidden">
    <div class="card">
      <h2>Account Transactions</h2>
      <div id="txns-list"></div>
    </div>
    <div class="card" style="margin-top:14px">
      <h2>Card Spend Transactions</h2>
      <div id="card-txns-list"></div>
    </div>
  </section>

  <section id="tab-cards" class="hidden">
    <div class="card">
      <h2>Add Credit Card / Spend / Bill</h2>
      <form class="inline" onsubmit="addCard(event)">
        <input id="c-bank" placeholder="Bank Name" required />
        <input id="c-last4" maxlength="4" placeholder="Last 4 digits" required />
        <input id="c-nick" placeholder="Nickname (optional)" />
        <input id="c-limit" type="number" step="0.01" placeholder="Limit" />
        <button type="submit">Add Card</button>
      </form>
      <form class="inline" style="margin-top:10px" onsubmit="addCardTxn(event)">
        <select id="ct-card" required></select>
        <input id="ct-merch" placeholder="Merchant" required />
        <input id="ct-amt" type="number" step="0.01" placeholder="Amount" required />
        <input id="ct-cat" placeholder="Category (optional)" />
        <button type="submit">Add Card Spend</button>
      </form>
      <form class="inline" style="margin-top:10px" onsubmit="addBill(event)">
        <select id="b-card" required></select>
        <input id="b-period" placeholder="Period (e.g. Nov 2026)" required />
        <input id="b-total" type="number" step="0.01" placeholder="Bill Amount" required />
        <input id="b-min" type="number" step="0.01" placeholder="Min Due" required />
        <input id="b-due" placeholder="Due Date (dd/MM/yyyy)" required />
        <button type="submit">Add Bill</button>
      </form>
    </div>
    <div class="card" style="margin-top:14px">
      <h2>Credit Cards (Total Outstanding: <span id="cards-total" class="danger"></span>)</h2>
      <div id="cards-list"></div>
    </div>
    <div class="card" style="margin-top:14px">
      <h2>Bills (Unpaid First, Paid Last)</h2>
      <div id="bills-list"></div>
    </div>
  </section>
</main>
<div id="toast"></div>
<script>
let DATA = null;
const fmt = n => '৳' + Number(n || 0).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
function toast(msg){ const el=document.getElementById('toast'); el.textContent=msg; el.style.display='block'; setTimeout(()=>el.style.display='none',2600); }
function showTab(id, btn){
  document.querySelectorAll('main section').forEach(s => s.classList.add('hidden'));
  document.getElementById('tab-' + id).classList.remove('hidden');
  document.querySelectorAll('.tabs .tab').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
}
async function api(url, opts={}){
  const r = await fetch(url, { headers:{'Content-Type':'application/json'}, ...opts });
  const j = await r.json();
  if(!r.ok || j.ok === false) throw new Error(j.error || 'Request failed');
  return j;
}
async function loadData(){
  DATA = await api('/api/overview');
  render();
}
function render(){
  const s = DATA.summary;
  document.getElementById('summary-cards').innerHTML = `
    <div class="card"><div class="label">Net Worth</div><div class="val">${'$'}{fmt(s.netWorth)}</div></div>
    <div class="card"><div class="label">Assets</div><div class="val ok">${'$'}{fmt(s.assetsTotal)}</div></div>
    <div class="card"><div class="label">Account Liabilities</div><div class="val danger">${'$'}{fmt(s.accountLiabilitiesTotal)}</div></div>
    <div class="card"><div class="label">Card Total (Liability)</div><div class="val danger">${'$'}{fmt(s.cardLiabilityTotal)}</div></div>`;
  document.getElementById('cards-total').textContent = fmt(s.cardLiabilityTotal);

  const acctOpts = DATA.accounts.map(a => `<option value="${'$'}{a.id}">${'$'}{esc(a.name)} (${'$'}{esc(a.accountType)})</option>`).join('');
  document.getElementById('q-acct').innerHTML = acctOpts;

  const typeOpts = DATA.accountTypes.map(t => `<option value="${'$'}{esc(t.name)}">${'$'}{esc(t.name)}${'$'}{t.isLiability?' (Liability)':''}</option>`).join('');
  document.getElementById('a-type').innerHTML = typeOpts;
  document.getElementById('a-type').onchange = () => {
    const sel = DATA.accountTypes.find(t => t.name === document.getElementById('a-type').value);
    if(sel) document.getElementById('a-liab').checked = !!sel.isLiability;
  };

  document.getElementById('types-list').innerHTML = DATA.accountTypes.map(t => `
    <span class="pill ${'$'}{t.isLiability?'liab':''}">${'$'}{esc(t.name)} ${'$'}{t.isLiability?'(Liability)':'(Asset)'}
      ${'$'}{t.isCustom ? `<a href="#" style="color:#fff;margin-left:4px" onclick="delType('${'$'}{encodeURIComponent(t.name)}');return false">✕</a>` : ''}
    </span>`).join('');

  const grouped = {};
  DATA.accounts.forEach(a => { (grouped[a.accountType] = grouped[a.accountType] || []).push(a); });
  let acctHtml = '';
  Object.entries(grouped).forEach(([type, list]) => {
    const tot = list.filter(x => x.includeInTotal).reduce((acc, x) => acc + (x.isLiability ? -Math.abs(x.balance) : x.balance), 0);
    acctHtml += `<div style="margin-top:10px"><strong>${'$'}{esc(type)}</strong> — Type Total: <strong>${'$'}{fmt(tot)}</strong>`;
    list.forEach(a => {
      acctHtml += `<div class="row">
        <div><strong>${'$'}{esc(a.name)}</strong> <span class="pill ${'$'}{a.isLiability?'liab':''}">${'$'}{a.isLiability?'Liability':'Asset'}</span><div class="label">${'$'}{esc(a.accountMask)}</div></div>
        <div style="display:flex;align-items:center;gap:8px">
          <strong class="${'$'}{a.isLiability?'danger':''}">${'$'}{fmt(a.balance)}</strong>
          <button class="sec" onclick="editAccount(${'$'}{a.id})">Edit</button>
        </div>
      </div>`;
    });
    acctHtml += '</div>';
  });
  document.getElementById('accounts-grouped').innerHTML = acctHtml || '<div class="label">No accounts</div>';

  const cardOpts = DATA.cards.map(c => `<option value="${'$'}{c.id}">${'$'}{esc(c.displayName)} (••${'$'}{esc(c.cardLast4)})</option>`).join('');
  document.getElementById('ct-card').innerHTML = cardOpts;
  document.getElementById('b-card').innerHTML = cardOpts;

  document.getElementById('cards-list').innerHTML = DATA.cards.map(c => `
    <div class="row">
      <div><strong>${'$'}{esc(c.displayName)}</strong><div class="label">•••• ${'$'}{esc(c.cardLast4)} • Limit ${'$'}{fmt(c.creditLimit)}</div></div>
      <div class="danger"><strong>${'$'}{fmt(c.outstanding)}</strong></div>
    </div>`).join('') || '<div class="label">No cards</div>';

  const unpaid = DATA.bills.filter(b => !b.isPaid);
  const paid = DATA.bills.filter(b => b.isPaid);
  const billRow = b => `
    <div class="row">
      <div>
        <strong>${'$'}{esc(b.displayName)}</strong> (${'$'}{esc(b.billPeriod)})
        <div class="label">Bill: ${'$'}{fmt(b.totalDue)} • <span class="${'$'}{b.isPaid?'ok':'danger'}">Outstanding: ${'$'}{fmt(b.outstanding)}</span> • Due: ${'$'}{esc(b.formattedDueDate)}</div>
      </div>
      <div style="display:flex;gap:6px">
        ${'$'}{!b.isPaid ? `<button class="red" onclick="payBill(${'$'}{b.billId})">Pay Bill</button>` : `<button class="sec" onclick="unpayBill(${'$'}{b.billId})">Mark Unpaid</button>`}
        <button class="sec" onclick="editBill(${'$'}{b.billId}, ${'$'}{b.totalDue}, ${'$'}{b.minDue}, '${'$'}{esc(b.dueDate)}')">Edit</button>
      </div>
    </div>`;
  document.getElementById('overview-bills').innerHTML = unpaid.map(billRow).join('') || '<div class="ok">All bills paid!</div>';
  document.getElementById('bills-list').innerHTML = [...unpaid, ...paid].map(billRow).join('') || '<div class="label">No bills</div>';

  document.getElementById('txns-list').innerHTML = DATA.transactions.map(t => `
    <div class="row">
      <div><strong>${'$'}{esc(t.counterparty || t.category || 'Transaction')}</strong><div class="label">${'$'}{esc(t.accountName)} • ${'$'}{esc(t.category || '')} • ${'$'}{new Date(t.occurredAt).toLocaleString()}</div></div>
      <div style="display:flex;align-items:center;gap:8px">
        <strong class="${'$'}{t.direction==='IN'?'ok':'danger'}">${'$'}{t.direction==='IN'?'+':'-'}${'$'}{fmt(t.amount)}</strong>
        <button class="sec" onclick="editTxn(${'$'}{t.id}, ${'$'}{t.amount}, '${'$'}{esc(t.category||'')}', '${'$'}{esc(t.counterparty||'')}')">Edit</button>
        <button class="red" onclick="delTxn(${'$'}{t.id})">Del</button>
      </div>
    </div>`).join('') || '<div class="label">No transactions</div>';

  document.getElementById('card-txns-list').innerHTML = DATA.cardTransactions.map(ct => `
    <div class="row">
      <div><strong>${'$'}{esc(ct.merchant)}</strong><div class="label">${'$'}{esc(ct.bankName)} ••${'$'}{esc(ct.cardTail)} • ${'$'}{new Date(ct.occurredAt).toLocaleString()}</div></div>
      <div style="display:flex;align-items:center;gap:8px">
        <strong class="danger">${'$'}{fmt(ct.amount)}</strong>
        <button class="red" onclick="delCardTxn(${'$'}{ct.id})">Del</button>
      </div>
    </div>`).join('') || '<div class="label">No card spend transactions</div>';
}

async function addTxn(e){ e.preventDefault(); try {
  await api('/api/transactions',{method:'POST',body:JSON.stringify({
    accountId:Number(document.getElementById('q-acct').value),
    direction:document.getElementById('q-dir').value,
    amount:Number(document.getElementById('q-amt').value),
    category:document.getElementById('q-cat').value,
    counterparty:document.getElementById('q-note').value
  })}); e.target.reset(); toast('Transaction added'); await loadData(); } catch(err){ toast(err.message); } }

async function addAccount(e){ e.preventDefault(); try {
  await api('/api/accounts',{method:'POST',body:JSON.stringify({
    name:document.getElementById('a-name').value,
    accountType:document.getElementById('a-type').value,
    bankName:document.getElementById('a-bank').value,
    initialBalance:Number(document.getElementById('a-bal').value||0),
    isLiability:document.getElementById('a-liab').checked
  })}); e.target.reset(); toast('Account created'); await loadData(); } catch(err){ toast(err.message); } }

async function editAccount(id){
  const a = DATA.accounts.find(x => x.id === id); if(!a) return;
  const name = prompt('Account name:', a.name); if(name === null) return;
  const type = prompt('Account type (Cash/Savings/Deposit/Loan/custom):', a.accountType); if(type === null) return;
  const balStr = prompt('Set balance (leave blank to keep current):', String(a.balance));
  const liab = confirm('Is this a Liability? OK = Liability, Cancel = Asset');
  const payload = { name, accountType: type, isLiability: liab };
  if(balStr !== null && balStr.trim() !== '') payload.targetBalance = Number(balStr);
  await api('/api/accounts/' + id, { method:'PUT', body: JSON.stringify(payload) });
  toast('Account updated'); await loadData();
}

async function addType(e){ e.preventDefault(); try {
  await api('/api/account-types',{method:'POST',body:JSON.stringify({
    name:document.getElementById('t-name').value,
    isLiability:document.getElementById('t-liab').checked
  })}); e.target.reset(); toast('Type added'); await loadData(); } catch(err){ toast(err.message); } }

async function delType(name){ await api('/api/account-types/' + name, {method:'DELETE'}); toast('Type removed'); await loadData(); }
async function editTxn(id, amt, cat, note){
  const nAmt = prompt('Amount:', amt); if(nAmt === null) return;
  const nCat = prompt('Category:', cat); if(nCat === null) return;
  const nNote = prompt('Counterparty / Note:', note); if(nNote === null) return;
  await api('/api/transactions/' + id, {method:'PUT', body:JSON.stringify({amount:Number(nAmt), category:nCat, counterparty:nNote})});
  toast('Transaction updated'); await loadData();
}
async function delTxn(id){ if(!confirm('Delete transaction?')) return; await api('/api/transactions/' + id, {method:'DELETE'}); toast('Deleted'); await loadData(); }
async function delCardTxn(id){ if(!confirm('Delete card transaction?')) return; await api('/api/cards/transactions/' + id, {method:'DELETE'}); toast('Deleted'); await loadData(); }
async function addCard(e){ e.preventDefault(); await api('/api/cards',{method:'POST',body:JSON.stringify({
  bankName:document.getElementById('c-bank').value, cardLast4:document.getElementById('c-last4').value,
  cardNickname:document.getElementById('c-nick').value, creditLimit:Number(document.getElementById('c-limit').value||0)
})}); e.target.reset(); toast('Card added'); await loadData(); }
async function addCardTxn(e){ e.preventDefault(); await api('/api/cards/transactions',{method:'POST',body:JSON.stringify({
  cardId:Number(document.getElementById('ct-card').value), merchant:document.getElementById('ct-merch').value,
  amount:Number(document.getElementById('ct-amt').value), category:document.getElementById('ct-cat').value
})}); e.target.reset(); toast('Card spend added'); await loadData(); }
async function addBill(e){ e.preventDefault(); await api('/api/bills',{method:'POST',body:JSON.stringify({
  cardId:Number(document.getElementById('b-card').value), billPeriod:document.getElementById('b-period').value,
  totalDue:Number(document.getElementById('b-total').value), minDue:Number(document.getElementById('b-min').value),
  dueDate:document.getElementById('b-due').value
})}); e.target.reset(); toast('Bill added'); await loadData(); }
async function payBill(id){
  const val = prompt('Leave empty to mark FULL paid, or enter partial paid amount:');
  if(val === null) return;
  const body = val.trim() === '' ? {} : { amount: Number(val) };
  await api('/api/bills/' + id + '/pay', {method:'POST', body:JSON.stringify(body)});
  toast('Bill payment updated'); await loadData();
}
async function unpayBill(id){ await api('/api/bills/' + id + '/unpay', {method:'POST'}); toast('Marked unpaid'); await loadData(); }
async function editBill(id, total, min, due){
  const t = prompt('Bill Amount:', total); if(t === null) return;
  const m = prompt('Min Due:', min); if(m === null) return;
  const d = prompt('Due Date (dd/MM/yyyy):', due); if(d === null) return;
  await api('/api/bills/' + id, {method:'PUT', body:JSON.stringify({totalDue:Number(t), minDue:Number(m), dueDate:d})});
  toast('Bill updated'); await loadData();
}
loadData().catch(err => toast(err.message));
</script>
</body>
</html>
""".trimIndent()
}
