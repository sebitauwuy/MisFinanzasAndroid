package cl.personal.finanzas

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * Etiquetas personales para comercios. La descripción bancaria original NO se modifica:
 * deduplicación, conciliación, importación y movimientos siguen usando su texto auténtico.
 * Las reglas de usuario se guardan localmente y se incluyen en el respaldo JSON.
 */
object MerchantAliases {
    private const val PREFS = "merchant_aliases_v1"
    private const val KEY = "rules"
    private val prefix = Regex(
        """^(?:(?:compra\s+(?:en|con)|pago\s+(?:en|con|a)|consumo\s+en)\s+)*(?:(?:google\s*pay|gpay)\s+)?""",
        RegexOption.IGNORE_CASE
    )

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun key(description: String): String {
        val plain = Normalizer.normalize(description.trim(), Normalizer.Form.NFD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("""\s+"""), " ")
        val shortened = plain.replace(prefix, "").trim().ifEmpty { plain }
        return shortened.replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .replace(Regex("""\s+"""), " ").trim().take(120)
    }

    fun suggestedName(description: String): String {
        val cleaned = description.trim().replace(prefix, "")
            .replace(Regex("""\s+"""), " ").trim().ifBlank { description.trim() }
        return cleaned.replaceFirstChar { it.titlecase(Locale.forLanguageTag("es-CL")) }
            .take(60)
    }

    private fun all(context: Context): JSONObject = runCatching {
        JSONObject(preferences(context).getString(KEY, "{}") ?: "{}")
    }.getOrElse { JSONObject() }

    fun nickname(context: Context, original: String): String? {
        val slug = key(original)
        if (slug.isBlank()) return null
        return all(context).optString(slug).takeIf { it.isNotBlank() }
    }

    fun label(context: Context, original: String): String =
        nickname(context, original) ?: original

    fun count(description: String, movements: List<Movement>, candidates: List<MailCandidate>): Int {
        val slug = key(description)
        return if (slug.isBlank()) 0 else
            movements.count { key(it.description) == slug } +
                candidates.count { key(it.description) == slug && it.status != "IMPORTADO" }
    }

    fun save(context: Context, original: String, desired: String): Boolean {
        val slug = key(original)
        val name = desired.trim().replace(Regex("""\s+"""), " ")
        if (slug.isBlank() || slug.length > 120 ||
            name.length !in 1..60 || name.any { it == '\n' || it == '\r' }) return false
        val map = all(context)
        if (map.length() >= 300 && !map.has(slug)) return false
        map.put(slug, name)
        return preferences(context).edit().putString(KEY, map.toString()).commit()
    }

    fun remove(context: Context, original: String): Boolean {
        val map = all(context)
        map.remove(key(original))
        return preferences(context).edit().putString(KEY, map.toString()).commit()
    }

    fun export(context: Context): JSONObject = all(context)

    fun validate(input: JSONObject) {
        require(input.length() <= 300) { "Demasiados nombres de comercios" }
        input.keys().forEach { slug ->
            val name = input.getString(slug)
            require(slug.isNotBlank() && slug.length <= 120 &&
                slug == key(slug) && name.trim().length in 1..60) {
                "Nombre personalizado inválido en respaldo"
            }
        }
    }

    fun restore(context: Context, input: JSONObject): Boolean {
        validate(input)
        return preferences(context).edit().putString(KEY, input.toString()).commit()
    }
}

@Composable
fun MerchantAliasDialog(
    description: String,
    matches: Int,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val oldName = remember(description) { MerchantAliases.nickname(context, description) }
    var newName by remember(description) {
        mutableStateOf(oldName ?: MerchantAliases.suggestedName(description))
    }
    var error by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nombre para este comercio") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Descripción original: $description",
                    style = MaterialTheme.typography.bodySmall)
                if (matches >= 2) {
                    Text("Aparece $matches veces. El nombre se aplicará también a los " +
                        "próximos movimientos con el mismo comercio.",
                        style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("El nombre se aplicará a los próximos movimientos con " +
                        "la misma descripción bancaria.",
                        style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it.take(60); error = false },
                    label = { Text("Nombre visible") },
                    placeholder = { Text("Rinconcito") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("No se cambia la descripción original usada para comprobar " +
                    "cartolas y evitar duplicados.", style = MaterialTheme.typography.bodySmall)
                if (error) Text("No se pudo guardar el nombre.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
                if (oldName != null) {
                    TextButton(onClick = {
                        if (MerchantAliases.remove(context, description)) onSaved()
                        else error = true
                    }) { Text("Restaurar descripción original") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = newName.trim().isNotEmpty(),
                onClick = {
                    if (MerchantAliases.save(context, description, newName)) onSaved()
                    else error = true
                }
            ) { Text("Guardar nombre") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}
