package cl.personal.finanzas

import android.app.KeyguardManager
import android.os.Bundle
import android.os.Build
import android.content.Intent
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.ui.Alignment
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.room.*
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import org.json.JSONArray
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

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
    @Insert suspend fun insertMovement(value: Movement): Long
    @Update suspend fun updateMovement(value: Movement)
    @Query("SELECT * FROM movements WHERE id = :id LIMIT 1")
    suspend fun getMovementById(id: Long): Movement?
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
    @Query("SELECT * FROM obligations WHERE id = :id LIMIT 1") suspend fun getObligationById(id: Long): Obligation?
    @Query("DELETE FROM obligations") suspend fun clearObligations()
    @Query("SELECT * FROM planned_expenses ORDER BY active DESC, nextDueDate ASC")
    fun plannedExpenses(): Flow<List<PlannedExpense>>
    @Query("SELECT * FROM planned_expenses ORDER BY id")
    suspend fun allPlannedExpenses(): List<PlannedExpense>
    @Query("SELECT * FROM planned_expenses WHERE id = :id LIMIT 1")
    suspend fun getPlannedExpense(id: Long): PlannedExpense?
    @Insert suspend fun insertPlannedExpense(plan: PlannedExpense): Long
    @Update suspend fun updatePlannedExpense(plan: PlannedExpense)
    @Delete suspend fun deletePlannedExpense(plan: PlannedExpense)
    @Query("DELETE FROM planned_expenses") suspend fun clearPlannedExpenses()
    @Query("SELECT * FROM planned_expense_links ORDER BY linkedAt DESC")
    fun plannedExpenseLinks(): Flow<List<PlannedExpenseLink>>
    @Query("SELECT * FROM planned_expense_links ORDER BY linkedAt DESC")
    suspend fun allPlannedExpenseLinks(): List<PlannedExpenseLink>
    @Query("SELECT * FROM planned_expense_links WHERE movementId = :movementId LIMIT 1")
    suspend fun getPlannedExpenseLink(movementId: Long): PlannedExpenseLink?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlannedExpenseLink(link: PlannedExpenseLink)
    @Delete suspend fun deletePlannedExpenseLink(link: PlannedExpenseLink)
    @Query("DELETE FROM planned_expense_links") suspend fun clearPlannedExpenseLinks()
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMailCandidate(candidate: MailCandidate): Long
    @Query("SELECT * FROM mail_candidates ORDER BY operationDate DESC, sourceId DESC")
    fun mailCandidates(): Flow<List<MailCandidate>>
    @Query("SELECT * FROM mail_candidates ORDER BY operationDate DESC")
    suspend fun allMailCandidates(): List<MailCandidate>
    @Query("DELETE FROM mail_candidates") suspend fun clearMailCandidates()
    @Query("SELECT * FROM mail_candidates WHERE sourceId = :id LIMIT 1")
    suspend fun getMailCandidate(id: String): MailCandidate?
    @Query("UPDATE mail_candidates SET status = :status WHERE sourceId = :id")
    suspend fun markMailCandidate(id: String, status: String)
}

@Database(entities = [Movement::class, Obligation::class, MailCandidate::class, PlannedExpense::class, PlannedExpenseLink::class], version = 7, exportSchema = false)
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

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS planned_expenses (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            title TEXT NOT NULL,
            icon TEXT NOT NULL,
            estimatedAmountClp INTEGER NOT NULL,
            isVariable INTEGER NOT NULL,
            frequency TEXT NOT NULL,
            nextDueDate TEXT NOT NULL,
            anchorDay INTEGER NOT NULL,
            defaultAccount TEXT NOT NULL,
            active INTEGER NOT NULL,
            lastPaidDate TEXT NOT NULL,
            lastPaidAmountClp INTEGER NOT NULL,
            lastPaidWithoutMovement INTEGER NOT NULL,
            lastPaymentMovementId INTEGER NOT NULL
        )""")
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS planned_expense_links (
            movementId INTEGER NOT NULL PRIMARY KEY,
            planId INTEGER NOT NULL,
            dueBefore TEXT NOT NULL,
            dueAfter TEXT NOT NULL,
            estimateBefore INTEGER NOT NULL,
            estimateAfter INTEGER NOT NULL,
            previousPaidDate TEXT NOT NULL,
            previousPaidAmount INTEGER NOT NULL,
            previousAlreadyRegistered INTEGER NOT NULL,
            previousMovementId INTEGER NOT NULL,
            linkedAt INTEGER NOT NULL
        )""")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Conserva todas las filas y añade una categoría no destructiva a los gastos previstos.
        db.execSQL("ALTER TABLE planned_expenses ADD COLUMN category TEXT NOT NULL DEFAULT 'General'")
    }
}

private val accounts = AccountCatalog.names
private val clp = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL"))
private fun money(value: Long) = clp.format(value)
private fun parseDate(value: String): LocalDate? = try { LocalDate.parse(value) } catch (_: Exception) { null }

class MainActivity : ComponentActivity() {
    private val sharedText = androidx.compose.runtime.mutableStateOf("")
    private val foregroundEvent = androidx.compose.runtime.mutableIntStateOf(0)
    override fun onResume() {
        super.onResume()
        foregroundEvent.intValue += 1
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedText.value = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        val db = Room.databaseBuilder(applicationContext, FinanceDatabase::class.java, "finanzas.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            sharedText.value = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        }
        setContent { FinanceApp(db, sharedText.value, foregroundEvent.intValue) { sharedText.value = "" } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceApp(db: FinanceDatabase, sharedText: String = "", foregroundEvent: Int = 0, onSharedTextConsumed: () -> Unit = {}) {
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
    // Las alertas no crean movimientos: solo comparan el gasto del mes con los límites guardados.
    LaunchedEffect(loadedMovements, unlocked) {
        if (unlocked && loadedMovements != null) {
            withContext(Dispatchers.IO) { CategoryBudgetAlerts.check(context, movements) }
        }
    }
    val loadedPlannedExpenses by remember(dao) {
        dao.plannedExpenses().map<List<PlannedExpense>, List<PlannedExpense>?> { it }
    }.collectAsState(initial = null)
    val plannedExpenses = loadedPlannedExpenses.orEmpty()
    val plannedLinks by remember(dao) { dao.plannedExpenseLinks() }.collectAsState(initial = emptyList())
    LaunchedEffect(loadedObligations, loadedPlannedExpenses) {
        if (loadedObligations != null && loadedPlannedExpenses != null) {
            PaymentDueWidgetUpdater.render(context, obligations, plannedExpenses)
        }
    }
    LaunchedEffect(loadedObligations) {
        if (loadedObligations != null) {
            try { FinanceReminderScheduler.reschedule(context, loadedObligations.orEmpty()) }
            catch (_: Exception) { /* Se puede reintentar guardando los recordatorios en Ajustes. */ }
        }
    }
    var tab by remember { mutableStateOf(0) }
    // La clave de la guía es estable: NO depende de versionCode ni de las actualizaciones.
    var initialWelcomePending by remember {
        mutableStateOf(!QuickStartWelcome.wasShown(context))
    }
    var welcomeOpenedManually by remember { mutableStateOf(false) }
    var duePaymentPrompt by remember { mutableStateOf<DuePaymentNudge?>(null) }
    var promptedForegroundEvent by remember { mutableIntStateOf(-1) }
    var requestedPlannedPaymentId by remember { mutableStateOf<Long?>(null) }
    var showEmailImporter by remember { mutableStateOf(false) }
    var reviewPendingMails by remember { mutableStateOf(false) }
    LaunchedEffect(sharedText) {
        if (sharedText.isNotBlank()) { tab = 1; showEmailImporter = true }
    }
    val balancePrefs = remember { context.getSharedPreferences("opening_balances", 0) }
    var openingBalances by remember { mutableStateOf(accounts.associateWith { balancePrefs.getLong(it, 0L) }) }
    var accountConfigured by remember { mutableStateOf(AccountCatalog.isConfigured(context)) }
    var visibleAccounts by remember { mutableStateOf(AccountCatalog.selected(context)) }
    var managingAccounts by remember { mutableStateOf(false) }
    var widgetDetails by remember { mutableStateOf(PaymentDueWidgetSettings.showDetails(context)) }
    LaunchedEffect(foregroundEvent, unlocked, accountConfigured, loadedObligations, loadedPlannedExpenses) {
        // Solo se pregunta una vez por entrada a la app, después de las 17:00.
        // La actualización de Room o la navegación no vuelven a abrir el diálogo.
        if (foregroundEvent > 0 && promptedForegroundEvent != foregroundEvent &&
            unlocked && accountConfigured && loadedObligations != null && loadedPlannedExpenses != null &&
            FinanceReminderSettings.askOnOpeningAfterFive(context) &&
            java.time.LocalTime.now().hour >= 17) {
            val today = LocalDate.now()
            val planned = plannedExpenses.filter { it.active && it.nextDueDate == today.toString() }
            val installmentsDue = obligations.mapNotNull { obligation ->
                if (obligation.kind != "Pago" || obligation.paid) null
                else {
                    val first = parseDate(obligation.dueDate)
                    if (first != null && (obligation.paidCount until obligation.installments)
                            .any { first.plusMonths(it.toLong()) == today }) obligation
                    else null
                }
            }
            val total = planned.size + installmentsDue.size
            val first = planned.firstOrNull()?.let {
                DuePaymentNudge(it.id, "programado", it.title, it.estimatedAmountClp, total)
            } ?: installmentsDue.firstOrNull()?.let {
                DuePaymentNudge(it.id, "cuota", it.title, it.amountClp, total)
            }
            if (first != null) {
                promptedForegroundEvent = foregroundEvent
                duePaymentPrompt = first
            }
        }
    }
    val scope = rememberCoroutineScope()
    // Sincronización bajo demanda y al abrir la aplicación, nunca registra cargos sin revisión.
    LaunchedEffect(unlocked, accountConfigured) {
        if (unlocked && accountConfigured && OutlookGraph.enabled(context) && OutlookGraph.isConnected(context)) {
            try {
                val result = OutlookGraph.sync(context, dao)
                if (result.queued > 0) reviewPendingMails = true
            } catch (_: Exception) {
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
    var importError by remember { mutableStateOf<String?>(null) }
    var csvExisting by remember { mutableStateOf<List<Movement>>(emptyList()) }
    var pdfPasswordUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var pdfPasswordInput by remember { mutableStateOf("") }
    var pdfPasswordError by remember { mutableStateOf("") }
    fun loadStatement(uri: android.net.Uri, password: String = "") {
        scope.launch {
            // No abrir la conciliación cuando el archivo falló al interpretarse.
            importError = null
            csvMessage = ""
            csvPreview = emptyList()
            try {
                when (val parsed = StatementImport.readDocument(context, uri, csvAccount, password)) {
                    is ImportedStatement.CardSummary -> {
                        cardPdfReport = parsed.report
                        cardPdfError = null
                        csvMessage = ""
                        csvPreview = emptyList()
                    }
                    is ImportedStatement.Movements -> {
                        require(parsed.items.isNotEmpty()) {
                            "La cartola no contiene movimientos reconocibles."
                        }
                        if (parsed.source == "CuentaRUT / BancoEstado" && csvAccount != "BancoEstado") {
                            error("Esta cartola parece ser de CuentaRUT. Selecciona BancoEstado antes de importar.")
                        }
                        csvPreview = parsed.items
                        csvCutoff = LocalDate.now().toString()
                        csvRealBalance = ""
                        csvExisting = dao.allMovements()
                        csvMessage = "Cartola leída (" + parsed.source + "): " +
                            parsed.items.size + " movimientos. Revisa fechas, cargos y abonos. " +
                            if (AccountCatalog.isCash(csvAccount))
                                "Confirma el saldo real del banco antes de guardar."
                            else "Se importarán movimientos, sin modificar ningún saldo inicial."
                    }
                }
                pdfPasswordUri = null
                pdfPasswordError = ""
            } catch (_: PdfPasswordRequiredException) {
                pdfPasswordUri = uri
                pdfPasswordError = if (password.isEmpty()) "" else
                    "Contraseña incorrecta o PDF no desbloqueable. Intenta nuevamente."
                csvMessage = ""
                csvPreview = emptyList()
            } catch (e: Exception) {
                pdfPasswordUri = null
                pdfPasswordError = ""
                csvPreview = emptyList()
                csvMessage = ""
                importError = e.message ?: "No se pudo interpretar el formato del archivo."
            } finally {
                // La contraseña vive solo en el estado temporal del formulario.
                pdfPasswordInput = ""
            }
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadStatement(uri)
    }
    if (pdfPasswordUri != null) {
        val pending = pdfPasswordUri!!
        AlertDialog(
            onDismissRequest = {
                pdfPasswordUri = null
                pdfPasswordInput = ""
                pdfPasswordError = ""
            },
            title = { Text("Desbloquear PDF") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Ingresa la contraseña de apertura de esta cartola. Se utiliza solo " +
                        "para leer el archivo en tu teléfono; no se guarda ni se envía.")
                    Text("No ingreses aquí tu clave bancaria, clave de internet o PIN.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = pdfPasswordInput,
                        onValueChange = { pdfPasswordInput = it },
                        label = { Text("Contraseña del archivo PDF") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    if (pdfPasswordError.isNotBlank()) Text(
                        pdfPasswordError, color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = pdfPasswordInput.isNotEmpty(), onClick = {
                    val submitted = pdfPasswordInput
                    pdfPasswordInput = ""
                    loadStatement(pending, submitted)
                }) { Text("Desbloquear") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pdfPasswordUri = null
                    pdfPasswordInput = ""
                    pdfPasswordError = ""
                }) { Text("Cancelar") }
            }
        )
    }
    if (importError != null) {
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("No se pudo leer la cartola") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(importError.orEmpty(),
                        color = MaterialTheme.colorScheme.error)
                    Text("No se importaron movimientos ni se modificaron saldos.",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Si es una cartola de CuentaRUT, necesitamos identificar " +
                        "los encabezados y el orden de las columnas de ese PDF. " +
                        "Puedes compartir una captura de la tabla ocultando los datos personales.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text("Entendido") }
            }
        )
    }
    if (csvMessage.isNotBlank() && csvPreview.isNotEmpty()) {
        val cashImport = AccountCatalog.isCash(csvAccount)
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
        val calculatedOpening = if (cashImport && realBalance != null) {
            try { Math.subtractExact(realBalance, Math.addExact(recordedAtDate, newAtDate)) }
            catch (_: ArithmeticException) { null }
        } else null
        val laterCount = accountHistory.count { row ->
            asOf != null && parseDate(row.date)?.isAfter(asOf) == true
        }
        AlertDialog(
            onDismissRequest = { csvMessage = ""; csvPreview = emptyList() },
            title = { Text(if (cashImport) "Conciliar cartola con el banco" else "Importar movimientos de tarjeta") },
            text = {
                Column(Modifier.heightIn(max = 390.dp).verticalScroll(rememberScrollState())) {
                    Text(csvMessage)
                    if (firstStatementDate != null && lastStatementDate != null)
                        Text("Fechas de la cartola: $firstStatementDate a $lastStatementDate", style = MaterialTheme.typography.bodySmall)
                    CalendarDateField(csvCutoff, { csvCutoff = it },
                        if (cashImport) "Fecha del saldo bancario" else "Importar movimientos hasta")
                    if (cashImport) {
                        OutlinedTextField(csvRealBalance, {
                            csvRealBalance = it.filter { c -> c.isDigit() || c == '-' }
                        }, label = { Text("Saldo real del banco (CLP)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth())
                    } else {
                        Text("Tarjeta de crédito: no se modifica ningún saldo inicial, " +
                            "cupo ni registro de cuotas. Comprueba que los cargos " +
                            "no estén importados previamente.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (asOf == null) Text("Fecha inválida", color = MaterialTheme.colorScheme.error)
                    else if (asOf.isAfter(LocalDate.now())) Text("La fecha del saldo no puede ser futura.", color = MaterialTheme.colorScheme.error)
                    else if (firstStatementDate != null && asOf.isBefore(firstStatementDate))
                        Text("La fecha es anterior a todos los movimientos de la cartola. Elige una fecha igual o posterior a $firstStatementDate.", color = MaterialTheme.colorScheme.error)
                    else if (cashImport && realBalance == null)
                        Text("Ingresa el saldo real, sin puntos ni símbolo de pesos.")
                    else if (cashImport && calculatedOpening == null)
                        Text("El monto excede el rango admitido.")
                    else {
                        Text("Movimientos nuevos hasta la fecha: ${newRows.size}")
                        Text("Movimientos del archivo posteriores a la fecha: ${csvPreview.count { parseDate(it.date)?.isAfter(asOf) == true }}")
                        if (newRows.isEmpty() && accountHistory.none { parseDate(it.date)?.isAfter(asOf) == false })
                            Text("No hay movimientos para conciliar en esta fecha. Cambia la fecha antes de guardar.", color = MaterialTheme.colorScheme.error)
                        Text("Movimientos ya registrados hasta la fecha: ${accountHistory.count { parseDate(it.date)?.isAfter(asOf) == false }}")
                        Text("Variación total de movimientos: ${money(recordedAtDate + newAtDate)}")
                        if (cashImport && calculatedOpening != null && realBalance != null) {
                            Text("Saldo inicial calculado: ${money(calculatedOpening)}",
                                style = MaterialTheme.typography.titleMedium)
                            Text("Saldo conciliado a ${asOf}: ${money(realBalance)}",
                                style = MaterialTheme.typography.titleMedium)
                            if (laterCount > 0) Text("Atención: hay ${laterCount} movimientos posteriores " +
                                "a esa fecha; cambiarán el saldo mostrado hoy.",
                                color = MaterialTheme.colorScheme.error)
                            Text("Se reemplazará el saldo inicial de ${csvAccount}; " +
                                "no se creará un ingreso ficticio. Solo se agregarán " +
                                "movimientos nuevos anteriores o iguales a la fecha indicada.",
                                style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text("Se añadirán solo movimientos nuevos a ${csvAccount}, " +
                                "sin modificar el saldo inicial ni las cuotas.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        newRows.take(5).forEach { Text("${it.date} · ${it.description} · ${money(it.amountClp)}", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = csvPreview.isNotEmpty() && dateWithinStatement &&
                        if (cashImport) {
                            calculatedOpening != null &&
                                (newRows.isNotEmpty() || accountHistory.any {
                                    asOf != null && parseDate(it.date)?.isAfter(asOf) == false
                                })
                        } else newRows.isNotEmpty(),
                    onClick = {
                        val account = csvAccount
                        val target = calculatedOpening
                        val batch = newRows.toList()
                        val cutoffDate = asOf!!
                        csvMessage = ""; csvPreview = emptyList()
                        scope.launch {
                            try {
                                db.withTransaction {
                                    // Evita duplicados según cantidad de ocurrencias del historial.
                                    batch.forEach { row -> dao.insertMovement(row) }
                                }
                                if (cashImport && target != null) {
                                    if (!balancePrefs.edit().putLong(account, target).commit())
                                        error("No se pudo guardar el saldo inicial")
                                    openingBalances = openingBalances + (account to target)
                                    val persisted = dao.allMovements().filter {
                                        it.account == account &&
                                            parseDate(it.date)?.isAfter(cutoffDate) == false
                                    }.sumOf { it.amountClp }
                                    val verified = target + persisted
                                    Toast.makeText(context,
                                        if (verified == realBalance)
                                            "Conciliado a la fecha: ${money(verified)}"
                                        else "Revisa el saldo conciliado",
                                        Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(context, "${batch.size} movimientos importados en ${account}.",
                                        Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) { Text(if (cashImport) "Conciliar y guardar" else "Importar movimientos") }
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
                            val ids = dao.allMovements().filter {
                                account == null || it.account == account
                            }.map { it.id }.toSet()
                            require(dao.allPlannedExpenseLinks().none { it.movementId in ids } &&
                                dao.allPlannedExpenses().none {
                                    it.lastPaymentMovementId != 0L && it.lastPaymentMovementId in ids
                                }) {
                                "Hay gastos programados vinculados. Desvincúlalos desde Inicio → + Movimiento antes de borrar."
                            }
                            if (account == null) dao.clearMovements()
                            else dao.clearAccountMovements(account)
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
                CalendarDateField(reconcileDate, { reconcileDate = it }, "Fecha de conciliación")
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
                        FinanceReminderScheduler.reschedule(context, dao.allObligations())
                        Toast.makeText(context, "Respaldo restaurado", Toast.LENGTH_LONG).show() }
                    catch (e: Exception) { Toast.makeText(context, "No se restauró: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }) { Text("Reemplazar datos") } },
            dismissButton = { TextButton(onClick = { importConfirm = null }) { Text("Cancelar") } })
    }
    // Se presenta una sola vez, después del desbloqueo y de configurar las cuentas.
    // No interrumpe avisos compartidos ni recordatorios de pago.
    val welcomeReady = unlocked && accountConfigured && !managingAccounts &&
        loadedMovements != null && loadedObligations != null &&
        loadedPlannedExpenses != null && duePaymentPrompt == null &&
        tab == 0 && sharedText.isBlank()
    fun closeWelcome() {
        // Solo marcar después de cerrar la guía: si aparece un aviso prioritario
        // antes de que la persona la lea, permanece pendiente para otro inicio.
        if (initialWelcomePending) QuickStartWelcome.markShown(context)
        initialWelcomePending = false
        welcomeOpenedManually = false
    }

    FinanceTheme {
        if (reviewPendingMails && unlocked && accountConfigured && !managingAccounts &&
            duePaymentPrompt == null && !(welcomeReady && initialWelcomePending) &&
            !welcomeOpenedManually) {
            PendingMailReviewDialog(db, onDismiss = { reviewPendingMails = false })
        }
        if ((welcomeReady && initialWelcomePending) || welcomeOpenedManually) {
            QuickStartWelcomeDialog(
                onClose = { closeWelcome() },
                onOpenSettings = {
                    closeWelcome()
                    tab = 4
                }
            )
        }
        if (unlocked && accountConfigured && duePaymentPrompt != null) {
            val nudge = duePaymentPrompt!!
            DuePaymentNudgeDialog(
                nudge = nudge,
                onReview = {
                    duePaymentPrompt = null
                    if (nudge.kind == "programado") {
                        requestedPlannedPaymentId = nudge.id
                        tab = 5
                    } else {
                        tab = 2
                    }
                },
                onLater = { duePaymentPrompt = null }
            )
        }
        if (!unlocked) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text("Mis Finanzas", style = MaterialTheme.typography.headlineLarge)
                Text("Tus datos están protegidos por el bloqueo del dispositivo.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = requestUnlock) { Text("Desbloquear") }
            }
        } else if (loadedMovements == null || loadedObligations == null || loadedPlannedExpenses == null) {
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
            val hazeState = rememberHazeState()
            val refreshSnackbar = remember { SnackbarHostState() }
            var refreshingOutlook by remember { mutableStateOf(false) }
            Scaffold(
                containerColor = FinancePalette.Surface,
                snackbarHost = { SnackbarHost(hostState = refreshSnackbar) },
                topBar = {
                    val logoScale = remember { Animatable(1f) }
                    var logoTap by remember { mutableIntStateOf(0) }
                    LaunchedEffect(logoTap) {
                        if (logoTap > 0) {
                            logoScale.animateTo(0.97f, spring(stiffness = Spring.StiffnessHigh))
                            logoScale.animateTo(1f, spring(stiffness = Spring.StiffnessMedium))
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth().background(FinancePalette.Navy).statusBarsPadding(),
                        color = FinancePalette.Navy
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (tab == 4 || tab == 1) {
                                IconButton(onClick = { tab = 0 }) {
                                    Icon(Icons.Filled.ArrowBack, "Volver a Inicio", tint = Color.White)
                                }
                            }
                            Column(Modifier.weight(1f).graphicsLayer {
                                scaleX = logoScale.value
                                scaleY = logoScale.value
                            }.clickable { logoTap++ }) {
                                Text("Mis Finanzas", color = Color.White,
                                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                // Etiqueta solicitada: queda fija hasta la próxima versión estable.
                                Text("v1.13 · TUS FINANZAS EN ORDEN", color = FinancePalette.Cyan,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                            Surface(color = Color.White.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(14.dp)) {
                                Text("CLP", Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                                    color = Color.White, style = MaterialTheme.typography.labelLarge)
                            }
                            Spacer(Modifier.width(6.dp))
                            IconButton(onClick = { tab = if (tab == 4) 0 else 4 }) {
                                Icon(Icons.Filled.Settings, "Abrir Ajustes",
                                    tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                },
                bottomBar = {
                    val glassShape = RoundedCornerShape(26.dp)
                    val surface = if (Build.VERSION.SDK_INT >= 31) {
                        Modifier.hazeBlur(
                            input = HazeInput.Backdrop(hazeState),
                            style = HazeMaterials.thin()
                        )
                    } else {
                        Modifier.background(Color(0xFFF6F9FE))
                    }
                    NavigationBar(
                        modifier = Modifier
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                            .shadow(10.dp, glassShape)
                            .clip(glassShape)
                            .then(surface),
                        containerColor = if (Build.VERSION.SDK_INT >= 31)
                            Color.White.copy(alpha = 0.28f) else Color(0xFFF6F9FE),
                        tonalElevation = 0.dp
                    ) {
                        // Cuatro accesos. Movimientos se abre desde "+ Movimiento" en Inicio.
                        // Conservamos el ID interno 1 para no alterar rutas ni datos.
                        val sections = listOf(
                            Triple(0, "Inicio", Icons.Filled.Home),
                            Triple(2, "Créditos", Icons.Filled.CreditCard),
                            Triple(5, "Gastos", Icons.Filled.Event),
                            Triple(3, "Cobros", Icons.Filled.Payments)
                        )
                        sections.forEach { (sectionId, label, sectionIcon) ->
                            val iconScale by animateFloatAsState(
                                targetValue = if (tab == sectionId || (tab == 1 && sectionId == 0)) 1.12f else 1f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "navbar_icon_$sectionId"
                            )
                            NavigationBarItem(
                                modifier = Modifier.padding(horizontal = 3.dp, vertical = 4.dp),
                                selected = tab == sectionId || (tab == 1 && sectionId == 0),
                                onClick = { tab = sectionId },
                                icon = {
                                    Icon(sectionIcon, contentDescription = label,
                                        modifier = Modifier.graphicsLayer {
                                            scaleX = iconScale
                                            scaleY = iconScale
                                        })
                                },
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
                PullToRefreshBox(
                    isRefreshing = refreshingOutlook,
                    modifier = Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()),
                    onRefresh = {
                        if (!refreshingOutlook) {
                            if (!OutlookGraph.isConnected(context)) {
                                scope.launch {
                                    val response = refreshSnackbar.showSnackbar(
                                        message = "Conecta Outlook en Ajustes para buscar avisos bancarios.",
                                        actionLabel = "Ajustes",
                                        duration = SnackbarDuration.Long
                                    )
                                    if (response == SnackbarResult.ActionPerformed) tab = 4
                                }
                            } else {
                                refreshingOutlook = true
                                scope.launch {
                                    try {
                                        val result = OutlookGraph.sync(context, dao)
                                        val pendingCount = dao.allMailCandidates().count {
                                            it.status == "PENDIENTE" || it.status == "POSIBLE_DUPLICADO"
                                        }
                                        val summary = when {
                                            result.queued > 0 ->
                                                "${result.queued} aviso(s) nuevo(s) de Outlook. ${pendingCount} pendiente(s) por revisar."
                                            pendingCount > 0 ->
                                                "Sin avisos nuevos. Tienes ${pendingCount} pendiente(s) por revisar."
                                            else ->
                                                "Outlook actualizado: sin avisos nuevos (${result.scanned} correos revisados)."
                                        }
                                        refreshingOutlook = false
                                        if (pendingCount > 0) {
                                            // Al actualizar, abrir DIRECTAMENTE los avisos pendientes,
                                            // sin obligar a buscar en Ajustes una sección desplegable.
                                            reviewPendingMails = true
                                        } else {
                                            refreshSnackbar.showSnackbar(
                                                message = summary,
                                                duration = SnackbarDuration.Short
                                            )
                                        }
                                    } catch (e: Exception) {
                                        refreshingOutlook = false
                                        val detail = e.message?.replace(Regex("""[\r\n]+"""), " ")
                                            ?.take(110) ?: "Comprueba tu conexión."
                                        refreshSnackbar.showSnackbar(
                                            message = "No se pudo buscar avisos de Outlook: $detail",
                                            duration = SnackbarDuration.Long
                                        )
                                    } finally {
                                        refreshingOutlook = false
                                    }
                                }
                            }
                        }
                    }
                ) {
                    // El contenedor compartido NUNCA debe hacerse scrollable:
                    // AnimatedContent mantiene visible el Dashboard (LazyColumn) durante
                    // la salida, y un verticalScroll padre produciría nested scroll infinito.
                    Column(Modifier.fillMaxSize().hazeSource(state = hazeState).padding(horizontal = 16.dp)) {
                    AnimatedContent(
                        targetState = tab,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(180)) togetherWith
                                fadeOut(animationSpec = tween(130))
                        },
                        label = "transicion_pestanas"
                    ) { currentTab ->
                    when (currentTab) {
                        0 -> Dashboard(movements, obligations, plannedExpenses, openingBalances, visibleAccounts, onNavigate = { tab = it }) { account, amount ->
                            balancePrefs.edit().putLong(account, amount).apply()
                            openingBalances = openingBalances + (account to amount)
                        }
                        1 -> if (showEmailImporter) {
                            Column(Modifier.fillMaxSize()) {
                                TextButton(onClick = { showEmailImporter = false }) { Text("Volver a movimientos") }
                                EmailImportSection(sharedText, onSharedTextConsumed, movements, visibleAccounts, dao)
                            }
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                OutlinedButton(onClick = { showEmailImporter = true }) { Text("Importar aviso desde Outlook / Hotmail") }
                                MovementScreen(db, movements, plannedExpenses, plannedLinks, dao, scope, visibleAccounts)
                            }
                        }
                        2 -> ObligationScreen(obligations.filter { it.kind == "Pago" }, "Pago", dao, scope, visibleAccounts, movements)
                        3 -> ObligationScreen(obligations.filter { it.kind == "Reembolso" }, "Reembolso", dao, scope, visibleAccounts)
                        6 -> CategoryInsightsScreen(movements) { tab = 0 }
                        5 -> PlannedExpensesScreen(
                            db = db,
                            plans = plannedExpenses,
                            movements = movements,
                            visibleAccounts = visibleAccounts,
                            onBack = { tab = 0 },
                            requestedPaymentId = requestedPlannedPaymentId,
                            onPaymentRequestConsumed = { requestedPlannedPaymentId = null }
                        )
                        else -> {
                            // El scroll pertenece sólo a la pantalla de Ajustes,
                            // no al padre de la transición entre pestañas.
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            Spacer(Modifier.height(16.dp))
                            FinanceSettingsGroup(
                                "Guía de inicio",
                                "Importar cartola y conectar correo",
                                expandedAtStart = false
                            ) {
                                Text(
                                    "Revisa los primeros pasos cuando quieras. " +
                                        "La guía no vuelve a aparecer automáticamente al actualizar.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                OutlinedButton(onClick = { welcomeOpenedManually = true }) {
                                    Text("Ver primeros pasos")
                                }
                            }
                            Spacer(Modifier.height(12.dp))
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
                            FinanceSettingsGroup("Widget de pantalla de inicio", "Pagos que vencen hoy", expandedAtStart = false) {
                                Text("Añade el widget desde la pantalla de inicio de Android: mantén pulsado un espacio vacío → Widgets → Mis Finanzas.",
                                    style = MaterialTheme.typography.bodySmall)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Mostrar nombres e importes")
                                        Text("Desactivado por privacidad: el widget mostrará únicamente la cantidad de pagos pendientes.",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                    Switch(checked = widgetDetails, onCheckedChange = { enabled ->
                                        if (PaymentDueWidgetSettings.setShowDetails(context, enabled)) {
                                            widgetDetails = enabled
                                            PaymentDueWidgetUpdater.render(context, obligations, plannedExpenses)
                                        }
                                    })
                                }
                                Text("El widget se actualiza al cambiar registros mientras la app está abierta y periódicamente mediante Android. No marca pagos ni cambia saldos.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            Spacer(Modifier.height(12.dp))
                            FinanceSettingsGroup("Recordatorios", "Cobros y créditos con avisos independientes", expandedAtStart = false) {
                                FinanceReminderSettingsSection(obligations)
                            }
                            Spacer(Modifier.height(12.dp))
                            FinanceSettingsGroup("Correos conectados", "Gmail y Outlook · avisos bancarios", expandedAtStart = false) {
                                GmailMailSection(db)
                                HorizontalDivider()
                                Spacer(Modifier.height(6.dp))
                                OutlookMailSection(db)
                            }
                            Spacer(Modifier.height(12.dp))
                            FinanceSettingsGroup("Conexiones bancarias", "Estado de conexiones autorizadas") {
                            val outlookLinked = OutlookGraph.isConnected(context)
                            val outlookSynced = OutlookGraph.lastSuccessfulSyncMs(context) > 0L
                            Text(if (outlookLinked)
                                "Outlook autorizado para buscar avisos de Banco de Chile y BancoEstado. No es una conexión directa a los bancos."
                            else "Sin conexiones bancarias autorizadas actualmente.",
                                style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            visibleAccounts.filter { it != "Efectivo" }.forEach { bank ->
                                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Column(Modifier.padding(14.dp)) {
                                        Text(AccountCatalog.displayName(bank), style = MaterialTheme.typography.titleMedium)
                                        if (bank == "Banco de Chile" && outlookLinked) {
                                            Text(if (outlookSynced) "Conectado mediante correo · Avisos detectados con Microsoft Graph"
                                                else "Outlook autorizado · Aún no hay una lectura completada",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.tertiary)
                                            Text("Lee avisos de compras y transferencias. No consulta el saldo bancario ni importa automáticamente movimientos sin revisión.",
                                                style = MaterialTheme.typography.labelSmall)
                                        } else if (bank == "BancoEstado" && outlookLinked) {
                                            Text(
                                                if (OutlookGraph.bancoEstadoReceiptsSeen(context))
                                                    "Avisos de transferencia reconocidos mediante correo (Outlook)"
                                                else if (outlookSynced)
                                                    "Outlook conectado · Sin TEF de BancoEstado identificadas todavía"
                                                else "Outlook autorizado · Pendiente primera búsqueda",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.tertiary
                                            )
                                            Text("Busca comprobantes de transferencias enviadas desde CuentaRUT. Las operaciones quedan para revisión; no consulta saldos ni es una API de BancoEstado. Comprobantes solo en imagen todavía no se interpretan.",
                                                style = MaterialTheme.typography.labelSmall)
                                        } else {
                                            Text("Sin conexión directa · Los movimientos siguen siendo manuales",
                                                style = MaterialTheme.typography.bodySmall)
                                            Text("Puedes registrar movimientos o importar cartolas cuando el formato esté disponible.",
                                                style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                            Text("Seguridad de las conexiones", style = MaterialTheme.typography.titleMedium)
                            Text("Nunca ingreses tu clave bancaria, PIN, coordenadas ni códigos SMS en Mis Finanzas. La autorización de correo se gestiona por Microsoft y puede desconectarse desde Outlook / Hotmail.",
                                style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(12.dp))
                            }
                            Spacer(Modifier.height(12.dp))
                            FinanceSettingsGroup("Importar documentos", "Cartolas PDF, CSV, TXT y XLS", expandedAtStart = true) {
                            Text("Selecciona la cuenta o tarjeta a la que pertenecen los movimientos. " +
                                "El formato de archivo no limita el tipo de cuenta.",
                                style = MaterialTheme.typography.bodySmall)
                            AccountPicker(csvAccount, { csvAccount = it }, visibleAccounts)
                            Button(onClick = {
                                csvLauncher.launch(arrayOf("application/pdf",
                                    "application/vnd.ms-excel", "text/*", "application/csv",
                                    "application/octet-stream", "*/*"))
                            }, modifier = Modifier.fillMaxWidth()) { Text("Importar cartola · PDF / XLS / TXT / CSV") }
                            Text("Los PDF con contraseña piden la clave del archivo. Se reconocen " +
                                "tablas de movimientos con cargos y abonos (incluida CuentaRUT, " +
                                "cuando el formato permite identificar las columnas), TXT Banco de Chile, " +
                                "XLS de débito Falabella y CSV de fecha, descripción y monto.",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Los PDF de cupos de tarjetas Banco de Chile se muestran como resumen " +
                                "informativo. No se importan como cargos de cuenta.",
                                style = MaterialTheme.typography.bodySmall)
                            Text("Todo se lee en el teléfono. Revisa la vista previa y la cuenta " +
                                "antes de confirmar; un archivo ambiguo se rechaza.",
                                style = MaterialTheme.typography.bodySmall)
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
                            Spacer(Modifier.height(112.dp))
                            } // fin del Column scrollable de Ajustes
                        }
                    }
                    }
                }
                }
            }
        }
    }
}

private fun bankColor(account: String): Color = when (account) {
    "Banco de Chile" -> Color(0xFF1246A0)
    "MACH" -> Color(0xFF7137AD)
    "Tenpo" -> Color(0xFF15854C)
    "BancoEstado" -> Color(0xFFE36B24)
    "Santander" -> Color(0xFFB82F37)
    "Mercado Pago" -> Color(0xFF168DBD)
    "Banco Falabella" -> Color(0xFF43B02A)
    "Efectivo" -> Color(0xFF465B70)
    else -> FinancePalette.Navy
}

private val financeColorPalette = listOf(
    "0B2052", "1246A0", "168DBD", "00A6A6",
    "15854C", "43B02A", "E36B24", "F2A900",
    "B82F37", "DA4E76", "7137AD", "513F96",
    "465B70", "20242A", "865B3E", "008C95"
)

private fun readDashboardOrder(raw: String?): List<String>? {
    if (raw == null) return null
    return try {
        val array = JSONArray(raw)
        (0 until array.length()).map { array.optString(it) }
            .filter { it in AccountCatalog.names && AccountCatalog.isCash(it) }
            .distinct()
    } catch (_: Exception) { null }
}

private fun hexFinanceColor(value: String): Color? = try {
    val hex = value.trim().removePrefix("#")
    if (hex.length == 6 && hex.all { it in "0123456789abcdefABCDEF" })
        Color(android.graphics.Color.parseColor("#$hex")) else null
} catch (_: IllegalArgumentException) { null }

private data class DashboardUpcoming(
    val date: LocalDate,
    val title: String,
    val amountClp: Long,
    val destinationTab: Int
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Dashboard(
    movements: List<Movement>,
    obligations: List<Obligation>,
    plannedExpenses: List<PlannedExpense>,
    openingBalances: Map<String, Long>,
    visibleAccounts: List<String>,
    onNavigate: (Int) -> Unit,
    onBalanceChange: (String, Long) -> Unit
) {
    val context = LocalContext.current
    val themePrefs = remember(context) { context.getSharedPreferences("dashboard_theme", android.content.Context.MODE_PRIVATE) }
    var balanceTheme by remember { mutableStateOf(themePrefs.getString("mode", "auto") ?: "auto") }
    var customHex by remember { mutableStateOf(themePrefs.getString("hex", "0B2052") ?: "0B2052") }
    var themeDialog by remember { mutableStateOf(false) }
    val cardPrefs = remember(context) { context.getSharedPreferences("dashboard_account_cards", android.content.Context.MODE_PRIVATE) }
    var manualBankOrder by remember { mutableStateOf(readDashboardOrder(cardPrefs.getString("manual_order", null))) }
    var cardColorRevision by remember { mutableIntStateOf(0) }
    var bankMenu by remember { mutableStateOf<String?>(null) }
    var bankColorEditor by remember { mutableStateOf<String?>(null) }
    var bankColorChoice by remember { mutableStateOf("") }
    val overrides = remember(cardColorRevision) {
        AccountCatalog.names.associateWith { name ->
            cardPrefs.getString("color_$name", null)?.let(::hexFinanceColor)
        }
    }
    fun cardColor(account: String): Color = overrides[account] ?: bankColor(account)
    val today = LocalDate.now()
    val month = YearMonth.from(today)
    val cashAccounts = visibleAccounts.filter { AccountCatalog.isCash(it) }
    val movementsToDate = remember(movements, today) {
        movements.filter { parseDate(it.date)?.isAfter(today) != true }
    }
    val movementsByAccount = remember(movementsToDate) {
        movementsToDate.groupBy { it.account }.mapValues { (_, items) -> items.sumOf { it.amountClp } }
    }
    val accountBalances = remember(cashAccounts, openingBalances, movementsByAccount) {
        cashAccounts.associateWith { account ->
            (openingBalances[account] ?: 0L) + (movementsByAccount[account] ?: 0L)
        }
    }
    val balance = accountBalances.values.sum()
    val automaticBankOrder = cashAccounts.sortedWith(
        compareByDescending<String> { accountBalances[it] ?: 0L }.thenBy { cashAccounts.indexOf(it) }
    )
    val displayedBankOrder = manualBankOrder?.let { saved ->
        saved.filter { it in cashAccounts } + automaticBankOrder.filterNot { it in saved }
    } ?: automaticBankOrder
    fun moveBank(bank: String, newIndex: Int) {
        val list = displayedBankOrder.toMutableList()
        if (!list.remove(bank)) return
        list.add(newIndex.coerceIn(0, list.size), bank)
        val array = JSONArray()
        list.forEach { array.put(it) }
        if (cardPrefs.edit().putString("manual_order", array.toString()).commit()) {
            manualBankOrder = list
        }
    }
    val leadingAccount = automaticBankOrder.firstOrNull()
    val totalColor = when (balanceTheme) {
        "custom" -> hexFinanceColor(customHex) ?: FinancePalette.Navy
        "auto" -> leadingAccount?.let { cardColor(it) } ?: FinancePalette.Navy
        else -> cardColor(balanceTheme)
    }
    if (themeDialog) {
        AlertDialog(
            onDismissRequest = { themeDialog = false },
            title = { Text("Color del saldo total") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Automático: usa el color de la cuenta con mayor saldo.")
                    listOf("auto" to "Automático (mayor saldo)",
                        "Banco de Chile" to "Azul · Banco de Chile",
                        "MACH" to "Morado · MACH",
                        "Tenpo" to "Verde · Tenpo",
                        "BancoEstado" to "Naranja · BancoEstado",
                        "Santander" to "Rojo · Santander",
                        "Mercado Pago" to "Celeste · Mercado Pago",
                        "custom" to "Color personalizado").forEach { (key, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = balanceTheme == key, onClick = { balanceTheme = key })
                            TextButton(onClick = { balanceTheme = key }) { Text(label) }
                        }
                    }
                    if (balanceTheme == "custom") {
                        Text("Elige un color tocándolo:", style = MaterialTheme.typography.bodyMedium)
                        financeColorPalette.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { hex ->
                                    val selected = customHex.removePrefix("#").equals(hex, true)
                                    Box(
                                        Modifier.size(50.dp)
                                            .background(hexFinanceColor(hex) ?: FinancePalette.Navy,
                                                RoundedCornerShape(12.dp))
                                            .border(
                                                if (selected) 3.dp else 1.dp,
                                                if (selected) MaterialTheme.colorScheme.onSurface
                                                else MaterialTheme.colorScheme.outlineVariant,
                                                RoundedCornerShape(12.dp)
                                            )
                                            .clickable { customHex = hex },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (selected) Text("✓", color = Color.White,
                                            style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = balanceTheme != "custom" || hexFinanceColor(customHex) != null,
                    onClick = {
                        themePrefs.edit().putString("mode", balanceTheme).putString("hex", customHex).apply()
                        themeDialog = false
                    }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { themeDialog = false }) { Text("Cerrar") } }
        )
    }
    if (bankMenu != null) {
        val account = bankMenu!!
        val position = displayedBankOrder.indexOf(account)
        AlertDialog(
            onDismissRequest = { bankMenu = null },
            title = { Text(AccountCatalog.displayName(account)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Saldo registrado: ${money(accountBalances[account] ?: 0L)}")
                    Text(if (manualBankOrder == null) "Orden automático: de mayor a menor saldo."
                         else "Orden personalizado: se mantiene aunque cambien tus saldos.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        bankColorChoice = cardPrefs.getString("color_$account", null)
                            ?: financeColorPalette.firstOrNull { hexFinanceColor(it) == bankColor(account) }
                            ?: "0B2052"
                        bankColorEditor = account
                        bankMenu = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("Personalizar color") }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = position > 0,
                            onClick = { moveBank(account, position - 1) },
                            modifier = Modifier.weight(1f)
                        ) { Text("↑ Subir") }
                        OutlinedButton(
                            enabled = position >= 0 && position < displayedBankOrder.lastIndex,
                            onClick = { moveBank(account, position + 1) },
                            modifier = Modifier.weight(1f)
                        ) { Text("↓ Bajar") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = position > 0, onClick = { moveBank(account, 0) },
                            modifier = Modifier.weight(1f)) { Text("Poner primero") }
                        TextButton(
                            enabled = position >= 0 && position < displayedBankOrder.lastIndex,
                            onClick = { moveBank(account, displayedBankOrder.lastIndex) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Poner último") }
                    }
                    if (manualBankOrder != null) TextButton(onClick = {
                        if (cardPrefs.edit().remove("manual_order").commit()) manualBankOrder = null
                    }, modifier = Modifier.fillMaxWidth()) { Text("Restaurar orden por saldo") }
                }
            },
            confirmButton = { TextButton(onClick = { bankMenu = null }) { Text("Listo") } }
        )
    }
    if (bankColorEditor != null) {
        val account = bankColorEditor!!
        AlertDialog(
            onDismissRequest = { bankColorEditor = null },
            title = { Text("Color de ${AccountCatalog.displayName(account)}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Elige un color para esta tarjeta. No cambia los datos financieros.")
                    financeColorPalette.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { hex ->
                                val selected = bankColorChoice.equals(hex, ignoreCase = true)
                                Box(
                                    Modifier.size(50.dp)
                                        .background(hexFinanceColor(hex) ?: FinancePalette.Navy,
                                            RoundedCornerShape(12.dp))
                                        .border(if (selected) 3.dp else 1.dp,
                                            if (selected) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.outlineVariant,
                                            RoundedCornerShape(12.dp))
                                        .clickable { bankColorChoice = hex },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (selected) Text("✓", color = Color.White,
                                        style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                    }
                    TextButton(onClick = {
                        if (cardPrefs.edit().remove("color_$account").commit()) {
                            cardColorRevision++
                            bankColorEditor = null
                        }
                    }) { Text("Usar color original del banco") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (hexFinanceColor(bankColorChoice) != null &&
                        cardPrefs.edit().putString("color_$account", bankColorChoice).commit()) {
                        cardColorRevision++
                        bankColorEditor = null
                    }
                }) { Text("Guardar color") }
            },
            dismissButton = { TextButton(onClick = { bankColorEditor = null }) { Text("Cancelar") } }
        )
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
    // Una vista de los vencimientos inmediatos, conservando separadas las cuotas
    // y los gastos programados; no se modifican deudas ni movimientos.
    val nextDueRows = remember(plannedExpenses, obligations, today) {
        val planned = plannedExpenses.filter { it.active }.mapNotNull { plan ->
            parseDate(plan.nextDueDate)?.let { due ->
                DashboardUpcoming(due, plan.icon + " " + plan.title, plan.estimatedAmountClp, 5)
            }
        }
        val installments = obligations.filter { it.kind == "Pago" && !it.paid }.flatMap { o ->
            val first = parseDate(o.dueDate) ?: return@flatMap emptyList<DashboardUpcoming>()
            (o.paidCount until o.installments).map { i ->
                DashboardUpcoming(first.plusMonths(i.toLong()), "💳 " + o.title, o.amountClp, 2)
            }
        }
        (planned + installments).filter {
            !it.date.isAfter(today.plusDays(35)) && !it.date.isBefore(today.minusDays(14))
        }
            .sortedWith(compareBy<DashboardUpcoming> { it.date }.thenBy { it.title })
            .take(3)
    }
    val balanceAnim = remember { Animatable(0f) }
    LaunchedEffect(balance) {
        balanceAnim.animateTo(balance.toFloat(), animationSpec = tween(durationMillis = 750))
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
    LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 18.dp, bottom = 112.dp)) {
        item {
            Text("Tu resumen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Actualizado con tus registros · ${today.dayOfMonth}/${today.monthValue}/${today.year}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            val foreground = if (totalColor.luminance() > 0.44f) FinancePalette.NavyDeep else Color.White
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(26.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Box(
                    Modifier.fillMaxWidth().background(
                        Brush.linearGradient(
                            listOf(
                                lerp(totalColor, Color.White, 0.06f),
                                lerp(totalColor, FinancePalette.NavyDeep, 0.27f)
                            )
                        )
                    )
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("DISPONIBLE REGISTRADO",
                                color = foreground.copy(alpha = 0.88f),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { themeDialog = true },
                                modifier = Modifier.size(34.dp)
                            ) {
                                Icon(Icons.Filled.Palette, "Personalizar color",
                                    tint = foreground, modifier = Modifier.size(19.dp))
                            }
                        }
                        // Float anima suavemente; el Long original evita redondeos
                        // en saldos grandes una vez terminada la animación.
                        val displayed = if (!balanceAnim.isRunning &&
                            balanceAnim.value == balance.toFloat()) balance
                            else balanceAnim.value.toLong()
                        Text(money(displayed), color = foreground,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                color = foreground.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(50)
                            ) {
                                Text("Saldo local", Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    color = foreground, style = MaterialTheme.typography.labelSmall)
                            }
                            Text("${cashAccounts.size} cuentas activas",
                                color = foreground.copy(alpha = 0.88f),
                                style = MaterialTheme.typography.labelSmall)
                        }
                        HorizontalDivider(color = foreground.copy(alpha = 0.17f))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("Ingresos del mes", color = foreground.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.labelSmall)
                                Text(money(income), color = foreground, fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Gastos del mes", color = foreground.copy(alpha = 0.8f),
                                    style = MaterialTheme.typography.labelSmall)
                                Text(money(expenses), color = foreground, fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onNavigate(1) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = FinancePalette.Cyan,
                                    contentColor = FinancePalette.NavyDeep)) {
                                Text("+ Movimiento", maxLines = 1)
                            }
                            OutlinedButton(onClick = { onNavigate(2) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = foreground)) {
                                Text("Ver pagos", maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        item(key = "upcoming_dashboard") {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth().border(1.dp, FinancePalette.Border, RoundedCornerShape(22.dp))) {
                Column(Modifier.fillMaxWidth().padding(15.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Próximos vencimientos", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold)
                    if (nextDueRows.isEmpty()) {
                        Text("No hay gastos ni cuotas próximos registrados.",
                            style = MaterialTheme.typography.bodySmall, color = FinancePalette.Muted)
                    } else {
                        nextDueRows.forEach { due ->
                            Row(Modifier.fillMaxWidth()
                                .clickable { onNavigate(due.destinationTab) }
                                .padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(due.title, style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    val whenText = when {
                                        due.date.isBefore(today) -> "Vencido · " + dateForDisplay(due.date.toString())
                                        due.date == today -> "Hoy"
                                        else -> dateForDisplay(due.date.toString())
                                    }
                                    Text(whenText, style = MaterialTheme.typography.labelSmall,
                                        color = if (due.date.isBefore(today)) MaterialTheme.colorScheme.error
                                            else FinancePalette.Muted)
                                }
                                Text(money(due.amountClp), style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold)
                            }
                            if (due != nextDueRows.last()) HorizontalDivider(color = FinancePalette.Border)
                        }
                    }
                }
            }
        }
        item(key = "accounts_title") {
            Text("Disponible por cuenta", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            Text(if (manualBankOrder == null)
                "Mayor a menor saldo · Mantén pulsada una tarjeta para personalizarla."
                else "Orden personalizado · Mantén pulsada una tarjeta para reorganizarla.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(displayedBankOrder.chunked(2), key = { "bank-row-" + it.joinToString("|") }) { pair ->
            Row(Modifier.fillMaxWidth().animateItem(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { account ->
                    val amount = accountBalances[account] ?: 0L
                    val base = cardColor(account)
                    // Evita el rectángulo fantasma causado por la transparencia del Card.
                    val cardBase = if (amount == 0L) lerp(base, Color.White, 0.16f) else base
                    val labelColor = if (cardBase.luminance() > 0.42f) FinancePalette.NavyDeep else Color.White
                    val cardInteractions = remember(account) { MutableInteractionSource() }
                    val pressed by cardInteractions.collectIsPressedAsState()
                    val haptics = LocalHapticFeedback.current
                    val pressedScale by animateFloatAsState(
                        targetValue = if (pressed) 0.965f else 1f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessMedium
                        ),
                        label = "bank_scale_$account"
                    )
                    val elevation by animateDpAsState(
                        targetValue = if (pressed) 11.dp else 2.dp,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        label = "bank_elevation_$account"
                    )
                    val shape = RoundedCornerShape(18.dp)
                    Card(
                        modifier = Modifier.weight(1f).padding(bottom = 7.dp)
                            .shadow(elevation, shape)
                            .graphicsLayer {
                                scaleX = pressedScale
                                scaleY = pressedScale
                            }
                            .combinedClickable(
                                interactionSource = cardInteractions,
                                indication = LocalIndication.current,
                                onClick = { bankMenu = account },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    bankMenu = account
                                }
                            ),
                        shape = shape,
                        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Box(
                            Modifier.fillMaxWidth().background(
                                Brush.linearGradient(
                                    listOf(lerp(cardBase, Color.White, 0.12f),
                                        lerp(cardBase, FinancePalette.NavyDeep, 0.12f))
                                )
                            )
                        ) {
                            Column(Modifier.fillMaxWidth().padding(13.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.size(30.dp).background(
                                            labelColor.copy(alpha = 0.14f), RoundedCornerShape(50)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(AccountCatalog.displayName(account).take(1),
                                            color = labelColor, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                    Text(AccountCatalog.displayName(account),
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = labelColor, maxLines = 2)
                                }
                                Text(money(amount), style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold, color = labelColor, maxLines = 1)
                            }
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item(key = "weekly_vico") {
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth().border(1.dp, FinancePalette.Border, RoundedCornerShape(22.dp))
            ) {
                WeeklyExpenseChart(movements, modifier = Modifier.fillMaxWidth().padding(15.dp))
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
            val activePlans = plannedExpenses.filter { it.active }.sortedBy { it.nextDueDate }
            val dueSoon = activePlans.count {
                parseDate(it.nextDueDate)?.let { date -> !date.isAfter(today.plusDays(7)) } == true
            }
            Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth().border(1.dp, FinancePalette.Border, RoundedCornerShape(20.dp))) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("GASTOS PREVISTOS", style = MaterialTheme.typography.labelLarge,
                                color = FinancePalette.Navy, fontWeight = FontWeight.Bold)
                            Text("${activePlans.size} activos · ${dueSoon} vencen en los próximos 7 días",
                                style = MaterialTheme.typography.bodySmall, color = FinancePalette.Muted)
                        }
                        TextButton(onClick = { onNavigate(5) }) { Text("Administrar") }
                    }
                    activePlans.take(3).forEach { plan ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("${plan.icon} ${plan.title}", modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium)
                            Text(money(plan.estimatedAmountClp), fontWeight = FontWeight.SemiBold)
                        }
                    }
                    if (activePlans.isEmpty()) {
                        Text("Agrega luz, agua u otros gastos semanales o mensuales para organizar tu presupuesto.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Los montos son estimados y no se descuentan hasta que registres un pago.",
                        style = MaterialTheme.typography.bodySmall, color = FinancePalette.Muted)
                }
            }
        }
        item {
            val allCategorySpend = CategoryBudgetStore.spending(movements, month)
            val categorySpendTotal = allCategorySpend.values.sum().coerceAtLeast(1L)
            val orderedCategorySpend = allCategorySpend.entries.sortedByDescending { it.value }
            val usedByCategory = if (orderedCategorySpend.size <= 5)
                orderedCategorySpend.map { it.key to it.value }
            else orderedCategorySpend.take(4).map { it.key to it.value } +
                ("Otras categorías" to orderedCategorySpend.drop(4).sumOf { it.value })
            Card(shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                modifier = Modifier.fillMaxWidth().border(1.dp, FinancePalette.Border, RoundedCornerShape(20.dp))) {
                Column(Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("ANÁLISIS DE GASTOS", fontWeight = FontWeight.Bold,
                                color = FinancePalette.Navy,
                                style = MaterialTheme.typography.labelLarge)
                            Text("Distribución mensual y presupuestos",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { onNavigate(6) }) { Text("Ver gráficos") }
                    }
                    if (usedByCategory.isEmpty()) {
                        Text("Los gráficos aparecerán cuando registres gastos.",
                            style = MaterialTheme.typography.bodySmall)
                    } else {
                        val chartColors = listOf(
                            Color(0xFF159C7C), Color(0xFFEB7B67), Color(0xFF6B76C7),
                            Color(0xFFE3AC51), Color(0xFF8D79A8)
                        )
                        // Barra apilada: todos los segmentos comparten el mismo total.
                        Row(
                            Modifier.fillMaxWidth().height(16.dp)
                                .clip(RoundedCornerShape(9.dp))
                        ) {
                            usedByCategory.forEachIndexed { i, pair ->
                                Box(
                                    Modifier.weight(pair.second.toFloat().coerceAtLeast(0.1f))
                                        .fillMaxHeight()
                                        .background(chartColors[i % chartColors.size])
                                )
                            }
                        }
                        usedByCategory.forEachIndexed { i, pair ->
                            val share = pair.second * 100.0 / categorySpendTotal
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.size(9.dp).background(
                                    chartColors[i % chartColors.size],
                                    RoundedCornerShape(3.dp)))
                                Text(pair.first, modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                Text("${share.toInt()}%",
                                    style = MaterialTheme.typography.labelSmall)
                                Text(money(pair.second), style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
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
                            Text("Crédito · Disponible en Créditos", style = MaterialTheme.typography.labelSmall, color = FinancePalette.Muted)
                    }
                    TextButton(onClick = {
                        editingAccount = account
                        openingInput = (openingBalances[account] ?: 0L).toString()
                    }) { Text("Editar") }
                }
            }
        }
        item(key = "six_month_cashflow") {
            SixMonthCashflowChart(
                movements = movementsToDate,
                cashAccounts = cashAccounts,
                endMonth = month,
                modifier = Modifier.fillMaxWidth()
            )
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
fun MovementScreen(
    db: FinanceDatabase,
    movements: List<Movement>,
    plans: List<PlannedExpense>,
    links: List<PlannedExpenseLink>,
    dao: FinanceDao,
    scope: kotlinx.coroutines.CoroutineScope,
    availableAccounts: List<String>
) {
    val context = LocalContext.current
    val merchantFrequency = remember(movements) {
        movements.groupingBy { MerchantAliases.key(it.description) }.eachCount()
    }
    var merchantAliasEditor by remember { mutableStateOf<String?>(null) }
    var aliasRevision by remember { mutableIntStateOf(0) }
    var description by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var account by remember(availableAccounts) { mutableStateOf(availableAccounts.firstOrNull() ?: "Banco de Chile") }
    var category by remember { mutableStateOf("General") }
    var expense by remember { mutableStateOf(true) }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var search by remember { mutableStateOf("") }
    var selectedAccount by remember { mutableStateOf("Todas") }
    var movementForPlan by remember { mutableStateOf<Movement?>(null) }
    var deletionWarning by remember { mutableStateOf("") }
    var categorizingMovement by remember { mutableStateOf<Movement?>(null) }
    var categoryForEdit by remember { mutableStateOf("General") }
    var editingDescription by remember { mutableStateOf<Movement?>(null) }
    var descriptionForEdit by remember { mutableStateOf("") }
    var descriptionUpdateBusy by remember { mutableStateOf(false) }
    var saveMovementBusy by remember { mutableStateOf(false) }
    var movementSaveFeedback by remember { mutableStateOf("") }
    if (editingDescription != null) {
        val existing = editingDescription!!
        AlertDialog(
            onDismissRequest = { if (!descriptionUpdateBusy) editingDescription = null },
            title = { Text("Editar descripción") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("${AccountCatalog.displayName(existing.account)} · ${dateForDisplay(existing.date)} · ${money(existing.amountClp)}",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = descriptionForEdit,
                        onValueChange = { descriptionForEdit = it.take(200) },
                        label = { Text("Descripción del movimiento") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("Solo cambiará el texto. El monto, la fecha, la cuenta y las asociaciones se mantienen.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !descriptionUpdateBusy && descriptionForEdit.trim().isNotEmpty(),
                    onClick = {
                        val updatedText = descriptionForEdit.trim() // Capturar antes de iniciar la operación.
                        val movementId = existing.id
                        descriptionUpdateBusy = true
                        scope.launch {
                            try {
                                val latest = dao.getMovementById(movementId)
                                    ?: error("Este movimiento ya no existe.")
                                dao.updateMovement(latest.copy(description = updatedText))
                                editingDescription = null
                                movementSaveFeedback = "Descripción actualizada."
                            } catch (e: Exception) {
                                movementSaveFeedback = "No se pudo editar: ${e.message.orEmpty()}"
                            } finally {
                                descriptionUpdateBusy = false
                            }
                        }
                    }
                ) { Text(if (descriptionUpdateBusy) "Guardando…" else "Guardar") }
            },
            dismissButton = {
                TextButton(
                    enabled = !descriptionUpdateBusy,
                    onClick = { editingDescription = null }
                ) { Text("Cancelar") }
            }
        )
    }
    if (merchantAliasEditor != null) {
        val original = merchantAliasEditor!!
        MerchantAliasDialog(
            description = original,
            matches = merchantFrequency[MerchantAliases.key(original)] ?: 0,
            onDismiss = { merchantAliasEditor = null },
            onSaved = { aliasRevision++; merchantAliasEditor = null }
        )
    }
    if (categorizingMovement != null) {
        val selected = categorizingMovement!!
        AlertDialog(
            onDismissRequest = { categorizingMovement = null },
            title = { Text("Categorizar movimiento") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(selected.description, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    ExpenseCategoryPicker(categoryForEdit, { categoryForEdit = it },
                        historicalCategories = movements.map { it.category })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { dao.updateMovement(selected.copy(category = categoryForEdit)) }
                    categorizingMovement = null
                }) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { categorizingMovement = null }) { Text("Cancelar") }
            }
        )
    }
    if (movementForPlan != null) {
        LinkPlannedExpenseDialog(
            db = db,
            movement = movementForPlan!!,
            plans = plans,
            existingLinks = links,
            onDismiss = { movementForPlan = null }
        )
    }
    val weekExpanded = remember { mutableStateMapOf<String, Boolean>() }
    val thisMonday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val lastMonday = thisMonday.minusWeeks(1)
    val filteredMovements = movements.filter {
        (selectedAccount == "Todas" || it.account == selectedAccount) &&
            (search.isBlank() || it.description.contains(search, ignoreCase = true) ||
                MerchantAliases.label(context, it.description).contains(search, ignoreCase = true) ||
                it.category.contains(search, ignoreCase = true) || it.date.contains(search))
    }
    val groupedMovements = filteredMovements.groupBy { movement ->
        parseDate(movement.date)?.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))?.toString()
            ?: "sin-fecha"
    }.toSortedMap(compareByDescending<String> { parseDate(it) ?: LocalDate.MIN })
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 112.dp)) {
        item {
            Text("Registrar movimiento", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(description, { description = it }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Monto CLP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            AccountPicker(account, { account = it }, availableAccounts)
            ExpenseCategoryPicker(category, { category = it },
                historicalCategories = movements.map { it.category })
            CalendarDateField(date, { date = it }, "Fecha del movimiento")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(expense, { expense = true }, label = { Text("Gasto") })
                FilterChip(!expense, { expense = false }, label = { Text("Ingreso") })
            }
            val valid = description.trim().isNotEmpty() &&
                (amount.toLongOrNull() ?: 0L) > 0L && parseDate(date) != null
            Button(
                enabled = valid && !saveMovementBusy,
                onClick = {
                    val value = amount.toLongOrNull() ?: return@Button
                    // IMPORTANTE: capturar todos los campos ANTES de lanzar la coroutine.
                    // Antes se limpiaba description inmediatamente y Room recibía "".
                    val entry = Movement(
                        account = account,
                        description = description.trim(),
                        amountClp = if (expense) -value else value,
                        date = date,
                        category = category.trim()
                    )
                    if (entry.description.isBlank()) return@Button
                    saveMovementBusy = true
                    movementSaveFeedback = ""
                    scope.launch {
                        try {
                            val insertedId = dao.insertMovement(entry)
                            require(insertedId > 0L) { "No se confirmó el guardado." }
                            description = ""
                            amount = ""
                            movementSaveFeedback = "Movimiento guardado con su descripción."
                        } catch (e: Exception) {
                            // Conserva el formulario para reintentar si Room falla.
                            movementSaveFeedback = "No se pudo guardar: ${e.message.orEmpty()}"
                        } finally {
                            saveMovementBusy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (saveMovementBusy) "Guardando…" else "Guardar movimiento") }
            if (movementSaveFeedback.isNotBlank()) {
                Text(movementSaveFeedback, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            Text("Historial", style = MaterialTheme.typography.titleLarge)
            Text("En una salida de dinero, usa «Asociar gasto» para marcar un gasto programado como pagado sin volver a descontar el monto.",
                style = MaterialTheme.typography.bodySmall)
            if (deletionWarning.isNotBlank()) Text(deletionWarning,
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
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
        if (filteredMovements.isEmpty()) {
            item { Text("No hay movimientos que coincidan con el filtro.", style = MaterialTheme.typography.bodySmall) }
        }
        groupedMovements.forEach { (weekKey, weekItems) ->
            val monday = parseDate(weekKey)
            val weekLabel = when (monday) {
                thisMonday -> "Esta semana"
                lastMonday -> "Semana anterior"
                null -> "Sin fecha"
                else -> "Semana del ${monday.dayOfMonth}/${monday.monthValue} al ${monday.plusDays(6).dayOfMonth}/${monday.plusDays(6).monthValue}"
            }
            val isExpanded = if (search.isNotBlank()) true else weekExpanded[weekKey] ?: (monday == thisMonday)
            item(key = "week_$weekKey") {
                Card(
                    onClick = { if (search.isBlank()) weekExpanded[weekKey] = !isExpanded },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8EEF8)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(weekLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                                color = FinancePalette.Navy)
                            Text("${weekItems.size} movimientos", style = MaterialTheme.typography.bodySmall,
                                color = FinancePalette.Muted)
                        }
                        Text(if (isExpanded) "▴" else "▾", style = MaterialTheme.typography.titleLarge,
                            color = FinancePalette.Navy)
                    }
                }
            }
            if (isExpanded) {
                items(weekItems, key = { it.id }) { m ->
                    Card(Modifier.fillMaxWidth().animateItem()) {
                        Row(Modifier.padding(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                val friendly = remember(m.description, aliasRevision) {
                                    MerchantAliases.label(context, m.description)
                                }
                                Text(friendly.ifBlank { "(Sin descripción)" },
                                    style = MaterialTheme.typography.titleMedium)
                                if (friendly != m.description) {
                                    Text("Banco: ${m.description}",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                                Text("${AccountCatalog.displayName(m.account)} · ${ExpenseCategories.flat(m.category)} · ${dateForDisplay(m.date)}",
                                    style = MaterialTheme.typography.bodySmall)
                                Text(money(m.amountClp))
                                val link = links.firstOrNull { it.movementId == m.id }
                                val legacy = plans.firstOrNull { it.lastPaymentMovementId == m.id }
                                if (link != null || legacy != null) {
                                    Text("✓ Gasto programado pagado" +
                                        (plans.firstOrNull { it.id == link?.planId }?.let { ": " + it.title }
                                            ?: legacy?.let { ": " + it.title }.orEmpty()),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary)
                                }
                                if (m.amountClp < 0L &&
                                    m.category !in listOf("Transferencia interna", "Traspaso interno", "Gasto previsto",
                                        "Pago tarjeta", "Pago de tarjeta", "Abono tarjeta")) {
                                    TextButton(onClick = { movementForPlan = m }) {
                                        Text(if (link != null || legacy != null)
                                            "Ver gasto pagado" else "✓ Asociar gasto")
                                    }
                                }
                                TextButton(onClick = {
                                    editingDescription = m
                                    descriptionForEdit = m.description
                                }) { Text(if (m.description.isBlank()) "Agregar descripción" else "Editar descripción") }
                                TextButton(onClick = {
                                    categorizingMovement = m
                                    categoryForEdit = m.category
                                }) { Text("Categorizar") }
                                TextButton(onClick = { merchantAliasEditor = m.description }) {
                                    val repeatCount = merchantFrequency[MerchantAliases.key(m.description)] ?: 0
                                    Text(if (repeatCount >= 2) "Renombrar comercio · ${repeatCount} veces"
                                        else "Poner nombre")
                                }
                            }
                            TextButton(onClick = {
                                val linked = links.any { it.movementId == m.id } ||
                                    plans.any { it.lastPaymentMovementId == m.id && it.lastPaymentMovementId > 0L }
                                if (linked) deletionWarning =
                                    "Este movimiento está vinculado a un gasto programado. Desvincúlalo antes de eliminarlo."
                                else scope.launch { dao.deleteMovement(m) }
                            }) { Text("Eliminar") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ObligationScreen(items: List<Obligation>, kind: String, dao: FinanceDao, scope: kotlinx.coroutines.CoroutineScope, availableAccounts: List<String>, movements: List<Movement> = emptyList()) {
    var title by remember(kind) { mutableStateOf("") }
    var amount by remember(kind) { mutableStateOf("") }
    var account by remember(kind, availableAccounts) { mutableStateOf(listOf(if (kind == "Pago") "CMR" else "Banco de Chile").firstOrNull { it in availableAccounts } ?: availableAccounts.firstOrNull() ?: "Banco de Chile") }
    var date by remember(kind) { mutableStateOf(LocalDate.now().toString()) }
    var installments by remember(kind) { mutableStateOf("1") }
    val context = LocalContext.current
    var editingObligation by remember { mutableStateOf<Obligation?>(null) }
    var editingBusy by remember { mutableStateOf(false) }
    if (editingObligation != null) {
        val original = editingObligation!!
        EditObligationDialog(
            original = original,
            availableAccounts = availableAccounts,
            saving = editingBusy,
            onDismiss = { if (!editingBusy) editingObligation = null },
            onSave = { updated ->
                if (!editingBusy) {
                    editingBusy = true
                    scope.launch {
                        try {
                            val fresh = dao.getObligationById(original.id)
                                ?: error("Este registro ya fue eliminado.")
                            require(fresh.kind == original.kind &&
                                fresh.paidCount == original.paidCount &&
                                fresh.paid == original.paid) {
                                "El estado de pago cambió. Abre nuevamente la edición."
                            }
                            require(updated.installments >= fresh.paidCount) {
                                "El total no puede ser menor que las cuotas pagadas."
                            }
                            dao.updateObligation(fresh.copy(
                                title = updated.title,
                                account = updated.account,
                                amountClp = updated.amountClp,
                                dueDate = updated.dueDate,
                                installments = updated.installments,
                                paid = if (fresh.kind == "Pago")
                                    fresh.paidCount >= updated.installments else fresh.paid
                            ))
                            editingObligation = null
                            Toast.makeText(context, "Cuota o cobro actualizado", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            editingObligation = null
                            Toast.makeText(context, "No se pudo editar: ${e.message}",
                                Toast.LENGTH_LONG).show()
                        } finally { editingBusy = false }
                    }
                }
            }
        )
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 112.dp)) {
        if (kind == "Pago") {
            item {
                CreditLimitsSection(movements, availableAccounts)
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
            }
        }
        item {
            Text(if (kind == "Pago") "Cuotas y pagos pendientes" else "Reembolsos por cobrar", style = MaterialTheme.typography.titleLarge)
            Text(if (kind == "Pago") "Ingresa el valor de CADA cuota y la fecha del primer vencimiento. Para gastos recurrentes, registra cada mes como un pago separado." else "Registra dinero que otra persona debe devolverte.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(title, { title = it }, label = { Text(if (kind == "Pago") "Compra o cuenta" else "Persona y motivo") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text(if (kind == "Pago") "Valor de cada cuota CLP" else "Monto a cobrar CLP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            AccountPicker(account, { account = it }, availableAccounts)
            CalendarDateField(date, { date = it }, if (kind == "Pago") "Primer vencimiento" else "Fecha de cobro")
            if (kind == "Pago") OutlinedTextField(installments, { installments = it.filter(Char::isDigit) }, label = { Text("Cantidad total de cuotas") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
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
                    Text("${AccountCatalog.displayName(o.account)} · ${dateForDisplay(o.dueDate)}")
                    Text(if (o.kind == "Pago") "${(o.installments - o.paidCount).coerceAtLeast(0)} de ${o.installments} cuota(s) pendientes de ${money(o.amountClp)}" else money(o.amountClp))
                    Text(if (o.paid) "Finalizado" else "Pendiente")
                    if (o.kind == "Pago" && !o.paid) {
                        parseDate(o.dueDate)?.let { first ->
                            Text("Próximos vencimientos:", style = MaterialTheme.typography.labelMedium)
                            repeat(minOf((o.installments - o.paidCount).coerceAtLeast(0), 6)) { offset ->
                                Text("${dateForDisplay(first.plusMonths((o.paidCount + offset).toLong()).toString())}: ${money(o.amountClp)}", style = MaterialTheme.typography.bodySmall)
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
                        TextButton(onClick = { editingObligation = o }) { Text("Editar") }
                        TextButton(onClick = { scope.launch { dao.deleteObligation(o) } }) { Text("Eliminar") }
                    }
                }
            }
        }
    }
}
