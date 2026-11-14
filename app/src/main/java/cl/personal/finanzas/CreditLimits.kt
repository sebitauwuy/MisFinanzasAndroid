package cl.personal.finanzas

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Seguimiento local del cupo de crédito.
 *
 * Al ajustar el disponible desde la cifra del banco se guarda el mayor ID de
 * movimiento existente. Solo los movimientos NUEVOS de esa tarjeta afectan
 * al cupo, sin volver a descontar las compras ya contempladas por el banco.
 * Los pagos se registran como movimientos positivos en la tarjeta.
 * Las cuotas programadas por sí solas NO se suman al cupo utilizado.
 */
data class CreditLimitConfig(
    val limitClp: Long,
    val availableAtReconciliationClp: Long,
    val lastMovementIdAtReconciliation: Long
)

object CreditLimitStore {
    private const val FILE = "credit_limits_v1"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun prefix(account: String) = account + "."

    fun load(context: Context, account: String): CreditLimitConfig? {
        if (AccountCatalog.isCash(account) || account !in AccountCatalog.names) return null
        val p = prefs(context)
        val key = prefix(account)
        if (!p.getBoolean(key + "enabled", false)) return null
        val limit = p.getLong(key + "limit", 0L)
        val available = p.getLong(key + "available", 0L)
        if (limit <= 0L || available !in 0L..limit) return null
        return CreditLimitConfig(limit, available, p.getLong(key + "anchor", 0L))
    }

    fun available(config: CreditLimitConfig, account: String, movements: List<Movement>): Long {
        val today = LocalDate.now()
        val delta = movements.asSequence().filter { it.account == account &&
            it.id > config.lastMovementIdAtReconciliation &&
            runCatching { !LocalDate.parse(it.date).isAfter(today) }.getOrDefault(false)
        }.sumOf { it.amountClp }
        return (config.availableAtReconciliationClp + delta).coerceIn(0L, config.limitClp)
    }

    fun save(context: Context, account: String, total: Long, available: Long,
             movements: List<Movement>): Boolean {
        if (AccountCatalog.isCash(account) || account !in AccountCatalog.names ||
            total <= 0L || total > 1_000_000_000_000L || available !in 0L..total) return false
        val anchor = movements.maxOfOrNull { it.id } ?: 0L
        val key = prefix(account)
        return prefs(context).edit()
            .putBoolean(key + "enabled", true)
            .putLong(key + "limit", total)
            .putLong(key + "available", available)
            .putLong(key + "anchor", anchor)
            .commit()
    }

    fun export(context: Context): JSONObject {
        val root = JSONObject()
        AccountCatalog.names.filterNot(AccountCatalog::isCash).forEach { account ->
            load(context, account)?.let { config ->
                root.put(account, JSONObject()
                    .put("limit", config.limitClp)
                    .put("available", config.availableAtReconciliationClp)
                    .put("anchor", config.lastMovementIdAtReconciliation))
            }
        }
        return root
    }

    fun restore(context: Context, root: JSONObject) {
        val saved = mutableMapOf<String, CreditLimitConfig>()
        AccountCatalog.names.filterNot(AccountCatalog::isCash).forEach { account ->
            val obj = root.optJSONObject(account) ?: return@forEach
            val total = obj.optLong("limit", 0L)
            val available = obj.optLong("available", -1L)
            val anchor = obj.optLong("anchor", -1L)
            require(total in 1L..1_000_000_000_000L && available in 0L..total && anchor >= 0L) {
                "Cupo de crédito inválido en el respaldo"
            }
            saved[account] = CreditLimitConfig(total, available, anchor)
        }
        val editor = prefs(context).edit().clear()
        saved.forEach { (account, config) ->
            val key = prefix(account)
            editor.putBoolean(key + "enabled", true)
                .putLong(key + "limit", config.limitClp)
                .putLong(key + "available", config.availableAtReconciliationClp)
                .putLong(key + "anchor", config.lastMovementIdAtReconciliation)
        }
        require(editor.commit()) { "No se pudo restaurar la configuración de cupos" }
    }
}

@Composable
fun CreditLimitsSection(movements: List<Movement>, visibleAccounts: List<String>) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val cards = visibleAccounts.filter { !AccountCatalog.isCash(it) }
    var editAccount by remember { mutableStateOf<String?>(null) }
    var totalInput by remember { mutableStateOf("") }
    var availableInput by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    val clp = remember { NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL")) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Cupos de crédito", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text("Configura tu cupo y el disponible actual según el banco. Las compras registradas DESPUÉS del ajuste reducen el disponible; los pagos registrados lo aumentan.",
            style = MaterialTheme.typography.bodySmall)
        if (cards.isEmpty()) {
            Text("Activa tus tarjetas de crédito en Ajustes → Mis cuentas para configurar sus cupos.",
                style = MaterialTheme.typography.bodyMedium)
        }
        cards.forEach { account ->
            val config = remember(account, revision) { CreditLimitStore.load(context, account) }
            val available = config?.let { CreditLimitStore.available(it, account, movements) }
            val used = if (config != null && available != null) config.limitClp - available else null
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(AccountCatalog.displayName(account), modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = {
                            editAccount = account
                            totalInput = config?.limitClp?.toString().orEmpty()
                            availableInput = available?.toString().orEmpty()
                        }) { Text(if (config == null) "Configurar" else "Ajustar") }
                    }
                    if (config != null && available != null && used != null) {
                        Text("Disponible: ${clp.format(available)}", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                        Text("Cupo total: ${clp.format(config.limitClp)} · Utilizado: ${clp.format(used)}",
                            style = MaterialTheme.typography.bodySmall)
                        Box(Modifier.fillMaxWidth().height(9.dp).background(
                            MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))) {
                            Box(Modifier.fillMaxWidth((available.toFloat() / config.limitClp).coerceIn(0f, 1f))
                                .fillMaxHeight().background(MaterialTheme.colorScheme.primary,
                                    RoundedCornerShape(10.dp)))
                        }
                    } else {
                        Text("Sin cupo configurado. No se asumirá un límite a partir de tus cuotas.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        Text("Para registrar compras o pagos, usa Movs. y selecciona la tarjeta correspondiente. Las cuotas pendientes no se descuentan de nuevo del cupo.",
            style = MaterialTheme.typography.bodySmall)
    }

    if (editAccount != null) {
        val account = editAccount!!
        val total = totalInput.toLongOrNull()
        val available = availableInput.toLongOrNull()
        val valid = total != null && total in 1L..1_000_000_000_000L &&
            available != null && available in 0L..total
        AlertDialog(
            onDismissRequest = { editAccount = null },
            title = { Text("Cupo · ${AccountCatalog.displayName(account)}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Ingresa lo que muestra el banco ahora. Al guardar, esta cifra será el nuevo punto de partida.")
                    OutlinedTextField(value = totalInput,
                        onValueChange = { totalInput = it.filter(Char::isDigit).take(13) },
                        label = { Text("Cupo total (CLP)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = availableInput,
                        onValueChange = { availableInput = it.filter(Char::isDigit).take(13) },
                        label = { Text("Cupo disponible actual (CLP)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (total != null && available != null && available in 0L..total) {
                        Text("Utilizado actualmente: ${clp.format(total - available)}")
                    }
                    Text("Los movimientos anteriores a este ajuste no volverán a descontarse. Los nuevos gastos bajarán el cupo y los pagos a la tarjeta lo recuperarán.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    val ok = CreditLimitStore.save(context, account, total!!, available!!, movements)
                    message = if (ok) "Cupo actualizado para ${AccountCatalog.displayName(account)}."
                        else "No se pudo guardar el cupo."
                    if (ok) { revision++; editAccount = null }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { editAccount = null }) { Text("Cancelar") } }
        )
    }
}
