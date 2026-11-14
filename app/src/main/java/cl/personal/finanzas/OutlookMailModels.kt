package cl.personal.finanzas

import androidx.room.Entity
import androidx.room.PrimaryKey
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Datos mínimos extraídos. Nunca almacenamos el texto completo del correo. */
@Entity(tableName = "mail_candidates")
data class MailCandidate(
    @PrimaryKey val sourceId: String,
    val operationDate: String,
    val description: String,
    val amountClp: Long,
    val account: String = "Banco de Chile",
    val category: String = "Correo bancario",
    val status: String = "PENDIENTE",
    val note: String = ""
)

object ChileBankEmailParser {
    private val dateFormat = DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ROOT)
    private val purchase = Regex(
        """(?is)se\s+ha\s+realizado\s+una\s+compra\s+por\s+\$\s*([0-9][0-9.,]*)\s+con\s+cargo\s+a\s+cuenta\s+\*{2,}\s*(\d{4})\s+en\s+(.+?)\s+el\s+(\d{2}/\d{2}/\d{4})\s+(\d{2}:\d{2})"""
    )
    private val amount = Regex("""(?i)\bmonto(?:\s+transferencia)?\s*[:|]?\s*\$\s*([0-9][0-9.,]*)""")
    private val date = Regex("""(?i)\bfecha\s*[:|]?\s*(\d{2}[/-]\d{2}[/-]\d{4})""")
    private val destination = Regex("""(?is)cuenta\s+(?:de\s+)?destino\s*[:|]?\s*(.{1,160}?)\s*(?:\bmonto\b|\bnúmero\b|\bnro\b)""")


    private fun parseNewTransfer(body: String, subject: String, hash: String, suffix: String): MailCandidate? {
        if (subject !in setOf("transferencia a terceros", "transferencia entre mis cuentas")) return null
        val normalized = body.replace(Regex("""\s+"""), " ")
        val origin = Regex("""(?is)\bOrigen\b(.*?)\bDestino\b""").find(normalized)?.groupValues?.get(1) ?: return null
        val dest = Regex("""(?is)\bDestino\b(.*?)\bMonto\b""").find(normalized)?.groupValues?.get(1) ?: return null
        val accountPattern = Regex("""(?i)N[°ºo]?\s*de\s*Cuenta\s*([0-9][0-9-]{5,})""")
        val originNumber = accountPattern.find(origin)?.groupValues?.get(1)?.filter(Char::isDigit) ?: return null
        val destNumber = accountPattern.find(dest)?.groupValues?.get(1)?.filter(Char::isDigit) ?: return null
        val originMatch = originNumber.endsWith(suffix)
        val destMatch = destNumber.endsWith(suffix)
        if (!originMatch && !destMatch) return null
        val internal = subject == "transferencia entre mis cuentas"
        if (!internal && !originMatch) return null
        val amountMatch = Regex("""(?i)\bMonto\s*\$\s*([0-9][0-9.,]*)""").find(normalized) ?: return null
        val value = parseClp(amountMatch.groupValues[1]) ?: return null
        val months = mapOf("enero" to 1, "febrero" to 2, "marzo" to 3, "abril" to 4,
            "mayo" to 5, "junio" to 6, "julio" to 7, "agosto" to 8,
            "septiembre" to 9, "octubre" to 10, "noviembre" to 11, "diciembre" to 12)
        val dateMatch = Regex("""(?i)(?:lunes|martes|mi[eé]rcoles|jueves|viernes|s[aá]bado|domingo)\s+(\d{1,2})\s+de\s+([a-záéíóú]+)\s+de\s+(\d{4})""").find(normalized) ?: return null
        val day = try {
            LocalDate.of(dateMatch.groupValues[3].toInt(),
                months[dateMatch.groupValues[2].lowercase(Locale.ROOT)] ?: return null,
                dateMatch.groupValues[1].toInt())
        } catch (_: Exception) { return null }
        val recipient = Regex("""(?is)Nombre\s+y\s+Apellido\s+(.+?)\s+Rut\b""")
            .find(dest)?.groupValues?.get(1)?.trim()?.take(65)
        return MailCandidate(
            sourceId = hash, operationDate = day.toString(),
            description = if (internal) "Traspaso entre mis cuentas" else "Transferencia a ${recipient ?: "tercero"}",
            amountClp = if (internal && destMatch && !originMatch) value else -value,
            category = if (internal) "Transferencia interna" else "Transferencia",
            note = if (internal) "Requiere asociar ambas cuentas propias. No registrar como gasto ni ingreso."
                else "Transferencia enviada; comprobar contra cartola."
        )
    }

    fun parse(message: JSONObject, accountLast4: String): MailCandidate? {
        if (!Regex("""\d{4}""").matches(accountLast4)) return null
        val address = message.optJSONObject("sender")?.optJSONObject("emailAddress")?.optString("address")
            ?: message.optJSONObject("from")?.optJSONObject("emailAddress")?.optString("address")
            ?: return null
        // Filtro conservador: el nombre del remitente no es suficiente.
        if (address.lowercase(Locale.ROOT) !in setOf(
                "enviodigital@bancochile.cl",
                "serviciodetransferencias@bancochile.cl"
            )) return null
        val subject = message.optString("subject").trim().lowercase(Locale.ROOT)
        val rawBody = message.optJSONObject("body")?.optString("content")?.takeIf { it.isNotBlank() }
            ?: message.optString("bodyPreview")
        val body = rawBody.replace("\u200d", "").replace("\u00a0", " ")
        val source = message.optString("id")
        if (source.isEmpty()) return null
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(("outlook:" + source).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        if (subject == "cargo en cuenta") {
            val m = purchase.find(body) ?: return null
            if (m.groupValues[2] != accountLast4) return null
            val dateValue = parseDate(m.groupValues[4]) ?: return null
            val value = parseClp(m.groupValues[1]) ?: return null
            val merchant = m.groupValues[3].replace(Regex("""\s+"""), " ").trim().take(100)
            if (merchant.isBlank()) return null
            return MailCandidate(
                sourceId = hash, operationDate = dateValue.toString(),
                description = "Pago:$merchant", amountClp = -value,
                category = "Compra", note = "Aviso por correo; comprueba con tu cartola"
            )
        }
        parseNewTransfer(body, subject, hash, accountLast4)?.let { return it }
        if (subject != "aviso de transferencia de fondos") return null
        if (!body.contains("comprobante de transferencia", ignoreCase = true)) return null
        val amountValue = amount.find(body)?.groupValues?.get(1)?.let(::parseClp) ?: return null
        val dateValue = date.find(body)?.groupValues?.get(1)?.let(::parseDate) ?: return null
        val destBlock = destination.find(body)?.groupValues?.get(1) ?: return null
        // Si el propio número aparece como destino, es entrada; de lo contrario,
        // es una salida potencial que SIEMPRE queda pendiente de revisión.
        val digits = destBlock.filter { it.isDigit() }
        if (digits.length < 6) return null
        val incoming = digits.endsWith(accountLast4)
        val recipient = Regex("""(?i)(?:Nombre\s+y\s+Apellido|Estimado\(a\))\s*[:|]?\s*([^\r\n|]{2,90})""")
            .find(body)?.groupValues?.get(1)?.replace("*", "")?.trim()?.take(75)
        val note = if (incoming)
            "Transferencia recibida; verificar contra cartola"
        else "Posible transferencia enviada; revisar destinatario y cuenta de origen"
        return MailCandidate(
            sourceId = hash, operationDate = dateValue.toString(),
            description = if (incoming) "Transferencia recibida" else "Transferencia a ${recipient?.ifBlank { "destinatario" } ?: "destinatario"}",
            amountClp = if (incoming) amountValue else -amountValue,
            category = "Transferencia",
            note = note
        )
    }

    private fun parseDate(raw: String): LocalDate? = try {
        LocalDate.parse(raw.replace('-', '/'), dateFormat)
    } catch (_: Exception) { null }

    private fun parseClp(raw: String): Long? {
        val value = raw.filter(Char::isDigit).toLongOrNull()
        return value?.takeIf { it > 0 && it < 1_000_000_000_000L }
    }

    fun possibleDuplicate(candidate: MailCandidate, movements: List<Movement>): Boolean {
        val date = try { LocalDate.parse(candidate.operationDate) } catch (_: Exception) { return false }
        return movements.any { movement ->
            movement.account == candidate.account && movement.amountClp == candidate.amountClp &&
                try { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(movement.date), date)) <= 1L }
                catch (_: Exception) { false }
        }
    }
}
