package cl.personal.finanzas

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.launch

/** Gmail (una cuenta) y Outlook comparten la bandeja de revisión de MailCandidate. */
@Composable
fun GmailMailSection(db: FinanceDatabase) {
    val context = LocalContext.current
    val dao = remember(db) { db.financeDao() }
    val client = remember(context) { Identity.getAuthorizationClient(context) }
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(GmailBankSync.email(context)) }
    var cutoff by remember { mutableStateOf(GmailBankSync.cutoff(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmDisconnect by remember { mutableStateOf(false) }

    fun consume(result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrBlank() ||
            GmailBankSync.READ_SCOPE !in result.grantedScopes) {
            message = "No se concedió el permiso de lectura de Gmail."
            busy = false
            return
        }
        scope.launch {
            busy = true
            try {
                val scan = GmailBankSync.sync(context, dao, token)
                email = scan.mailbox
                message = "Gmail: revisados ${scan.scanned}, reconocidos ${scan.recognized}, " +
                    "nuevos ${scan.queued}, posibles duplicados ${scan.flagged}. " +
                    if (scan.unknownTenpo > 0)
                        "${scan.unknownTenpo} avisos de Tenpo no tienen formato compatible; " +
                            "se omitieron por seguridad."
                    else "Los avisos reconocidos están en Para revisar."
            } catch (error: Exception) {
                message = "Gmail: ${error.message ?: "no se pudo sincronizar"}"
            } finally {
                busy = false
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { response ->
        try {
            val data: Intent? = response.data
            if (response.resultCode != Activity.RESULT_OK || data == null) {
                message = "Autorización de Gmail cancelada."
                busy = false
            } else {
                consume(client.getAuthorizationResultFromIntent(data))
            }
        } catch (e: Exception) {
            busy = false
            message = "No se pudo autorizar Gmail: ${e.message}"
        }
    }

    fun authorize() {
        busy = true
        message = "Solicitando permiso de solo lectura a Google…"
        client.authorize(GmailBankSync.authorization(context))
            .addOnSuccessListener { result ->
                try {
                    if (result.hasResolution()) {
                        val intent = result.pendingIntent?.intentSender
                            ?: error("Google no entregó pantalla de autorización.")
                        launcher.launch(IntentSenderRequest.Builder(intent).build())
                    } else consume(result)
                } catch (e: Exception) {
                    busy = false
                    message = "Gmail: ${e.message}"
                }
            }
            .addOnFailureListener {
                busy = false
                message = "No se pudo conectar Gmail. Comprueba la configuración " +
                    "OAuth Android del proyecto Google Cloud y Google Play Services."
            }
    }

    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text("Gmail · avisos bancarios", style = MaterialTheme.typography.titleMedium)
        Text(
            "Conecta una cuenta Gmail además de Outlook. Lee avisos bancarios " +
                "de los últimos 30 días y crea candidatos pendientes de revisión. " +
                "Nunca modifica saldos sin confirmación.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(if (email.isBlank()) "Gmail: sin conectar" else "Gmail: $email",
            style = MaterialTheme.typography.bodyMedium)
        CalendarDateField(cutoff, { cutoff = it }, "Buscar operaciones posteriores a")
        OutlinedButton(
            enabled = !busy,
            onClick = {
                message = if (GmailBankSync.setCutoff(context, cutoff))
                    "Fecha de corte guardada." else "Fecha de corte inválida."
            }
        ) { Text("Guardar fecha de corte Gmail") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = { authorize() }) {
                Text(if (busy) "Procesando…" else
                    if (email.isBlank()) "Conectar Gmail" else "Buscar avisos Gmail")
            }
            if (email.isNotBlank()) {
                OutlinedButton(enabled = !busy, onClick = { confirmDisconnect = true }) {
                    Text("Desconectar")
                }
            }
        }
        Text(
            "Solo se solicita gmail.readonly. No se almacenan contraseñas, tokens " +
                "ni el contenido completo de los correos. Google puede pedir que " +
                "autorices el acceso nuevamente. Requiere OAuth Android " +
                "(paquete y SHA-1 de la firma) y Gmail API en Google Cloud.",
            style = MaterialTheme.typography.bodySmall
        )
        if (message.isNotBlank())
            Text(message, style = MaterialTheme.typography.bodySmall)
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text("Desconectar Gmail") },
            text = { Text("Se revocará el permiso Gmail. " +
                "Los movimientos y avisos ya revisados se conservarán.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisconnect = false
                    busy = true
                    GmailBankSync.revoke(context) { ok ->
                        busy = false
                        if (ok) email = ""
                        message = if (ok) "Gmail desconectado."
                            else "No se pudo revocar el permiso. Intenta nuevamente."
                    }
                }) { Text("Revocar acceso") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text("Cancelar") }
            }
        )
    }
}
