package cl.personal.finanzas

/**
 * En la cartola CuentaRUT, BancoEstado informa por separado "Total Cargos"
 * y "Total Giros". Ambos tipos son salidas (débito) en el listado de
 * movimientos, y ambos se deben comprobar antes de aceptar la importación.
 *
 * Ejemplo: cargos 301.511 + giros 60.000 = movimientos negativos 361.511.
 */
internal object CuentaRutSummaryValidation {
    fun checkOutgoing(
        totalCargos: Long?,
        totalGiros: Long?,
        parsedOutgoing: Long
    ) {
        if (totalCargos == null) return // Mantiene la conducta del lector anterior.
        val giros = totalGiros ?: 0L
        require(totalCargos >= 0L && giros >= 0L && parsedOutgoing >= 0L &&
            totalCargos <= Long.MAX_VALUE - giros &&
            totalCargos + giros == parsedOutgoing
        ) {
            "El total de cargos y giros no coincide; no se importó nada."
        }
    }
}
