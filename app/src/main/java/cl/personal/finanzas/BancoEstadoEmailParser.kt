package cl.personal.finanzas

import android.text.Html
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Reconoce comprobantes de TEF salientes de BancoEstado cuando sus datos
 * están presentes como texto HTML (sin OCR para comprobantes solo en imagen).
 * Únicamente crea candidatos para revisión: jamás cambia saldos por sí solo.
 */
object BancoEstadoEmailParser {
    private const val SENDER = "noreply@correo.bancoestado.cl"
    private val dateFormat = DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ROOT)

    fun isSupportedSender(address: String): Boolean =
        address.trim().equals(SENDER, ignoreCase = true)

    private fun normal(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.ROOT)

    private fun textFromMessage(message: JSONObject): String {
        val content = message.optJSONObject("body")?.optString("content").orEmpty()
        val fallback = message.optString("bodyPreview")
        val raw = content.ifBlank { fallback }
        val text = if (raw.contains("<") && raw.contains(">")) {
            Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        } else raw
        return normal(text.replace('\u00a0', ' ').replace('\u200d', ' '))
            .replace(Regex("""[ \t]+"""), " ")
    }

    fun parse(message: JSONObject): MailCandidate? {
        val sender = message.optJSONObject("sender")?.optJSONObject("emailAddress")
            ?.optString("address").orEmpty().ifBlank {
                message.optJSONObject("from")?.optJSONObject("emailAddress")
                    ?.optString("address").orEmpty()
            }
        if (!isSupportedSender(sender)) return null
        if (normal(message.optString("subject").trim()) !=
            "aviso de envio o recepcion de dinero") return null

        val source = message.optString("id").takeIf { it.isNotBlank() } ?: return null
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(("outlook:" + source).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val body = textFromMessage(message)
        if (!body.contains("comprobante de transferencia electronica de fondos") ||
            !body.contains("acabas de realizar una transferencia electronica")) return null

        // El comprobante aportado confirma un ENVÍO. No inferir recepciones
        // a partir del asunto, que sirve para ambos sentidos.
        val origin = Regex("""(?s)\bdesde\s*:\s*(.*?)\bhacia\s*:""")
            .find(body)?.groupValues?.get(1) ?: return null
        val destination = Regex("""(?s)\bhacia\s*:\s*(.*)""")
            .find(body)?.groupValues?.get(1) ?: return null
        if (!origin.contains("cuentarut")) return null
        val amountRaw = Regex("""\bmonto\s+transferido\s*[:\-]?\s*\$\s*([0-9][0-9.,]*)""")
            .find(body)?.groupValues?.get(1) ?: return null
        val amount = amountRaw.filter(Char::isDigit).toLongOrNull()
            ?.takeIf { it in 1L..999_999_999_999L } ?: return null

        val dateMatch = Regex(
            """\bfecha\s+y\s+hora\s+(?:de\s+)?tef\s*[:\-]?\s*(\d{1,2}[/-]\d{1,2}[/-]\d{4})\s+(\d{1,2}:\d{2})(?::\d{2})?"""
        ).find(origin) ?: return null
        val date = runCatching {
            LocalDate.parse(dateMatch.groupValues[1].replace('-', '/'), dateFormat)
        }.getOrNull() ?: return null
        val time = runCatching {
            LocalTime.parse(dateMatch.groupValues[2])
        }.getOrNull() ?: return null

        val rawBank = Regex("""(?m)^\s*banco\s*:\s*([^\r\n]+)""")
            .find(destination)?.groupValues?.get(1)?.trim()?.take(60)
        val bank = when {
            rawBank == null -> "banco no identificado"
            "chile" in rawBank -> "Banco de Chile"
            "estado" in rawBank -> "BancoEstado"
            "falabella" in rawBank -> "Banco Falabella"
            "santander" in rawBank -> "Santander"
            "tenpo" in rawBank -> "Tenpo"
            "mach" in rawBank -> "MACH"
            else -> rawBank
        }
        val digits = Regex(
            """(?:n[°ºo]?\s*de\s*cuenta|numero\s+de\s+cuenta)\s*:\s*([0-9.*•\s-]{4,35})"""
        ).find(destination)?.groupValues?.get(1).orEmpty().filter(Char::isDigit)
        val mask = if (digits.length >= 4) " ••••" + digits.takeLast(4) else ""

        return MailCandidate(
            sourceId = hash,
            operationDate = date.toString(),
            description = "Transferencia BancoEstado a " + bank + mask,
            amountClp = -amount,
            account = "BancoEstado",
            category = "Transferencia",
            note = "TEF enviada desde CuentaRUT. Verifica titularidad del destino y posibles traspasos entre cuentas propias antes de aprobar. Hora operación: " + time
        )
    }
}
