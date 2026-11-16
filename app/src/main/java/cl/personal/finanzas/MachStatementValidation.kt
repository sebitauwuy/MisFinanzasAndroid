package cl.personal.finanzas

import java.time.LocalDate
import java.text.Normalizer
import java.util.Locale

/**
 * Validación local del formato MACHBANK. No crea ni guarda movimientos.
 * Los importes de egreso/ingreso son CLP (aunque la columna Cambio indique USD).
 */
internal data class MachStatementRow(
    val date: LocalDate,
    val description: String,
    val outgoingClp: Long,
    val incomingClp: Long,
    val balanceClp: Long
)

internal object MachStatementValidation {
    /**
     * Los encabezados MACH son cuatro celdas fijas. PDFBox puede reordenar
     * letras de una misma palabra al ordenar glifos según su posición X:
     * p. ej. "final" se extrae "fnial". Detectar por prefijo y columna,
     * no por coincidencia textual íntegra que rechazaba cartolas válidas.
     */
    fun isSummaryHeader(initial: String, income: String, outgoing: String, closing: String): Boolean {
        fun compact(raw: String) = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.ROOT)
            .filterNot { it.isWhitespace() }
        val a = compact(initial)
        val b = compact(income)
        val c = compact(outgoing)
        val d = compact(closing)
        return a.startsWith("saldoinicialdelperiodo") &&
            b.startsWith("totalingresosdelperiodo") &&
            c.startsWith("totalegresosdelperiodo") &&
            d.startsWith("saldocontable") && d.endsWith("delperiodo")
    }

    fun check(
        openingClp: Long,
        totalIncomingClp: Long,
        totalOutgoingClp: Long,
        closingClp: Long,
        rows: List<MachStatementRow>
    ) {
        require(rows.size in 1..5000) {
            "No se encontraron movimientos válidos de MACH; no se importó nada."
        }
        require(listOf(openingClp, totalIncomingClp, totalOutgoingClp, closingClp)
            .all { it in 0L..999_999_999_999L }) {
            "Los totales de la cartola MACH no son válidos; no se importó nada."
        }
        var expected = openingClp
        var lastDate: LocalDate? = null
        for (row in rows) {
            require(row.description.isNotBlank() && row.description.length <= 300 &&
                row.outgoingClp in 0L..999_999_999_999L &&
                row.incomingClp in 0L..999_999_999_999L &&
                row.balanceClp in 0L..999_999_999_999L &&
                (row.outgoingClp > 0L) != (row.incomingClp > 0L)) {
                "Una operación MACH tiene montos o descripción ambiguos; no se importó nada."
            }
            require(lastDate == null || !row.date.isBefore(lastDate)) {
                "Las fechas de MACH no están en orden; no se importó nada."
            }
            expected = expected + row.incomingClp - row.outgoingClp
            require(expected == row.balanceClp) {
                "Un saldo intermedio MACH no coincide; no se importó nada."
            }
            lastDate = row.date
        }
        require(rows.sumOf { it.incomingClp } == totalIncomingClp &&
            rows.sumOf { it.outgoingClp } == totalOutgoingClp) {
            "El total de ingresos o egresos MACH no coincide; no se importó nada."
        }
        require(expected == closingClp && rows.last().balanceClp == closingClp) {
            "El saldo final MACH no coincide; no se importó nada."
        }
    }
}
