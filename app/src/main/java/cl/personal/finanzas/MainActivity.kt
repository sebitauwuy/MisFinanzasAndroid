package cl.personal.finanzas

import android.app.KeyguardManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
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
    val paid: Boolean = false,
    val paidCount: Int = 0
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

@Database(entities = [Movement::class, Obligation::class], version = 3, exportSchema = false)
abstract class FinanceDatabase : RoomDatabase() {
    abstract fun financeDao(): FinanceDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS obligations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, account TEXT NOT NULL, amountClp INTEGER NOT NULL, dueDate TEXT NOT NULL, kind TEXT NOT NULL, installments INTEGER NOT NULL, paid INTEGER NOT NULL)")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE obligations ADD COLUMN paidCount INTEGER NOT NULL DEFAULT 0")
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
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
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
    val balancePrefs = remember { context.getSharedPreferences("opening_balances", 0) }
    var openingBalances by remember { mutableStateOf(accounts.associateWith { balancePrefs.getLong(it, 0L) }) }
    val scope = rememberCoroutineScope()
    val billingPrefs = remember { context.getSharedPreferences("billing_cycles", 0) }
    var chileClose by remember { mutableStateOf(billingPrefs.getInt("chileClose", 28).toString()) }
    var chileDue by remember { mutableStateOf(billingPrefs.getInt("chileDue", 5).toString()) }
    var cmrClose by remember { mutableStateOf(billingPrefs.getInt("cmrClose", 12).toString()) }
    var cmrDue by remember { mutableStateOf(billingPrefs.getInt("cmrDue", 25).toString()) }
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
                    try { FinanceBackup.import(context, uri, db)
                        openingBalances = accounts.associateWith { balancePrefs.getLong(it, 0L) }
                        chileClose = billingPrefs.getInt("chileClose", 28).toString()
                        chileDue = billingPrefs.getInt("chileDue", 5).toString()
                        cmrClose = billingPrefs.getInt("cmrClose", 12).toString()
                        cmrDue = billingPrefs.getInt("cmrDue", 25).toString()
                        Toast.makeText(context, "Respaldo restaurado", Toast.LENGTH_LONG).show() }
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
                        Text("v0.6 · Datos locales · Sin conexión bancaria", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }, bottomBar = {
                NavigationBar {
                    listOf("Inicio", "Movimientos", "Cuotas", "Cobros", "Ajustes").forEachIndexed { i, label ->
                        NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(listOf("⌂", "+", "◷", "↗", "⚙")[i]) }, label = { Text(label) })
                    }
                }
            }) { pad ->
                Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).then(if (tab == 4) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                    when (tab) {
                        0 -> Dashboard(movements, obligations, openingBalances, onNavigate = { tab = it }) { account, amount ->
                            balancePrefs.edit().putLong(account, amount).apply()
                            openingBalances = openingBalances + (account to amount)
                        }
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
                            Text("Ciclos de tarjetas (días estimados)", style = MaterialTheme.typography.titleMedium)
                            Text("Configura los días de corte y vencimiento según tus estados de cuenta. No se calculan automáticamente.", style = MaterialTheme.typography.bodySmall)
                            OutlinedTextField(chileClose, { chileClose = it.filter(Char::isDigit) }, label = { Text("Banco de Chile: día de facturación") })
                            OutlinedTextField(chileDue, { chileDue = it.filter(Char::isDigit) }, label = { Text("Banco de Chile: día de pago") })
                            OutlinedTextField(cmrClose, { cmrClose = it.filter(Char::isDigit) }, label = { Text("CMR: día de facturación") })
                            OutlinedTextField(cmrDue, { cmrDue = it.filter(Char::isDigit) }, label = { Text("CMR: día de pago") })
                            val days = listOf(chileClose, chileDue, cmrClose, cmrDue).map { it.toIntOrNull() }
                            Button(enabled = days.all { it != null && it in 1..28 }, onClick = {
                                billingPrefs.edit().putInt("chileClose", days[0]!!).putInt("chileDue", days[1]!!)
                                    .putInt("cmrClose", days[2]!!).putInt("cmrDue", days[3]!!).apply()
                                Toast.makeText(context, "Ciclos guardados", Toast.LENGTH_SHORT).show()
                            }) { Text("Guardar ciclos") }
                            Spacer(Modifier.height(16.dp))
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
fun Dashboard(
    movements: List<Movement>,
    obligations: List<Obligation>,
    openingBalances: Map<String, Long>,
    onNavigate: (Int) -> Unit,
    onBalanceChange: (String, Long) -> Unit
) {
    val today = LocalDate.now()
    val month = YearMonth.from(today)
    val cashAccounts = listOf("Banco de Chile", "Efectivo")
    val balance = cashAccounts.sumOf { account ->
        (openingBalances[account] ?: 0L) + movements.filter { it.account == account }.sumOf { it.amountClp }
    }
    val monthMovements = movements.filter { parseDate(it.date)?.let { d -> YearMonth.from(d) == month } == true }
    val income = monthMovements.filter { it.amountClp > 0 && it.account in cashAccounts }.sumOf { it.amountClp }
    val expenses = -monthMovements.filter { it.amountClp < 0 && it.account in cashAccounts }.sumOf { it.amountClp }
    val pending = obligations.filter { it.kind == "Pago" && !it.paid }.sumOf { it.amountClp * (it.installments - it.paidCount).coerceAtLeast(0) }
    val receivable = obligations.filter { it.kind == "Reembolso" && !it.paid }.sumOf { it.amountClp }
    fun dueItems(target: YearMonth): List<Pair<Obligation, LocalDate>> =
        obligations.filter { it.kind == "Pago" && !it.paid }.flatMap { o ->
            val first = parseDate(o.dueDate)
            if (first == null) emptyList() else (o.paidCount until o.installments).mapNotNull { i ->
                val due = first.plusMonths(i.toLong())
                if (YearMonth.from(due) == target) o to due else null
            }
        }.sortedBy { it.second }
    val thisMonth = dueItems(month)
    val dueThisMonth = thisMonth.sumOf { it.first.amountClp }
    val future = (0..2).map { offset ->
        val ym = month.plusMonths(offset.toLong())
        ym to dueItems(ym).sumOf { it.first.amountClp }
    }
    var editingAccount by remember { mutableStateOf<String?>(null) }
    var openingInput by remember { mutableStateOf("") }
    if (editingAccount != null) {
        val selected = editingAccount!!
        AlertDialog(
            onDismissRequest = { editingAccount = null },
            title = { Text("Saldo inicial: $selected") },
            text = {
                Column {
                    Text("Saldo de partida, sin volver a registrar movimientos anteriores.")
                    OutlinedTextField(openingInput, { openingInput = it.filter { c -> c.isDigit() || c == '-' } },
                        label = { Text("Monto CLP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = { TextButton(enabled = openingInput.toLongOrNull() != null, onClick = {
                onBalanceChange(selected, openingInput.toLong()); editingAccount = null
            }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { editingAccount = null }) { Text("Cancelar") } }
        )
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 18.dp)) {
        item {
            Text("Tu panorama financiero", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Actualizado con tus registros · ${today.dayOfMonth}/${today.monthValue}/${today.year}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF34245C)), shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Text("DINERO DISPONIBLE REGISTRADO", color = Color(0xFFD8CBF5), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(money(balance), color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("Banco de Chile + efectivo · No es saldo bancario en tiempo real", color = Color(0xFFE4DDF1), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onNavigate(1) }, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF34245C))) { Text("+ Movimiento") }
                        OutlinedButton(onClick = { onNavigate(2) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("Ver pagos") }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { FinanceMetric("Ingresos del mes", money(income), Color(0xFF176D47)) }
                Box(Modifier.weight(1f)) { FinanceMetric("Salidas del mes", money(expenses), Color(0xFFAD4141)) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { FinanceMetric("Deuda pendiente", money(pending), Color(0xFF7853A6)) }
                Box(Modifier.weight(1f)) { FinanceMetric("Por cobrar", money(receivable), Color(0xFF236A86)) }
            }
        }
        item {
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("Proyección de fin de mes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(money(balance - dueThisMonth), style = MaterialTheme.typography.headlineMedium)
                    Text("Disponible registrado menos todas las cuotas pendientes del mes (${money(dueThisMonth)}).", style = MaterialTheme.typography.bodySmall)
                    Text("Estimación: no incluye futuros ingresos, gastos variables ni pagos ya reflejados en movimientos.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Text("Próximos vencimientos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (thisMonth.isEmpty()) {
                Text("No hay cuotas pendientes registradas para este mes.", style = MaterialTheme.typography.bodyMedium)
            } else {
                thisMonth.take(5).forEach { (o, date) ->
                    ListItem(
                        headlineContent = { Text(o.title, maxLines = 1) },
                        supportingContent = { Text("${o.account} · ${date.dayOfMonth}/${date.monthValue}/${date.year}") },
                        trailingContent = { Text(money(o.amountClp), fontWeight = FontWeight.Bold) }
                    )
                    HorizontalDivider()
                }
            }
            TextButton(onClick = { onNavigate(2) }) { Text("Gestionar todas las cuotas") }
        }
        item {
            Text("Compromisos de los próximos 3 meses", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            future.forEach { (ym, amount) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${ym.monthValue}/${ym.year}")
                    Text(money(amount), fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider()
            }
        }
        item {
            Text("Mis cuentas", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Edita el saldo inicial sin alterar tus movimientos.", style = MaterialTheme.typography.bodySmall)
        }
        items(accounts) { account ->
            val amount = (openingBalances[account] ?: 0L) + movements.filter { it.account == account }.sumOf { it.amountClp }
            Card(shape = RoundedCornerShape(14.dp)) {
                Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(account, fontWeight = FontWeight.SemiBold)
                        Text(money(amount), style = MaterialTheme.typography.titleMedium)
                        if (account == "CMR" || account == "Tarjeta Chile")
                            Text("Registro de tarjeta, no dinero disponible", style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = {
                        editingAccount = account
                        openingInput = (openingBalances[account] ?: 0L).toString()
                    }) { Text("Editar") }
                }
            }
        }
        item {
            Text("Ingresos y gastos · últimos 6 meses", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            (5 downTo 0).forEach { offset ->
                val ym = month.minusMonths(offset.toLong())
                val data = movements.filter { parseDate(it.date)?.let { d -> YearMonth.from(d) == ym } == true && it.account in cashAccounts }
                val earned = data.filter { it.amountClp > 0 }.sumOf { it.amountClp }
                val spent = -data.filter { it.amountClp < 0 }.sumOf { it.amountClp }
                Text("${ym.monthValue}/${ym.year} · +${money(earned)} / -${money(spent)}", style = MaterialTheme.typography.bodySmall)
                val maximum = maxOf(earned, spent, 1L).toFloat()
                Box(Modifier.fillMaxWidth().height(7.dp).background(Color(0xFFE4E1E9))) {
                    Box(Modifier.fillMaxWidth((earned.toFloat() / maximum).coerceIn(0f, 1f)).fillMaxHeight().background(Color(0xFF388E3C)))
                }
                Spacer(Modifier.height(3.dp))
                Box(Modifier.fillMaxWidth().height(7.dp).background(Color(0xFFE4E1E9))) {
                    Box(Modifier.fillMaxWidth((spent.toFloat() / maximum).coerceIn(0f, 1f)).fillMaxHeight().background(Color(0xFFB54747)))
                }
                Spacer(Modifier.height(9.dp))
            }
        }
    }
}

@Composable
fun FinanceMetric(label: String, value: String, accent: Color) {
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.10f))) {
        Column(Modifier.fillMaxWidth().padding(13.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = accent)
            Spacer(Modifier.height(5.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
    var search by remember { mutableStateOf("") }
    var selectedAccount by remember { mutableStateOf("Todas") }
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
            OutlinedTextField(search, { search = it }, label = { Text("Buscar movimientos") }, modifier = Modifier.fillMaxWidth())
            var expandedFilter by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(onClick = { expandedFilter = true }) { Text("Cuenta: $selectedAccount") }
                DropdownMenu(expanded = expandedFilter, onDismissRequest = { expandedFilter = false }) {
                    (listOf("Todas") + accounts).forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { selectedAccount = name; expandedFilter = false })
                    }
                }
            }
        }
        items(movements.filter {
            (selectedAccount == "Todas" || it.account == selectedAccount) &&
                (search.isBlank() || it.description.contains(search, ignoreCase = true) || it.category.contains(search, ignoreCase = true))
        }, key = { it.id }) { m ->
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
            Text(if (kind == "Pago") "Ingresa el valor de CADA cuota y la fecha del primer vencimiento. Para gastos recurrentes, registra cada mes como un pago separado." else "Registra dinero que otra persona debe devolverte.", style = MaterialTheme.typography.bodySmall)
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
                    Text(if (o.kind == "Pago") "${(o.installments - o.paidCount).coerceAtLeast(0)} de ${o.installments} cuota(s) pendientes de ${money(o.amountClp)}" else money(o.amountClp))
                    Text(if (o.paid) "Finalizado" else "Pendiente")
                    if (o.kind == "Pago" && !o.paid) {
                        parseDate(o.dueDate)?.let { first ->
                            Text("Próximos vencimientos:", style = MaterialTheme.typography.labelMedium)
                            repeat(minOf((o.installments - o.paidCount).coerceAtLeast(0), 6)) { offset ->
                                Text("${first.plusMonths((o.paidCount + offset).toLong())}: ${money(o.amountClp)}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Row {
                        TextButton(onClick = { scope.launch { dao.updateObligation(if (o.kind == "Pago") {
                            if (o.paid) o.copy(paid = false, paidCount = (o.installments - 1).coerceAtLeast(0))
                            else o.copy(paidCount = (o.paidCount + 1).coerceAtMost(o.installments), paid = o.paidCount + 1 >= o.installments)
                        } else o.copy(paid = !o.paid)) } }) {
                            Text(if (o.paid) "Reabrir" else if (o.kind == "Pago") "Pagar una cuota" else "Marcar cobrado")
                        }
                        TextButton(onClick = { scope.launch { dao.deleteObligation(o) } }) { Text("Eliminar") }
                    }
                }
            }
        }
    }
}
