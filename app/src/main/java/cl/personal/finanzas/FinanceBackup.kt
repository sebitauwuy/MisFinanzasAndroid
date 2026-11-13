package cl.personal.finanzas

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Portable JSON backup. User chooses destination using Android's Storage Access Framework. */
object FinanceBackup {
    suspend fun export(context: Context, uri: Uri, db: FinanceDatabase) = withContext(Dispatchers.IO) {
        val movements = db.financeDao().allMovements()
        val obligations = db.financeDao().allObligations()
        val root = JSONObject().put("format", "mis-finanzas").put("version", 3)
        val ms = JSONArray()
        movements.forEach { m ->
            ms.put(JSONObject().put("account",m.account).put("description",m.description)
                .put("amountClp",m.amountClp).put("date",m.date).put("category",m.category))
        }
        val os = JSONArray()
        obligations.forEach { o ->
            os.put(JSONObject().put("title",o.title).put("account",o.account)
                .put("amountClp",o.amountClp).put("dueDate",o.dueDate)
                .put("kind",o.kind).put("installments",o.installments).put("paid",o.paid).put("paidCount",o.paidCount))
        }
        root.put("movements",ms).put("obligations",os)
        val balances = context.getSharedPreferences("opening_balances", 0)
        val opening = JSONObject()
        AccountCatalog.names.forEach {
            opening.put(it, balances.getLong(it, 0L))
        }
        root.put("openingBalances", opening)
        val selected = JSONArray()
        AccountCatalog.selected(context).forEach { selected.put(it) }
        root.put("visibleAccounts", selected)
        val cycles = context.getSharedPreferences("billing_cycles", 0)
        val billing = JSONObject()
        listOf("chileClose", "chileDue", "cmrClose", "cmrDue").forEach {
            if (cycles.contains(it)) billing.put(it, cycles.getInt(it, 1))
        }
        root.put("billingCycles", billing)
        val stream = context.contentResolver.openOutputStream(uri) ?: error("No se pudo abrir el archivo")
        stream.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
    }

    suspend fun import(context: Context, uri: Uri, db: FinanceDatabase) = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri) ?: error("No se pudo leer el archivo")
        val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        require(raw.length < 10_000_000) { "Respaldo demasiado grande" }
        val root = JSONObject(raw)
        require(root.optString("format") == "mis-finanzas" && root.optInt("version") in 1..3) { "Formato de respaldo no reconocido" }
        val ms = root.getJSONArray("movements")
        val os = root.getJSONArray("obligations")
        require(ms.length() <= 10000 && os.length() <= 10000) { "Demasiados registros" }
        val movements = (0 until ms.length()).map { i ->
            val m=ms.getJSONObject(i)
            Movement(account=m.getString("account"),description=m.getString("description"),
                amountClp=m.getLong("amountClp"),date=m.getString("date"),category=m.getString("category"))
        }
        val obligations = (0 until os.length()).map { i ->
            val o=os.getJSONObject(i)
            Obligation(title=o.getString("title"),account=o.getString("account"),
                amountClp=o.getLong("amountClp"),dueDate=o.getString("dueDate"),kind=o.getString("kind"),
                installments=o.getInt("installments"),paid=o.getBoolean("paid"),paidCount=o.optInt("paidCount", if (o.getBoolean("paid")) o.getInt("installments") else 0))
        }
        val opening = root.optJSONObject("openingBalances")
        val billing = root.optJSONObject("billingCycles")
        val savedVisibility = if (root.optInt("version") == 3) {
            val selected = root.getJSONArray("visibleAccounts")
            val list = (0 until selected.length()).map { selected.getString(it) }
            require(list.isNotEmpty() && list.all { it in AccountCatalog.names }) {
                "La selección de cuentas del respaldo no es válida"
            }
            list
        } else null
        val balances = AccountCatalog.names.associateWith {
            opening?.optLong(it, 0L) ?: 0L
        }
        val cycleValues = listOf("chileClose", "chileDue", "cmrClose", "cmrDue").mapNotNull { key ->
            if (billing != null && billing.has(key)) {
                val day = billing.getInt(key)
                require(day in 1..28) { "Día de facturación inválido" }
                key to day
            } else null
        }
        // Replace database atomically after validating the complete backup.
        db.withTransaction {
            db.financeDao().clearMovements()
            db.financeDao().clearObligations()
            movements.forEach { db.financeDao().insertMovement(it) }
            obligations.forEach { db.financeDao().insertObligation(it) }
        }
        if (root.optInt("version") >= 2) {
            val editor = context.getSharedPreferences("opening_balances", 0).edit()
            balances.forEach { (key, value) -> editor.putLong(key, value) }
            editor.commit()
            val cyclesEditor = context.getSharedPreferences("billing_cycles", 0).edit()
            cycleValues.forEach { (key, value) -> cyclesEditor.putInt(key, value) }
            cyclesEditor.commit()
        }
        if (savedVisibility != null) {
            require(AccountCatalog.save(context, savedVisibility)) {
                "No se pudo recuperar la selección de cuentas"
            }
        }
    }
}
