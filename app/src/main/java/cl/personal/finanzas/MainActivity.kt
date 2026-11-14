package cl.personal.finanzas

import android.app.KeyguardManager
import android.os.Bundle
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Alignment
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
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    @Query("DELETE FROM movements WHERE account = :account") suspend fun clearAccountMovements(account: String)
    @Query("SELECT * FROM obligations ORDER BY dueDate ASC, id DESC")
    fun obligations(): Flow<List<Obligation>>
    @Insert suspend fun insertObligation(value: Obligation)
    @Update suspend fun updateObligation(value: Obligation)
    @Delete suspend fun deleteObligation(value: Obligation)
    @Query("SELECT * FROM obligations ORDER BY id") suspend fun allObligations(): List<Obligation>
    @Query("DELETE FROM obligations") suspend fun clearObligations()
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMailCandidate(candidate: MailCandidate): Long
    @Query("SELECT * FROM mail_candidates ORDER BY operationDate DESC, sourceId DESC")
    fun mailCandidates(): Flow<List<MailCandidate>>
    @Query("SELECT * FROM mail_candidates WHERE sourceId = :id LIMIT 1")
    suspend fun getMailCandidate(id: String): MailCandidate?
    @Query("UPDATE mail_candidates SET status = :status WHERE sourceId = :id")
    suspend fun markMailCandidate(id: String, status: String)
}

@Database(entities = [Movement::class, Obligation::class, MailCandidate::class], version = 4, exportSchema = false)
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

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS mail_candidates (sourceId TEXT NOT NULL PRIMARY KEY, operationDate TEXT NOT NULL, description TEXT NOT NULL, amountClp INTEGER NOT NULL, account TEXT NOT NULL, category TEXT NOT NULL, status TEXT NOT NULL, note TEXT NOT NULL)")
    }
}

private val accounts = AccountCatalog.names
private val clp = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL"))
private fun money(value: Long) = clp.format(value)
private fun parseDate(value: String): LocalDate? = try { LocalDate.parse(value) } catch (_: Exception) { null }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        val db = Room.databaseBuilder(applicationContext, FinanceDatabase::class.java, "finanzas.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build()
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
    val loadedMovements by remember(dao) {
        dao.movements().map<List<Movement>, List<Movement>?> { it }
    }.collectAsState(initial = null)
    val loadedObligations by remember(dao) {
        dao.obligations().map<List<Obligation>, List<Obligation>?> { it }
    }.collectAsState(initial = null)
    val movements = loadedMovements.orEmpty()
    val obligations = loadedObligations.orEmpty()
    var tab by remember { mutableStateOf(0) }
    val balancePrefs = remember { context.getSharedPreferences("opening_balances", 0) }
    var openingBalances by remember { mutableStateOf(accounts.associateWith { balancePrefs.getLong(it, 0L) }) }
    var accountConfigured by remember { mutableStateOf(AccountCatalog.isConfigured(context)) }
    var visibleAccounts by remember { mutableStateOf(AccountCatalog.selected(context)) }
    var managingAccounts by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // Sincronización bajo demanda y al abrir la aplicación, nunca registra cargos sin revisión.
    LaunchedEffect(Unit) {
        if (OutlookGraph.enabled(context) && OutlookGraph.isConnected(context)) {
            try { OutlookGraph.sync(context, dao) } catch (_: Exception) {
                // El usuario puede ver el error usando "Buscar avisos ahora" en Ajustes.
            }
        }
    }
    val billingPrefs = remember { context.getSharedPreferences("billing_cycles", 0) }
    var chileClose by remember { mutableStateOf(billingPrefs.getInt("chileClose", 28).toString()) }
    var chileDue by remember { mutableStateOf(billingPrefs.getInt("chileDue", 5).toString()) }
    var cmrClose by remember { mutableStateOf(billingPrefs.getInt("cmrClose", 12).toString()) }
    var cmrDue by remember { mutableStateOf(billingPrefs.getInt("cmrDue", 25).toString()) }
    // Migración no destructiva de instalaciones anteriores a la selección de cuentas.
    LaunchedEffect(loadedMovements, loadedObligations) {
        if (!accountConfigured && loadedMovements != null && loadedObligations != null) {
            val previousData = movements.isNotEmpty() || obligations.isNotEmpty() ||
                balancePrefs.all.isNotEmpty() || billingPrefs.all.isNotEmpty()
            if (previousData) {
                val fromHistory = (movements.map { it.account } + obligations.map { it.account }).toSet()
                val fromBalances = accounts.filter { balancePrefs.getLong(it, 0L) != 0L }.toSet()
                val recovered = accounts.filter { it in fromHistory || it in fromBalances }
                    .ifEmpty { listOf("Banco de Chile") }
                if (AccountCatalog.save(context, recovered)) {
                    visibleAccounts = recovered
                    accountConfigured = true
                }
            }
        }
    }
    var importConfirm by remember { mutableStateOf<android.net.Uri?>(null) }
    var resetAccount by remember { mutableStateOf<String?>(null) }
    var resetAllConfirm by remember { mutableStateOf(false) }
    var maintenanceOpen by remember { mutableStateOf(false) }
    var maintenanceExpanded by remember { mutableStateOf(false) }
    var maintenanceAccount by remember { mutableStateOf("Banco de Chile") }
    var reconcileAccount by remember { mutableStateOf<String?>(null) }
    var reconcileAmount by remember { mutableStateOf("") }
    var reconcileDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var cardPdfReport by remember { mutableStateOf<CardPdfReport?>(null) }
    var cardPdfError by remember { mutableStateOf<String?>(null) }
    val cardPdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            cardPdfReport = null
            cardPdfError = null
            try {
                cardPdfReport = CardPdfReader.read(context, uri)
            } catch (e: Exception) {
                cardPdfError = e.message ?: "No se pudo leer el PDF"
            }
        }
    }
    if (cardPdfReport != null || cardPdfError != null) {
        val report = cardPdfReport
        AlertDialog(
            onDismissRequest = { cardPdfReport = null; cardPdfError = null },
            title = { Text("Vista previa del PDF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cardPdfError != null) {
                        Text(cardPdfError!!, color = MaterialTheme.colorScheme.error)
                    } else if (report != null) {
                        Text("Banco de Chile · No facturado al ${report.reportDate}")
                        Text("Cupo utilizado: ${money(report.usedLimit)}", style = MaterialTheme.typography.titleMedium)
                        Text("Cupo disponible: ${money(report.availableLimit)}")
                        Text("Cupo total: ${money(report.totalLimit)}")
                        if (!report.amountsConsistent)
                            Text("Atención: los cupos no suman el cupo total. Verifica el documento.", color = MaterialTheme.colorScheme.error)
                        if (report.paymentMentioned)
                            Text("El archivo incluye al menos un pago; no se interpreta automáticamente como deuda facturada.")
                        Text("Este PDF no indica por sí solo cuánto debes pagar en el próximo vencimiento ni su fecha exacta.")
                        Text("Vista previa solamente: no se modificó ningún saldo ni se agregaron movimientos.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { cardPdfReport = null; cardPdfError = null }) { Text("Cerrar") } }
        )
    }
    var csvPreview by remember { mutableStateOf<List<Movement>>(emptyList()) }
    var csvAccount by remember { mutableStateOf("Banco de Chile") }
    LaunchedEffect(visibleAccounts) {
        if (visibleAccounts.isNotEmpty() && csvAccount !in visibleAccounts) {
            csvAccount = visibleAccounts.first()
        }
    }

    var csvCutoff by remember { mutableStateOf(LocalDate.now().toString()) }
    var csvRealBalance by remember { mutableStateOf("") }
    var csvMessage by remember { mutableStateOf("") }
    var csvExisting by remember { mutableStateOf<List<Movement>>(emptyList()) }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                csvPreview = StatementImport.read(context, uri, csvAccount)
                csvCutoff = LocalDate.now().toString()
                csvRealBalance = ""
                csvExisting = dao.allMovements()
                csvMessage = "Cartola leída: ${csvPreview.size} movimientos. Confirma el saldo real y su fecha antes de importar."
            } catch (e: Exception) {
                csvPreview = emptyList()
                csvMessage = "No se pudo leer: ${e.message}"
            }
        }
    }
    if (csvMessage.isNotBlank()) {
        val asOf = parseDate(csvCutoff)
        val realBalance = csvRealBalance.trim().toLongOrNull()
        val statementDates = csvPreview.mapNotNull { parseDate(it.date) }
        val firstStatementDate = statementDates.minOrNull()
        val lastStatementDate = statementDates.maxOrNull()
        val dateWithinStatement = asOf != null && !asOf.isAfter(LocalDate.now()) &&
            (firstStatementDate == null || !asOf.isBefore(firstStatementDate))
        val accountHistory = csvExisting.filter { it.account == csvAccount }
        // Respetar transacciones legítimas idénticas; comparar cantidad de ocurrencias.
        fun transactionKey(row: Movement) = listOf(row.date, row.description, row.amountClp.toString())
        val existingCounts = accountHistory.groupingBy { transactionKey(it) }.eachCount()
        val seenCounts = mutableMapOf<List<String>, Int>()
        val newRows = csvPreview.filter { row ->
            val date = parseDate(row.date)
            dateWithinStatement && date != null && !date.isAfter(asOf!!) &&
                run {
                    val key = transactionKey(row)
                    val occurrence = (seenCounts[key] ?: 0) + 1
                    seenCounts[key] = occurrence
                    occurrence > (existingCounts[key] ?: 0)
                }
        }
        val recordedAtDate = accountHistory.filter { row ->
            asOf != null && parseDate(row.date)?.isAfter(asOf) == false
        }.sumOf { it.amountClp }
        val newAtDate = newRows.sumOf { it.amountClp }
        val calculatedOpening = if (realBalance != null) {
            try { Math.subtractExact(realBalance, Math.addExact(recordedAtDate, newAtDate)) }
            catch (_: ArithmeticException) { null }
        } else null
        val laterCount = accountHistory.count { row ->
            asOf != null && parseDate(row.date)?.isAfter(asOf) == true
        }
        AlertDialog(
            onDismissRequest = { csvMessage = ""; csvPreview = emptyList() },
            title = { Text("Conciliar cartola con el banco") },
            text = {
                Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState())) {
                    Text(csvMessage)
                    if (firstStatementDate != null && lastStatementDate != null)
                        Text("Fechas de la cartola: $firstStatementDate a $lastStatementDate", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(csvCutoff, { csvCutoff = it },
                        label = { Text("Fecha del saldo bancario (AAAA-MM-DD)") },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(csvRealBalance, { csvRealBalance = it.filter { c -> c.isDigit() || c == '-' } },
                        label = { Text("Saldo real del banco (CLP)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth())
                    if (asOf == null) Text("Fecha inválida", color = MaterialTheme.colorScheme.error)
                    else if (asOf.isAfter(LocalDate.now())) Text("La fecha del saldo no puede ser futura.", color = MaterialTheme.colorScheme.error)
                    else if (firstStatementDate != null && asOf.isBefore(firstStatementDate))
                        Text("La fecha es anterior a todos los movimientos de la cartola. Elige una fecha igual o posterior a $firstStatementDate.", color = MaterialTheme.colorScheme.error)
                    else if (realBalance == null) Text("Ingresa el saldo real, sin puntos ni símbolo de pesos.")
                    else if (calculatedOpening == null) Text("El monto excede el rango admitido.")
                    else {
                        Text("Movimientos nuevos hasta la fecha: ${newRows.size}")
                        Text("Movimientos del archivo posteriores a la fecha: ${csvPreview.count { parseDate(it.date)?.isAfter(asOf) == true }}")
                        if (newRows.isEmpty() && accountHistory.none { parseDate(it.date)?.isAfter(asOf) == false })
                            Text("No hay movimientos para conciliar en esta fecha. Cambia la fecha antes de guardar.", color = MaterialTheme.colorScheme.error)
                        Text("Movimientos ya registrados hasta la fecha: ${accountHistory.count { parseDate(it.date)?.isAfter(asOf) == false }}")
                        Text("Variación total de movimientos: ${money(recordedAtDate + newAtDate)}")
                        Text("Saldo inicial calculado: ${money(calculatedOpening)}", style = MaterialTheme.typography.titleMedium)
                        Text("Saldo conciliado a ${asOf}: ${money(realBalance)}", style = MaterialTheme.typography.titleMedium)
                        if (laterCount > 0) Text("Atención: ya tienes ${laterCount} movimientos posteriores a esa fecha; cambiarán el saldo mostrado hoy.", color = MaterialTheme.colorScheme.error)
                        Text("Se reemplazará el saldo inicial de ${csvAccount}; no se creará un ingreso ficticio. Solo se agregarán movimientos nuevos anteriores o iguales a la fecha indicada.", style = MaterialTheme.typography.bodySmall)
                        newRows.take(5).forEach { Text("${it.date} · ${it.description} · ${money(it.amountClp)}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = csvPreview.isNotEmpty() && dateWithinStatement &&
                    calculatedOpening != null && (newRows.isNotEmpty() || (asOf != null && accountHistory.any { parseDate(it.date)?.isAfter(asOf) == false })), onClick = {
                    val account = csvAccount
                    val target = calculatedOpening!!
                    val batch = newRows.toList()
                    csvMessage = ""; csvPreview = emptyList()
                    scope.launch {
                        try {
                            db.withTransaction {
                                // La lista batch ya excluye los registros existentes con conteo de ocurrencias.
                                batch.forEach { row -> dao.insertMovement(row) }
                            }
                            if (!balancePrefs.edit().putLong(account, target).commit())
                                error("No se pudo guardar el saldo inicial")
                            openingBalances = openingBalances + (account to target)
                            val persisted = dao.allMovements().filter { it.account == account &&
                                parseDate(it.date)?.isAfter(asOf!!) == false }.sumOf { it.amountClp }
                            val verified = target + persisted
                            Toast.makeText(context,
                                if (verified == realBalance) "Conciliado a la fecha: ${money(verified)}"
                                else "Revisa el saldo: diferencia ${money(realBalance!! - verified)}",
                                Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Conciliar y guardar") }
            },
            dismissButton = { TextButton(onClick = { csvMessage = ""; csvPreview = emptyList() }) { Text("Cancelar") } }
        )
    }
    if (resetAccount != null || resetAllConfirm) {
        val account = resetAccount
        AlertDialog(onDismissRequest = { resetAccount = null; resetAllConfirm = false },
            title = { Text(if (account != null) "Borrar registros de $account" else "Borrar todos los saldos y movimientos") },
            text = { Text(if (account != null)
                "Se eliminarán permanentemente los movimientos de $account y su saldo inicial se pondrá en cero. Las cuotas y cobros permanecerán. Exporta un respaldo antes."
                else "Se eliminarán permanentemente TODOS los movimientos y se pondrán en cero los saldos iniciales de todas las cuentas. Las cuotas y cobros permanecerán. Exporta un respaldo antes.") },
            confirmButton = { TextButton(onClick = {
                resetAccount = null; resetAllConfirm = false
                scope.launch {
                    try {
                        db.withTransaction {
                            if (account == null) dao.clearMovements() else dao.clearAccountMovements(account)
                        }
                        val selected = if (account == null) accounts else listOf(account)
                        val editor = balancePrefs.edit()
                        selected.forEach { editor.putLong(it, 0L) }
                        if (!editor.commit()) error("No se pudieron guardar los saldos en cero")
                        openingBalances = openingBalances + selected.associateWith { 0L }
                        Toast.makeText(context, "Registros borrados. Ya puedes importar de nuevo.", Toast.LENGTH_LONG).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Error al borrar: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }) { Text("Sí, borrar definitivamente", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { resetAccount = null; resetAllConfirm = false }) { Text("Cancelar") } }
        )
    }
    if (reconcileAccount != null) {
        val account = reconcileAccount!!
        val asOf = parseDate(reconcileDate)
        val actual = reconcileAmount.toLongOrNull()
        val toDate = movements.filter { it.account == account && asOf != null && parseDate(it.date)?.isAfter(asOf) == false }.sumOf { it.amountClp }
        val opening = if (actual != null) try { Math.subtractExact(actual, toDate) } catch (_: ArithmeticException) { null } else null
        AlertDialog(onDismissRequest = { reconcileAccount = null },
            title = { Text("Conciliar saldo de ${AccountCatalog.displayName(account)}") },
            text = { Column {
                Text("Indica el saldo que muestra tu banco y la fecha. No se borran movimientos ni se crean ingresos.")
                OutlinedTextField(reconcileDate, { reconcileDate = it }, label = { Text("Fecha (AAAA-MM-DD)") })
                OutlinedTextField(reconcileAmount, { reconcileAmount = it.filter { c -> c.isDigit() || c == '-' } }, label = { Text("Saldo real CLP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                if (asOf != null && opening != null) {
                    Text("Saldo inicial nuevo: ${money(opening)}")
                    Text("Saldo calculado a ${asOf}: ${money(actual!!)}")
                    Text("Los movimientos posteriores a esa fecha no se incluyen en la conciliación.")
                }
            } },
            confirmButton = { TextButton(enabled = asOf != null && !asOf.isAfter(LocalDate.now()) && opening != null, onClick = {
                val target = opening!!
                if (balancePrefs.edit().putLong(account, target).commit()) {
                    openingBalances = openingBalances + (account to target)
                    reconcileAccount = null
                    Toast.makeText(context, "Saldo conciliado a la fecha indicada", Toast.LENGTH_LONG).show()
                } else Toast.makeText(context, "No se pudo guardar", Toast.LENGTH_LONG).show()
            }) { Text("Conciliar") } },
            dismissButton = { TextButton(onClick = { reconcileAccount = null }) { Text("Cancelar") } }
        )
    }
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
                        visibleAccounts = AccountCatalog.selected(context)
                        accountConfigured = AccountCatalog.isConfigured(context)
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
    FinanceTheme {
        if (!unlocked) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Mis Finanzas", style = MaterialTheme.typography.headlineLarge)
                Text("Tus datos están protegidos por el bloqueo del dispositivo.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = requestUnlock) { Text("Desbloquear") }
            }
        } else if (loadedMovements == null || loadedObligations == null) {
            Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (!accountConfigured || managingAccounts) {
            AccountSelectionScreen(
                selected = visibleAccounts,
                initialSetup = !accountConfigured,
                onSave = { chosen ->
                    if (AccountCatalog.save(context, chosen)) {
                        visibleAccounts = chosen
                        accountConfigured = true
                        managingAccounts = false
                    } else Toast.makeText(context, "Selecciona al menos una cuenta", Toast.LENGTH_LONG).show()
                },
                onCancel = { managingAccounts = false }
            )
        } else {
            Scaffold(
                containerColor = FinancePalette.Surface,
                topBar = {
                    Surface(modifier = Modifier.fillMaxWidth().background(FinancePalette.Navy).statusBarsPadding(),
                        color = FinancePalette.Navy) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("Mis Finanzas", color = Color.White,
                                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text("v1.0 · TUS FINANZAS EN ORDEN", color = FinancePalette.Cyan,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Surface(color = Color.White.copy(alpha = 0.13f),
                                shape = RoundedCornerShape(12.dp)) {
                                Text("CLP", Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
                                    color = Color.White, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                },
                bottomBar = {
                    NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                        val labels = listOf("Inicio", "Movs.", "Cuotas", "Cobros", "Ajustes")
                        val icons = listOf(Icons.Filled.Home, Icons.Filled.AddCircle,
                            Icons.Filled.CreditCard, Icons.Filled.Payments, Icons.Filled.Settings)
                        labels.forEachIndexed { i, label ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { tab = i },
                                icon = { Icon(icons[i], contentDescription = label) },
                                label = { Text(label, maxLines = 1) },
                                alwaysShowLabel = true,
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = FinancePalette.Navy,
                                    selectedTextColor = FinancePalette.Navy,
                                    indicatorColor = Color(0xFFDFF5FA),
                                    unselectedIconColor = FinancePalette.Muted,
                                    unselectedTextColor = FinancePalette.Muted
                                )
                            )
                        }
                    }
                }
            ) { pad ->
                Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp).then(if (tab == 4) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
                    when (tab) {
                        0 -> Dashboard(movements, obligations, openingBalances, visibleAccounts, onNavigate = { tab = it }) { account, amount ->
                            balancePrefs.edit().putLong(account, amount).apply()
                            openingBalances = openingBalances + (account to amount)
                        }
                        1 -> MovementScreen(movements, dao, scope, visibleAccounts)
                        2 -> ObligationScreen(obligations.filter { it.kind == "Pago" }, "Pago", dao, scope, visibleAccounts)
                        3 -> ObligationScreen(obligations.filter { it.kind == "Reembolso" }, "Reembolso", dao, scope, visibleAccounts)
                        else -> {
                            Spacer(Modifier.height(16.dp))
                            FinanceSettingsGroup("Mis cuentas", "Selecciona qué cuentas quieres ver", expandedAtStart = true) {
                            Text(visibleAccounts.joinToString(" · ") { AccountCatalog.displayName(it) }, style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { managingAccounts = true }) { Text("Agregar u ocultar cuentas") }
                            Spacer(Modifier.height(18.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            FinanceSettingsGroup("Privacidad y seguridad", "Bloqueo con PIN o biometría") {
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
                            }
                            Spacer(Modifier.height(12.dp))
                            FinanceSettingsGroup("Outlook / Hotmail", "Detectar compras y transferencias por correo", expandedAtStart = false) {
                                OutlookMailSection(db)
                            }
                            Spacer(Modifier.height(12.dp))
                            FinanceSettingsGroup("Conexiones bancarias", "Estado de conexiones autorizadas") {
                            Text("Preparación para acceso autorizado · No hay bancos conectados", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            visibleAccounts.filter { it != "Efectivo" }.forEach { bank ->
                                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(AccountCatalog.displayName(bank), style = MaterialTheme.typography.titleMedium)
                                        Text("Sin conexión · Los movimientos siguen siendo manuales", style = MaterialTheme.typography.bodySmall)
                                        Text("La conexión automática requiere un servicio autorizado. La lectura de cartolas depende del formato de cada institución.", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            Text("Seguridad de la futura integración", style = MaterialTheme.typography.titleMedium)
                            Text("Nunca ingreses tu clave bancaria, PIN, coordenadas ni códigos SMS en Mis Finanzas. Una futura autorización deberá realizarse mediante el canal seguro del proveedor y permitir revocación.", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(18.dp))
                            Spacer(Modifier.height(18.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            FinanceSettingsGroup("Importar documentos", "Cartolas y resúmenes PDF", expandedAtStart = true) {
                            Text("Lee XLS binario de débito Banco Falabella, TXT de Banco de Chile y CSV genérico (fecha, descripción, monto). Selecciona la institución correcta. La cartola de débito no representa la deuda de CMR.", style = MaterialTheme.typography.bodySmall)
                            AccountPicker(csvAccount, { csvAccount = it }, visibleAccounts)
                            Button(onClick = { csvLauncher.launch(arrayOf("application/vnd.ms-excel", "text/*", "application/csv", "application/octet-stream", "*/*")) }) { Text("Elegir cartola CSV o TXT") }
                            Text("Comprueba que los datos estén correctos antes de confirmar. No se sube el archivo a servidores.", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(18.dp))
                            Text("Leer PDF de tarjeta de crédito", style = MaterialTheme.typography.titleLarge)
                            Text("Banco de Chile: consulta cupo utilizado, disponible y total desde el PDF de movimientos no facturados. Solo lectura; no cambia tus saldos.", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { cardPdfLauncher.launch(arrayOf("application/pdf")) }) { Text("Elegir PDF de tarjeta") }
                            Spacer(Modifier.height(18.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            FinanceSettingsGroup("Fechas de tarjetas", "Facturación y vencimientos") {
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
                            }
                            Spacer(Modifier.height(12.dp))
                            FinanceSettingsGroup("Conciliación y reinicio", "Corrige saldos o limpia una cuenta") {
                            OutlinedButton(onClick = { maintenanceOpen = !maintenanceOpen }, modifier = Modifier.fillMaxWidth()) {
                                Text(if (maintenanceOpen) "Ocultar herramientas de cuentas ▲" else "Borrar o conciliar una cuenta ▼")
                            }
                            if (maintenanceOpen) {
                                val manageable = (visibleAccounts +
                                    movements.map { it.account } +
                                    openingBalances.filterValues { it != 0L }.keys).distinct()
                                val chosenAccount = if (maintenanceAccount in manageable) maintenanceAccount
                                    else manageable.firstOrNull()
                                Text("Elige primero la cuenta. Ocultar una cuenta no borra datos; estas acciones sí pueden modificarlos.", style = MaterialTheme.typography.bodySmall)
                                Box {
                                    OutlinedButton(onClick = { maintenanceExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Cuenta: ${chosenAccount?.let { AccountCatalog.displayName(it) } ?: "Ninguna"}")
                                    }
                                    DropdownMenu(expanded = maintenanceExpanded, onDismissRequest = { maintenanceExpanded = false }) {
                                        manageable.forEach { name ->
                                            DropdownMenuItem(text = { Text(AccountCatalog.displayName(name)) }, onClick = {
                                                maintenanceAccount = name
                                                maintenanceExpanded = false
                                            })
                                        }
                                    }
                                }
                                OutlinedButton(enabled = chosenAccount != null, onClick = {
                                    resetAccount = chosenAccount
                                }, modifier = Modifier.fillMaxWidth()) { Text("Borrar saldo y movimientos de esta cuenta") }
                                OutlinedButton(enabled = chosenAccount != null, onClick = {
                                    reconcileAccount = chosenAccount
                                    reconcileAmount = ""
                                    reconcileDate = LocalDate.now().toString()
                                }, modifier = Modifier.fillMaxWidth()) { Text("Conciliar esta cuenta con saldo real") }
                                Text("El borrado requiere confirmación y conserva cuotas y cobros. La conciliación modifica solo el saldo inicial.", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { resetAllConfirm = true }) {
                                    Text("Borrar saldos y movimientos de todas las cuentas", color = MaterialTheme.colorScheme.error)
                                }
                            }
                            Spacer(Modifier.height(16.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            FinanceSettingsGroup("Respaldos", "Exporta o restaura tus registros") {
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
}

@Composable
fun Dashboard(
    movements: List<Movement>,
    obligations: List<Obligation>,
    openingBalances: Map<String, Long>,
    visibleAccounts: List<String>,
    onNavigate: (Int) -> Unit,
    onBalanceChange: (String, Long) -> Unit
) {
    val today = LocalDate.now()
    val month = YearMonth.from(today)
    val cashAccounts = visibleAccounts.filter { AccountCatalog.isCash(it) }
    val movementsToDate = movements.filter { parseDate(it.date)?.isAfter(today) != true }
    val balance = cashAccounts.sumOf { account ->
        (openingBalances[account] ?: 0L) + movementsToDate.filter { it.account == account }.sumOf { it.amountClp }
    }
    val monthMovements = movementsToDate.filter { parseDate(it.date)?.let { d -> YearMonth.from(d) == month } == true }
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
            Text("Tu resumen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Actualizado con tus registros · ${today.dayOfMonth}/${today.monthValue}/${today.year}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = FinancePalette.Navy), shape = RoundedCornerShape(26.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Text("DISPONIBLE REGISTRADO", color = FinancePalette.Cyan, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(money(balance), color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text("${cashAccounts.size} cuenta(s) activas · Saldo local, no bancario en tiempo real", color = Color(0xFFCAD8EE), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onNavigate(1) }, colors = ButtonDefaults.buttonColors(containerColor = FinancePalette.Cyan, contentColor = FinancePalette.NavyDeep)) { Text("+ Movimiento") }
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
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().border(1.dp, FinancePalette.Border, RoundedCornerShape(20.dp))) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("PROYECCIÓN DE FIN DE MES", style = MaterialTheme.typography.labelLarge, color = FinancePalette.Muted, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(money(balance - dueThisMonth), style = MaterialTheme.typography.headlineMedium, color = FinancePalette.Navy, fontWeight = FontWeight.Bold)
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
                        supportingContent = { Text("${AccountCatalog.displayName(o.account)} · ${date.dayOfMonth}/${date.monthValue}/${date.year}") },
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
        items(visibleAccounts) { account ->
            val amount = (openingBalances[account] ?: 0L) + movementsToDate.filter { it.account == account }.sumOf { it.amountClp }
            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().border(1.dp, FinancePalette.Border, RoundedCornerShape(18.dp))) {
                Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(AccountCatalog.displayName(account), fontWeight = FontWeight.SemiBold)
                        Text(money(amount), style = MaterialTheme.typography.titleMedium)
                        if (!AccountCatalog.isCash(account))
                            Text("Crédito · No suma al disponible", style = MaterialTheme.typography.labelSmall, color = FinancePalette.Muted)
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
                val data = movementsToDate.filter { parseDate(it.date)?.let { d -> YearMonth.from(d) == ym } == true && it.account in cashAccounts }
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
    val radius = RoundedCornerShape(19.dp)
    Card(
        modifier = Modifier.fillMaxWidth().border(1.dp, FinancePalette.Border, radius),
        shape = radius,
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box(Modifier.size(8.dp).background(accent, RoundedCornerShape(50)))
                Text(label, style = MaterialTheme.typography.labelMedium, color = FinancePalette.Muted)
            }
            Spacer(Modifier.height(9.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = FinancePalette.Ink)
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
fun AccountPicker(value: String, onChange: (String) -> Unit, choices: List<String> = accounts) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Cuenta: ${AccountCatalog.displayName(value)}") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { a -> DropdownMenuItem(text = { Text(AccountCatalog.displayName(a)) }, onClick = { onChange(a); expanded = false }) }
        }
    }
}

@Composable
fun MovementScreen(movements: List<Movement>, dao: FinanceDao, scope: kotlinx.coroutines.CoroutineScope, availableAccounts: List<String>) {
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var account by remember(availableAccounts) { mutableStateOf(availableAccounts.firstOrNull() ?: "Banco de Chile") }
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
            AccountPicker(account, { account = it }, availableAccounts)
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
                    (listOf("Todas") + availableAccounts + movements.map { it.account }).distinct().forEach { name ->
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
                        Text("${AccountCatalog.displayName(m.account)} · ${m.category} · ${m.date}", style = MaterialTheme.typography.bodySmall)
                        Text(money(m.amountClp))
                    }
                    TextButton(onClick = { scope.launch { dao.deleteMovement(m) } }) { Text("Eliminar") }
                }
            }
        }
    }
}

@Composable
fun ObligationScreen(items: List<Obligation>, kind: String, dao: FinanceDao, scope: kotlinx.coroutines.CoroutineScope, availableAccounts: List<String>) {
    var title by remember(kind) { mutableStateOf("") }
    var amount by remember(kind) { mutableStateOf("") }
    var account by remember(kind, availableAccounts) { mutableStateOf(listOf(if (kind == "Pago") "CMR" else "Banco de Chile").firstOrNull { it in availableAccounts } ?: availableAccounts.firstOrNull() ?: "Banco de Chile") }
    var date by remember(kind) { mutableStateOf(LocalDate.now().toString()) }
    var installments by remember(kind) { mutableStateOf("1") }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
        item {
            Text(if (kind == "Pago") "Cuotas y pagos pendientes" else "Reembolsos por cobrar", style = MaterialTheme.typography.titleLarge)
            Text(if (kind == "Pago") "Ingresa el valor de CADA cuota y la fecha del primer vencimiento. Para gastos recurrentes, registra cada mes como un pago separado." else "Registra dinero que otra persona debe devolverte.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(title, { title = it }, label = { Text(if (kind == "Pago") "Compra o cuenta" else "Persona y motivo") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text(if (kind == "Pago") "Valor de cada cuota CLP" else "Monto a cobrar CLP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            AccountPicker(account, { account = it }, availableAccounts)
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
                    Text("${AccountCatalog.displayName(o.account)} · ${o.dueDate}")
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
