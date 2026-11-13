package cl.personal.finanzas

import android.content.Context

/** Catálogo local de cuentas; mostrar u ocultar no cambia los registros almacenados. */
data class AccountOption(val name: String, val type: String, val cashBalance: Boolean)

object AccountCatalog {
    val options = listOf(
        AccountOption("Banco de Chile", "Banco", true),
        AccountOption("BancoEstado", "Banco", true),
        AccountOption("Santander", "Banco", true),
        AccountOption("Tenpo", "Billetera / cuenta", true),
        AccountOption("MACH", "Billetera / cuenta", true),
        AccountOption("Mercado Pago", "Billetera / cuenta", true),
        AccountOption("Tarjeta Chile", "Tarjeta de crédito", false),
        AccountOption("CMR", "Tarjeta de crédito", false),
        AccountOption("Efectivo", "Efectivo", true)
    )
    val names: List<String> = options.map { it.name }
    fun isCash(account: String): Boolean = options.firstOrNull { it.name == account }?.cashBalance ?: false

    private fun preferences(context: Context) = context.getSharedPreferences("account_visibility", Context.MODE_PRIVATE)
    fun isConfigured(context: Context): Boolean = preferences(context).getBoolean("configured", false)
    fun selected(context: Context): List<String> {
        val active = preferences(context).getStringSet("enabled", emptySet()).orEmpty()
        return names.filter { it in active }
    }
    fun save(context: Context, enabled: Collection<String>): Boolean {
        val normalized = names.filter { it in enabled }.toSet()
        if (normalized.isEmpty()) return false
        return preferences(context).edit()
            .putStringSet("enabled", normalized)
            .putBoolean("configured", true)
            .commit()
    }
}
