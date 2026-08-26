import java.util.regex.Pattern

fun main() {
    val body = "BDT 3031.4 has been credited to your a/c ***674 from MD NAZIM UDDIN on 27-JUL-26 through PI Banking.A/C Bal:-17874"
    val balanceAfter = Regex(
        """(?:Current\s+Balance|A/?C\s*Bal(?:ance)?|C/?B|(?:Account\s+)?Balance|Bal)\s*(?:is)?\s*:?\s*(?:Tk|TK|BDT)?\.?\s*(?:CR|DR)?\.?\s*(-?)\s*([\d,]+(?:\.\d+)?)""",
        RegexOption.IGNORE_CASE
    )
    val m = balanceAfter.find(body)
    if (m != null) {
        println("group 1: '${m.groupValues[1]}'")
        println("group 2: '${m.groupValues[2]}'")
    } else {
        println("No match")
    }
}
