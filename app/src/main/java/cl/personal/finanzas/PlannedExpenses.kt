package cl.personal.finanzas

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.withTransaction
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Presupuesto recurrente: no afecta los saldos hasta registrar realmente el pago. */
@Entity(tableName = "planned_expenses")
data class PlannedExpense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val icon: String,
    val estimatedAmountClp: Long,
    val isVariable: Boolean,
    val frequency: String, // SEMANAL o MENSUAL
    val nextDueDate: String,
    val anchorDay: Int,
    val defaultAccount: String,
    val active: Boolean = true,
    val lastPaidDate: String = "",
    val lastPaidAmountClp: Long = 0L,
    val lastPaidWithoutMovement: Boolean = false,
    val lastPaymentMovementId: Long = 0L
)

object PlannedExpenseRules {
    val icons = listOf("💡", "💧", "👨‍👩‍👧", "🏠", "📱", "🛒", "🚗", "⛽", "🍽️",
        "🐶", "💊", "🎓", "🌐", "🎁", "💳", "🔧", "🚌", "💰")

    fun valid(plan: PlannedExpense): Boolean {
        val date = runCatching { LocalDate.parse(plan.nextDueDate) }.getOrNull()
        return plan.title.isNotBlank() && plan.title.length <= 100 &&
            plan.icon in icons && plan.estimatedAmountClp in 1L..999_999_999_999L &&
            plan.frequency in setOf("SEMANAL", "MENSUAL") &&
            date != null && plan.anchorDay in 1..31 &&
            plan.defaultAccount in AccountCatalog.names &&
            plan.lastPaidAmountClp >= 0 && plan.lastPaymentMovementId >= 0
    }

    fun nextDueAfterPayment(plan: PlannedExpense, today: LocalDate): LocalDate {
        val due = LocalDate.parse(plan.nextDueDate)
        if (plan.frequency == "SEMANAL") {
            // Mantener el día de la semana original, aun con pagos atrasados.
            val weeks = (ChronoUnit.DAYS.between(due, today).coerceAtLeast(0) / 7) + 1
            return due.plusWeeks(weeks)
        }
        var month = YearMonth.from(due).plusMonths(1)
        while (true) {
            val next = month.atDay(minOf(plan.anchorDay, month.lengthOfMonth()))
            if (next.isAfter(today)) return next
            month = month.plusMonths(1)
        }
    }
}

@Composable
fun PlannedExpensesScreen(
    db: FinanceDatabase,
    plans: List<PlannedExpense>,
    movements: List<Movement>,
    visibleAccounts: List<String>,
    onBack: () -> Unit,
    requestedPaymentId: Long? = null,
    onPaymentRequestConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val dao = remember(db) { db.financeDao() }
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    val clp = remember { NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL")) }
    val paymentAccounts = visibleAccounts

    var showEditor by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PlannedExpense?>(null) }
    var title by remember { mutableStateOf("") }
    var amountInput by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("💡") }
    var frequency by remember { mutableStateOf("MENSUAL") }
    var variable by remember { mutableStateOf(true) }
    var nextDue by remember { mutableStateOf(today.toString()) }
    var defaultAccount by remember { mutableStateOf(paymentAccounts.firstOrNull() ?: "Banco de Chile") }
    var paymentPlan by remember { mutableStateOf<PlannedExpense?>(null) }
    var paymentAmount by remember { mutableStateOf("") }
    var paymentAccount by remember { mutableStateOf("") }
    var paymentDate by remember { mutableStateOf(today.toString()) }
    var paymentExisting by remember { mutableStateOf(false) }
    var paymentConfirmDuplicate by remember { mutableStateOf(false) }
    var deletePlan by remember { mutableStateOf<PlannedExpense?>(null) }
    var working by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf("") }

    fun openEditor(plan: PlannedExpense?) {
        editing = plan
        title = plan?.title.orEmpty()
        amountInput = plan?.estimatedAmountClp?.toString().orEmpty()
        icon = plan?.icon ?: "💡"
        frequency = plan?.frequency ?: "MENSUAL"
        variable = plan?.isVariable ?: true
        nextDue = plan?.nextDueDate ?: today.toString()
        defaultAccount = plan?.defaultAccount?.takeIf { it in paymentAccounts }
            ?: paymentAccounts.firstOrNull() ?: "Banco de Chile"
        showEditor = true
    }

    fun startPayment(plan: PlannedExpense) {
        paymentPlan = plan
        paymentAmount = plan.estimatedAmountClp.toString()
        paymentAccount = plan.defaultAccount.takeIf { it in paymentAccounts } ?: paymentAccounts.firstOrNull().orEmpty()
        paymentDate = today.toString()
        paymentExisting = false
        paymentConfirmDuplicate = false
    }

    LaunchedEffect(requestedPaymentId, plans) {
        val id = requestedPaymentId
        if (id != null) {
            plans.firstOrNull { it.id == id && it.active }?.let(::startPayment)
            onPaymentRequestConsumed()
        }
    }

    val activePlans = plans.filter { it.active }.sortedBy { it.nextDueDate }
    val inactivePlans = plans.filterNot { it.active }.sortedBy { it.title }
    val weeklyEstimate = activePlans.filter { it.frequency == "SEMANAL" }.sumOf { it.estimatedAmountClp }
    val monthlyEstimate = activePlans.filter { it.frequency == "MENSUAL" }.sumOf { it.estimatedAmountClp }
    val approximateMonth = monthlyEstimate + weeklyEstimate * 52 / 12
    val dueNow = activePlans.count { runCatching { LocalDate.parse(it.nextDueDate) }.getOrNull()?.isAfter(today) == false }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Inicio") }
            Text("Gastos programados", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Presupuesto mensual aproximado", style = MaterialTheme.typography.titleMedium)
                        Text(clp.format(approximateMonth), style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold)
                        Text("${activePlans.size} gasto(s) activos · ${dueNow} vencido(s) o para hoy",
                            style = MaterialTheme.typography.bodySmall)
                        Text("Estimación: los gastos semanales se convierten a 52 semanas / 12 meses. No se descuentan del saldo hasta que registres el pago.",
                            style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { openEditor(null) }) { Text("+ Agregar gasto previsto") }
                    }
                }
            }
            if (activePlans.isEmpty() && inactivePlans.isEmpty()) {
                item {
                    Text("Puedes añadir Luz 💡, Agua 💧 o un aporte semanal 👨‍👩‍👧. Elige su frecuencia y si el valor es fijo o variable.",
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            items(activePlans.size, key = { "active_${activePlans[it].id}" }) { index ->
                val plan = activePlans[index]
                val due = runCatching { LocalDate.parse(plan.nextDueDate) }.getOrDefault(today)
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(plan.icon, style = MaterialTheme.typography.headlineMedium)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(plan.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("${if (plan.frequency == "SEMANAL") "Cada semana" else "Cada mes"} · ${if (plan.isVariable) "Variable" else "Fijo"}",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Text(clp.format(plan.estimatedAmountClp), fontWeight = FontWeight.Bold)
                        }
                        Text("Próximo vencimiento: ${dateForDisplay(due.toString())}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (!due.isAfter(today)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (plan.lastPaidDate.isNotBlank()) {
                            Text("Último pago: ${clp.format(plan.lastPaidAmountClp)} · ${dateForDisplay(plan.lastPaidDate)}" +
                                if (plan.lastPaidWithoutMovement) " (ya registrado)" else "",
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(onClick = { startPayment(plan) }) { Text("Marcar pagado") }
                            TextButton(onClick = { openEditor(plan) }) { Text("Editar") }
                            TextButton(onClick = {
                                scope.launch { dao.updatePlannedExpense(plan.copy(active = false)) }
                            }) { Text("Pausar") }
                        }
                    }
                }
            }
            if (inactivePlans.isNotEmpty()) {
                item { Text("Pausados", style = MaterialTheme.typography.titleMedium) }
                items(inactivePlans.size, key = { "paused_${inactivePlans[it].id}" }) { index ->
                    val plan = inactivePlans[index]
                    Card {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Text("${plan.icon} ${plan.title} · ${clp.format(plan.estimatedAmountClp)}")
                            Row {
                                TextButton(onClick = { scope.launch { dao.updatePlannedExpense(plan.copy(active = true)) } }) {
                                    Text("Reactivar")
                                }
                                TextButton(onClick = { openEditor(plan) }) { Text("Editar") }
                                TextButton(onClick = { deletePlan = plan }) { Text("Eliminar") }
                            }
                        }
                    }
                }
            }
            item {
                if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodySmall)
                Text("Para no descontar dos veces, usa «Ya está registrado» si el gasto ya aparece en Movs. o fue importado desde Outlook.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showEditor) {
        val date = runCatching { LocalDate.parse(nextDue) }.getOrNull()
        val amount = amountInput.toLongOrNull()
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(if (editing == null) "Nuevo gasto previsto" else "Editar gasto previsto") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(title, { title = it.take(100) }, label = { Text("Nombre (ej. Luz, Agua, aporte)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("Ícono", style = MaterialTheme.typography.labelLarge)
                    PlannedExpenseRules.icons.chunked(4).forEach { icons ->
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            icons.forEach { option ->
                                FilterChip(selected = icon == option, onClick = { icon = option },
                                    label = { Text(option) })
                            }
                        }
                    }
                    OutlinedTextField(amountInput, { amountInput = it.filter(Char::isDigit).take(12) },
                        label = { Text("Monto previsto en CLP") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                    Text("Tipo de monto", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !variable, onClick = { variable = false }, label = { Text("Fijo") })
                        FilterChip(selected = variable, onClick = { variable = true }, label = { Text("Variable") })
                    }
                    Text("Frecuencia", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = frequency == "SEMANAL", onClick = { frequency = "SEMANAL" },
                            label = { Text("Semanal") })
                        FilterChip(selected = frequency == "MENSUAL", onClick = { frequency = "MENSUAL" },
                            label = { Text("Mensual") })
                    }
                    OutlinedButton(onClick = {
                        val chosen = date ?: today
                        DatePickerDialog(context, { _, y, m, d ->
                            nextDue = LocalDate.of(y, m + 1, d).toString()
                        }, chosen.year, chosen.monthValue - 1, chosen.dayOfMonth).show()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("📅 Próximo vencimiento: ${dateForDisplay(nextDue)}")
                    }
                    if (paymentAccounts.isEmpty()) {
                        Text("Activa una cuenta o tarjeta desde Ajustes para poder guardar este gasto.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (paymentAccounts.isNotEmpty()) {
                        Text("Cuenta o tarjeta habitual de pago", style = MaterialTheme.typography.labelLarge)
                        AccountPicker(defaultAccount, { defaultAccount = it }, paymentAccounts)
                    }
                    Text("Los valores estimados no cambian tus saldos hasta marcar el gasto como pagado.",
                        style = MaterialTheme.typography.bodySmall)
                    if (editing != null) TextButton(onClick = {
                        val plan = editing
                        showEditor = false
                        deletePlan = plan
                    }) { Text("Eliminar gasto previsto", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !working && title.isNotBlank() && amount != null &&
                        amount in 1L..999_999_999_999L && date != null && defaultAccount in paymentAccounts,
                    onClick = {
                        val old = editing
                        val plan = PlannedExpense(
                            id = old?.id ?: 0L, title = title.trim(), icon = icon,
                            estimatedAmountClp = amount!!, isVariable = variable, frequency = frequency,
                            nextDueDate = nextDue,
                            anchorDay = if (old != null && old.nextDueDate == nextDue && old.frequency == frequency)
                                old.anchorDay else date!!.dayOfMonth,
                            defaultAccount = defaultAccount,
                            active = old?.active ?: true, lastPaidDate = old?.lastPaidDate.orEmpty(),
                            lastPaidAmountClp = old?.lastPaidAmountClp ?: 0L,
                            lastPaidWithoutMovement = old?.lastPaidWithoutMovement ?: false,
                            lastPaymentMovementId = old?.lastPaymentMovementId ?: 0L
                        )
                        if (!PlannedExpenseRules.valid(plan)) {
                            feedback = "Comprueba los datos del gasto previsto."
                        } else {
                            working = true
                            scope.launch {
                                try {
                                    if (old == null) dao.insertPlannedExpense(plan)
                                    else dao.updatePlannedExpense(plan)
                                    showEditor = false
                                    feedback = "Gasto previsto guardado."
                                } catch (e: Exception) {
                                    feedback = "No se pudo guardar: ${e.message}"
                                } finally { working = false }
                            }
                        }
                    }
                ) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { showEditor = false }) { Text("Cancelar") } }
        )
    }

    if (paymentPlan != null) {
        val plan = paymentPlan!!
        val actual = if (plan.isVariable) paymentAmount.toLongOrNull() else plan.estimatedAmountClp
        val paidDate = runCatching { LocalDate.parse(paymentDate) }.getOrNull()
        val possibleDuplicates = movements.any {
            it.account == paymentAccount && it.amountClp == -(actual ?: 0L) &&
                runCatching { kotlin.math.abs(ChronoUnit.DAYS.between(LocalDate.parse(it.date), paidDate)) <= 1L }
                    .getOrDefault(false)
        }
        AlertDialog(
            onDismissRequest = { paymentPlan = null },
            title = { Text("Registrar pago · ${plan.title}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Previsto: ${clp.format(plan.estimatedAmountClp)} · ${if (plan.isVariable) "Variable" else "Fijo"}")
                    if (plan.isVariable) OutlinedTextField(paymentAmount,
                        { paymentAmount = it.filter(Char::isDigit).take(12) },
                        label = { Text("Valor real pagado (CLP)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth())
                    else Text("Se registrará el monto fijo: ${clp.format(plan.estimatedAmountClp)}")
                    Text("Cuenta o tarjeta utilizada", style = MaterialTheme.typography.labelLarge)
                    if (paymentAccounts.isNotEmpty()) AccountPicker(paymentAccount,
                        { paymentAccount = it; paymentConfirmDuplicate = false }, paymentAccounts)
                    OutlinedButton(onClick = {
                        val date = paidDate ?: today
                        DatePickerDialog(context, { _, y, m, d ->
                            paymentDate = LocalDate.of(y, m + 1, d).toString()
                            paymentConfirmDuplicate = false
                        }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                    }) { Text("📅 Fecha del pago: ${dateForDisplay(paymentDate)}") }
                    if (paidDate?.isAfter(today) == true) Text(
                        "No se puede marcar como realizado un pago de una fecha futura.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (plan.isVariable && actual != null) Text("Diferencia frente al previsto: ${clp.format(actual - plan.estimatedAmountClp)}",
                        style = MaterialTheme.typography.bodySmall)
                    if (possibleDuplicates && !paymentExisting) {
                        Text("Ya hay una salida similar en esta cuenta y fecha. Puede ser un pago registrado previamente.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (paymentConfirmDuplicate) Text("Confirma que se trata de otro pago distinto antes de registrarlo.",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text("«Registrar gasto» descuenta dinero. «Ya registrado» avanza el vencimiento sin modificar saldos. Si había períodos antiguos sin pagar, no se generan cargos retroactivos.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = !working && actual != null && actual in 1L..999_999_999_999L &&
                    paidDate != null && !paidDate.isAfter(today) && paymentAccount in paymentAccounts, onClick = {
                    if (possibleDuplicates && !paymentConfirmDuplicate && !paymentExisting) {
                        paymentConfirmDuplicate = true
                        return@TextButton
                    }
                    val accountToPay = paymentAccount
                    val amountToPay = actual!!
                    val paidOn = paymentDate
                    val alreadyRecorded = paymentExisting
                    val duplicateAllowed = paymentConfirmDuplicate
                    working = true
                    scope.launch {
                        try {
                            db.withTransaction {
                                val fresh = dao.getPlannedExpense(plan.id) ?: error("Este gasto ya no existe.")
                                require(fresh.active && fresh.nextDueDate == plan.nextDueDate &&
                                    fresh.estimatedAmountClp == plan.estimatedAmountClp &&
                                    fresh.isVariable == plan.isVariable) {
                                    "Este vencimiento ya fue procesado o modificado."
                                }
                                if (!alreadyRecorded && !duplicateAllowed) {
                                    val duplicateNow = dao.allMovements().any { movement ->
                                        movement.account == accountToPay &&
                                            movement.amountClp == -amountToPay &&
                                            runCatching {
                                                kotlin.math.abs(ChronoUnit.DAYS.between(
                                                    LocalDate.parse(movement.date), LocalDate.parse(paidOn)
                                                )) <= 1L
                                            }.getOrDefault(false)
                                    }
                                    require(!duplicateNow) {
                                        "Se encontró un posible duplicado al guardar. Vuelve a abrir el pago y confirma si corresponde."
                                    }
                                }
                                val movementId = if (!alreadyRecorded) {
                                    dao.insertMovement(Movement(
                                        account = accountToPay, description = "${fresh.icon} ${fresh.title}",
                                        amountClp = -amountToPay, date = paidOn,
                                        category = "Gasto previsto"
                                    ))
                                } else 0L
                                dao.updatePlannedExpense(fresh.copy(
                                    nextDueDate = PlannedExpenseRules.nextDueAfterPayment(fresh, today).toString(),
                                    lastPaidDate = paidOn, lastPaidAmountClp = amountToPay,
                                    lastPaidWithoutMovement = alreadyRecorded,
                                    lastPaymentMovementId = movementId
                                ))
                            }
                            paymentPlan = null
                            feedback = if (alreadyRecorded) "Marcado como pagado, sin descontar otra vez."
                                else "Pago registrado y descontado de ${AccountCatalog.displayName(accountToPay)}."
                        } catch (e: Exception) {
                            feedback = "No se pudo guardar el pago: ${e.message}"
                        } finally { working = false }
                    }
                }) { Text(if (paymentConfirmDuplicate) "Registrar igualmente" else
                    if (paymentExisting) "Confirmar registrado" else "Registrar gasto") }
            },
            dismissButton = {
                Row {
                    TextButton(enabled = !working, onClick = { paymentPlan = null }) { Text("Cancelar") }
                    TextButton(enabled = !working, onClick = {
                        paymentExisting = !paymentExisting
                        paymentConfirmDuplicate = false
                    }) { Text(if (paymentExisting) "Registrar nuevo" else "Ya registrado") }
                }
            }
        )
    }

    if (deletePlan != null) {
        val plan = deletePlan!!
        AlertDialog(
            onDismissRequest = { deletePlan = null },
            title = { Text("Eliminar gasto previsto") },
            text = { Text("Se eliminará ${plan.title} del presupuesto. Los movimientos bancarios ya registrados se conservarán.") },
            confirmButton = { TextButton(onClick = {
                deletePlan = null
                scope.launch {
                    try { dao.deletePlannedExpense(plan); feedback = "Gasto previsto eliminado." }
                    catch (e: Exception) { feedback = "No se pudo eliminar: ${e.message}" }
                }
            }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deletePlan = null }) { Text("Cancelar") } }
        )
    }
}
