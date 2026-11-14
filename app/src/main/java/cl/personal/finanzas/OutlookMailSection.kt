package cl.personal.finanzas

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.room.withTransaction
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/** Correos -> revisión -> movimientos. Nunca afecta saldos sin aprobación. */
@Composable
fun OutlookMailSection(db: FinanceDatabase) {
    val context = LocalContext.current
    val dao = remember(db) { db.financeDao() }
    val scope = rememberCoroutineScope()
    val mailItems by remember(dao) { dao.mailCandidates() }.collectAsState(initial = emptyList())
    var applicationId by remember { mutableStateOf(OutlookGraph.clientId(context)) }
    var accountSuffix by remember { mutableStateOf(OutlookGraph.accountLast4(context)) }
    var cutoff by remember { mutableStateOf(OutlookGraph.cutoffDate(context)) }
    var cutoffClock by remember { mutableStateOf(OutlookGraph.cutoffTime(context)) }
    var autoSync by remember { mutableStateOf(OutlookGraph.enabled(context)) }
    var connected by remember { mutableStateOf(OutlookGraph.isConnected(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf<OutlookDeviceCode?>(null) }
    var overrideCandidate by remember { mutableStateOf<MailCandidate?>(null) }
    val clp = remember { NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL")) }

    fun approve(candidate: MailCandidate, allowDuplicate: Boolean) {
        scope.launch {
            try {
                var blocked = false
                var blockedByCutoff = false
                db.withTransaction {
                    val fresh = dao.getMailCandidate(candidate.sourceId) ?: return@withTransaction
                    if (fresh.status != "PENDIENTE" && fresh.status != "POSIBLE_DUPLICADO") return@withTransaction
                    if (!OutlookGraph.isAfterCutoff(context, fresh)) {
                        blockedByCutoff = true
                        return@withTransaction
                    }
                    if (fresh.category == "Transferencia interna") { blocked = true; return@withTransaction }
                    if (!allowDuplicate && ChileBankEmailParser.possibleDuplicate(fresh, dao.allMovements())) {
                        dao.markMailCandidate(fresh.sourceId, "POSIBLE_DUPLICADO")
                        blocked = true
                        return@withTransaction
                    }
                    dao.insertMovement(Movement(
                        account = fresh.account, description = fresh.description,
                        amountClp = fresh.amountClp, date = fresh.operationDate,
                        category = fresh.category
                    ))
                    dao.markMailCandidate(fresh.sourceId, "IMPORTADO")
                }
                if (blockedByCutoff) {
                    message = "Ese movimiento es anterior o igual al saldo inicial: no debe sumarse nuevamente."
                } else if (blocked) {
                    if (candidate.category == "Transferencia interna") {
                        message = "Traspaso interno pendiente: aún no se pueden asociar las dos cuentas automáticamente."
                        return@launch
                    }
                    overrideCandidate = candidate
                    message = "Ya existe un monto similar en la cartola. Revisa antes de duplicar."
                } else message = "Movimiento revisado. Comprueba tu saldo."
            } catch (e: Exception) {
                message = "No se pudo registrar: ${e.message}"
            }
        }
    }

    if (prompt != null) {
        val code = prompt!!
        AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text("Autorizar Outlook") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Abre el sitio oficial de Microsoft e ingresa este código. No lo compartas con otra persona.")
                Text(code.code, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(code.verificationUrl, style = MaterialTheme.typography.bodySmall)
                Text("La aplicación comprobará tu autorización. No ingreses tu contraseña dentro de Mis Finanzas.")
                Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUrl)))
                }) { Text("Abrir Microsoft") }
            } },
            confirmButton = { TextButton(onClick = { prompt = null }) { Text("Cerrar") } }
        )
    }

    if (overrideCandidate != null) {
        val candidate = overrideCandidate!!
        AlertDialog(
            onDismissRequest = { overrideCandidate = null },
            title = { Text("Posible movimiento duplicado") },
            text = { Text("${candidate.description}, ${clp.format(candidate.amountClp)} del ${candidate.operationDate}. Existe un movimiento similar. ¿Seguro que quieres sumarlo otra vez?") },
            confirmButton = { TextButton(onClick = { overrideCandidate = null; approve(candidate, true) }) { Text("Registrar igualmente") } },
            dismissButton = { TextButton(onClick = { overrideCandidate = null }) { Text("Cancelar") } }
        )
    }

    Text("Conexión experimental: solo lectura de correo, sin claves bancarias. Para usarla hay que registrar Mis Finanzas como aplicación pública en Microsoft y copiar su ID (no es una contraseña).",
        style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = applicationId, onValueChange = { applicationId = it.trim().take(36) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("ID público de aplicación Microsoft") },
        placeholder = { Text("xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx") }
    )
    OutlinedTextField(
        value = accountSuffix,
        onValueChange = { accountSuffix = it.filter(Char::isDigit).take(4) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Últimos 4 dígitos de cuenta Banco de Chile") }
    )
    OutlinedTextField(
        value = cutoff, onValueChange = { cutoff = it.take(10) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Fecha del saldo ya conciliado (AAAA-MM-DD)") }
    )
    OutlinedTextField(
        value = cutoffClock, onValueChange = { cutoffClock = it.take(5) },
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("Hora del saldo conciliado (HH:mm, 24 horas)") },
        placeholder = { Text("19:50") }
    )
    Text("Solo se ofrecerán operaciones posteriores a la fecha y hora indicadas. Si no conocemos la hora de un aviso del mismo día, quedará excluido por seguridad. Comprueba que el saldo inicial no incluya estos movimientos.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = {
            message = if (OutlookGraph.saveSettings(context, applicationId, accountSuffix, cutoff, cutoffClock))
                "Configuración guardada" else "Comprueba el ID, los cuatro dígitos y la fecha y hora de corte."
            connected = OutlookGraph.isConnected(context)
        }) { Text("Guardar") }
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://entra.microsoft.com/")))
        }) { Text("Registro Microsoft") }
    }
    Text(if (connected) "Outlook autorizado en este dispositivo" else "Outlook sin conectar",
        color = if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)

    if (!connected) {
        Button(enabled = !busy, onClick = {
            if (!OutlookGraph.saveSettings(context, applicationId, accountSuffix, cutoff)) {
                message = "Primero ingresa el ID de aplicación, los cuatro dígitos de tu cuenta y la fecha y hora del saldo ya conciliado."
            } else {
                busy = true
                message = "Solicitando código a Microsoft…"
                scope.launch {
                    try {
                        val code = OutlookGraph.beginLogin(context)
                        prompt = code
                        message = "Autoriza el acceso en Microsoft con el código que se muestra."
                        OutlookGraph.awaitLogin(context, code)
                        connected = true
                        prompt = null
                        message = "Outlook conectado. Ya puedes buscar avisos bancarios."
                    } catch (e: Exception) {
                        message = "No fue posible conectar: ${e.message}"
                        prompt = null
                    } finally { busy = false }
                }
            }
        }) { Text(if (busy) "Conectando…" else "Conectar Outlook / Hotmail") }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val result = OutlookGraph.sync(context, dao)
                        message = "Revisados ${result.scanned} · remitentes Banco de Chile ${result.bankSender} · reconocidos ${result.parsed} · excluidos por fecha ${result.excludedByDate} · formatos no reconocidos ${result.unrecognized} · nuevos ${result.queued} · posibles duplicados ${result.possibleDuplicates}"
                    } catch (e: Exception) { message = "Error de sincronización: ${e.message}" }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Sincronizando…" else "Buscar avisos ahora") }
            TextButton(onClick = {
                OutlookGraph.forget(context)
                connected = false
                prompt = null
                message = "Sesión local desconectada; los movimientos ya registrados permanecen."
            }) { Text("Desconectar") }
        }
    }
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text("Buscar correos al abrir la app", modifier = Modifier.weight(1f))
        Switch(checked = autoSync, onCheckedChange = { autoSync = it; OutlookGraph.setEnabled(context, it) })
    }
    Text("Solo se detectan avisos de remitentes conocidos de Banco de Chile y de la cuenta indicada. Nunca se registran automáticamente. La lectura inicial revisa hasta 30 días de correo; no equivale a un saldo bancario en vivo.",
        style = MaterialTheme.typography.bodySmall)
    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)

    val pending = mailItems.filter { it.status == "PENDIENTE" || it.status == "POSIBLE_DUPLICADO" }
    Text("Para revisar (${pending.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    if (pending.isEmpty()) Text("Todavía no hay avisos nuevos por revisar.", style = MaterialTheme.typography.bodySmall)
    pending.take(30).forEach { candidate ->
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(candidate.description, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("${candidate.operationDate} · ${clp.format(candidate.amountClp)} · ${candidate.category}",
                    style = MaterialTheme.typography.bodySmall)
                Text(candidate.note, style = MaterialTheme.typography.labelSmall)
                if (candidate.status == "POSIBLE_DUPLICADO")
                    Text("Posible duplicado de la cartola", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        if (candidate.status == "POSIBLE_DUPLICADO" && candidate.category != "Transferencia interna") overrideCandidate = candidate
                        else approve(candidate, false)
                    }) { Text(if (candidate.category == "Transferencia interna") "Revisar traspaso" else "Registrar") }
                    TextButton(onClick = {
                        scope.launch {
                            dao.markMailCandidate(candidate.sourceId, "OMITIDO")
                            message = "Aviso omitido."
                        }
                    }) { Text("Omitir") }
                }
            }
        }
    }
    val processed = mailItems.count { it.status == "IMPORTADO" }
    if (processed > 0) Text("Importados desde el correo: $processed", style = MaterialTheme.typography.labelMedium)
}
