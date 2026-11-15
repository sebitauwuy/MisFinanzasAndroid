package cl.personal.finanzas

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Revisión accesible al actualizar Inicio y al recibir nuevos avisos por correo.
 * Usa EXACTAMENTE las acciones de OutlookMailSection: duplicados, traspasos y
 * límites de fecha se comprueban antes de guardar cualquier movimiento.
 */
@Composable
fun PendingMailReviewDialog(db: FinanceDatabase, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp)
                .fillMaxHeight(0.84f),
            shape = RoundedCornerShape(23.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Movimientos por confirmar",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            "Revisa cada aviso antes de afectar tu saldo",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlookMailSection(db, reviewOnly = true)
                }
            }
        }
    }
}
