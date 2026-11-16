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
            val isCuentaRut = Regex("""CARTOLA\s+CUENTARUT""", RegexOption.IGNORE_CASE)
                .containsMatchIn(fullText)
            val isMach = Regex("""CARTOLA\s+CUENTA\s+MACHBANK""", RegexOption.IGNORE_CASE)
                .containsMatchIn(fullText)
            val movements = when {
                isCuentaRut -> parseCuentaRut(fullText, rows, account)
                isMach -> parseMach(rows, account)
                else -> parseTable(rows, account)
            }
            require(movements.isNotEmpty()) {
                "No se identificaron filas completas de fecha, descripción, cargos y abonos. " +
                    "El PDF se pudo abrir, pero su diseño aún no está reconocido; no se importó nada."
            }
            val source = when {
                isCuentaRut -> "CuentaRUT / BancoEstado"
                isMach -> "MACH / MACHBANK"
                else -> "PDF de movimientos"
            }
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


    /**
     * MACHBANK cuenta corriente: Fecha operación | Descripción | Cambio |
     * Moneda | Egreso | Ingreso | Saldo.
     *
     * La columna "Cambio" puede ser 1 o un tipo USD; Egreso, Ingreso y Saldo
     * están expresados en CLP. Los encabezados y saldos se repiten por página.
     * Conserva operaciones anteriores al inicio nominal del período cuando
     * el propio banco las incluye en su resumen; de otro modo no cuadraría.
     */
    private fun parseMach(lines: List<Line>, account: String): List<Movement> {
        require(account == "MACH") {
            "Esta cartola corresponde a MACHBANK. Selecciona MACH antes de importar."
        }

        // PDFBox puede entregar espacios como separaciones visuales, sin
        // glifos de espacio. Para reconocer títulos no dependemos de ellos.
        val summaryHeaders = lines.filter { line ->
            // El orden de glifos puede entregar "fnial" en vez de "final".
            // Identificamos las cuatro celdas del encabezado por su posición
            // y palabras estables, sin dejar de exigir todos los totales.
            MachStatementValidation.isSummaryHeader(
                initial = line.cell(30f, 169f),
                income = line.cell(169f, 311f),
                outgoing = line.cell(311f, 425f),
                closing = line.cell(425f, 602f)
            )
        }
        require(summaryHeaders.isNotEmpty()) {
            "No se reconoció el resumen de saldos MACH; no se importó nada."
        }

        fun summary(header: Line): List<Long> {
            val headerY = header.chars.first().y
            val amounts = lines.firstOrNull { row ->
                row.page == header.page &&
                    row.chars.first().y - headerY in 9f..34f &&
                    row.cell(30f, 169f).isNotBlank() &&
                    row.cell(169f, 311f).isNotBlank() &&
                    row.cell(311f, 425f).isNotBlank() &&
                    row.cell(425f, 602f).isNotBlank()
            } ?: error("Faltan los importes del resumen MACH; no se importó nada.")
            return listOf(
                readClp(amounts.cell(30f, 169f)),
                readClp(amounts.cell(169f, 311f)),
                readClp(amounts.cell(311f, 425f)),
                readClp(amounts.cell(425f, 602f))
            )
        }
        val totals = summary(summaryHeaders.first())
        require(summaryHeaders.all { summary(it) == totals }) {
            "El resumen MACH no coincide entre las páginas; no se importó nada."
        }

        data class Parsed(
            val page: Int, val y: Float, val date: LocalDate,
            val description: StringBuilder, val outgoing: Long,
            val incoming: Long, val balance: Long
        )
        val parsed = mutableListOf<Parsed>()
        val rowDatePattern = Regex("""^\d{2}/\d{2}/\d{4}$""")
        var currentPage = -1
        var inTable = false

        for (line in lines) {
            if (line.page != currentPage) {
                currentPage = line.page
                inTable = false
            }
            val label = line.normalized.replace(" ", "")
            if (label.contains("fecha") && label.contains("operacion") &&
                label.contains("descripcion") && label.contains("egreso") &&
                label.contains("ingreso") && label.contains("saldo")) {
                inTable = true
                continue
            }
            if (!inTable) continue
            if (label.contains("informaciongeneral")) {
                inTable = false
                continue
            }
            val rawDate = line.cell(30f, 117f).replace(" ", "").trim()
            if (rowDatePattern.matches(rawDate)) {
                val date = parseDate(rawDate)
                    ?: error("Una fecha MACH no es válida; no se importó nada.")
                val description = line.cell(118f, 327f)
                    .replace(Regex("""\s+"""), " ").trim()
                val currency = line.cell(378f, 428f).replace(" ", "").trim()
                require(currency == "CLP" || currency == "USD") {
                    "No se reconoce la moneda de una operación MACH; no se importó nada."
                }
                val outgoing = readClp(line.cell(428f, 478f))
                val incoming = readClp(line.cell(478f, 531f))
                val rawBalance = line.cell(531f, 602f)
                require(rawBalance.isNotBlank()) {
                    "Una operación MACH no tiene saldo; no se importó nada."
                }
                require(description.isNotBlank() &&
                    (outgoing > 0L) != (incoming > 0L)) {
                    "Una operación MACH no tiene cargo o abono identificable; no se importó nada."
                }
                parsed += Parsed(
                    line.page, line.chars.first().y, date,
                    StringBuilder(description), outgoing, incoming, readClp(rawBalance)
                )
                require(parsed.size <= 5000) { "Demasiados movimientos MACH." }
            } else {
                // Varias descripciones MACH ocupan dos líneas. La segunda
                // no tiene fecha, monto, moneda ni saldo, solo descripción.
                val previous = parsed.lastOrNull()
                val y = line.chars.first().y
                if (previous != null && previous.page == line.page &&
                    y - previous.y in 0f..20f &&
                    line.cell(30f, 117f).isBlank() &&
                    line.cell(327f, 602f).isBlank()) {
                    val continuation = line.cell(118f, 327f)
                        .replace(Regex("""\s+"""), " ").trim()
                    if (continuation.isNotBlank()) {
                        previous.description.append(' ').append(continuation)
                    }
                }
            }
        }
        val validated = parsed.map {
            MachStatementRow(it.date, it.description.toString().trim(),
                it.outgoing, it.incoming, it.balance)
        }
        MachStatementValidation.check(
            openingClp = totals[0],
            totalIncomingClp = totals[1],
            totalOutgoingClp = totals[2],
            closingClp = totals[3],
            rows = validated
        )
        return validated.map {
            Movement(
                account = account,
                description = it.description,
                amountClp = it.incomingClp - it.outgoingClp,
                date = it.date.toString(),
                category = "Importado"
            )
        }
    }

    /**
     * Lector del formato real BancoEstado:
     * Fecha | N° Operación | Descripción | Abonos | Cargos | Saldo.
     * Verifica sumas y saldos antes de devolver movimientos.
     */
    private fun parseCuentaRut(text: String, lines: List<Line>, account: String): List<Movement> {
        require(account == "BancoEstado") {
            "Esta cartola es CuentaRUT. Selecciona BancoEstado antes de importar."
        }
        fun dateLabel(label: String): LocalDate {
            val expression = Regex(
                Regex.escape(label) + """\s*:?\s*(\d{2}/\d{2}/\d{4})""",
                RegexOption.IGNORE_CASE
            )
            val raw = expression.find(text)?.groupValues?.get(1)
                ?: error("Faltan las fechas de la cartola.")
            val split = raw.split('/')
            return LocalDate.of(split[2].toInt(), split[1].toInt(), split[0].toInt())
        }
        fun summaryAmount(label: String): Long? {
            val expression = Regex(
                Regex.escape(label) + """\s*\$?\s*([0-9][0-9.]*)""",
                RegexOption.IGNORE_CASE
            )
            return expression.find(text)?.groupValues?.get(1)
                ?.filter(Char::isDigit)?.toLongOrNull()
        }
        val firstDate = dateLabel("Fecha Inicio")
        val finalDate = dateLabel("Fecha Final")
        require(!finalDate.isBefore(firstDate) &&
            finalDate.isBefore(firstDate.plusYears(2))) {
            "El rango de fechas del PDF no es válido."
        }

        val months = mapOf(
            "ene" to 1, "feb" to 2, "mar" to 3, "abr" to 4,
            "may" to 5, "jun" to 6, "jul" to 7, "ago" to 8,
            "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dic" to 12
        )
        fun rowDate(value: String): LocalDate? {
            val match = Regex(
                """^(\d{1,2})/([A-Za-zÁÉÍÓÚáéíóú]{3,4})\.?$"""
            ).find(value.trim()) ?: return null
            val month = months[match.groupValues[2].lowercase()] ?: return null
            val day = match.groupValues[1].toIntOrNull() ?: return null
            var date = runCatching {
                LocalDate.of(finalDate.year, month, day)
            }.getOrNull() ?: return null
            if (date.isAfter(finalDate)) date = date.minusYears(1)
            return date.takeIf { !it.isBefore(firstDate) && !it.isAfter(finalDate) }
        }

        data class Row(
            val page: Int, val y: Float, val date: LocalDate,
            val description: StringBuilder, val abono: Long, val cargo: Long,
            val saldo: Long
        ) {
            val signedAmount get() = abono - cargo
        }

        val parsed = mutableListOf<Row>()
        var activePage = -1
        var insideTable = false
        for (line in lines) {
            if (line.page != activePage) {
                activePage = line.page
                insideTable = false
            }
            val label = line.normalized
            if (label.contains("fecha") && label.contains("abonos") &&
                label.contains("cargos") && label.contains("saldo")) {
                insideTable = true
                continue
            }
            if (!insideTable) continue

            // Coordenadas de las columnas en el PDF original (ancho 595 pt).
            // El número de operación se omite, sin modificar los importes.
            val dateRaw = line.cell(40f, 112f).replace(" ", "")
            val date = rowDate(dateRaw)
            if (date != null) {
                val abonoRaw = line.cell(346f, 423f)
                val cargoRaw = line.cell(423f, 500f)
                val saldoRaw = line.cell(500f, 560f)
                require(saldoRaw.isNotBlank()) {
                    "Una fila no tiene saldo: no se importó nada."
                }
                val abono = readClp(abonoRaw)
                val cargo = readClp(cargoRaw)
                require((abono > 0L) != (cargo > 0L)) {
                    "Una fila tiene un cargo/abono ambiguo; no se importó nada."
                }
                val detail = line.cell(190f, 346f).replace(Regex("""\s+"""), " ").trim()
                parsed.add(Row(
                    line.page, line.chars.first().y, date,
                    StringBuilder(detail), abono, cargo, readClp(saldoRaw)
                ))
                require(parsed.size <= 5000) { "Hay demasiados movimientos." }
            } else {
                // Descripciones de dos líneas en el diseño CuentaRUT.
                val last = parsed.lastOrNull()
                val y = line.chars.first().y
                if (last != null && last.page == line.page &&
                    y - last.y in 0f..18f &&
                    line.cell(40f, 112f).isBlank() &&
                    line.cell(346f, 560f).isBlank()) {
                    val additional = line.cell(190f, 346f)
                        .replace(Regex("""\s+"""), " ").trim()
                    if (additional.isNotBlank()) {
                        if (last.description.isNotEmpty()) last.description.append(' ')
                        last.description.append(additional)
                    }
                }
            }
        }
        require(parsed.isNotEmpty()) {
            "No se reconocieron movimientos de CuentaRUT."
        }

        // Comprobación de integridad: el archivo está ordenado de nuevo a antiguo.
        summaryAmount("N° de Movimientos")?.let { count ->
            require(count == parsed.size.toLong()) {
                "La cantidad de movimientos no coincide; no se importó nada."
            }
        }
        summaryAmount("Total Abonos")?.let { total ->
            require(total == parsed.sumOf { it.abono }) {
                "El total de abonos no coincide; no se importó nada."
            }
        }
        // BancoEstado muestra los giros por separado de "Total Cargos",
        // aunque ambos aparecen como débitos en la columna Cargos de la tabla.
        // Comparar solo con "Total Cargos" rechazaba cartolas correctas
        // con retiros de cajero (p. ej., 80.000 + 20.000 = 100.000).
        CuentaRutSummaryValidation.checkOutgoing(
            totalCargos = summaryAmount("Total Cargos"),
            totalGiros = summaryAmount("Total Giros"),
            parsedOutgoing = parsed.sumOf { it.cargo }
        )
        summaryAmount("Saldo Final")?.let { balance ->
            require(balance == parsed.first().saldo) {
                "El saldo final no coincide; no se importó nada."
            }
        }
        summaryAmount("Saldo Anterior")?.let { balance ->
            require(balance == parsed.last().saldo - parsed.last().signedAmount) {
                "El saldo anterior no coincide; no se importó nada."
            }
        }
        for (i in 0 until parsed.lastIndex) {
            require(parsed[i].saldo == parsed[i + 1].saldo + parsed[i].signedAmount) {
                "Un saldo intermedio no coincide; no se importó nada."
            }
        }
        return parsed.map { row ->
            val detail = row.description.toString().trim()
            require(detail.isNotBlank()) {
                "Hay un movimiento sin descripción; no se importó nada."
            }
            Movement(
                account = account, description = detail.take(300),
                amountClp = row.signedAmount, date = row.date.toString(),
                category = "Importado"
            )
        }
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
