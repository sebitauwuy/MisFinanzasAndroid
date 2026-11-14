package cl.personal.finanzas

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.room.Room
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Dos configuraciones independientes: reembolsos por cobrar y pagos/cuotas pendientes. */
data class FinanceReminderConfig(val enabled: Boolean, val daysBefore: Int, val time: LocalTime)

object FinanceReminderSettings {
    private const val PREFS = "finance_reminders"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun prefix(kind: String) = if (kind == "Reembolso") "cobros" else "creditos"

    fun load(context: Context, kind: String): FinanceReminderConfig {
        val prefix = prefix(kind)
        val prefs = prefs(context)
        val time = runCatching { LocalTime.parse(prefs.getString("${prefix}_time", "15:00")) }
            .getOrDefault(LocalTime.of(15, 0))
        return FinanceReminderConfig(
            enabled = prefs.getBoolean("${prefix}_enabled", false),
            daysBefore = prefs.getInt("${prefix}_days", 1).coerceIn(0, 30),
            time = time
        )
    }

    fun save(context: Context, kind: String, config: FinanceReminderConfig): Boolean {
        if (kind != "Pago" && kind != "Reembolso") return false
        if (config.daysBefore !in 0..30) return false
        val prefix = prefix(kind)
        return prefs(context).edit()
            .putBoolean("${prefix}_enabled", config.enabled)
            .putInt("${prefix}_days", config.daysBefore)
            .putString("${prefix}_time", config.time.toString())
            .commit()
    }
}

/** WorkManager persiste los recordatorios y los reprograma tras reinicios.
 * El sistema puede aplazar la ejecución por batería o ahorro de energía.
 */
object FinanceReminderScheduler {
    private const val TAG = "misfinanzas_recordatorios"
    private val mutex = Mutex()

    suspend fun reschedule(context: Context, obligations: List<Obligation>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val manager = WorkManager.getInstance(context.applicationContext)
            // Esperar la cancelación antes de volver a programar para que los trabajos
            // de configuraciones anteriores no se disparen.
            manager.cancelAllWorkByTag(TAG).result.get()
            val now = ZonedDateTime.now()
            val jobs = mutableListOf<Pair<Long, OneTimeWorkRequest>>()
            for (obligation in obligations) {
                if (obligation.id <= 0L || obligation.paid || obligation.kind !in setOf("Pago", "Reembolso")) continue
                val firstDue = runCatching { LocalDate.parse(obligation.dueDate) }.getOrNull() ?: continue
                val config = FinanceReminderSettings.load(context, obligation.kind)
                if (!config.enabled) continue
                val indices = if (obligation.kind == "Reembolso") listOf(0)
                    else (obligation.paidCount.coerceAtLeast(0) until obligation.installments.coerceAtMost(120)).toList()
                for (index in indices) {
                    val due = if (obligation.kind == "Pago") firstDue.plusMonths(index.toLong()) else firstDue
                    val trigger = due.minusDays(config.daysBefore.toLong()).atTime(config.time)
                        .atZone(ZoneId.systemDefault())
                    if (!trigger.isAfter(now) || trigger.isAfter(now.plusYears(2))) continue
                    val epochMs = trigger.toInstant().toEpochMilli()
                    val delay = Duration.between(now.toInstant(), trigger.toInstant()).toMillis()
                    val data = workDataOf(
                        "obligation_id" to obligation.id,
                        "installment" to index,
                        "reminder_kind" to obligation.kind,
                        "trigger_ms" to epochMs
                    )
                    val work = OneTimeWorkRequestBuilder<FinanceReminderWorker>()
                        .setInputData(data)
                        .setInitialDelay(delay.coerceAtLeast(1000), TimeUnit.MILLISECONDS)
                        .addTag(TAG)
                        .build()
                    jobs.add(epochMs to work)
                }
            }
            // Priorizar próximos vencimientos. No crear miles de trabajos.
            jobs.sortedBy { it.first }.take(250).forEach { (_, work) -> manager.enqueue(work) }
        }
    }
}

class FinanceReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong("obligation_id", -1L)
        val index = inputData.getInt("installment", -1)
        val kind = inputData.getString("reminder_kind") ?: return Result.success()
        val plannedMs = inputData.getLong("trigger_ms", -1L)
        if (id <= 0L || index < 0 || plannedMs <= 0L) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return Result.success()
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return Result.success()

        val db = Room.databaseBuilder(applicationContext, FinanceDatabase::class.java, "finanzas.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
        val obligation = try { db.financeDao().getObligationById(id) } finally { db.close() }
        if (obligation == null || obligation.paid || obligation.kind != kind ||
            (kind == "Pago" && (index < obligation.paidCount || index >= obligation.installments)) ||
            (kind == "Reembolso" && index != 0)) return Result.success()
        val config = FinanceReminderSettings.load(applicationContext, kind)
        if (!config.enabled) return Result.success()
        val due = runCatching { LocalDate.parse(obligation.dueDate) }.getOrNull()
            ?.plusMonths(if (kind == "Pago") index.toLong() else 0L) ?: return Result.success()
        val trigger = due.minusDays(config.daysBefore.toLong()).atTime(config.time)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (trigger != plannedMs) return Result.success() // Aviso antiguo tras cambio de configuración.

        val notificationManager = applicationContext.getSystemService(NotificationManager::class.java)
        val channelId = "finance_reminders"
        notificationManager.createNotificationChannel(NotificationChannel(
            channelId, "Recordatorios financieros", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Avisos de cobros pendientes y cuotas por vencer" })
        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(applicationContext, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val heading = if (kind == "Reembolso") "Cobro pendiente" else "Cuota o pago pendiente"
        val clp = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL"))
        val detail = "${obligation.title} · ${clp.format(obligation.amountClp)} · Vence ${due.dayOfMonth}/${due.monthValue}"
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(heading)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        @Suppress("MissingPermission")
        NotificationManagerCompat.from(applicationContext)
            .notify((id.toString() + ":" + kind + ":" + index).hashCode(), notification)
        return Result.success()
    }
}

@Composable
fun FinanceReminderSettingsSection(obligations: List<Obligation>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val initialCobros = remember { FinanceReminderSettings.load(context, "Reembolso") }
    val initialCreditos = remember { FinanceReminderSettings.load(context, "Pago") }
    var cobrosOn by remember { mutableStateOf(initialCobros.enabled) }
    var creditosOn by remember { mutableStateOf(initialCreditos.enabled) }
    var cobrosDays by remember { mutableStateOf(initialCobros.daysBefore.toString()) }
    var creditosDays by remember { mutableStateOf(initialCreditos.daysBefore.toString()) }
    var cobrosTime by remember { mutableStateOf(initialCobros.time.toString().take(5)) }
    var creditosTime by remember { mutableStateOf(initialCreditos.time.toString().take(5)) }
    var feedback by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    fun persist() {
        val cDays = cobrosDays.toIntOrNull()
        val pDays = creditosDays.toIntOrNull()
        val cTime = runCatching { LocalTime.parse(cobrosTime) }.getOrNull()
        val pTime = runCatching { LocalTime.parse(creditosTime) }.getOrNull()
        if (cDays == null || pDays == null || cDays !in 0..30 || pDays !in 0..30 ||
            cTime == null || pTime == null) {
            feedback = "Usa días de anticipación entre 0 y 30 y horarios HH:mm (por ejemplo 15:00)."
            return
        }
        val ok1 = FinanceReminderSettings.save(context, "Reembolso", FinanceReminderConfig(cobrosOn, cDays, cTime))
        val ok2 = FinanceReminderSettings.save(context, "Pago", FinanceReminderConfig(creditosOn, pDays, pTime))
        if (!ok1 || !ok2) {
            feedback = "No se pudo guardar la configuración."
            return
        }
        saving = true
        scope.launch {
            try {
                FinanceReminderScheduler.reschedule(context, obligations)
                feedback = "Recordatorios guardados y programados para tus cobros y cuotas pendientes."
            } catch (e: Exception) {
                feedback = "Configuración guardada, pero no se pudieron programar los avisos: ${e.message}"
            } finally { saving = false }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) persist()
        else feedback = "Debes permitir notificaciones de Mis Finanzas para recibir avisos."
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Recordatorios", style = MaterialTheme.typography.titleLarge)
        Text("Configura Cobros y Cuotas/créditos de manera independiente. Se avisará sobre registros pendientes con fecha de vencimiento.",
            style = MaterialTheme.typography.bodySmall)
        listOf(
            Triple("Cobros pendientes", cobrosOn, "Reembolso"),
            Triple("Cuotas y créditos", creditosOn, "Pago")
        ).forEach { (label, enabled, kind) ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium)
                    Row {
                        Text(if (enabled) "Activado" else "Desactivado", modifier = Modifier.weight(1f))
                        Switch(checked = enabled, onCheckedChange = {
                            if (kind == "Reembolso") cobrosOn = it else creditosOn = it
                        })
                    }
                    OutlinedTextField(
                        value = if (kind == "Reembolso") cobrosDays else creditosDays,
                        onValueChange = {
                            val digits = it.filter(Char::isDigit).take(2)
                            if (kind == "Reembolso") cobrosDays = digits else creditosDays = digits
                        },
                        label = { Text("Días antes (0 a 30)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = if (kind == "Reembolso") cobrosTime else creditosTime,
                        onValueChange = {
                            if (kind == "Reembolso") cobrosTime = it.take(5) else creditosTime = it.take(5)
                        },
                        label = { Text("Hora del aviso (HH:mm)") }, placeholder = { Text("15:00") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
            }
        }
        Button(enabled = !saving, modifier = Modifier.fillMaxWidth(), onClick = {
            val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                (cobrosOn || creditosOn) &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            if (needsPermission) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else persist()
        }) { Text(if (saving) "Programando…" else "Guardar recordatorios") }
        if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodySmall)
        if ((cobrosOn || creditosOn) && !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Text("Las notificaciones están desactivadas en Android. Actívalas en Ajustes del sistema para recibir avisos.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Text("Los avisos se programan localmente. Android puede retrasarlos por ahorro de batería; la hora elegida es orientativa, no garantizada al minuto. No consulta el banco para confirmar pagos.",
            style = MaterialTheme.typography.bodySmall)
    }
}
