package cl.personal.finanzas

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Preferencia independiente de versionCode/versionName.
 *
 * Cuando una persona ve la guía por primera vez, se marca como mostrada.
 * Las siguientes actualizaciones NO reinician la preferencia. Solo volverá
 * automáticamente si nunca apareció (o si se borraron los datos de la app).
 */
object QuickStartWelcome {
    private const val PREF_NAME = "quick_start_welcome"
    private const val SEEN_KEY = "setup_guide_v1_was_shown"

    fun wasShown(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(SEEN_KEY, false)

    fun markShown(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(SEEN_KEY, true).commit()
}

@Composable
fun QuickStartWelcomeDialog(
    onClose: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onClose,
        shape = RoundedCornerShape(24.dp),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "¡Bienvenido a Mis Finanzas!",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Configúrala en dos pasos",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(15.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "1. Importa tu cartola",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "En Ajustes → Importar documentos, elige tu banco y abre la cartola. " +
                            "Revisa los movimientos y el saldo antes de confirmar.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "2. Conecta un correo (opcional)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "En Ajustes → Correos conectados, vincula Gmail u Outlook " +
                            "para detectar avisos de bancos. Ninguno se registra sin tu revisión. " +
                            "Gmail requiere autorización de Google.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    "Esta guía solo se mostrará una vez. Podrás verla nuevamente " +
                        "desde Ajustes → Guía de inicio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text("Abrir Ajustes") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Entendido") }
        }
    )
}
