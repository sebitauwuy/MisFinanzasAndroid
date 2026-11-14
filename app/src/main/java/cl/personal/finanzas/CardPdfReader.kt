package cl.personal.finanzas

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resumen informativo de tarjeta, nunca crea cargos ni modifica saldos. */
data class CardPdfReport(
    val reportDate: String,
    val usedLimit: Long,
    val availableLimit: Long,
    val totalLimit: Long,
    val amountsConsistent: Boolean,
    val paymentMentioned: Boolean
)

object CardPdfReader {
    fun matches(text: String): Boolean =
        Regex("""Saldo\s+y\s+Movimientos\s+No\s+Facturados""", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)

    fun fromText(text: String): CardPdfReport {
        require(matches(text)) {
            "El PDF no es un resumen de cupos no facturados de Banco de Chile."
        }
        fun readAmount(label: String): Long {
            val expression = Regex(Regex.escape(label) + """\s*\$?\s*([0-9][0-9.,]*)""",
                RegexOption.IGNORE_CASE)
            val captured = expression.find(text)?.groupValues?.getOrNull(1)
                ?: error("No se encontró el campo: " + label)
            return captured.filter(Char::isDigit).toLongOrNull()
                ?: error("Monto no válido para " + label)
        }
        val reportDate = Regex(
            """Saldo\s+y\s+Movimientos\s+No\s+Facturados\s+al\s+(\d{2}/\d{2}/\d{4})""",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.get(1) ?: "Fecha no identificada"
        val available = readAmount("Cupo Disponible")
        val used = readAmount("Cupo Utilizado")
        val total = readAmount("Cupo Total")
        return CardPdfReport(
            reportDate = reportDate,
            usedLimit = used,
            availableLimit = available,
            totalLimit = total,
            amountsConsistent = available + used == total,
            paymentMentioned = text.contains("PAGO PESOS TEF", ignoreCase = true)
        )
    }

    /** Acceso anterior conservado para llamadas existentes, ahora con clave opcional. */
    suspend fun read(context: Context, uri: Uri, password: String = ""): CardPdfReport =
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openInputStream(uri)
                ?: error("No se pudo abrir el PDF")
            val bytes = stream.use { it.readBytes() }
            require(bytes.size in 1..10_000_000) { "PDF vacío o demasiado grande (máximo 10 MB)" }
            PDFBoxResourceLoader.init(context.applicationContext)
            try {
                PDDocument.load(bytes, password).use { pdf ->
                    require(pdf.numberOfPages in 1..30) { "El PDF debe tener entre 1 y 30 páginas" }
                    fromText(PDFTextStripper().getText(pdf))
                }
            } catch (_: InvalidPasswordException) {
                throw PdfPasswordRequiredException()
            }
        }
}
