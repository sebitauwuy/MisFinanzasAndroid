package cl.personal.finanzas

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.withTransaction
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.text.NumberFormat
import java.util.Locale

/**
 * Una vinculación por movimiento evita usar dos veces el mismo cargo.
 * Se guarda una instantánea del gasto para permitir revertir la vinculación
 * solo cuando no existen cambios posteriores.
 */
@Entity(tableName = "planned_expense_links")
data class PlannedExpenseLink(
    @PrimaryKey val movementId: Long,
    val planId: Long,
    val dueBefore: String,
    val dueAfter: String,
    val estimateBefore: Long,
    val estimateAfter: Long,
    val previousPaidDate: String,
    val previousPaidAmount: Long,
    val previousAlreadyRegistered: Boolean,
    val previousMovementId: Long,
    val linkedAt: Long
)

@Composable
fun LinkPlannedExpenseDialog(
    db: FinanceDatabase,
    movement: Movement,
    plans: List<PlannedExpense>,
    existingLinks: List<PlannedExpenseLink>,
    onDismiss: () -> Unit
) {
    val dao = remember(db) { db.financeDao() }
    val scope = rememberCoroutineScope()
    val money = remember { NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL")) }
    val knownLink = existingLinks.firstOrNull { it.movementId == movement.id }
    val legacyPlan = plans.firstOrNull { it.lastPaymentMovementId == movement.id && it.id > 0 }
    val linkedPlan = knownLink?.let { link -> plans.firstOrNull { it.id == link.planId } } ?: legacyPlan
    val availablePlans = plans.filter { it.active && it.id > 0L }
        .sortedWith(compareBy({ kotlin.math.abs(runCatching {
            ChronoUnit.DAYS.between(LocalDate.parse(it.nextDueDate), LocalDate.parse(movement.date))
        }.getOrDefault(9999L)) }, { it.title }))
    var selectedId by remember(movement.id) {
        mutableStateOf(availablePlans.firstOrNull()?.id)
    }
    val selectedPlan = availablePlans.firstOrNull { it.id == selectedId }
    val actual = -movement.amountClp
    var updateEstimate by remember(movement.id, selectedId) {
        mutableStateOf(selectedPlan?.isVariable == true)
    }
    var busy by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }
    val paymentDate = runCatching { LocalDate.parse(movement.date) }.getOrNull()
    val dateValid = paymentDate != null && !paymentDate.isAfter(LocalDate.now())

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(if (linkedPlan != null) "Gasto programado vinculado"
                 else "Marcar gasto programado como pagado")
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(movement.description, style = MaterialTheme.typography.titleSmall)
                Text(money.format(actual) + " · " + AccountCatalog.displayName(movement.account) +
                    " · " + dateForDisplay(movement.date),
                    style = MaterialTheme.typography.bodySmall)

                if (linkedPlan != null) {
                    Text("Asociado con " + linkedPlan.icon + " " + linkedPlan.title,
                        fontWeight = FontWeight.Bold)
                    Text("El gasto ya quedó marcado como pagado con este movimiento. No se sumó ni descontó otra vez.")
                    if (knownLink == null) Text("Esta vinculación es anterior al historial de asociaciones. No se puede deshacer desde aquí.",
                        style = MaterialTheme.typography.bodySmall)
                } else if (knownLink != null) {
                    Text("Asociado a un gasto eliminado. El movimiento ya se utilizó y no puede vincularse otra vez.")
                } else if (!dateValid || actual <= 0) {
                    Text("Solo se pueden vincular gastos ya registrados con fecha no futura.",
                        color = MaterialTheme.colorScheme.error)
                } else if (availablePlans.isEmpty()) {
                    Text("No tienes gastos programados activos. Créalo primero en la pestaña Gastos.")
                } else {
                    Text("Elige a qué gasto programado corresponde este movimiento:")
                    availablePlans.forEach { plan ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            RadioButton(selected = selectedId == plan.id, onClick = {
                                selectedId = plan.id
                                updateEstimate = plan.isVariable
                            })
                            Column(Modifier.weight(1f)) {
                                Text(plan.icon + " " + plan.title, fontWeight =
                                    if (selectedId == plan.id) FontWeight.Bold else FontWeight.Normal)
                                Text("Previsto " + money.format(plan.estimatedAmountClp) +
                                    " · Vence " + dateForDisplay(plan.nextDueDate),
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (selectedPlan != null) {
                        val delta = actual - selectedPlan.estimatedAmountClp
                        Text("Valor real: " + money.format(actual) +
                            " · Diferencia frente al previsto: " + money.format(delta),
                            style = MaterialTheme.typography.bodySmall)
                        if (movement.account != selectedPlan.defaultAccount)
                            Text("Aviso: el movimiento corresponde a otra cuenta. Confirma que pagaste este gasto con ella.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        if (!selectedPlan.isVariable && delta != 0L)
                            Text("El gasto está configurado como fijo y el monto no coincide. El pago quedará con el valor real del movimiento.",
                                style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = updateEstimate, onCheckedChange = { updateEstimate = it })
                            Text("Usar este valor real como monto previsto para los siguientes pagos",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Se marcará pagado y avanzará al siguiente vencimiento. El dinero ya está descontado en Movs.; no se volverá a descontar.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (feedback.isNotBlank()) Text(feedback, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            if (knownLink != null && linkedPlan == null) {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            db.withTransaction {
                                val link = dao.getPlannedExpenseLink(movement.id)
                                    ?: error("La asociación ya no existe.")
                                require(dao.getPlannedExpense(link.planId) == null) {
                                    "El gasto programado cambió. Vuelve a abrir el historial."
                                }
                                dao.deletePlannedExpenseLink(link)
                            }
                            onDismiss()
                        } catch (e: Exception) {
                            feedback = "No se pudo quitar la asociación: " + e.message
                        } finally { busy = false }
                    }
                }) { Text("Quitar asociación antigua") }
            } else if (knownLink != null && linkedPlan != null) {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            db.withTransaction {
                                val link = dao.getPlannedExpenseLink(movement.id)
                                    ?: error("La vinculación ya no existe.")
                                val fresh = dao.getPlannedExpense(link.planId)
                                    ?: error("El gasto fue eliminado.")
                                require(fresh.lastPaymentMovementId == movement.id &&
                                    fresh.nextDueDate == link.dueAfter &&
                                    fresh.estimatedAmountClp == link.estimateAfter) {
                                    "Hay cambios posteriores; no se puede deshacer automáticamente."
                                }
                                dao.updatePlannedExpense(fresh.copy(
                                    nextDueDate = link.dueBefore,
                                    estimatedAmountClp = link.estimateBefore,
                                    lastPaidDate = link.previousPaidDate,
                                    lastPaidAmountClp = link.previousPaidAmount,
                                    lastPaidWithoutMovement = link.previousAlreadyRegistered,
                                    lastPaymentMovementId = link.previousMovementId
                                ))
                                dao.deletePlannedExpenseLink(link)
                            }
                            onDismiss()
                        } catch (e: Exception) {
                            feedback = "No se pudo desvincular: " + e.message
                        } finally { busy = false }
                    }
                }) { Text("Deshacer vínculo") }
            } else if (knownLink == null && legacyPlan == null) {
                TextButton(enabled = !busy && selectedPlan != null && dateValid && actual > 0L,
                    onClick = {
                        val plan = selectedPlan ?: return@TextButton
                        val shouldUpdate = updateEstimate
                        busy = true
                        scope.launch {
                            try {
                                db.withTransaction {
                                    val freshMovement = dao.getMovementById(movement.id)
                                        ?: error("El movimiento fue eliminado.")
                                    val freshPlan = dao.getPlannedExpense(plan.id)
                                        ?: error("El gasto programado fue eliminado.")
                                    require(freshMovement == movement && freshMovement.amountClp < 0 &&
                                        runCatching { LocalDate.parse(freshMovement.date) <= LocalDate.now() }
                                            .getOrDefault(false)) {
                                        "El movimiento cambió. Vuelve a abrir su historial."
                                    }
                                    require(freshPlan == plan && freshPlan.active) {
                                        "El gasto cambió. Vuelve a abrir el historial."
                                    }
                                    require(dao.getPlannedExpenseLink(movement.id) == null &&
                                        dao.allPlannedExpenses().none { it.lastPaymentMovementId == movement.id }) {
                                        "Este movimiento ya está asociado con un gasto."
                                    }
                                    val next = PlannedExpenseRules.nextDueAfterPayment(freshPlan, LocalDate.now())
                                    val newEstimate = if (shouldUpdate) -freshMovement.amountClp
                                        else freshPlan.estimatedAmountClp
                                    val link = PlannedExpenseLink(
                                        movementId = movement.id, planId = freshPlan.id,
                                        dueBefore = freshPlan.nextDueDate, dueAfter = next.toString(),
                                        estimateBefore = freshPlan.estimatedAmountClp,
                                        estimateAfter = newEstimate,
                                        previousPaidDate = freshPlan.lastPaidDate,
                                        previousPaidAmount = freshPlan.lastPaidAmountClp,
                                        previousAlreadyRegistered = freshPlan.lastPaidWithoutMovement,
                                        previousMovementId = freshPlan.lastPaymentMovementId,
                                        linkedAt = System.currentTimeMillis()
                                    )
                                    dao.insertPlannedExpenseLink(link)
                                    dao.updatePlannedExpense(freshPlan.copy(
                                        nextDueDate = next.toString(),
                                        estimatedAmountClp = newEstimate,
                                        lastPaidDate = movement.date,
                                        lastPaidAmountClp = -movement.amountClp,
                                        lastPaidWithoutMovement = true,
                                        lastPaymentMovementId = movement.id
                                    ))
                                }
                                onDismiss()
                            } catch (e: Exception) {
                                feedback = "No se pudo asociar: " + e.message
                            } finally { busy = false }
                        }
                    }) { Text("Marcar pagado") }
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cerrar") } }
    )
}
