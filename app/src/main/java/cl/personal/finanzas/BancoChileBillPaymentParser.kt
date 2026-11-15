package cl.personal.finanzas

import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/**
 * Comprobante de Pago Cuentas del Banco de Chile (APP Mi Banco).
 *
 * Se registra UN solo candidato por el total debitado. Cada empresa pagada se
 * mantiene como detalle informativo; dividir el pago en varios cargos sin
 * comprobar la cartola podría descontar el mismo dinero dos veces.
 *
 * El remitente y el asunto se validan antes de llamar a este lector.
 * Ante cualquier campo ambiguo se omite el aviso, sin alterar saldos.
 */
object BancoChileBillPaymentParser {
    private data class Bill(val merchant: String, val amount: Long)

    private val months = mapOf(
        "enero" to 1, "febrero" to 2, "marzo" to 3, "abril" to 4,
        "mayo" to 5, "junio" to 6, "julio" to 7, "agosto" to 8,
        "septiembre" to 9, "octubre" to 10, "noviembre" to 11,
        "diciembre" to 12
    )
    private val billedCompany = Regex(
        """\bempresa\s*:?\s*([a-z0-9][a-z0-9 .,&()\-]{1,85}?)\s+\bid\s*:?\s*([a-z0-9\-]{3,35})\s+\bmonto\s*:?\s*\$?\s*([0-9][0-9.,]*)""",
        RegexOption.IGNORE_CASE
    )
    private val total = Regex(
        """\btotal\s*:?\s*\$?\s*([0-9][0-9.,]*)""",
        RegexOption.IGNORE_CASE
    )
    private val paymentAccount = Regex(
        """\bmedio\s+de\s+pago\s*:?\s*cuenta\s+(?:corriente|vista)\s*\*+\s*(\d{4})\b""",
        RegexOption.IGNORE_CASE
    )
    private val paymentDate = Regex(
        """\bfecha\s+y\s+hora\s*:?\s*(?:(?:lunes|martes|miercoles|jueves|viernes|sabado|domingo)\s*,?\s*)?(\d{1,2})\s+de\s+([a-z]+)\s+de\s+(\d{4})\s+(\d{1,2}:\d{2})\b""",
        RegexOption.IGNORE_CASE
    )
    private val hasReceiptHeading = Regex(
        """\bcomprobante\s+de\s+pago\s+de\s+cuentas\b""",
        RegexOption.IGNORE_CASE
    )

    private fun cleaned(body: String): String =
        Normalizer.normalize(body.replace('\u00a0', ' ').replace('\u200d', ' '),
            Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun clp(raw: String): Long? {
        val amount = raw.filter(Char::isDigit).toLongOrNull()
        return amount?.takeIf { it in 1L..999_999_999_999L }
    }

    fun parse(body: String, sourceId: String, accountLast4: String): MailCandidate? {
        if (sourceId.isBlank() || !Regex("""\d{4}""").matches(accountLast4)) return null
        val text = cleaned(body.take(150_000))
        if (!hasReceiptHeading.containsMatchIn(text)) return null

        // El origen debe ser la misma cuenta que se configuró en Outlook.
        val digits = paymentAccount.find(text)?.groupValues?.get(1) ?: return null
        if (digits != accountLast4) return null

        val totalValue = total.find(text)?.groupValues?.get(1)?.let(::clp) ?: return null
        val found = billedCompany.findAll(text).toList()
        if (found.isEmpty() || found.size > 20) return null

        val bills = found.map { match ->
            val merchant = match.groupValues[1].trim()
            val amount = clp(match.groupValues[3]) ?: return null
            if (merchant.isBlank()) return null
            Bill(merchant.replaceFirstChar { it.titlecase(Locale.forLanguageTag("es-CL")) },
                amount)
        }

        // Verifica el documento antes de proponer la salida bancaria.
        if (bills.sumOf { it.amount } != totalValue) return null

        val dateMatch = paymentDate.find(text) ?: return null
        val day = dateMatch.groupValues[1].toIntOrNull() ?: return null
        val month = months[dateMatch.groupValues[2]] ?: return null
        val year = dateMatch.groupValues[3].toIntOrNull() ?: return null
        val date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        val clock = dateMatch.groupValues[4]
        if (runCatching { LocalTime.parse(clock) }.isFailure ||
            date.isAfter(LocalDate.now().plusDays(1)) ||
            date.isBefore(LocalDate.now().minusYears(3))) return null

        val merchants = bills.map { it.merchant }.distinct()
        val description = ("Pago de cuentas: " + merchants.joinToString(" y ")).take(180)
        val detail = bills.joinToString("; ") { "${it.merchant}: $${it.amount}" }
            .take(390)
        return MailCandidate(
            sourceId = sourceId,
            operationDate = date.toString(),
            description = description,
            amountClp = -totalValue,
            category = "Gastos comunes",
            note = "Comprobante Banco de Chile. $detail. " +
                "Total pagado: $totalValue. Verifica que no esté en la cartola " +
                "o asociado a gastos previstos. Hora operación: $clock"
        )
    }
}
