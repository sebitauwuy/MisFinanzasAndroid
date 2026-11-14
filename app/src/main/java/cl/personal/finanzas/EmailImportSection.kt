package cl.personal.finanzas

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private data class EmailDraft(val date: String, val amount: Long, val description: String, val warning: String)

private fun parseSharedBankEmail(raw: String): EmailDraft? {
    val text = raw.replace("\u00a0", " ").replace(Regex("<[^>]+>"), " ")
    if (text.length < 30 || text.length > 100000) return null
    val amount = Regex("""(?i)(?:compra\s+por|cargo\s+(?:de|por)|monto(?:\s+transferencia)?\s*[:：]?|por\s+un\s+monto\s+de)\s*\$?\s*([0-9][0-9.,]*)""")
        .find(text)?.groupValues?.get(1)?.filter(Char::isDigit)?.toLongOrNull()
        ?.takeIf { it in 1..999999999999L } ?: return null
    val dateRaw = Regex("""\b(\d{4}-\d{2}-\d{2}|\d{1,2}[/-]\d{1,2}[/-]\d{4})\b""")
        .find(text)?.value ?: return null
    val date = try {
        if (dateRaw.startsWith("20")) LocalDate.parse(dateRaw)
        else LocalDate.parse(dateRaw.replace('-', '/'), DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ROOT))
    } catch (_: Exception) { return null }
    if (date.isAfter(LocalDate.now().plusDays(1)) || date.isBefore(LocalDate.now().minusYears(5))) return null
    val purchase = Regex("""(?is)(?:compra\s+por\s+\$?\s*[0-9.,]+.{0,120}?\s+en\s+)(.{3,90}?)\s+el\s+\d{1,2}[/-]\d{1,2}[/-]\d{4}""").find(text)
    val merchant = purchase?.groupValues?.get(1)?.replace(Regex("""\s+"""), " ")?.trim()
    val isPurchase = text.contains("cargo en cuenta", true) || text.contains("se ha realizado una compra", true)
    val isTransfer = text.contains("transferencia", true)
    if (!isPurchase && !isTransfer) return null
    return EmailDraft(date.toString(), -amount,
        if (isPurchase) "Compra: ${merchant?.take(70) ?: "correo bancario"}" else "Transferencia (revisar dirección)",
        if (isTransfer) "La dirección de una transferencia no se puede asegurar leyendo solo este texto. Revisa el signo antes de guardar."
        else "Verifica comercio, monto, fecha y cuenta con tu cartola.")
}

@Composable
fun EmailImportSection(
    sharedText: String,
    onConsumed: () -> Unit,
    movements: List<Movement>,
    accounts: List<String>,
    dao: FinanceDao
) {
    var raw by remember { mutableStateOf("") }
    var selectedAccount by remember(accounts) { mutableStateOf(accounts.firstOrNull() ?: "Banco de Chile") }
    var editedDate by remember { mutableStateOf("") }
    var editedAmount by remember { mutableStateOf("") }
    var editedDescription by remember { mutableStateOf("") }
    var income by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(false) }
    var confirmDuplicate by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(sharedText) {
        if (sharedText.isNotBlank()) {
            raw = sharedText.take(100000)
            preview = false
            onConsumed()
        }
    }
    val draft = remember(raw) { parseSharedBankEmail(raw) }
    val amount = editedAmount.filter(Char::isDigit).toLongOrNull()
    val signed = amount?.let { if (income) it else -it }
    val valid = preview && amount != null && amount > 0 && editedDescription.isNotBlank() &&
        runCatching { LocalDate.parse(editedDate) }.isSuccess
    val duplicate = valid && movements.any {
        it.account == selectedAccount && it.amountClp == signed && it.date == editedDate
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Importar aviso bancario", style = MaterialTheme.typography.titleLarge)
        Text("Copia el texto de un correo de Outlook y pégalo aquí. No necesitas Azure ni compartir tu contraseña. También puedes usar Compartir → Mis Finanzas cuando Outlook lo permita.")
        OutlinedTextField(raw, { raw = it.take(100000); preview = false; message = "" },
            modifier = Modifier.fillMaxWidth(), label = { Text("Texto del correo") }, minLines = 5, maxLines = 9)
        Button(onClick = {
            if (draft != null) {
                editedDate = draft.date
                editedAmount = kotlin.math.abs(draft.amount).toString()
                editedDescription = draft.description
                income = draft.amount > 0
                preview = true
                message = ""
            } else message = "No se reconoció con seguridad el monto, fecha y tipo. Prueba copiar el cuerpo completo de un aviso de Banco de Chile."
        }, enabled = raw.isNotBlank()) { Text("Analizar correo") }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        if (preview && draft != null) {
            HorizontalDivider()
            Text("Vista previa: revisa antes de guardar", style = MaterialTheme.typography.titleMedium)
            Text(draft.warning, style = MaterialTheme.typography.bodySmall)
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expanded = true }) { Text("Cuenta: ${AccountCatalog.displayName(selectedAccount)}") }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    accounts.forEach { account ->
                        DropdownMenuItem(text = { Text(AccountCatalog.displayName(account)) },
                            onClick = { selectedAccount = account; expanded = false })
                    }
                }
            }
            OutlinedTextField(editedDate, { editedDate = it.take(10) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Fecha AAAA-MM-DD") }, singleLine = true)
            OutlinedTextField(editedDescription, { editedDescription = it.take(120) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Descripción") }, singleLine = true)
            OutlinedTextField(editedAmount, { editedAmount = it.filter(Char::isDigit).take(12) },
                modifier = Modifier.fillMaxWidth(), label = { Text("Monto CLP (sin signo)") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !income, onClick = { income = false }, label = { Text("Cargo / gasto") })
                FilterChip(selected = income, onClick = { income = true }, label = { Text("Abono / ingreso") })
            }
            if (duplicate) Text("Atención: ya existe un movimiento de igual fecha, cuenta y monto.", color = MaterialTheme.colorScheme.error)
            Button(enabled = valid && !saving, onClick = {
                if (duplicate && !confirmDuplicate) confirmDuplicate = true
                else {
                    saving = true
                    scope.launch {
                        try {
                            dao.insertMovement(Movement(account = selectedAccount,
                                description = editedDescription.trim(), amountClp = signed!!,
                                date = editedDate, category = "Correo bancario"))
                            raw = ""; preview = false; confirmDuplicate = false
                            message = "Movimiento guardado. Revisa el saldo."
                        } catch (_: Exception) { message = "No se pudo guardar el movimiento." }
                        finally { saving = false }
                    }
                }
            }) { Text("Confirmar movimiento") }
        }
    }
    if (confirmDuplicate) AlertDialog(
        onDismissRequest = { confirmDuplicate = false },
        title = { Text("Posible duplicado") },
        text = { Text("Ya existe un movimiento con la misma fecha, cuenta y monto. Si lo registras de nuevo, tu saldo cambiará dos veces.") },
        confirmButton = { TextButton(onClick = { confirmDuplicate = false; message = "No se registró el posible duplicado." }) { Text("No duplicar") } },
        dismissButton = { TextButton(onClick = {
            confirmDuplicate = false
            saving = true
            scope.launch {
                try {
                    dao.insertMovement(Movement(account = selectedAccount, description = editedDescription.trim(),
                        amountClp = signed!!, date = editedDate, category = "Correo bancario"))
                    raw = ""; preview = false; message = "Movimiento guardado."
                } catch (_: Exception) { message = "Error al guardar." }
                finally { saving = false }
            }
        }) { Text("Registrar igualmente") } }
    )
}
