package cl.personal.finanzas

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Asistente inicial y editor de cuentas; ocultar no borra saldos ni movimientos. */
@Composable
fun AccountSelectionScreen(
    selected: List<String>,
    initialSetup: Boolean,
    onSave: (List<String>) -> Unit,
    onCancel: () -> Unit
) {
    var checked by remember(selected) { mutableStateOf(selected.toSet()) }
    Column(Modifier.fillMaxSize().background(FinancePalette.Surface).statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (initialSetup) "¡Bienvenido a Mis Finanzas!" else "Administrar mis cuentas",
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = FinancePalette.Navy)
        Text(if (initialSetup)
            "Elige los bancos, tarjetas y billeteras que utilizas. Verás solo esas cuentas y podrás cambiarlo después."
            else "Activa las cuentas que quieres ver. Desactivar una cuenta solo la oculta: nunca elimina sus registros.",
            style = MaterialTheme.typography.bodyMedium)
        AccountCatalog.options.groupBy { it.type }.forEach { (type, options) ->
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(type, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = FinancePalette.Navy)
                    Spacer(Modifier.height(8.dp))
                    options.forEach { option ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(checked = option.name in checked, onCheckedChange = { enabled ->
                                checked = if (enabled) checked + option.name else checked - option.name
                            })
                            Text(option.displayName, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
        Text("La selección afecta solo a lo que se muestra y permite registrar movimientos. Los saldos ocultos siguen guardados.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { onSave(AccountCatalog.names.filter { it in checked }) },
            enabled = checked.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (initialSetup) "Comenzar" else "Guardar mis cuentas")
        }
        if (!initialSetup) OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
    }
}
