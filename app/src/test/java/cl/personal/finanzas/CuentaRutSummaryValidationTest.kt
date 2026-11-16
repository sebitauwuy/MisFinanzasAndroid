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
            totalCargos = 80_000L,
            totalGiros = 20_000L,
            parsedOutgoing = 100_000L
        )
    }

    @Test
    fun acceptsCartolaWithoutWithdrawals() {
        CuentaRutSummaryValidation.checkOutgoing(
            totalCargos = 80_000L,
            totalGiros = 0L,
            parsedOutgoing = 80_000L
        )
    }

    @Test
    fun doesNotRequireOptionalWithdrawalTotalInLegacyPdf() {
        CuentaRutSummaryValidation.checkOutgoing(
            totalCargos = 91_000L,
            totalGiros = null,
            parsedOutgoing = 91_000L
        )
    }

    @Test
    fun rejectsEvenOnePesoMismatch() {
        assertThrows(IllegalArgumentException::class.java) {
            CuentaRutSummaryValidation.checkOutgoing(
                totalCargos = 80_000L,
                totalGiros = 20_000L,
                parsedOutgoing = 99_999L
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
                totalCargos = 80_000L,
                totalGiros = -20_000L,
                parsedOutgoing = 60_000L
            )
        }
    }
}
