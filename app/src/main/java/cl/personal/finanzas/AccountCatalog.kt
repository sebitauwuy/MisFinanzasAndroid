package cl.personal.finanzas

import android.content.Context

/** Catálogo local de cuentas; mostrar u ocultar no cambia los registros almacenados. */
data class AccountOption(val name: String, val type: String, val cashBalance: Boolean, val displayName: String = name)

object AccountCatalog {
    val options = listOf(
        AccountOption("Banco de Chile", "Cuentas y billeteras", true),
        AccountOption("Banco Falabella", "Cuentas y billeteras", true),
        AccountOption("BancoEstado", "Cuentas y billeteras", true),
        AccountOption("Santander", "Cuentas y billeteras", true),
        AccountOption("Tenpo", "Cuentas y billeteras", true),
        AccountOption("MACH", "Cuentas y billeteras", true),
        AccountOption("Mercado Pago", "Cuentas y billeteras", true),
        AccountOption("Tarjeta Chile", "Tarjetas de crédito", false, "Crédito Banco de Chile"),
        AccountOption("CMR", "Tarjetas de crédito", false),
        AccountOption("Crédito Mercado Pago", "Tarjetas de crédito", false),
        AccountOption("Crédito MACH", "Tarjetas de crédito", false),
        AccountOption("Crédito Santander", "Tarjetas de crédito", false),
        AccountOption("Efectivo", "Cuentas y billeteras", true)
    )
    val names: List<String> = options.map { it.name }
    fun isCash(account: String): Boolean = options.firstOrNull { it.name == account }?.cashBalance ?: false
    fun displayName(account: String): String = options.firstOrNull { it.name == account }?.displayName ?: account

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
