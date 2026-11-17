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
import java.time.YearMonth
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Solo muestra datos locales de la app, nunca escribe movimientos ni accede a ChatGPT. */
class FinancialSummaryWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) = FinancialSummaryWidgetUpdater.schedule(context)
    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(FinancialSummaryWidgetUpdater.WORK_NAME)
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        FinancialSummaryWidgetUpdater.schedule(context)
        FinancialSummaryWidgetUpdater.refreshAsync(context)
    }
}

object FinancialSummaryWidgetUpdater {
    const val WORK_NAME = "financial_summary_widget_refresh"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val currency = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL"))

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<FinancialSummaryWidgetWorker>(6, TimeUnit.HOURS).build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun refreshAsync(context: Context) {
        scope.launch {
            try { refreshFromDatabase(context.applicationContext) }
            catch (_: Exception) { /* Reintenta al abrir la app o en la próxima actualización. */ }
        }
    }

    suspend fun refreshFromDatabase(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, FinancialSummaryWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val db = Room.databaseBuilder(context, FinanceDatabase::class.java, "finanzas.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).build()
        try {
            render(context, db.financeDao().allMovements(),
                db.financeDao().allObligations(), db.financeDao().allPlannedExpenses())
        } finally {
            db.close()
        }
    }

    fun render(
        context: Context,
        movements: List<Movement>,
        obligations: List<Obligation>,
        plans: List<PlannedExpense>
    ) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, FinancialSummaryWidgetProvider::class.java))
        if (ids.isEmpty()) return

        val today = LocalDate.now()
        val month = YearMonth.from(today)
        val cashAccounts = AccountCatalog.selected(context).filter(AccountCatalog::isCash)
        val dated = movements.filter {
            runCatching { LocalDate.parse(it.date) }.getOrNull()?.isAfter(today) != true
        }
        val opening = context.getSharedPreferences("opening_balances", Context.MODE_PRIVATE)
        val balance = cashAccounts.sumOf { account ->
            opening.getLong(account, 0L) + dated.filter { it.account == account }.sumOf { it.amountClp }
        }
        val monthly = dated.filter {
            runCatching { YearMonth.from(LocalDate.parse(it.date)) }.getOrNull() == month &&
                it.account in cashAccounts
        }
        val expenses = -monthly.filter { it.amountClp < 0L }.sumOf { it.amountClp }
        val projection = MonthlyCashflowProjection.compute(
            today, balance, cashAccounts, movements, obligations, plans
        )
        val showAmounts = PaymentDueWidgetSettings.showDetails(context)
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_financial_summary)
            views.setTextViewText(R.id.finance_widget_month,
                month.month.getDisplayName(java.time.format.TextStyle.FULL, Locale("es", "CL"))
                    .replaceFirstChar { it.uppercase() } + " " + month.year)
            views.setTextViewText(R.id.finance_widget_balance,
                if (showAmounts) currency.format(balance) else "••••••")
            views.setTextViewText(R.id.finance_widget_expenses,
                if (showAmounts) currency.format(expenses) else "••••")
            views.setTextViewText(R.id.finance_widget_pending,
                if (showAmounts) currency.format(projection.totalToPayClp) else "••••")
            views.setTextViewText(R.id.finance_widget_projected,
                if (showAmounts) currency.format(projection.projectedClp) else "••••••")
            views.setTextViewText(R.id.finance_widget_privacy,
                if (showAmounts) "Proyección hasta fin de mes" else "Importes ocultos · activa los detalles en Ajustes")
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            views.setOnClickPendingIntent(R.id.finance_widget_root,
                PendingIntent.getActivity(context, 2051, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            manager.updateAppWidget(id, views)
        }
    }
}

class FinancialSummaryWidgetWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        FinancialSummaryWidgetUpdater.refreshFromDatabase(applicationContext)
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
