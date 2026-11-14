package cl.personal.finanzas

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.util.Locale

/** Recordatorio en pantalla, solamente al abrir la aplicación después de las 17:00. */
data class DuePaymentNudge(
    val id: Long,
    val kind: String, // "programado" o "cuota"
    val title: String,
    val amountClp: Long,
    val dueCount: Int
)

@Composable
fun DuePaymentNudgeDialog(
    nudge: DuePaymentNudge,
    onReview: () -> Unit,
    onLater: () -> Unit
) {
    val clp = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL"))
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text("¿Ya pagaste lo que vence hoy?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${nudge.title} · ${clp.format(nudge.amountClp)}")
                if (nudge.dueCount > 1) Text(
                    "Tienes ${nudge.dueCount} compromisos con vencimiento hoy.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    if (nudge.kind == "programado")
                        "Puedes registrar el monto real y la cuenta utilizada, o marcarlo como ya registrado si llegó desde Outlook. Solo se descontará si confirmas un gasto nuevo."
                    else
                        "Puedes revisar la cuota en Cuotas y marcarla pagada. Marcar una cuota como pagada no genera un movimiento bancario ni descuenta dinero automáticamente.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onReview) {
                Text(if (nudge.kind == "programado") "Registrar o confirmar pago" else "Ir a Cuotas")
            }
        },
        dismissButton = { TextButton(onClick = onLater) { Text("Después") } }
    )
}
