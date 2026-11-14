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
        val mail = db.financeDao().allMailCandidates()
        val root = JSONObject().put("format", "mis-finanzas").put("version", 8)
        val ms = JSONArray()
        movements.forEach { m ->
            ms.put(JSONObject().put("id",m.id).put("account",m.account).put("description",m.description)
                .put("amountClp",m.amountClp).put("date",m.date).put("category",m.category))
        }
        val os = JSONArray()
        obligations.forEach { o ->
            os.put(JSONObject().put("title",o.title).put("account",o.account)
                .put("amountClp",o.amountClp).put("dueDate",o.dueDate)
                .put("kind",o.kind).put("installments",o.installments).put("paid",o.paid).put("paidCount",o.paidCount))
        }
        root.put("movements",ms).put("obligations",os)
        val plans = JSONArray()
        db.financeDao().allPlannedExpenses().forEach { plan ->
            plans.put(JSONObject()
                .put("id", plan.id).put("title", plan.title).put("icon", plan.icon)
                .put("estimatedAmountClp", plan.estimatedAmountClp).put("isVariable", plan.isVariable)
                .put("frequency", plan.frequency).put("nextDueDate", plan.nextDueDate)
                .put("anchorDay", plan.anchorDay).put("defaultAccount", plan.defaultAccount)
                .put("active", plan.active).put("lastPaidDate", plan.lastPaidDate)
                .put("lastPaidAmountClp", plan.lastPaidAmountClp)
                .put("lastPaidWithoutMovement", plan.lastPaidWithoutMovement)
                .put("lastPaymentMovementId", plan.lastPaymentMovementId))
        }
        root.put("plannedExpenses", plans)
        val emails = JSONArray()
        mail.forEach { m ->
            emails.put(JSONObject().put("sourceId", m.sourceId).put("operationDate", m.operationDate)
                .put("description", m.description).put("amountClp", m.amountClp)
                .put("account", m.account).put("category", m.category)
                .put("status", m.status).put("note", m.note))
        }
        root.put("mailCandidates", emails)
        root.put("creditLimits", CreditLimitStore.export(context))
        root.put("reminderSettings", FinanceReminderSettings.export(context))
        val dashboardPrefs = context.getSharedPreferences("dashboard_account_cards", Context.MODE_PRIVATE)
        val dashboardStyle = JSONObject()
        dashboardPrefs.getString("manual_order", null)?.let { dashboardStyle.put("manual_order", it) }
        AccountCatalog.names.filter(AccountCatalog::isCash).forEach { account ->
            dashboardPrefs.getString("color_$account", null)?.let { dashboardStyle.put("color_$account", it) }
        }
        root.put("dashboardCardStyle", dashboardStyle)
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
        require(root.optString("format") == "mis-finanzas" && root.optInt("version") in 1..8) { "Formato de respaldo no reconocido" }
        val ms = root.getJSONArray("movements")
        val os = root.getJSONArray("obligations")
        require(ms.length() <= 10000 && os.length() <= 10000) { "Demasiados registros" }
        val movements = (0 until ms.length()).map { i ->
            val m=ms.getJSONObject(i)
            Movement(id=if (root.optInt("version") >= 5) m.optLong("id", 0L) else 0L,
                account=m.getString("account"),description=m.getString("description"),
                amountClp=m.getLong("amountClp"),date=m.getString("date"),category=m.getString("category"))
        }
        if (root.optInt("version") >= 5) {
            require(movements.all { it.id > 0L } &&
                movements.map { it.id }.distinct().size == movements.size) {
                "Identificadores de movimientos inválidos en el respaldo"
            }
        }
        val obligations = (0 until os.length()).map { i ->
            val o=os.getJSONObject(i)
            Obligation(title=o.getString("title"),account=o.getString("account"),
                amountClp=o.getLong("amountClp"),dueDate=o.getString("dueDate"),kind=o.getString("kind"),
                installments=o.getInt("installments"),paid=o.getBoolean("paid"),paidCount=o.optInt("paidCount", if (o.getBoolean("paid")) o.getInt("installments") else 0))
        }
        val plannedArray = root.optJSONArray("plannedExpenses")
        if (root.optInt("version") >= 8) require(plannedArray != null) { "Faltan gastos previstos en el respaldo" }
        require(plannedArray == null || plannedArray.length() <= 1000) { "Demasiados gastos previstos" }
        val planned = plannedArray?.let { array ->
            (0 until array.length()).map { index ->
                val p = array.getJSONObject(index)
                PlannedExpense(
                    id = p.getLong("id"), title = p.getString("title"),
                    icon = p.getString("icon"), estimatedAmountClp = p.getLong("estimatedAmountClp"),
                    isVariable = p.getBoolean("isVariable"), frequency = p.getString("frequency"),
                    nextDueDate = p.getString("nextDueDate"), anchorDay = p.getInt("anchorDay"),
                    defaultAccount = p.getString("defaultAccount"), active = p.getBoolean("active"),
                    lastPaidDate = p.optString("lastPaidDate"),
                    lastPaidAmountClp = p.optLong("lastPaidAmountClp", 0L),
                    lastPaidWithoutMovement = p.optBoolean("lastPaidWithoutMovement", false),
                    lastPaymentMovementId = p.optLong("lastPaymentMovementId", 0L)
                ).also { require(it.id > 0L && PlannedExpenseRules.valid(it)) {
                    "Gasto previsto inválido en el respaldo"
                } }
            }.also { list ->
                require(list.map { it.id }.distinct().size == list.size) {
                    "Hay gastos previstos duplicados en el respaldo"
                }
                require(list.all { it.lastPaidDate.isBlank() ||
                    runCatching { java.time.LocalDate.parse(it.lastPaidDate) }.isSuccess }) {
                    "Fecha de pago inválida en el respaldo"
                }
            }
        }
        val opening = root.optJSONObject("openingBalances")
        val billing = root.optJSONObject("billingCycles")
        val savedVisibility = if (root.optInt("version") >= 3) {
            val selected = root.getJSONArray("visibleAccounts")
            val list = (0 until selected.length()).map { selected.getString(it) }
            require(list.isNotEmpty() && list.all { it in AccountCatalog.names }) {
                "La selección de cuentas del respaldo no es válida"
            }
            list
        } else null
        val emails = root.optJSONArray("mailCandidates")
        require(emails == null || emails.length() <= 1500) { "Demasiados avisos bancarios" }
        val candidates = if (root.optInt("version") >= 4 && emails != null) {
            (0 until emails.length()).map { index ->
                val m = emails.getJSONObject(index)
                val status = m.getString("status")
                require(status in listOf("PENDIENTE", "POSIBLE_DUPLICADO", "IMPORTADO", "OMITIDO")) {
                    "Estado de aviso inválido"
                }
                MailCandidate(
                    sourceId = m.getString("sourceId"), operationDate = m.getString("operationDate"),
                    description = m.getString("description"), amountClp = m.getLong("amountClp"),
                    account = m.getString("account"), category = m.getString("category"),
                    status = status, note = m.optString("note")
                )
            }
        } else emptyList()
        val dashboardStyle = root.optJSONObject("dashboardCardStyle")
        if (root.optInt("version") >= 6) require(dashboardStyle != null) { "Falta configuración visual del respaldo" }
        if (dashboardStyle != null) {
            if (dashboardStyle.has("manual_order")) {
                val rawOrder = dashboardStyle.getString("manual_order")
                val array = JSONArray(rawOrder)
                val names = (0 until array.length()).map { array.getString(it) }
                require(names.distinct().size == names.size &&
                    names.all { it in AccountCatalog.names && AccountCatalog.isCash(it) }) {
                    "Orden de bancos inválido en respaldo"
                }
            }
            AccountCatalog.names.filter(AccountCatalog::isCash).forEach { account ->
                if (dashboardStyle.has("color_$account")) {
                    require(Regex("""[0-9a-fA-F]{6}""").matches(dashboardStyle.getString("color_$account"))) {
                        "Color de banco inválido en respaldo"
                    }
                }
            }
        }
        val reminders = root.optJSONObject("reminderSettings")
        if (root.optInt("version") >= 7) require(reminders != null) { "Faltan recordatorios en el respaldo" }
        if (reminders != null) FinanceReminderSettings.validate(reminders)
        val creditLimits = root.optJSONObject("creditLimits")
        if (creditLimits != null) CreditLimitStore.validate(creditLimits)
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
            db.financeDao().clearMailCandidates()
            // Respaldos antiguos no contenían esta sección: conservar planes existentes.
            if (planned != null) db.financeDao().clearPlannedExpenses()
            candidates.forEach { db.financeDao().insertMailCandidate(it) }
            movements.forEach { db.financeDao().insertMovement(it) }
            obligations.forEach { db.financeDao().insertObligation(it) }
            planned?.forEach { db.financeDao().insertPlannedExpense(it) }
        }
        if (root.optInt("version") >= 2) {
            val editor = context.getSharedPreferences("opening_balances", 0).edit()
            balances.forEach { (key, value) -> editor.putLong(key, value) }
            editor.commit()
            val cyclesEditor = context.getSharedPreferences("billing_cycles", 0).edit()
            cycleValues.forEach { (key, value) -> cyclesEditor.putInt(key, value) }
            cyclesEditor.commit()
        }
        if (creditLimits != null) CreditLimitStore.restore(context, creditLimits)
        if (reminders != null) FinanceReminderSettings.restore(context, reminders)
        if (dashboardStyle != null) {
            val editor = context.getSharedPreferences("dashboard_account_cards", Context.MODE_PRIVATE).edit().clear()
            if (dashboardStyle.has("manual_order")) editor.putString("manual_order", dashboardStyle.getString("manual_order"))
            AccountCatalog.names.filter(AccountCatalog::isCash).forEach { account ->
                if (dashboardStyle.has("color_$account")) editor.putString("color_$account", dashboardStyle.getString("color_$account"))
            }
            require(editor.commit()) { "No se pudo recuperar el orden y colores bancarios" }
        }
        if (savedVisibility != null) {
            require(AccountCatalog.save(context, savedVisibility)) {
                "No se pudo recuperar la selección de cuentas"
            }
        }
    }
}
