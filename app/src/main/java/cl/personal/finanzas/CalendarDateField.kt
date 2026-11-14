package cl.personal.finanzas

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Fecha elegida desde calendario: en pantalla dd/MM/yyyy y en Room ISO yyyy-MM-dd. */
private val dateDisplayFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.forLanguageTag("es-CL"))

fun dateForDisplay(value: String): String = try {
    LocalDate.parse(value).format(dateDisplayFormatter)
} catch (_: Exception) {
    "Seleccionar fecha"
}

@Composable
fun CalendarDateField(
    value: String,
    onDateSelected: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    enabled: Boolean = true
) {
    val context = LocalContext.current
    OutlinedButton(
        enabled = enabled,
        modifier = modifier,
        onClick = {
            val current = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
            DatePickerDialog(context, { _, year, zeroBasedMonth, day ->
                onDateSelected(LocalDate.of(year, zeroBasedMonth + 1, day).toString())
            }, current.year, current.monthValue - 1, current.dayOfMonth).show()
        }
    ) {
        Text("📅 $label: ${dateForDisplay(value)}")
    }
}
