package cl.personal.finanzas

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.NumberFormat
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private val categoryColors = listOf(
    Color(0xFF1246A0), Color(0xFF15854C), Color(0xFFE36B24), Color(0xFF7137AD),
    Color(0xFF168DBD), Color(0xFFB82F37), Color(0xFF008C95), Color(0xFF865B3E)
)

@Composable
private fun SpendingDonut(values: List<Pair<String, Long>>) {
    val total = values.sumOf { it.second }.coerceAtLeast(1L)
    Canvas(modifier = Modifier.size(145.dp)) {
        var start = -90f
        values.forEachIndexed { index, pair ->
            val sweep = (pair.second.toDouble() / total * 360.0).toFloat()
            drawArc(categoryColors[index % categoryColors.size], startAngle = start,
                sweepAngle = sweep, useCenter = false, style = Stroke(26.dp.toPx()))
            start += sweep
        }
    }
}

@Composable
fun CategoryInsightsScreen(movements: List<Movement>, onBack: () -> Unit) {
    val context = LocalContext.current
    val clp = remember { NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL")) }
    val month = YearMonth.now()
    val spending = remember(movements, month) { CategoryBudgetStore.spending(movements, month) }
    val categories = spending.entries.sortedByDescending { it.value }.take(8).map { it.key to it.value }
    val months = (5 downTo 0).map { month.minusMonths(it.toLong()) }
    val history = months.map { CategoryBudgetStore.spending(movements, it).values.sum() }
    val maximum = history.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    var revision by remember { mutableIntStateOf(0) }
    val budgets = remember(revision) { CategoryBudgetStore.all(context).sortedBy { it.category } }
    var editorOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<CategoryBudget?>(null) }
    var name by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf("") }
    var alertPercent by remember { mutableIntStateOf(80) }
    var notifications by remember { mutableStateOf(true) }
    var deleteBudget by remember { mutableStateOf<CategoryBudget?>(null) }
    var feedback by remember { mutableStateOf("") }
    val suggestedCategories = (
        spending.keys + movements.filter { it.amountClp < 0 }.map { it.category } +
        ExpenseCategories.predefined + listOf("Alimentación", "Luz", "Agua", "Internet", "Transporte", "Hogar", "Salud", "Otros")
    ).map { it.trim() }.filter { it.isNotBlank() }.distinct().sorted()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) CategoryBudgetAlerts.check(context, movements)
        else feedback = "Puedes activar notificaciones desde los ajustes del teléfono."
    }

    fun edit(item: CategoryBudget?) {
        editing = item
        name = item?.category.orEmpty()
        limit = item?.limitClp?.toString().orEmpty()
        alertPercent = item?.alertPercent ?: 80
        notifications = item?.notifications ?: true
        editorOpen = true
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Inicio") }
            Text("Análisis y presupuestos", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Gastos por categoría · " + month.monthValue + "/" + month.year,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(clp.format(spending.values.sum()), style = MaterialTheme.typography.headlineMedium)
                    if (categories.isEmpty()) Text("No hay gastos para este mes.")
                    else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SpendingDonut(categories)
                            Spacer(Modifier.width(20.dp))
                            Column(Modifier.weight(1f)) {
                                categories.take(4).forEachIndexed { i, pair ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(9.dp).background(categoryColors[i], RoundedCornerShape(3.dp)))
                                        Spacer(Modifier.width(5.dp))
                                        Column {
                                            Text(pair.first, maxLines = 1,
                                                style = MaterialTheme.typography.labelSmall)
                                            Text(clp.format(pair.second), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                        val maxCategory = categories.maxOf { it.second }.coerceAtLeast(1L)
                        categories.forEachIndexed { i, pair ->
                            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(pair.first, modifier = Modifier.weight(1f), maxLines = 1,
                                    style = MaterialTheme.typography.bodySmall)
                                Text(clp.format(pair.second), style = MaterialTheme.typography.bodySmall)
                            }
                            Box(Modifier.fillMaxWidth().height(8.dp).background(
                                MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(9.dp))) {
                                Box(Modifier.fillMaxWidth((pair.second.toFloat()/maxCategory).coerceIn(0f, 1f))
                                    .fillMaxHeight().background(categoryColors[i % categoryColors.size],
                                        RoundedCornerShape(9.dp)))
                            }
                        }
                    }
                }
            }
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Comparación mensual · últimos 6 meses",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth().height(155.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.Bottom) {
                        history.forEachIndexed { i, value ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.fillMaxWidth().height(110.dp), contentAlignment = Alignment.BottomCenter) {
                                    Box(Modifier.fillMaxWidth(0.72f)
                                        .fillMaxHeight((value.toFloat()/maximum).coerceIn(0.01f,1f))
                                        .background(if (i == 5) Color(0xFF1246A0) else Color(0xFF9AB9DA),
                                            RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)))
                                }
                                Text(months[i].month.getDisplayName(TextStyle.SHORT, Locale.forLanguageTag("es-CL"))
                                    .take(3), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                    Text("Este mes: " + clp.format(history.last()), style = MaterialTheme.typography.bodySmall)
                }
            }
            Card {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Presupuestos por categoría", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { edit(null) }) { Text("+ Nuevo") }
                    }
                    if (budgets.isEmpty()) Text("Configura un límite mensual para cada categoría.",
                        style = MaterialTheme.typography.bodySmall)
                    budgets.forEach { budget ->
                        val amount = CategoryBudgetStore.spentFor(spending, budget.category)
                        val ratio = amount.toDouble() / budget.limitClp
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(budget.category, modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = { edit(budget) }) { Text("Editar") }
                            TextButton(onClick = { deleteBudget = budget }) { Text("×") }
                        }
                        Text(clp.format(amount) + " / " + clp.format(budget.limitClp) +
                            " · " + (ratio * 100).toInt() + "%",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (ratio >= 1.0) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.fillMaxWidth().height(10.dp).background(
                            MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))) {
                            Box(Modifier.fillMaxWidth(ratio.toFloat().coerceIn(0f,1f)).fillMaxHeight()
                                .background(if (ratio >= 1) Color(0xFFB82F37)
                                    else if (ratio >= budget.alertPercent/100.0) Color(0xFFE36B24)
                                    else Color(0xFF15854C), RoundedCornerShape(8.dp)))
                        }
                        Text(if (budget.notifications) "Aviso desde " + budget.alertPercent + "% del límite"
                            else "Alertas desactivadas", style = MaterialTheme.typography.labelSmall)
                        HorizontalDivider()
                    }
                    if (feedback.isNotBlank()) Text(feedback, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Incluye gastos registrados de cuentas y tarjetas de crédito. Excluye transferencias internas y pagos de tarjeta identificados con esas categorías. Los movimientos duplicados podrían aparecer dos veces.",
                style = MaterialTheme.typography.bodySmall)
        }
    }

    if (editorOpen) {
        val amount = limit.toLongOrNull()
        AlertDialog(
            onDismissRequest = { editorOpen = false },
            title = { Text(if (editing == null) "Nuevo presupuesto" else "Editar presupuesto") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedTextField(name, { name = it.take(70) },
                        label = { Text("Categoría") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    if (editing == null) {
                        suggestedCategories.take(10).chunked(2).forEach { options ->
                            Row {
                                options.forEach { option ->
                                    TextButton(onClick = { name = option }, modifier = Modifier.weight(1f)) {
                                        Text(option, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                    OutlinedTextField(limit, { limit = it.filter(Char::isDigit).take(12) },
                        label = { Text("Límite mensual (CLP)") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true)
                    Text("Avisar desde " + alertPercent + "%")
                    Slider(value = alertPercent.toFloat(), valueRange = 50f..100f, steps = 49,
                        onValueChange = { alertPercent = kotlin.math.round(it).toInt() })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Notificaciones de presupuesto", modifier = Modifier.weight(1f))
                        Switch(checked = notifications, onCheckedChange = { notifications = it })
                    }
                    Text("El límite es informativo: no reserva ni descuenta dinero.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = CategoryBudgetStore.validate(
                    CategoryBudget(name.trim(), amount ?: 0L, alertPercent, notifications)),
                    onClick = {
                        val ok = CategoryBudgetStore.save(context,
                            CategoryBudget(name.trim(), amount!!, alertPercent, notifications), editing?.category)
                        if (ok) {
                            revision++
                            editorOpen = false
                            feedback = "Presupuesto guardado."
                            if (notifications && Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                                    != PackageManager.PERMISSION_GRANTED)
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else CategoryBudgetAlerts.check(context, movements)
                        } else feedback = "No fue posible guardar el presupuesto."
                    }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { editorOpen = false }) { Text("Cancelar") } }
        )
    }
    if (deleteBudget != null) {
        val item = deleteBudget!!
        AlertDialog(onDismissRequest = { deleteBudget = null },
            title = { Text("Eliminar presupuesto") },
            text = { Text("Se eliminará el límite de " + item.category + ", pero no sus movimientos.") },
            confirmButton = { TextButton(onClick = {
                if (CategoryBudgetStore.delete(context, item.category)) revision++
                deleteBudget = null
            }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { deleteBudget = null }) { Text("Cancelar") } })
    }
}
