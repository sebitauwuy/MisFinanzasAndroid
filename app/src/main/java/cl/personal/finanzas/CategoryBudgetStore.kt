package cl.personal.finanzas

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** Límites informativos, sin modificar movimientos ni saldos. */
data class CategoryBudget(val category: String, val limitClp: Long,
                          val alertPercent: Int = 80, val notifications: Boolean = true)

object CategoryBudgetStore {
    private const val FILE = "category_budgets_v1"
    private const val KEY = "budgets"
    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun key(value: String) = value.trim().lowercase(Locale.ROOT)
    fun validate(item: CategoryBudget): Boolean =
        item.category.isNotBlank() && item.category.length <= 70 &&
            item.limitClp in 1L..999_999_999_999L && item.alertPercent in 50..100

    fun all(context: Context): List<CategoryBudget> = runCatching {
        val array = JSONArray(prefs(context).getString(KEY, "[]") ?: "[]")
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            CategoryBudget(item.getString("category"), item.getLong("limitClp"),
                item.optInt("alertPercent", 80), item.optBoolean("notifications", true))
        }.filter(::validate).distinctBy { key(it.category) }
    }.getOrDefault(emptyList())

    private fun json(items: List<CategoryBudget>): JSONArray {
        val array = JSONArray()
        items.forEach { item ->
            array.put(JSONObject().put("category", item.category)
                .put("limitClp", item.limitClp)
                .put("alertPercent", item.alertPercent)
                .put("notifications", item.notifications))
        }
        return array
    }

    fun save(context: Context, item: CategoryBudget, replacing: String? = null): Boolean {
        if (!validate(item)) return false
        val next = all(context).filterNot {
            key(it.category) == key(item.category) ||
                (replacing != null && key(it.category) == key(replacing))
        } + item
        return prefs(context).edit().putString(KEY, json(next).toString()).commit()
    }
    fun delete(context: Context, category: String): Boolean =
        prefs(context).edit().putString(KEY,
            json(all(context).filterNot { key(it.category) == key(category) }).toString()).commit()

    fun export(context: Context): JSONArray = json(all(context))
    fun validateJson(array: JSONArray) {
        require(array.length() <= 100) { "Demasiados presupuestos" }
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val budget = CategoryBudget(item.getString("category"),
                item.getLong("limitClp"), item.getInt("alertPercent"),
                item.getBoolean("notifications"))
            require(validate(budget) && seen.add(key(budget.category))) {
                "Presupuesto inválido o duplicado en el respaldo"
            }
        }
    }
    fun restore(context: Context, array: JSONArray): Boolean {
        validateJson(array)
        return prefs(context).edit().putString(KEY, array.toString()).commit()
    }

    fun spending(movements: List<Movement>, month: YearMonth): Map<String, Long> =
        movements.asSequence().filter { m ->
            m.amountClp < 0L &&
                runCatching { YearMonth.from(LocalDate.parse(m.date)) == month }.getOrDefault(false) &&
                !isInternal(m.category)
        }.groupBy { it.category.trim().ifBlank { "General" } }
            .mapValues { (_, values) -> values.sumOf { -it.amountClp } }
            .filterValues { it > 0L }

    fun spentFor(spending: Map<String, Long>, category: String): Long =
        spending.filterKeys { key(it) == key(category) || key(it).startsWith(key(category) + " / ") }.values.sum()

    private fun isInternal(category: String): Boolean {
        val value = key(category)
        return value.contains("transferencia interna") || value.contains("traspaso interno") ||
            value.contains("pago tarjeta") || value.contains("pago de tarjeta") ||
            value.contains("abono tarjeta")
    }
}

object CategoryBudgetAlerts {
    fun check(context: Context, movements: List<Movement>) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val budgets = CategoryBudgetStore.all(context).filter { it.notifications }
        if (budgets.isEmpty()) return
        val month = YearMonth.now()
        val spending = CategoryBudgetStore.spending(movements, month)
        val prefs = context.getSharedPreferences("category_budget_alerts_v1", Context.MODE_PRIVATE)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            "category_budgets", "Alertas de presupuesto", NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Avisos al acercarte al límite de gasto por categoría" })
        budgets.forEach { budget ->
            val used = CategoryBudgetStore.spentFor(spending, budget.category)
            val fraction = used.toDouble() / budget.limitClp
            val threshold = when {
                fraction >= 1.0 -> 100
                fraction >= budget.alertPercent / 100.0 -> budget.alertPercent
                else -> 0
            }
            if (threshold == 0) return@forEach
            val id = budget.category.trim().lowercase(Locale.ROOT)
            val key = "${month}:${id}:${threshold}"
            if (prefs.getBoolean(key, false)) return@forEach
            val intent = Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val title = if (threshold == 100) "Presupuesto alcanzado: ${budget.category}"
                else "Cerca del límite: ${budget.category}"
            val body = "${threshold}% del límite mensual · $used pesos registrados"
            val notification = NotificationCompat.Builder(context, "category_budgets")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(body)
                .setContentIntent(pending).setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(context).notify(("budget:$key").hashCode(), notification)
            prefs.edit().putBoolean(key, true).apply()
        }
    }
}
