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
 * Categorías y subcategorías elegidas por el usuario.
 * Los movimientos guardan el valor "Categoría / Subcategoría" en la columna
 * category existente, sin tocar importes ni saldos.
 */
object ExpenseCategories {
    val predefined = listOf("Suscripción", "Juego", "Comida", "Ocio", "Gastos comunes")
    private const val FILE = "expense_subcategories_v1"
    private const val KEY = "paths"

    fun parentOf(value: String): String = value.substringBefore(" / ").trim().ifBlank { "General" }
    fun childOf(value: String): String = if (" / " in value) value.substringAfter(" / ").trim() else ""

    private fun validParent(parent: String) = parent in predefined
    private fun validChild(child: String) = child.isNotBlank() &&
        child.length <= 40 && '/' !in child && '\n' !in child && '\r' !in child

    private fun isValidPath(path: String): Boolean {
        val parent = parentOf(path)
        val child = childOf(path)
        return validParent(parent) && validChild(child) &&
            path == parent + " / " + child
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun all(context: Context): List<String> = runCatching {
        val items = JSONArray(preferences(context).getString(KEY, "[]") ?: "[]")
        (0 until items.length()).map { items.getString(it) }
            .filter(::isValidPath).distinct().take(200)
    }.getOrDefault(emptyList())

    fun children(context: Context, parent: String): List<String> =
        all(context).filter { parentOf(it) == parent }.map(::childOf).sorted()

    fun add(context: Context, parent: String, child: String): Boolean {
        val cleaned = child.trim()
        if (!validParent(parent) || !validChild(cleaned)) return false
        val path = parent + " / " + cleaned
        val old = all(context)
        if (old.any { it.equals(path, ignoreCase = true) }) return true
        if (old.size >= 200) return false
        val next = JSONArray()
        (old + path).forEach { next.put(it) }
        return preferences(context).edit().putString(KEY, next.toString()).commit()
    }

    fun export(context: Context): JSONArray =
        JSONArray().also { array -> all(context).forEach { array.put(it) } }

    fun validateImport(array: JSONArray) {
        require(array.length() <= 200) { "Demasiadas subcategorías" }
        val seen = mutableSetOf<String>()
        for (i in 0 until array.length()) {
            val path = array.getString(i)
            require(isValidPath(path) &&
                seen.add(path.lowercase(Locale.ROOT))) {
                "Subcategoría inválida o duplicada en el respaldo"
            }
        }
    }

    fun restore(context: Context, array: JSONArray): Boolean {
        validateImport(array)
        return preferences(context).edit().putString(KEY, array.toString()).commit()
    }
}

/** Selector compartido entre Movs. y Gastos programados. */
@Composable
fun ExpenseCategoryPicker(
    category: String,
    onChange: (String) -> Unit
) {
    val context = LocalContext.current
    val parent = ExpenseCategories.parentOf(category)
    val selectedChild = ExpenseCategories.childOf(category)
    var parentExpanded by remember { mutableStateOf(false) }
    var childExpanded by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var newChild by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf(false) }
    val parents = (listOf("General") + ExpenseCategories.predefined + parent).distinct()
    val children = remember(parent, revision) {
        ExpenseCategories.children(context, parent)
    }
    val availableChildren = (children + listOfNotNull(selectedChild.takeIf { it.isNotBlank() })).distinct()

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Categoría", style = MaterialTheme.typography.labelLarge)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { parentExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(parent, maxLines = 1)
            }
            DropdownMenu(expanded = parentExpanded, onDismissRequest = { parentExpanded = false }) {
                parents.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onChange(option)
                            parentExpanded = false
                            creating = false
                        }
                    )
                }
            }
        }
        if (parent in ExpenseCategories.predefined) {
            Text("Subcategoría (opcional)", style = MaterialTheme.typography.labelLarge)
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { childExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selectedChild.ifBlank { "Sin subcategoría" }, maxLines = 1)
                }
                DropdownMenu(expanded = childExpanded, onDismissRequest = { childExpanded = false }) {
                    DropdownMenuItem(text = { Text("Sin subcategoría") }, onClick = {
                        onChange(parent)
                        childExpanded = false
                    })
                    availableChildren.forEach { sub ->
                        DropdownMenuItem(text = { Text(sub) }, onClick = {
                            onChange(parent + " / " + sub)
                            childExpanded = false
                        })
                    }
                }
            }
            TextButton(onClick = {
                creating = !creating
                error = false
                newChild = ""
            }) { Text(if (creating) "Cancelar nueva subcategoría" else "+ Crear subcategoría") }
            if (creating) {
                OutlinedTextField(
                    value = newChild,
                    onValueChange = { newChild = it.take(40); error = false },
                    label = { Text("Nombre de subcategoría") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                if (error) Text("No se pudo guardar. Usa un nombre distinto y sin barras.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                Button(
                    enabled = newChild.trim().isNotBlank() && !newChild.contains("/"),
                    onClick = {
                        val candidate = newChild.trim()
                        if (ExpenseCategories.add(context, parent, candidate)) {
                            revision++
                            onChange(parent + " / " + candidate)
                            newChild = ""
                            creating = false
                            error = false
                        } else error = true
                    }, modifier = Modifier.fillMaxWidth()
                ) { Text("Guardar subcategoría") }
            }
        }
    }
}
