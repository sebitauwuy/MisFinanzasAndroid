package cl.personal.finanzas

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import java.util.Locale

/**
 * Un solo nivel de categorías. El campo category de Room sigue siendo String:
 * NO se alteran saldos ni descripciones históricas.
 */
object ExpenseCategories {
    val predefined = listOf("Suscripción", "Juego", "Comida", "Ocio", "Gastos comunes")
    private const val FILE = "expense_categories_v2"
    private const val KEY = "names"
    private const val LIMIT = 100

    /** Solo para mostrar y agrupar valores históricos "Comida / Restaurante". */
    fun flat(value: String): String =
        value.trim().substringBefore(" / ").trim().ifBlank { "General" }

    private fun normalize(value: String) = value.trim().replace(Regex("""\s+"""), " ")
    private fun valid(value: String): Boolean {
        val name = normalize(value)
        return name.length in 1..50 && '/' !in name &&
            '\n' !in name && '\r' !in name &&
            name != "+ Nueva categoría"
    }
    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(context: Context): List<String> = runCatching {
        val names = JSONArray(preferences(context).getString(KEY, "[]") ?: "[]")
        (0 until names.length()).map { normalize(names.getString(it)) }
            .filter(::valid)
            .distinctBy { it.lowercase(Locale.ROOT) }
            .take(LIMIT)
    }.getOrDefault(emptyList())

    /** La categoría en uso siempre sigue disponible, incluso si fue importada de un banco. */
    fun choices(context: Context, history: Collection<String> = emptyList()): List<String> =
        (listOf("General") + predefined + all(context) + history.map(::flat))
            .map(::normalize)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }

    fun add(context: Context, requested: String): Boolean {
        val name = normalize(requested)
        if (!valid(name)) return false
        if ((listOf("General") + predefined).any { it.equals(name, true) }) return true
        val old = all(context)
        if (old.any { it.equals(name, true) }) return true
        if (old.size >= LIMIT) return false
        val array = JSONArray()
        (old + name).forEach { array.put(it) }
        return preferences(context).edit().putString(KEY, array.toString()).commit()
    }

    /** Desde respaldo v13: las entradas son solo categorías planas del usuario. */
    fun export(context: Context): JSONArray = JSONArray().also { array ->
        all(context).forEach(array::put)
    }

    /** Los respaldos v11-v12 contienen rutas antiguas de subcategoría. */
    fun validateImport(array: JSONArray, legacy: Boolean = false) {
        require(array.length() <= if (legacy) 200 else LIMIT) {
            "Demasiadas categorías en el respaldo"
        }
        val seen = mutableSetOf<String>()
        for (index in 0 until array.length()) {
            val name = array.getString(index)
            val validLegacy = legacy && " / " in name &&
                name.substringBefore(" / ") in predefined &&
                name.substringAfter(" / ").isNotBlank() &&
                name.length <= 100
            require((valid(name) || validLegacy) &&
                seen.add(name.lowercase(Locale.ROOT))) {
                "Categoría inválida o duplicada en el respaldo"
            }
        }
    }

    fun restore(context: Context, array: JSONArray, legacy: Boolean = false): Boolean {
        validateImport(array, legacy)
        // No promovemos rutas de subcategoría antiguas a categorías nuevas.
        // Los movimientos históricos conservan su texto y se agrupan por categoría principal.
        val names = if (legacy) emptyList() else
            (0 until array.length()).map { array.getString(it) }
        val saved = JSONArray()
        names.forEach { saved.put(it) }
        return preferences(context).edit().putString(KEY, saved.toString()).commit()
    }
}

/**
 * Selector único para Movimientos y Gastos programados.
 * "+ Nueva categoría" abre un diálogo; se guarda como opción reutilizable.
 */
@Composable
fun ExpenseCategoryPicker(
    category: String,
    onChange: (String) -> Unit,
    historicalCategories: Collection<String> = emptyList()
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    val chosen = ExpenseCategories.flat(category)
    val options = remember(category, historicalCategories, revision) {
        ExpenseCategories.choices(context, historicalCategories + category)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Categoría", style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text(chosen, maxLines = 1) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, maxLines = 1) },
                        onClick = {
                            onChange(option)
                            expanded = false
                        }
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("+ Nueva categoría") },
                    onClick = {
                        expanded = false
                        error = false
                        newCategory = ""
                        creating = true
                    }
                )
            }
        }
    }

    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("Crear categoría") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Podrás usarla en cualquier movimiento o gasto programado.")
                    OutlinedTextField(
                        value = newCategory,
                        onValueChange = { newCategory = it.take(50); error = false },
                        label = { Text("Nombre de la categoría") },
                        placeholder = { Text("Ej. Mascotas") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (error) Text(
                        "El nombre no es válido o no se pudo guardar.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newCategory.trim().isNotBlank() && '/' !in newCategory,
                    onClick = {
                        val newName = newCategory.trim().replace(Regex("""\s+"""), " ")
                        if (ExpenseCategories.add(context, newName)) {
                            revision++
                            val canonical = ExpenseCategories.choices(context)
                                .firstOrNull { it.equals(newName, ignoreCase = true) } ?: newName
                            onChange(canonical)
                            creating = false
                        } else error = true
                    }
                ) { Text("Crear") }
            },
            dismissButton = {
                TextButton(onClick = { creating = false }) { Text("Cancelar") }
            }
        )
    }
}
