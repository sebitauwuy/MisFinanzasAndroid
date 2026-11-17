package cl.personal.finanzas

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Widget informativo para pagos que vencen hoy.
 * No desbloquea datos, no registra pagos y no altera saldos.
 * La persona debe añadir el widget desde la pantalla de inicio de Android.
 */
object PaymentDueWidgetSettings {
    private const val FILE = "widget_finance_privacy"
    fun showDetails(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("show_details", false)
    fun setShowDetails(context: Context, enabled: Boolean): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putBoolean("show_details", enabled).commit()
}

class PaymentDueWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) {
        PaymentDueWidgetUpdater.schedule(context)
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PaymentDueWidgetUpdater.WORK_NAME)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        PaymentDueWidgetUpdater.schedule(context)
        PaymentDueWidgetUpdater.refreshAsync(context.applicationContext)
    }
}

object PaymentDueWidgetUpdater {
    const val WORK_NAME = "payment_due_widget_refresh"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun schedule(context: Context) {
        val work = PeriodicWorkRequestBuilder<PaymentDueWidgetWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, work)
    }

    fun refreshAsync(context: Context) {
        scope.launch { refreshFromDatabase(context.applicationContext) }
    }

    suspend fun refreshFromDatabase(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PaymentDueWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val db = Room.databaseBuilder(context, FinanceDatabase::class.java, "finanzas.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()
        try {
            render(context, db.financeDao().allObligations(), db.financeDao().allPlannedExpenses())
        } finally {
            db.close()
        }
    }

    fun render(context: Context, obligations: List<Obligation>, planned: List<PlannedExpense>) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, PaymentDueWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val today = LocalDate.now()
        val titles = mutableListOf<Pair<String, Long>>()
        planned.filter { it.active && it.nextDueDate == today.toString() }.forEach { plan ->
            titles.add(plan.icon + " " + plan.title to plan.estimatedAmountClp)
        }
        obligations.filter { it.kind == "Pago" && !it.paid }.forEach { obligation ->
            val first = runCatching { LocalDate.parse(obligation.dueDate) }.getOrNull()
            if (first != null && (obligation.paidCount until obligation.installments).any {
                    first.plusMonths(it.toLong()) == today
                }) {
                titles.add(obligation.title to obligation.amountClp)
            }
        }
        val clp = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL"))
        val total = titles.sumOf { it.second }
        val showDetails = PaymentDueWidgetSettings.showDetails(context)
        val summary = if (titles.isEmpty()) "Sin pagos programados para hoy"
            else if (showDetails) titles.size.toString() + " pago(s) · " + clp.format(total)
            else titles.size.toString() + " pago(s) vencen hoy"
        val detail = if (titles.isEmpty()) "Todo al día según tus registros."
            else if (!showDetails) "Toca para revisar los pagos en la app."
            else titles.take(2).joinToString("  •  ") { it.first + ": " + clp.format(it.second) } +
                (if (titles.size > 2) "  •  Y " + (titles.size - 2) + " más" else "")
        ids.forEach { widgetId ->
            val view = RemoteViews(context.packageName, R.layout.widget_due_payments)
            view.setTextViewText(R.id.widget_summary, summary)
            view.setTextViewText(R.id.widget_detail, detail)
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pending = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            view.setOnClickPendingIntent(R.id.widget_root, pending)
            manager.updateAppWidget(widgetId, view)
        }
    }
}

class PaymentDueWidgetWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        PaymentDueWidgetUpdater.refreshFromDatabase(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
