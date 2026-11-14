package cl.personal.finanzas

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.columnModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.text.NumberFormat
import java.util.Locale

/**
 * Mini gráfico Vico de la semana actual. Se alimenta de movimientos existentes,
 * sin tocar saldos. Excluye transferencias internas y pagos de tarjeta categorizados.
 */
@Composable
fun WeeklyExpenseChart(movements: List<Movement>, modifier: Modifier = Modifier) {
    val monday = remember { LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) }
    val today = LocalDate.now()
    val amounts = remember(movements, monday) {
        (0L..6L).map { day ->
            val target = monday.plusDays(day)
            movements.asSequence().filter {
                it.amountClp < 0L &&
                    it.date == target.toString() &&
                    it.category.lowercase(Locale.ROOT).let { category ->
                        !category.contains("transferencia interna") &&
                            !category.contains("traspaso interno") &&
                            !category.contains("pago tarjeta") &&
                            !category.contains("pago de tarjeta") &&
                            !category.contains("abono tarjeta")
                    }
            }.sumOf { -it.amountClp }
        }
    }
    val formatted = remember { NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-CL")) }
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(amounts) {
        producer.runTransaction {
            columnModel { series(amounts.map { it.toDouble() }) }
        }
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Gastos de esta semana", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            Text(formatted.format(amounts.sum()), style = MaterialTheme.typography.titleMedium)
        }
        Text("De lunes a domingo · movimientos registrados",
            style = MaterialTheme.typography.bodySmall)
        if (amounts.none { it > 0 }) {
            Text("Aún no tienes gastos registrados esta semana.",
                style = MaterialTheme.typography.bodyMedium)
        } else {
            CartesianChartHost(
                chart = rememberCartesianChart(
                    rememberColumnCartesianLayer(),
                    startAxis = VerticalAxis.rememberStart(),
                    bottomAxis = HorizontalAxis.rememberBottom(),
                ),
                modelProducer = producer,
                modifier = Modifier.fillMaxWidth().height(134.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("L", "M", "M", "J", "V", "S", "D").forEach {
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
