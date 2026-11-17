package cl.personal.finanzas

import java.text.Normalizer
import java.util.Locale

/**
 * Clasificación conservadora para abonos de Uber en Banco de Chile.
 *
 * No depende del monto (las liquidaciones varían), NO crea un movimiento y
 * nunca convierte en ingreso un cargo bancario. Conserva íntegra la
 * descripción original para conciliar e identificar posibles duplicados.
 *
 * El comprobante "Pago Proveedores" por sí solo no acredita el signo; la
 * identificación se aplica únicamente a abonos positivos de la cartola.
 */
object UberPayoutRecognition {
    const val DISPLAY_NAME = "Uber · Ganancias"
    private const val CATEGORY = "Trabajo"

    private fun normalized(description: String): String =
        Normalizer.normalize(description, Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun matches(movement: Movement): Boolean {
        if (movement.account != "Banco de Chile" || movement.amountClp <= 0L) return false
        val clean = normalized(movement.description)
        // Coincidencia exacta de nombre corporativo, no "UBER TRIP" ni
        // compras pagadas por tarjeta. Se admiten otros textos alrededor.
        return Regex("""(?:^|\s)uber pagos chile(?:\s|$)""").containsMatchIn(clean)
    }

    fun classify(movement: Movement): Movement =
        if (matches(movement) && movement.category in listOf("Importado", "General"))
            movement.copy(category = CATEGORY)
        else movement

    fun displayName(movement: Movement, alias: String?): String =
        alias ?: if (matches(movement))
            DISPLAY_NAME
        else movement.description
}
