package cl.personal.finanzas

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lector local de saldos y movimientos NO facturados de Banco de Chile.
 * Solo genera una vista previa: NO crea movimientos ni modifica saldos.
 */
data class CardPdfReport(
    val reportDate: String,
    val usedLimit: Long,
    val availableLimit: Long,
    val totalLimit: Long,
    val amountsConsistent: Boolean,
    val paymentMentioned: Boolean
)

object CardPdfReader {
    suspend fun read(context: Context, uri: Uri): CardPdfReport = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: error("No se pudo abrir el PDF")
        val bytes = stream.use { it.readBytes() }
        require(bytes.size in 1..10_000_000) { "PDF vacío o demasiado grande (máximo 10 MB)" }

        PDFBoxResourceLoader.init(context.applicationContext)
        val text = try {
            PDDocument.load(bytes).use { pdf ->
                require(pdf.numberOfPages in 1..20) { "El PDF debe tener entre 1 y 20 páginas" }
                require(!pdf.isEncrypted) { "No se admiten PDFs protegidos con contraseña" }
                PDFTextStripper().getText(pdf)
            }
        } catch (e: Exception) {
            throw IllegalArgumentException("No se pudo extraer texto del PDF: ${e.message}", e)
        }

        val marker = Regex("""Saldo\s+y\s+Movimientos\s+No\s+Facturados""", RegexOption.IGNORE_CASE)
        require(marker.containsMatchIn(text)) {
            "Este PDF no es un resumen de movimientos no facturados de Banco de Chile. Un estado de cuenta facturado necesita un lector diferente."
        }
        fun readAmount(label: String): Long {
            val expression = Regex(Regex.escape(label) + """\s*\$?\s*([0-9][0-9.,]*)""", RegexOption.IGNORE_CASE)
            val captured = expression.find(text)?.groupValues?.getOrNull(1)
                ?: error("No se encontró el campo: $label")
            return captured.filter { it.isDigit() }.toLongOrNull()
                ?: error("Monto no válido para $label")
        }

        val reportDate = Regex(
            """Saldo\s+y\s+Movimientos\s+No\s+Facturados\s+al\s+(\d{2}/\d{2}/\d{4})""",
            RegexOption.IGNORE_CASE
        ).find(text)?.groupValues?.get(1) ?: "Fecha no identificada"

        val available = readAmount("Cupo Disponible")
        val used = readAmount("Cupo Utilizado")
        val total = readAmount("Cupo Total")

        CardPdfReport(
            reportDate = reportDate,
            usedLimit = used,
            availableLimit = available,
            totalLimit = total,
            amountsConsistent = available + used == total,
            paymentMentioned = text.contains("PAGO PESOS TEF", ignoreCase = true)
        )
    }
}
