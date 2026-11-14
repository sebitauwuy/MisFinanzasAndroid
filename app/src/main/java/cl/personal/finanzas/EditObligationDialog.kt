package cl.personal.finanzas

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Edita el mismo registro de cuota/reembolso, conservando id y cuotas pagadas.
 * Las modificaciones son de planificación: no insertan movimientos bancarios.
 */
@Composable
fun EditObligationDialog(
    original: Obligation,
    availableAccounts: List<String>,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (Obligation) -> Unit
) {
    var title by remember(original.id) { mutableStateOf(original.title) }
    var amount by remember(original.id) { mutableStateOf(original.amountClp.toString()) }
    var account by remember(original.id) { mutableStateOf(original.account) }
    var dueDate by remember(original.id) { mutableStateOf(original.dueDate) }
    var totalInstallments by remember(original.id) { mutableStateOf(original.installments.toString()) }

    val isInstallment = original.kind == "Pago"
    val amountValue = amount.toLongOrNull()
    val total = if (isInstallment) totalInstallments.toIntOrNull() else original.installments
    val valid = title.isNotBlank() && amountValue != null && amountValue > 0L &&
        total != null && total in 1..120 && total >= original.paidCount &&
        runCatching { java.time.LocalDate.parse(dueDate) }.isSuccess &&
        account in AccountCatalog.names

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (isInstallment) "Editar cuotas" else "Editar cobro") },
        text = {
            Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text(if (isInstallment) "Compra o compromiso" else "Persona y motivo") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter(Char::isDigit) },
                    label = { Text(if (isInstallment) "Monto de cada cuota (CLP)" else "Monto por cobrar (CLP)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                AccountPicker(account, { account = it }, (availableAccounts + original.account).distinct())
                CalendarDateField(dueDate, { dueDate = it },
                    if (isInstallment) "Primer vencimiento" else "Fecha de cobro")
                if (isInstallment) {
                    OutlinedTextField(
                        value = totalInstallments,
                        onValueChange = { totalInstallments = it.filter(Char::isDigit).take(3) },
                        label = { Text("Cantidad total de cuotas") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Text("${original.paidCount} cuota(s) ya marcadas como pagadas. Se conservarán.",
                        style = MaterialTheme.typography.bodySmall)
                    if (total != null && total < original.paidCount) {
                        Text("El total no puede ser menor que las cuotas ya pagadas.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (original.paidCount > 0) Text(
                        "Al corregir el valor o el primer vencimiento se actualizará la planificación. No se reescribirán movimientos ni pagos anteriores.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (original.paid) {
                    Text("Este cobro ya figura como cobrado y mantendrá ese estado.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !saving, onClick = {
                onSave(original.copy(
                    title = title.trim(),
                    account = account,
                    amountClp = amountValue!!,
                    dueDate = dueDate,
                    installments = total!!,
                    paid = if (isInstallment) original.paidCount >= total else original.paid
                ))
            }) { Text(if (saving) "Guardando…" else "Guardar cambios") }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
