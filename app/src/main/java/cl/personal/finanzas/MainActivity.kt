package cl.personal.finanzas

import android.app.KeyguardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Entity(tableName = "movements")
data class Movement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val account: String,
    val description: String,
    val amountClp: Long,
    val date: String,
    val category: String = "General"
)

@Entity(tableName = "obligations")
data class Obligation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val account: String,
    val amountClp: Long,
    val dueDate: String,
    val kind: String,
    val installments: Int = 1,
    val paid: Boolean = false
)

@Dao
interface FinanceDao {
    @Query("SELECT * FROM movements ORDER BY date DESC, id DESC")
    fun movements(): Flow<List<Movement>>
    @Insert suspend fun insertMovement(value: Movement)
    @Delete suspend fun deleteMovement(value: Movement)
    @Query("SELECT * FROM movements ORDER BY id") suspend fun allMovements(): List<Movement>
    @Query("DELETE FROM movements") suspend fun clearMovements()
    @Query("SELECT * FROM obligations ORDER BY dueDate ASC, id DESC")
    fun obligations(): Flow<List<Obligation>>
    @Insert suspend fun insertObligation(value: Obligation)
    @Update suspend fun updateObligation(value: Obligation)
    @Delete suspend fun deleteObligation(value: Obligation)
    @Query("SELECT * FROM obligations ORDER BY id") suspend fun allObligations(): List<Obligation>
    @Query("DELETE FROM obligations") suspend fun clearObligations()
}

@Database(entities = [Movement::class, Obligation::class], version = 2, exportSchema = false)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS obligations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, account TEXT NOT NULL, amountClp INTEGER NOT NULL, dueDate TEXT NOT NULL, kind TEXT NOT NULL, installments INTEGER NOT NULL, paid INTEGER NOT NULL)")
    }
}

private val accounts = listOf("Banco de Chile", "Tarjeta Chile", "CMR", "Efectivo")
private val clp = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL"))
private fun money(value: Long) = clp.format(value)
private fun parseDate(value: String): LocalDate? = try { LocalDate.parse(value) } catch (_: Exception) { null }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Room.databaseBuilder(applicationContext, FinanceDatabase::class.java, "finanzas.db")
            .addMigrations(MIGRATION_1_2).build()
        setContent { FinanceApp(db) }
    }
}

@Composable
fun FinanceApp(db: FinanceDatabase) {
    val dao = db.financeDao()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("security", 0) }
    var lockEnabled by remember { mutableStateOf(prefs.getBoolean("lock", false)) }
    var unlocked by remember { mutableStateOf(!lockEnabled) }
    val keyguard = remember { context.getSystemService(KeyguardManager::class.java) }
    val unlockLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        unlocked = it.resultCode == android.app.Activity.RESULT_OK
    }
    val requestUnlock = {
        val intent = keyguard?.createConfirmDeviceCredentialIntent("Mis Finanzas", "Confirma tu identidad para abrir tus finanzas")
        if (intent != null) unlockLauncher.launch(intent) else unlocked = true
    }
    LaunchedEffect(lockEnabled) {
        if (lockEnabled && !unlocked) requestUnlock()
    }
    val movements by dao.movements().collectAsState(initial = emptyList())
    val obligations by dao.obligations().collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var importConfirm by remember { mutableStateOf<android.net.Uri?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try { FinanceBackup.export(context, uri, db); Toast.makeText(context, "Respaldo guardado", Toast.LENGTH_LONG).show() }
            catch (e: Exception) { Toast.makeText(context, "Error al exportar: ${e.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importConfirm = uri
    }
    if (importConfirm != null) {
        AlertDialog(onDismissRequest = { importConfirm = null },
            title = { Text("¿Restaurar respaldo?") },
            text = { Text("Esto reemplazará TODOS los movimientos, cuotas y reembolsos actuales. Exporta primero una copia.") },
            confirmButton = { TextButton(onClick = {
                val uri = importConfirm!!
                importConfirm = null
                scope.launch {
                    try { FinanceBackup.import(context, uri, db); Toast.makeText(context, "Respaldo restaurado", Toast.LENGTH_LONG).show() }
                    catch (e: Exception) { Toast.makeText(context, "No se restauró: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }) { Text("Reemplazar datos") } },
            dismissButton = { TextButton(onClick = { importConfirm = null }) { Text("Cancelar") } })
    }
    MaterialTheme {
        if (!unlocked) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Mis Finanzas", style = MaterialTheme.typography.headlineLarge)
                Text("Tus datos están protegidos por el bloqueo del dispositivo.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = requestUnlock) { Text("Desbloquear") }
            }
        } else {
            Scaffold(topBar = {
                Surface(tonalElevation = 2.dp) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Mis Finanzas · CLP", style = MaterialTheme.typography.titleLarge)
                        Text("Datos locales · Sin conexión bancaria", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }, bottomBar = {
                NavigationBar {
                    listOf("Inicio", "Movimientos", "Cuotas", "Reembolsos", "Seguridad").forEachIndexed { i, label ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(listOf("⌂", "$", "#", "↗", "●")[i]) }, label = { Text(label) })
                    }
                }
            }) { pad ->
                Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)) {
                    when (tab) {
                        0 -> Dashboard(movements, obligations)
                        1 -> MovementScreen(movements, dao, scope)
                        2 -> ObligationScreen(obligations.filter { it.kind == "Pago" }, "Pago", dao, scope)
                        3 -> ObligationScreen(obligations.filter { it.kind == "Reembolso" }, "Reembolso", dao, scope)
                        else -> {
                            Spacer(Modifier.height(16.dp))
                            Text("Seguridad", style = MaterialTheme.typography.headlineSmall)
                            Text("El bloqueo usa la autenticación configurada en tu Samsung (PIN, patrón, contraseña o biometría cuando esté disponible).")
                            Spacer(Modifier.height(16.dp))
                            Row {
                                Text("Pedir desbloqueo al abrir", modifier = Modifier.weight(1f))
                                Switch(checked = lockEnabled, onCheckedChange = {
                                    if (!it || keyguard?.isDeviceSecure == true) {
                                        lockEnabled = it
                                        prefs.edit().putBoolean("lock", it).apply()
                                    }
                                })
                            }
                            Text("Si activas el bloqueo, se solicitará al iniciar de nuevo la app. La base de datos local todavía no tiene cifrado de archivo.", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { unlocked = false; requestUnlock() }, enabled = lockEnabled) { Text("Bloquear ahora") }
                            Spacer(Modifier.height(20.dp))
                            Text("Copias de seguridad", style = MaterialTheme.typography.titleMedium)
                            Text("El archivo JSON no está cifrado: guárdalo en un lugar privado.", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { exportLauncher.launch("mis-finanzas-respaldo.json") }) { Text("Exportar respaldo") }
                            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Restaurar respaldo") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Dashboard(movements: List<Movement>, obligations: List<Obligation>) {
    val today = LocalDate.now()
    val month = YearMonth.from(today)
    val current = movements.filter { parseDate(it.date)?.let { d -> YearMonth.from(d) == month } == true }
    val income = current.filter { it.amountClp > 0 }.sumOf { it.amountClp }
    val expenses = -current.filter { it.amountClp < 0 }.sumOf { it.amountClp }
    val pending = obligations.filter { it.kind == "Pago" && !it.paid }.sumOf { it.amountClp * it.installments }
    val receivable = obligations.filter { it.kind == "Reembolso" && !it.paid }.sumOf { it.amountClp }
    val balance = movements.sumOf { it.amountClp }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item {
            Text("Resumen financiero", style = MaterialTheme.typography.headlineSmall)
            SummaryCard("Balance registrado (no bancario)", money(balance))
            SummaryCard("Ingresos de este mes", money(income))
            SummaryCard("Gastos de este mes", money(expenses))
            SummaryCard("Cuotas y pagos pendientes", money(pending))
            SummaryCard("Reembolsos por cobrar", money(receivable))
            SummaryCard("Proyección simple (sin futuros ingresos)", money(balance - pending + receivable))
            Text("La proyección es orientativa: no incluye saldos reales ni pagos ya reflejados en movimientos. Evita registrar dos veces el mismo pago.", style = MaterialTheme.typography.bodySmall)
        }
        item { Text("Movimientos por cuenta", style = MaterialTheme.typography.titleMedium) }
        items(accounts) { name ->
            val sum = movements.filter { it.account == name }.sumOf { it.amountClp }
            SummaryCard(name, money(sum))
        }
    }
}

@Composable
fun SummaryCard(label: String, value: String) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
fun AccountPicker(value: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Cuenta: $value") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { a -> DropdownMenuItem(text = { Text(a) }, onClick = { onChange(a); expanded = false }) }
        }
    }
}

@Composable
fun MovementScreen(movements: List<Movement>, dao: FinanceDao, scope: kotlinx.coroutines.CoroutineScope) {
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(accounts.first()) }
    var category by remember { mutableStateOf("General") }
    var expense by remember { mutableStateOf(true) }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Text("Registrar movimiento", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(description, { description = it }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Monto CLP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            AccountPicker(account) { account = it }
            OutlinedTextField(category, { category = it }, label = { Text("Categoría") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(date, { date = it }, label = { Text("Fecha AAAA-MM-DD") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(expense, { expense = true }, label = { Text("Gasto") })
                FilterChip(!expense, { expense = false }, label = { Text("Ingreso") })
            }
            val valid = description.isNotBlank() && (amount.toLongOrNull() ?: 0L) > 0 && parseDate(date) != null
            Button(enabled = valid, onClick = {
                val value = amount.toLongOrNull() ?: return@Button
                scope.launch { dao.insertMovement(Movement(account = account, description = description.trim(), amountClp = if (expense) -value else value, date = date, category = category.trim())) }
                description = ""; amount = ""
            }, modifier = Modifier.fillMaxWidth()) { Text("Guardar movimiento") }
            HorizontalDivider()
            Text("Historial", style = MaterialTheme.typography.titleLarge)
        }
        items(movements, key = { it.id }) { m ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(m.description.ifBlank { "(Sin descripción)" }, style = MaterialTheme.typography.titleMedium)
                        Text("${m.account} · ${m.category} · ${m.date}", style = MaterialTheme.typography.bodySmall)
                        Text(money(m.amountClp))
                    }
                    TextButton(onClick = { scope.launch { dao.deleteMovement(m) } }) { Text("Eliminar") }
                }
            }
        }
    }
}

@Composable
fun ObligationScreen(items: List<Obligation>, kind: String, dao: FinanceDao, scope: kotlinx.coroutines.CoroutineScope) {
    var title by remember(kind) { mutableStateOf("") }
    var amount by remember(kind) { mutableStateOf("") }
    var account by remember(kind) { mutableStateOf(if (kind == "Pago") "CMR" else "Banco de Chile") }
    var date by remember(kind) { mutableStateOf(LocalDate.now().toString()) }
    var installments by remember(kind) { mutableStateOf("1") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Text(if (kind == "Pago") "Cuotas y pagos pendientes" else "Reembolsos por cobrar", style = MaterialTheme.typography.titleLarge)
            Text(if (kind == "Pago") "Ingresa el valor de CADA cuota y la fecha del primer vencimiento. Las siguientes vencen mensualmente." else "Registra dinero que otra persona debe devolverte.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(title, { title = it }, label = { Text(if (kind == "Pago") "Compra o cuenta" else "Persona y motivo") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text(if (kind == "Pago") "Valor de cada cuota CLP" else "Monto a cobrar CLP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            AccountPicker(account) { account = it }
            OutlinedTextField(date, { date = it }, label = { Text("Primer vencimiento AAAA-MM-DD") }, modifier = Modifier.fillMaxWidth())
            if (kind == "Pago") OutlinedTextField(installments, { installments = it.filter(Char::isDigit) }, label = { Text("Cuotas pendientes") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            val count = if (kind == "Pago") installments.toIntOrNull() ?: 0 else 1
            val valid = title.isNotBlank() && (amount.toLongOrNull() ?: 0L) > 0 && parseDate(date) != null && count in 1..120
            Button(enabled = valid, onClick = {
                val value = amount.toLongOrNull() ?: return@Button
                scope.launch { dao.insertObligation(Obligation(title = title.trim(), account = account, amountClp = value, dueDate = date, kind = kind, installments = count)) }
                title = ""; amount = ""; installments = "1"
            }, modifier = Modifier.fillMaxWidth()) { Text("Agregar") }
            HorizontalDivider()
        }
        items(items, key = { it.id }) { o ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(o.title, style = MaterialTheme.typography.titleMedium)
                    Text("${o.account} · ${o.dueDate}")
                    Text(if (o.kind == "Pago") "${o.installments} cuota(s) de ${money(o.amountClp)}" else money(o.amountClp))
                    Text(if (o.paid) "Finalizado" else "Pendiente")
                    Row {
                        TextButton(onClick = { scope.launch { dao.updateObligation(o.copy(paid = !o.paid)) } }) {
                            Text(if (o.paid) "Marcar pendiente" else if (o.kind == "Pago") "Marcar todo pagado" else "Marcar cobrado")
                        }
                        TextButton(onClick = { scope.launch { dao.deleteObligation(o) } }) { Text("Eliminar") }
                    }
                }
            }
        }
    }
}
