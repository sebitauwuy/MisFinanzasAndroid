package cl.personal.finanzas

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
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
    var showSetupHelp by remember { mutableStateOf(false) }
    var showChileSettings by remember { mutableStateOf(false) }
    var chileLast4 by remember {
        mutableStateOf(GmailBankSync.bancoChileLast4(context))
    }

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
                // La cuenta puede quedar vinculada aun si una consulta a Gmail falla.
                email = GmailBankSync.email(context)
                message = if (email.isNotBlank())
                    "Cuenta Gmail vinculada, pero no se pudo buscar correos. " +
                        (error.message ?: "Error de sincronización.")
                else
                    "No se pudo conectar Gmail: " +
                        (error.message ?: "Fallo en la autorización.")
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
            if (data == null) {
                // Una vuelta sin intent NO demuestra que el usuario haya cancelado.
                // Puede ocurrir con cliente OAuth/certificado no configurado.
                busy = false
                message = if (response.resultCode == Activity.RESULT_CANCELED)
                    "Google regresó sin conceder el acceso a Gmail. " +
                        "Si seleccionaste tu correo, revisa el cliente OAuth Android " +
                        "(paquete, SHA-1 de la APK firmada y usuario de prueba)."
                else
                    "Google no devolvió autorización (resultado ${response.resultCode}). " +
                        "Revisa la configuración OAuth y vuelve a intentar."
            } else {
                // Google documenta extraer el resultado desde el Intent.
                // Si falló, getAuthorizationResultFromIntent entrega ApiException
                // con código de estado útil para el diagnóstico.
                consume(client.getAuthorizationResultFromIntent(data))
            }
        } catch (e: ApiException) {
            busy = false
            message = "Google rechazó la autorización (código ${e.statusCode}). " +
                "Comprueba OAuth Android, huella SHA-1, Gmail API y usuarios de prueba."
        } catch (e: Exception) {
            busy = false
            message = "No se pudo completar Gmail: " +
                (e.message?.take(120) ?: "error no identificado")
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
            .addOnFailureListener { error ->
                busy = false
                message = if (error is ApiException)
                    "Google no pudo iniciar la autorización (código ${error.statusCode}). " +
                        "Revisa la firma de la APK y el cliente OAuth en Google Cloud."
                else
                    "Google no pudo iniciar la autorización: " +
                        (error.message?.take(120) ?: "revisa Google Play Services y la conexión.")
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
        Text(if (email.isBlank()) "Gmail: sin vincular" else "Cuenta Gmail vinculada: $email",
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
        TextButton(onClick = { showChileSettings = !showChileSettings }) {
            Text(if (showChileSettings) "Ocultar datos bancarios"
                else "¿Recibes correos de Banco de Chile? (opcional)")
        }
        if (showChileSettings) {
            OutlinedTextField(
                value = chileLast4,
                onValueChange = { chileLast4 = it.filter(Char::isDigit).take(4) },
                label = { Text("Últimos 4 dígitos de la cuenta de Banco de Chile") },
                supportingText = {
                    Text("Solo para identificar avisos de esta cuenta recibidos en Gmail. " +
                        "No requiere conectar Outlook.")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedButton(
                enabled = !busy && (chileLast4.isBlank() || chileLast4.length == 4),
                onClick = {
                    message = if (GmailBankSync.setBancoChileLast4(context, chileLast4))
                        "Cuenta Banco de Chile guardada para Gmail."
                    else "Debes ingresar cuatro dígitos o dejar vacío."
                }
            ) { Text("Guardar cuenta de Gmail") }
        }

        if (message.isNotBlank()) {
            Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Text(
            "Gmail necesita autorización de Google. Outlook no es obligatorio.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextButton(onClick = { showSetupHelp = !showSetupHelp }) {
            Text(if (showSetupHelp) "Ocultar ayuda de Gmail" else "¿No se conecta Gmail?")
        }
        if (showSetupHelp) {
            Text(
                "Para el dueño de la aplicación: habilita Gmail API en Google Cloud, " +
                    "registra un cliente OAuth Android con el paquete cl.personal.finanzas " +
                    "y SHA-1 de la APK firmada, agrega gmail.readonly y añade como " +
                    "usuarios de prueba cada dirección Gmail que vaya a conectar. " +
                    "La persona que usa la app no necesita configurar Google Cloud. " +
                    "No compartas claves, contraseñas ni tokens.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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
