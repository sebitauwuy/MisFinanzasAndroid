package cl.personal.finanzas

import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/** Solo proyección de caja: NO modifica movimientos, saldos ni deudas. */
data class MonthlyCashflowSummary(
    val cashBalanceClp: Long,
    val creditPaymentsClp: Long,
    val plannedPaymentsClp: Long,
    val alreadyRegisteredClp: Long,
    val overlapClp: Long,
    val expectedReceivablesClp: Long,
    val projectedClp: Long,
    val creditsCount: Int,
    val plansCount: Int,
    val alreadyRegisteredCount: Int,
    val overlapsCount: Int,
    val excludedCreditPlansClp: Long,
) {
    val totalToPayClp: Long get() = creditPaymentsClp + plannedPaymentsClp
    val optimisticClp: Long get() = projectedClp + expectedReceivablesClp
}

object MonthlyCashflowProjection {
    private data class Due(
        val date: LocalDate, val amount: Long, val title: String, val account: String,
        val lastPaidDate: LocalDate? = null, val lastPaymentMovementId: Long = 0L
    )

    private fun parsed(raw: String) = runCatching { LocalDate.parse(raw) }.getOrNull()
    private fun label(raw: String): String = Normalizer
        .normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        .replace(Regex("""\s+"""), " ").trim()

    private fun nameMatches(title: String, description: String): Boolean {
        val name = label(title)
        val desc = label(description)
        if (name.length < 3 || desc.isBlank()) return false
        return desc == name || desc.startsWith(name + " ") ||
            desc.endsWith(" " + name) || desc.contains(" " + name + " ")
    }
    private fun near(a: LocalDate, b: LocalDate): Boolean =
        abs(ChronoUnit.DAYS.between(a, b)) <= 7L

    private fun creditDues(obligations: List<Obligation>, end: LocalDate): List<Due> =
        obligations.asSequence()
            .filter { it.kind == "Pago" && !it.paid && it.amountClp > 0L }
            .flatMap { o ->
                val first = parsed(o.dueDate) ?: return@flatMap emptySequence<Due>()
                (o.paidCount.coerceAtLeast(0) until o.installments.coerceAtMost(120))
                    .asSequence()
                    .map { index -> Due(first.plusMonths(index.toLong()),
                        o.amountClp, o.title, o.account) }
                    .takeWhile { !it.date.isAfter(end) }
            }.toList()

    private fun plannedDues(
        plans: List<PlannedExpense>, today: LocalDate, end: LocalDate,
        cashAccounts: Set<String>
    ): Pair<List<Due>, Long> {
        val dues = mutableListOf<Due>()
        var excludedCredit = 0L
        plans.filter { it.active && it.estimatedAmountClp > 0L }.forEach { plan ->
            val first = parsed(plan.nextDueDate) ?: return@forEach
            if (first.isAfter(end)) return@forEach
            val dates = mutableListOf(first)
            if (plan.frequency == "SEMANAL") {
                // Un solo vencimiento atrasado, nunca meses de cargos retroactivos.
                var next = if (first.isAfter(today)) first else
                    first.plusWeeks(ChronoUnit.DAYS.between(first, today) / 7L + 1L)
                while (!next.isAfter(end) && dates.size < 7) {
                    if (next != first) dates += next
                    next = next.plusWeeks(1)
                }
            }
            if (plan.defaultAccount !in cashAccounts) {
                excludedCredit += plan.estimatedAmountClp * dates.size
                return@forEach
            }
            dates.forEach { date -> dues += Due(
                date, plan.estimatedAmountClp, plan.title, plan.defaultAccount,
                lastPaidDate = parsed(plan.lastPaidDate),
                lastPaymentMovementId = plan.lastPaymentMovementId
            ) }
        }
        return dues to excludedCredit
    }

    private fun recorded(
        movement: Movement, due: Due, first: LocalDate, today: LocalDate,
        sign: Int, requireAccount: Boolean
    ): Boolean {
        val date = parsed(movement.date) ?: return false
        return date >= first && date <= today && near(date, due.date) &&
            (due.lastPaidDate == null || date.isAfter(due.lastPaidDate)) &&
            (due.lastPaymentMovementId == 0L || movement.id != due.lastPaymentMovementId) &&
            movement.amountClp == due.amount * sign &&
            (!requireAccount || movement.account == due.account) &&
            nameMatches(due.title, movement.description)
    }

    fun compute(
        today: LocalDate,
        cashBalanceClp: Long,
        cashAccounts: Collection<String>,
        movements: List<Movement>,
        obligations: List<Obligation>,
        plans: List<PlannedExpense>
    ): MonthlyCashflowSummary {
        val first = YearMonth.from(today).atDay(1)
        val end = YearMonth.from(today).atEndOfMonth()
        val cash = cashAccounts.toSet()
        val withdrawals = movements.filter { it.account in cash && it.amountClp < 0L }
        val deposits = movements.filter { it.account in cash && it.amountClp > 0L }
        val credit = creditDues(obligations, end)
        val (planned, creditAccountPlans) = plannedDues(plans, today, end, cash)

        val usedMovements = mutableSetOf<Long>()
        var recordedAmount = 0L
        var recordedCount = 0
        val unpaidPlans = planned.filterNot { due ->
            val match = withdrawals.firstOrNull { it.id !in usedMovements &&
                recorded(it, due, first, today, -1, true) }
            if (match == null) false else {
                usedMovements += match.id
                recordedAmount += due.amount
                recordedCount++
                true
            }
        }.toMutableList()

        // Un cargo bancario puede incluir varios servicios: monto TOTAL exacto,
        // nombres de TODOS los servicios, misma cuenta y fechas cercanas.
        withdrawals.filter { it.id !in usedMovements }.forEach { movement ->
            if (!label(movement.description).startsWith("pago de cuentas "))
                return@forEach
            val date = parsed(movement.date) ?: return@forEach
            if (date < first || date > today) return@forEach
            val matches = unpaidPlans.filter { due ->
                due.account == movement.account &&
                    near(date, due.date) &&
                    (due.lastPaidDate == null || date.isAfter(due.lastPaidDate)) &&
                    (due.lastPaymentMovementId == 0L ||
                        movement.id != due.lastPaymentMovementId) &&
                    nameMatches(due.title, movement.description)
            }
            if (matches.size < 2 || matches.sumOf { it.amount } != -movement.amountClp)
                return@forEach
            unpaidPlans.removeAll(matches.toSet())
            usedMovements += movement.id
            recordedAmount += -movement.amountClp
            recordedCount += matches.size
        }

        val unpaidCredits = credit.filterNot { due ->
            val match = withdrawals.firstOrNull { it.id !in usedMovements &&
                recorded(it, due, first, today, -1, false) }
            if (match == null) false else {
                usedMovements += match.id
                recordedAmount += due.amount
                recordedCount++
                true
            }
        }
        var overlap = 0L
        var overlapCount = 0
        unpaidCredits.forEach { due ->
            val match = unpaidPlans.firstOrNull {
                label(it.title) == label(due.title) &&
                    it.amount == due.amount && near(it.date, due.date)
            }
            if (match != null) {
                unpaidPlans.remove(match)
                overlap += match.amount
                overlapCount++
            }
        }

        val creditAmount = unpaidCredits.sumOf { it.amount }
        val planAmount = unpaidPlans.sumOf { it.amount }
        val receivables = obligations.asSequence()
            .filter { it.kind == "Reembolso" && !it.paid && it.amountClp > 0L }
            .filter { parsed(it.dueDate)?.let { date -> !date.isAfter(end) } == true }
            .filterNot { o ->
                val date = parsed(o.dueDate) ?: return@filterNot false
                deposits.any { movement ->
                    recorded(movement, Due(date, o.amountClp, o.title, o.account),
                        first, today, 1, false)
                }
            }.sumOf { it.amountClp }

        return MonthlyCashflowSummary(
            cashBalanceClp, creditAmount, planAmount, recordedAmount, overlap,
            receivables, cashBalanceClp - creditAmount - planAmount,
            unpaidCredits.size, unpaidPlans.size, recordedCount, overlapCount,
            creditAccountPlans
        )
    }

    fun approximateMonthlyRecurring(plans: List<PlannedExpense>): Long {
        val active = plans.filter { it.active && it.estimatedAmountClp > 0L }
        return active.filter { it.frequency == "MENSUAL" }.sumOf { it.estimatedAmountClp } +
            active.filter { it.frequency == "SEMANAL" }
                .sumOf { it.estimatedAmountClp } * 52L / 12L
    }
}
