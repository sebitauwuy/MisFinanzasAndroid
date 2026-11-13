package cl.personal.finanzas

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Importación local de CSV/TXT normalizados; nunca se conecta al banco. */
object StatementImport {
    suspend fun read(context: Context, uri: Uri, account: String): List<Movement> = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri) ?: error("Archivo inaccesible")
        val bytes = stream.use { it.readBytes() }
        require(bytes.size <= 2_000_000) { "Archivo demasiado grande (máximo 2 MB)" }
        val raw = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        require(lines.size <= 5001) { "Máximo 5000 movimientos" }
        require(lines.isNotEmpty()) { "Archivo vacío" }
        val separator = if (lines.first().count { it == ';' } > lines.first().count { it == ',' }) ';' else ','
        val result = mutableListOf<Movement>()
        lines.forEachIndexed { index, line ->
            val fields = splitCsv(line, separator)
            if (fields.size < 3) {
                if (index != 0) error("Fila ${index + 1}: se necesitan fecha, descripción y monto")
                return@forEachIndexed
            }
            val date = parseDate(fields[0])
            val amount = parseAmount(fields[2])
            if (index == 0 && (date == null || amount == null)) return@forEachIndexed // encabezado
            require(date != null && amount != null && amount != 0L) { "Fila ${index + 1}: fecha o monto inválido" }
            val description = fields[1].trim()
            require(description.isNotEmpty() && description.length <= 300) { "Fila ${index + 1}: descripción inválida" }
            result.add(Movement(account = account, description = description, amountClp = amount, date = date.toString(), category = "Importado"))
        }
        require(result.isNotEmpty()) { "No se encontraron movimientos" }
        result
    }

    private fun parseDate(input: String): LocalDate? {
        val value = input.trim().trim('"')
        return try {
            if (value.contains('/')) LocalDate.parse(value, DateTimeFormatter.ofPattern("d/M/uuuu"))
            else LocalDate.parse(value)
        } catch (_: Exception) { null }
    }

    private fun parseAmount(input: String): Long? {
        val value = input.trim().trim('"').replace("$", "").replace(" ", "")
        if (value.isBlank()) return null
        val negative = value.startsWith("-") || (value.startsWith("(") && value.endsWith(")"))
        val digits = value.filter { it.isDigit() }
        if (digits.isBlank()) return null
        val absolute = digits.toLongOrNull() ?: return null
        return if (negative) -absolute else absolute
    }

    private fun splitCsv(line: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == separator && !quoted -> { parts.add(field.toString()); field.clear() }
                else -> field.append(c)
            }
            i++
        }
        require(!quoted) { "Comillas sin cerrar en archivo" }
        parts.add(field.toString())
        return parts
    }
}
