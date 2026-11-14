package cl.personal.finanzas

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.time.LocalDate
import kotlin.math.abs

/** Se solicita la clave dentro de la app; nunca se persiste en disco ni en el respaldo. */
class PdfPasswordRequiredException : IllegalArgumentException(
    "Este PDF necesita una contraseña o la contraseña ingresada no es correcta."
)

sealed class ImportedStatement {
    data class Movements(val items: List<Movement>, val source: String) : ImportedStatement()
    data class CardSummary(val report: CardPdfReport) : ImportedStatement()
}

/**
 * Lee PDF localmente, sin subir el archivo, y solo importa filas de cargos/abonos
 * cuando las columnas se pueden determinar por su posición real en la página.
 * No interpreta un saldo como un movimiento ni inventa el signo de las compras.
 */
object PdfStatementReader {
    private data class Glyph(
        val page: Int, val x: Float, val y: Float,
        val width: Float, val value: String
    )
    private data class Line(val page: Int, val chars: List<Glyph>) {
        val ordered = chars.sortedBy { it.x }
        val text = ordered.joinToString("") { it.value }
        val normalized = text.lowercase()
            .replace("ó", "o").replace("é", "e").replace("í", "i")
        fun position(fragment: String): Float? {
            val index = normalized.indexOf(fragment)
            if (index < 0) return null
            var offset = 0
            for (glyph in ordered) {
                val end = offset + glyph.value.length
                if (index < end) return glyph.x
                offset = end
            }
            return null
        }
        fun cell(from: Float, until: Float): String {
            val filtered = ordered.filter { it.x >= from && it.x < until }
            val output = StringBuilder()
            var previous: Glyph? = null
            filtered.forEach { glyph ->
                val before = previous
                if (before != null && glyph.x - (before.x + before.width) > 3.5f)
                    output.append(' ')
                output.append(glyph.value)
                previous = glyph
            }
            return output.toString().trim()
        }
    }

    private data class Columns(
        val date: Float, val description: Float, val debit: Float,
        val credit: Float, val balance: Float
    )

    private class PositionedText : PDFTextStripper() {
        val glyphs = mutableListOf<Glyph>()
        override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
            val page = currentPageNo
            textPositions.forEach { pos ->
                val value = pos.unicode
                if (value.isNotBlank()) {
                    glyphs.add(Glyph(page, pos.xDirAdj, pos.yDirAdj,
                        pos.widthDirAdj, value))
                }
            }
            super.writeString(text, textPositions)
        }
    }

    fun read(context: Context, bytes: ByteArray, account: String, password: String): ImportedStatement {
        require(bytes.size in 1..10_000_000) { "PDF vacío o demasiado grande (máximo 10 MB)." }
        PDFBoxResourceLoader.init(context.applicationContext)
        val pdf = try {
            PDDocument.load(bytes, password)
        } catch (_: InvalidPasswordException) {
            throw PdfPasswordRequiredException()
        } catch (e: Exception) {
            throw IllegalArgumentException("No se pudo abrir el PDF. Comprueba el archivo.", e)
        }
        return pdf.use { document ->
            require(document.numberOfPages in 1..30) {
                "El PDF debe contener entre 1 y 30 páginas."
            }
            val extractor = PositionedText().apply { sortByPosition = true }
            val fullText = try { extractor.getText(document) } catch (e: Exception) {
                throw IllegalArgumentException("No se pudo extraer texto del PDF.", e)
            }
            require(fullText.isNotBlank()) {
                "Este PDF no contiene texto seleccionable. Las cartolas escaneadas requieren OCR; " +
                    "por ahora exporta otra cartola con texto o un CSV."
            }
            if (CardPdfReader.matches(fullText)) {
                return@use ImportedStatement.CardSummary(CardPdfReader.fromText(fullText))
            }
            val rows = lines(extractor.glyphs)
            val movements = parseTable(rows, account)
            require(movements.isNotEmpty()) {
                "No se identificaron filas completas de fecha, descripción, cargos y abonos. " +
                    "El PDF se pudo abrir, pero su diseño aún no está reconocido; no se importó nada."
            }
            val source = if (fullText.contains("CuentaRUT", ignoreCase = true) ||
                fullText.contains("BancoEstado", ignoreCase = true)) "CuentaRUT / BancoEstado"
                else "PDF de movimientos"
            ImportedStatement.Movements(movements, source)
        }
    }

    private fun lines(glyphs: List<Glyph>): List<Line> {
        val ordered = glyphs.sortedWith(compareBy<Glyph> { it.page }.thenBy { it.y }.thenBy { it.x })
        val groups = mutableListOf<MutableList<Glyph>>()
        for (glyph in ordered) {
            val current = groups.lastOrNull()
            if (current == null || current.first().page != glyph.page ||
                abs(current.first().y - glyph.y) > 2.4f) {
                groups.add(mutableListOf(glyph))
            } else current.add(glyph)
        }
        return groups.map { Line(it.first().page, it) }
    }

    private fun columns(row: Line): Columns? {
        val date = row.position("fecha") ?: return null
        val detail = row.position("descripcion") ?: row.position("detalle") ?: return null
        val debit = row.position("cargos") ?: row.position("cargo") ?:
            row.position("debitos") ?: return null
        val credit = row.position("abonos") ?: row.position("abono") ?:
            row.position("creditos") ?: return null
        val balance = row.position("saldo") ?: return null
        if (!(date < detail && detail < debit && debit < credit && credit < balance)) return null
        return Columns(date, detail, debit, credit, balance)
    }

    private fun parseTable(rows: List<Line>, account: String): List<Movement> {
        var layout: Columns? = null
        var foundHeader = false
        val entries = mutableListOf<Movement>()
        val datePattern = Regex("""\d{1,2}[-/]\d{1,2}[-/]\d{2,4}|\d{4}-\d{2}-\d{2}""")
        for (row in rows) {
            val header = columns(row)
            if (header != null) {
                layout = header
                foundHeader = true
                continue
            }
            val cols = layout ?: continue
            val dateCell = row.cell(cols.date - 5f, cols.description - 7f)
            val matched = datePattern.find(dateCell) ?: continue
            val date = parseDate(matched.value)
                ?: error("Fecha no reconocida en una fila del PDF.")
            val description = row.cell(cols.description - 7f, cols.debit - 7f)
                .replace(Regex("""\s+"""), " ").trim()
            require(description.isNotEmpty() && description.length <= 300) {
                "Una fila del PDF no tiene una descripción válida; no se importó nada."
            }
            val debit = readClp(row.cell(cols.debit - 7f, cols.credit - 7f))
            val credit = readClp(row.cell(cols.credit - 7f, cols.balance - 7f))
            require((debit > 0L) != (credit > 0L)) {
                "Una fila del PDF tiene cargos y abonos ambiguos. No se importó nada."
            }
            entries.add(Movement(
                account = account,
                description = description,
                amountClp = if (debit > 0L) -debit else credit,
                date = date.toString(),
                category = "Importado"
            ))
            require(entries.size <= 5000) { "El PDF tiene más de 5000 movimientos." }
        }
        require(foundHeader) {
            "El PDF se abrió, pero no contiene una tabla reconocible con fecha, descripción, " +
                "cargos, abonos y saldo. No se importó nada."
        }
        return entries
    }

    private fun parseDate(raw: String): LocalDate? = runCatching {
        if (raw.length >= 10 && raw[4] == '-') LocalDate.parse(raw) else {
            val parts = raw.split('/', '-')
            val year = parts[2].toInt().let { if (it < 100) 2000 + it else it }
            LocalDate.of(year, parts[1].toInt(), parts[0].toInt())
        }
    }.getOrNull()

    private fun readClp(raw: String): Long {
        val value = raw.trim()
        if (value.isBlank() || value == "-") return 0L
        require(Regex("""^\$?\s*[0-9]+(?:\.[0-9]{3})*(?:,00)?$""").matches(value)) {
            "Monto ambiguo en un cargo o abono del PDF; no se importó nada."
        }
        return value.substringBefore(',').filter(Char::isDigit).toLongOrNull()
            ?: error("Monto demasiado grande en el PDF.")
    }
}
