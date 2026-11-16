package cl.personal.finanzas

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class MonthlyCashflowProjectionTest {
    private val today = LocalDate.of(2026, 11, 16)
    private val cashAccounts = listOf("Banco de Chile")
    private fun installment(
        title: String = "Tarjeta CMR",
        amount: Long = 80_000L,
        due: String = "2026-11-20",
        paid: Boolean = false
    ) = Obligation(
        id = 1, title = title, account = "CMR", amountClp = amount,
        dueDate = due, kind = "Pago", paid = paid
    )
    private fun planned(
        id: Long = 1,
        title: String = "Internet",
        amount: Long = 20_000L,
        due: String = "2026-11-20",
        frequency: String = "MENSUAL",
        account: String = "Banco de Chile"
    ) = PlannedExpense(
        id = id, title = title, icon = "🌐", estimatedAmountClp = amount,
        isVariable = false, frequency = frequency, nextDueDate = due,
        anchorDay = 20, defaultAccount = account
    )
    private fun movement(
        id: Long, title: String, amount: Long,
        date: String = "2026-11-16",
        account: String = "Banco de Chile"
    ) = Movement(id = id, account = account, description = title,
        amountClp = amount, date = date)

    private fun forecast(
        movements: List<Movement> = emptyList(),
        obligations: List<Obligation> = emptyList(),
        plans: List<PlannedExpense> = emptyList(),
        balance: Long = 500_000
    ) = MonthlyCashflowProjection.compute(today, balance, cashAccounts,
        movements, obligations, plans)

    @Test fun countsCreditsAndRemainingPlansTogether() {
        val r = forecast(obligations = listOf(installment()),
            plans = listOf(planned()))
        assertEquals(80_000L, r.creditPaymentsClp)
        assertEquals(20_000L, r.plannedPaymentsClp)
        assertEquals(400_000L, r.projectedClp)
    }

    @Test fun avoidsChargingAlreadyRecordedPlanAgain() {
        val r = forecast(movements = listOf(
            movement(21, "🌐 Internet", -20_000L, "2026-11-16")),
            plans = listOf(planned()))
        assertEquals(0L, r.plannedPaymentsClp)
        assertEquals(1, r.alreadyRegisteredCount)
    }

    @Test fun avoidsChargingGroupedWaterAndElectricityAgain() {
        val r = forecast(
            movements = listOf(movement(37,
                "Pago de cuentas: Agua Hogar y Luz Hogar", -20_500L,
                "2026-11-15")),
            plans = listOf(
                planned(1, "Agua Hogar", 12_500L, "2026-11-16"),
                planned(2, "Luz Hogar", 8_000L, "2026-11-16")
            )
        )
        assertEquals(0L, r.plannedPaymentsClp)
        assertEquals(2, r.alreadyRegisteredCount)
        assertEquals(20_500L, r.alreadyRegisteredClp)
    }

    @Test fun weeklyPlansUseRemainingCalendarOccurrences() {
        val r = forecast(plans = listOf(
            planned(due = "2026-11-17", amount = 15_000L, frequency = "SEMANAL")))
        assertEquals(30_000L, r.plannedPaymentsClp) // November 17 and 24
    }

    @Test fun previouslyMissedWeeklyPaymentIsCountedOnceNotRetroactively() {
        val r = forecast(plans = listOf(
            planned(due = "2026-10-13", amount = 15_000L, frequency = "SEMANAL")))
        // One old unpaid due date + next 17 and 24 November.
        assertEquals(45_000L, r.plannedPaymentsClp)
    }

    @Test fun previouslyRecordedWeeklyPaymentDoesNotHideNextInstallment() {
        val plan = planned(due = "2026-11-17", frequency = "SEMANAL")
            .copy(lastPaidDate = "2026-11-15", lastPaymentMovementId = 42)
        val r = forecast(
            plans = listOf(plan),
            movements = listOf(movement(42, "🌐 Internet", -20_000L, "2026-11-15"))
        )
        assertEquals(40_000L, r.plannedPaymentsClp) // 17 and 24 Nov
    }

    @Test fun creditAccountPlanDoesNotReduceCashBeforeCardPayment() {
        val r = forecast(obligations = listOf(installment()),
            plans = listOf(planned(account = "CMR")))
        assertEquals(80_000L, r.creditPaymentsClp)
        assertEquals(0L, r.plannedPaymentsClp)
        assertEquals(20_000L, r.excludedCreditPlansClp)
    }

    @Test fun sameObligationInPlansAndCreditsIsCountedOnce() {
        val r = forecast(obligations = listOf(installment(
            title = "Internet", amount = 20_000L)),
            plans = listOf(planned()))
        assertEquals(20_000L, r.totalToPayClp)
        assertEquals(20_000L, r.overlapClp)
        assertEquals(1, r.overlapsCount)
    }

    @Test fun paymentAlreadyInCashMovementsIsNotProjectedAgain() {
        val r = forecast(obligations = listOf(installment()),
            movements = listOf(movement(11, "Pago Tarjeta CMR", -80_000L)))
        assertEquals(0L, r.creditPaymentsClp)
        assertEquals(1, r.alreadyRegisteredCount)
    }

    @Test fun previouslyPaidInstallmentsAreExcluded() {
        val r = forecast(obligations = listOf(installment(paid = true)))
        assertEquals(0L, r.creditPaymentsClp)
    }

    @Test fun receivablesAreOptionalAndNotPartOfConservativeTotal() {
        val receivable = Obligation(id = 2, title = "Reembolso",
            account = "Banco de Chile", amountClp = 45_000L,
            dueDate = "2026-11-24", kind = "Reembolso")
        val r = forecast(obligations = listOf(receivable))
        assertEquals(500_000L, r.projectedClp)
        assertEquals(545_000L, r.optimisticClp)
    }

    @Test fun categoryBudgetIsNotConsideredAnAdditionalDebit() {
        val r = forecast(plans = listOf(planned()))
        assertEquals(20_000L, r.totalToPayClp)
        assertEquals(20_000L, r.plannedPaymentsClp)
        // Los límites por categoría no forman parte de la función compute().
    }
}
