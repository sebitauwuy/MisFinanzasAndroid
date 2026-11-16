package cl.personal.finanzas

import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Montos de prueba sin incluir datos personales ni la cartola del usuario.
 */
class CuentaRutSummaryValidationTest {
    @Test
    fun acceptsSeparateCashWithdrawalsInBankTotals() {
        // La suma de los 36 cargos individuales incluye giros de cajero.
        CuentaRutSummaryValidation.checkOutgoing(
            totalCargos = 301_511L,
            totalGiros = 60_000L,
            parsedOutgoing = 361_511L
        )
    }

    @Test
    fun acceptsCartolaWithoutWithdrawals() {
        CuentaRutSummaryValidation.checkOutgoing(
            totalCargos = 301_511L,
            totalGiros = 0L,
            parsedOutgoing = 301_511L
        )
    }

    @Test
    fun doesNotRequireOptionalWithdrawalTotalInLegacyPdf() {
        CuentaRutSummaryValidation.checkOutgoing(
            totalCargos = 90_000L,
            totalGiros = null,
            parsedOutgoing = 90_000L
        )
    }

    @Test
    fun rejectsEvenOnePesoMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            CuentaRutSummaryValidation.checkOutgoing(
                totalCargos = 301_511L,
                totalGiros = 60_000L,
                parsedOutgoing = 361_510L
            )
        }
    }

    @Test
    fun rejectsOverflowingOrNegativeTotals() {
        assertThrows(IllegalArgumentException::class.java) {
            CuentaRutSummaryValidation.checkOutgoing(
                totalCargos = Long.MAX_VALUE,
                totalGiros = 1L,
                parsedOutgoing = Long.MAX_VALUE
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CuentaRutSummaryValidation.checkOutgoing(
                totalCargos = 301_511L,
                totalGiros = -60_000L,
                parsedOutgoing = 241_511L
            )
        }
    }
}
