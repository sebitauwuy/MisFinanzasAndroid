package cl.personal.finanzas

import java.time.LocalDate

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
