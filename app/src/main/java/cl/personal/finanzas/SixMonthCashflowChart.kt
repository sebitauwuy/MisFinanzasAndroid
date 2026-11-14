package cl.personal.finanzas

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.ColumnCartesianLayerModel
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private val MONTH_LOCALE = Locale.forLanguageTag("es-CL")
private data class MonthlyCashflow(
    val month: YearMonth, val income: Long, val spending: Long
) {
    val net: Long get() = income - spending
    val label: String get() = month.atDay(1)
        .format(DateTimeFormatter.ofPattern("MMM", MONTH_LOCALE))
        .removeSuffix(".").replaceFirstChar { it.uppercase(MONTH_LOCALE) }.take(3)
}

private fun netLabel(amount: Long): String {
    val value = abs(amount.toDouble())
    val sign = when {
        amount < 0L -> "−"
        amount > 0L -> "+"
        else -> ""
    }
    val text = when {
        value >= 1_000_000 -> String.format(MONTH_LOCALE, "%.1fM", value / 1_000_000)
        value >= 1_000 -> String.format(MONTH_LOCALE, "%.0fk", value / 1_000)
        else -> value.toLong().toString()
    }
    return if (amount == 0L) "$0" else "$sign$$text"
}

/** Gráfico no destructivo. Usa los mismos movimientos de cuentas con saldo que Inicio. */
@Composable
fun SixMonthCashflowChart(
    movements: List<Movement>,
    cashAccounts: Collection<String>,
    endMonth: YearMonth = YearMonth.now(),
    modifier: Modifier = Modifier
) {
    val today = LocalDate.now()
    val accounts = remember(cashAccounts) { cashAccounts.toSet() }
    val months = remember(movements, accounts, endMonth, today) {
        val first = endMonth.minusMonths(5)
        val grouped = movements.asSequence().filter { it.account in accounts }
            .mapNotNull { item ->
                val date = runCatching { LocalDate.parse(item.date) }.getOrNull()
                    ?: return@mapNotNull null
                val ym = YearMonth.from(date)
                if (date.isAfter(today) || ym < first || ym > endMonth)
                    return@mapNotNull null
                ym to item.amountClp
            }.groupBy({ it.first }, { it.second })
        (0L..5L).map { offset ->
            val ym = first.plusMonths(offset)
            val amounts = grouped[ym].orEmpty()
            MonthlyCashflow(
                ym,
                income = amounts.filter { it > 0L }.sum(),
                spending = amounts.filter { it < 0L }.sumOf { -it }
            )
        }
    }

    val money = remember { NumberFormat.getCurrencyInstance(MONTH_LOCALE) }
    val meanIncome = months.sumOf { it.income } / 6L
    val meanSpending = months.sumOf { it.spending } / 6L
    // Una sola escala para los 12 valores: NO se normaliza mes por mes.
    val top = max(1.0, months.maxOf { max(it.income, it.spending) }.toDouble() * 1.12)
    val range = remember(top) {
        object : CartesianLayerRangeProvider {
            override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = 0.0
            override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = top
        }
    }

    val modelProducer = remember { CartesianChartModelProducer() }
    LaunchedEffect(months) {
        modelProducer.runTransaction {
            columnModel {
                series(months.map { it.income.toDouble() })
                series(months.map { it.spending.toDouble() })
            }
        }
    }

    val mint = Color(0xFF19B391)
    val coral = Color(0xFFEF7465)
    val mintPast = Color(0xFFB0E9D7)
    val coralPast = Color(0xFFF8C8C0)
    val dark = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    var chosenIndex by remember { mutableStateOf<Int?>(null) }
    val cardShape = RoundedCornerShape(22.dp)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Ingresos y gastos · últimos 6 meses",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = dark
            )
            Text(
                "Comparación mensual con escala compartida · toca un mes",
                style = MaterialTheme.typography.bodySmall,
                color = muted
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AverageMetric("Promedio ingresos", money.format(meanIncome), mint,
                    Modifier.weight(1f))
                AverageMetric("Promedio gastos", money.format(meanSpending), coral,
                    Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                CashflowLegend(mint, "Ingresos")
                CashflowLegend(coral, "Gastos")
            }

            ProvideVicoTheme(
                rememberM3VicoTheme(
                    columnCartesianLayerColors = listOf(mint, coral),
                    lineColor = grid,
                    textColor = muted
                )
            ) {
                val topRounded = RoundedCornerShape(
                    topStart = 5.dp, topEnd = 5.dp,
                    bottomStart = 0.dp, bottomEnd = 0.dp
                )
                val incomeOld = rememberLineComponent(Fill(mintPast), 12.dp, topRounded)
                val expensesOld = rememberLineComponent(Fill(coralPast), 12.dp, topRounded)
                val incomeNow = rememberLineComponent(Fill(mint), 12.dp, topRounded)
                val expensesNow = rememberLineComponent(Fill(coral), 12.dp, topRounded)

                val base = remember(incomeOld, expensesOld) {
                    ColumnCartesianLayer.ColumnProvider.series(listOf(incomeOld, expensesOld))
                }
                val provider = remember(base, incomeNow, expensesNow) {
                    object : ColumnCartesianLayer.ColumnProvider by base {
                        override fun getColumn(
                            entry: ColumnCartesianLayerModel.Entry,
                            extraStore: ExtraStore
                        ): LineComponent {
                            val currentMonth = entry.x.toInt() == 5
                            return when {
                                entry.seriesIndex == 0 && currentMonth -> incomeNow
                                entry.seriesIndex == 0 -> incomeOld
                                currentMonth -> expensesNow
                                else -> expensesOld
                            }
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().height(176.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        (1..3).forEach { step ->
                            val y = size.height * step / 4f
                            drawLine(grid, Offset(0f, y), Offset(size.width, y),
                                strokeWidth = 1.dp.toPx())
                        }
                    }
                    CartesianChartHost(
                        chart = rememberCartesianChart(
                            rememberColumnCartesianLayer(
                                columnProvider = provider,
                                columnCollectionSpacing = 13.dp,
                                mergeMode = { ColumnCartesianLayer.MergeMode.Grouped() },
                                rangeProvider = range
                            )
                        ),
                        modelProducer = modelProducer,
                        modifier = Modifier.fillMaxSize(),
                        animationSpec = tween(durationMillis = 650),
                        animateIn = true
                    )
                    // Tocar cualquier zona de cada mes (incluso sin movimientos).
                    Row(Modifier.fillMaxSize()) {
                        months.forEachIndexed { index, _ ->
                            Box(
                                Modifier.fillMaxHeight().weight(1f)
                                    .clickable(role = Role.Button) {
                                        chosenIndex = if (chosenIndex == index) null else index
                                    }
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxWidth()) {
                    months.forEachIndexed { index, month ->
                        val chosen = chosenIndex == index
                        Column(
                            modifier = Modifier.weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(role = Role.Button) {
                                    chosenIndex = if (chosen) null else index
                                }
                                .padding(vertical = 3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(month.label, color = if (index == 5) dark else muted,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (chosen || index == 5)
                                    FontWeight.Bold else FontWeight.Normal)
                            Surface(
                                color = when {
                                    chosen -> MaterialTheme.colorScheme.primaryContainer
                                    month.net >= 0 -> Color(0xFFE3F5EE)
                                    else -> Color(0xFFFFEAE7)
                                },
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    netLabel(month.net),
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    color = when {
                                        chosen -> MaterialTheme.colorScheme.onPrimaryContainer
                                        month.net >= 0 -> Color(0xFF147752)
                                        else -> Color(0xFFA94C42)
                                    }
                                )
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = chosenIndex != null,
                    enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(150)) + shrinkVertically(tween(150))
                ) {
                    chosenIndex?.let { index ->
                        val month = months[index]
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Column(Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    month.month.atDay(1).format(
                                        DateTimeFormatter.ofPattern("MMMM yyyy", MONTH_LOCALE)
                                    ).replaceFirstChar { it.uppercase(MONTH_LOCALE) },
                                    fontWeight = FontWeight.SemiBold,
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text("Ingresos: ${money.format(month.income)}",
                                    color = Color(0xFF167955))
                                Text("Gastos: ${money.format(month.spending)}",
                                    color = Color(0xFFB84E45))
                                Text("Balance neto: ${money.format(month.net)}",
                                    fontWeight = FontWeight.Bold,
                                    color = if (month.net >= 0) Color(0xFF167955)
                                        else Color(0xFFB84E45))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AverageMetric(
    label: String, value: String, accent: Color, modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = lerp(accent, MaterialTheme.colorScheme.surface, 0.9f)
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CashflowLegend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(50)))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
