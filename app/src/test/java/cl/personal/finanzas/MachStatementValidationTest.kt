package cl.personal.finanzas

import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class MachStatementValidationTest {
    private fun validRows() = listOf(
        MachStatementRow(LocalDate.of(2026, 9, 29), "Compra de ejemplo",
            outgoingClp = 2_000L, incomingClp = 0L, balanceClp = 98_000L),
        MachStatementRow(LocalDate.of(2026, 10, 1), "Transferencia de ejemplo",
            outgoingClp = 0L, incomingClp = 3_000L, balanceClp = 101_000L),
        MachStatementRow(LocalDate.of(2026, 10, 2), "Compra internacional de ejemplo",
            outgoingClp = 1_500L, incomingClp = 0L, balanceClp = 99_500L)
    )

    private fun check(rows: List<MachStatementRow> = validRows()) =
        MachStatementValidation.check(
            openingClp = 100_000L,
            totalIncomingClp = 3_000L,
            totalOutgoingClp = 3_500L,
            closingClp = 99_500L,
            rows = rows
        )

    @Test fun summaryAndEveryIntermediateBalanceAreConsistent() {
        check()
    }

    @Test fun operationsBeforeNominalMonthCanBePartOfBankStatement() {
        // El estado se concilia completo, incluso con cargo de septiembre
        // incluido por el banco en la cartola de octubre.
        check()
    }

    @Test fun onePesoDifferenceIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            check(validRows().toMutableList().also {
                it[2] = it[2].copy(balanceClp = 99_499L)
            })
        }
    }

    @Test fun wrongIncomeTotalIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            MachStatementValidation.check(100_000L, 3_001L, 3_500L,
                99_500L, validRows())
        }
    }

    @Test fun wrongExpenseTotalIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            MachStatementValidation.check(100_000L, 3_000L, 3_499L,
                99_500L, validRows())
        }
    }

    @Test fun ambiguousMovementIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            check(validRows().toMutableList().also {
                it[2] = it[2].copy(incomingClp = 250L)
            })
        }
    }

    @Test fun missingDescriptionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            check(validRows().toMutableList().also {
                it[0] = it[0].copy(description = "")
            })
        }
    }

    @Test fun outOfOrderDatesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            check(validRows().reversed())
        }
    }

    @Test fun zeroOrEmptyRowsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { check(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) {
            check(validRows().toMutableList().also {
                it[1] = it[1].copy(incomingClp = 0L)
            })
        }
    }
}
