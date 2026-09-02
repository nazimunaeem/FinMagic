package com.finmagic.data.sms

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Parses the date/time styles found in the SMS corpus (plan §8):
 *  "25-11-2024 03:14:00PM", "30-04-25 @11:55 PM", "18-05-25 @03:16 PM",
 *  "24-FEB-26", "26/12/2024 11:51", "05/03/2026 11:52", "05-DEC-24".
 * Two-digit years are pivoted to 20xx (corpus spans Nov 2024 → Aug 2026).
 */
object SmsDateParser {

    private val datePattern = Regex("""\b(\d{1,2}[-/.](?:\d{1,2}|[A-Za-z]{3})[-/.]\d{2,4})\b""")
    private val timePattern = Regex("""(\d{1,2}):(\d{2})(?::(\d{2}))?\s*([AaPp])\.?\s*[Mm]?\.?""")

    /** Parses the first date (and optional time) found in [body]; null when none. */
    fun parseFirst(body: String): Long? = parseAt(body, 0)

    /** Parses the date occurrence at/after [fromIndex]. */
    fun parseAt(body: String, fromIndex: Int): Long? {
        val normalized = body.replace('\u00A0', ' ')
        val dateMatch = datePattern.find(normalized, fromIndex) ?: return null
        return parseDateAndTime(normalized, dateMatch.groupValues[1])
    }

    fun parseDateAndTime(body: String, dateText: String): Long? {
        val normalizedDate = try {
            BankSmsParser.normalizeDate(dateText)
        } catch (_: Exception) {
            return null
        }
        val dayMillis = try {
            val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
            sdf.isLenient = false
            sdf.parse(normalizedDate)?.time ?: return null
        } catch (_: Exception) {
            return null
        }

        val timeMatch = timePattern.find(body) ?: return null
        val hour12 = timeMatch.groupValues[1].toIntOrNull() ?: return null
        val minute = timeMatch.groupValues[2].toIntOrNull() ?: 0
        val second = timeMatch.groupValues[3].toIntOrNull() ?: 0
        val meridiem = timeMatch.groupValues[4].uppercase()

        if (hour12 !in 1..12 || minute !in 0..59 || second !in 0..59) return null

        val cal = Calendar.getInstance()
        cal.timeInMillis = dayMillis
        cal.set(Calendar.HOUR_OF_DAY, if (meridiem == "PM" && hour12 != 12) hour12 + 12 else if (meridiem == "AM" && hour12 == 12) 0 else hour12)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, second)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Epoch for a normalized "dd/MM/yyyy" string at midnight. */
    fun dayMillisFromNormalized(normalizedDate: String): Long? = try {
        val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
        sdf.isLenient = false
        sdf.parse(normalizedDate)?.time
    } catch (_: Exception) {
        null
    }
}
