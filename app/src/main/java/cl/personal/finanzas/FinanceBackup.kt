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
        val root = JSONObject().put("format", "mis-finanzas").put("version", 1)
        val ms = JSONArray()
        movements.forEach { m ->
            ms.put(JSONObject().put("account",m.account).put("description",m.description)
                .put("amountClp",m.amountClp).put("date",m.date).put("category",m.category))
        }
        val os = JSONArray()
        obligations.forEach { o ->
            os.put(JSONObject().put("title",o.title).put("account",o.account)
                .put("amountClp",o.amountClp).put("dueDate",o.dueDate)
                .put("kind",o.kind).put("installments",o.installments).put("paid",o.paid))
        }
        root.put("movements",ms).put("obligations",os)
        val stream = context.contentResolver.openOutputStream(uri) ?: error("No se pudo abrir el archivo")
        stream.use { it.write(root.toString(2).toByteArray(Charsets.UTF_8)) }
    }

    suspend fun import(context: Context, uri: Uri, db: FinanceDatabase) = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri) ?: error("No se pudo leer el archivo")
        val raw = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        require(raw.length < 10_000_000) { "Respaldo demasiado grande" }
        val root = JSONObject(raw)
        require(root.optString("format") == "mis-finanzas" && root.optInt("version") == 1) { "Formato de respaldo no reconocido" }
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
                installments=o.getInt("installments"),paid=o.getBoolean("paid"))
        }
        // Replace atomically after parsing and validation; never partially restore.
        db.withTransaction {
            db.financeDao().clearMovements()
            db.financeDao().clearObligations()
            movements.forEach { db.financeDao().insertMovement(it) }
            obligations.forEach { db.financeDao().insertObligation(it) }
        }
    }
}
