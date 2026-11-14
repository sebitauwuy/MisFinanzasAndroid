package cl.personal.finanzas

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class OutlookDeviceCode(
    val code: String,
    val verificationUrl: String,
    val privateCode: String,
    val intervalSeconds: Int,
    val expiresAtMs: Long,
    val clientId: String
)
data class OutlookSyncResult(val scanned: Int, val queued: Int, val possibleDuplicates: Int)

/**
 * Prototipo de cliente público OAuth con código de dispositivo.
 * Requiere registrar una aplicación Microsoft para obtener su client_id público.
 * Tokens almacenados cifrados con Android Keystore; nunca se guarda la contraseña.
 */
object OutlookGraph {
    private const val BASE_AUTH = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private const val MAIL_ENDPOINT = "https://graph.microsoft.com/v1.0/me/messages"
    private const val KEY_ALIAS = "misfinanzas-outlook-oauth-key"
    private const val SCOPES = "Mail.Read offline_access"

    private fun prefs(context: Context) = context.getSharedPreferences("outlook_sync_config", Context.MODE_PRIVATE)

    fun clientId(context: Context): String = prefs(context).getString("client_id", "").orEmpty()
    fun accountLast4(context: Context): String = prefs(context).getString("account_last4", "").orEmpty()
    fun cutoffDate(context: Context): String =
        prefs(context).getString("cutoff_date", LocalDate.now().toString()).orEmpty()
    fun enabled(context: Context): Boolean = prefs(context).getBoolean("auto_on_open", true)
    fun setEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("auto_on_open", enabled).apply() }
    fun saveSettings(context: Context, clientId: String, suffix: String, cutoff: String): Boolean {
        val id = clientId.trim()
        val last4 = suffix.trim()
        val cutoffParsed = try { LocalDate.parse(cutoff.trim()) } catch (_: Exception) { null }
        if (cutoffParsed == null || cutoffParsed.isAfter(LocalDate.now()) ||
            cutoffParsed.isBefore(LocalDate.now().minusYears(3))) return false
        if (!Regex("""[A-Fa-f0-9]{8}(-[A-Fa-f0-9]{4}){3}-[A-Fa-f0-9]{12}""").matches(id) ||
            !Regex("""\d{4}""").matches(last4)) return false
        val previousClient = clientId(context)
        if (previousClient.isNotBlank() && previousClient != id) {
            // No reutilizar credenciales de otra aplicación registrada.
            forget(context)
        }
        return prefs(context).edit().putString("client_id", id)
            .putString("account_last4", last4)
            .putString("cutoff_date", cutoffParsed.toString()).commit()
    }

    fun isConnected(context: Context): Boolean = try {
        tokens(context)?.optString("refresh_token").orEmpty().isNotBlank() && clientId(context).isNotBlank()
    } catch (_: Exception) { false }

    fun forget(context: Context) {
        prefs(context).edit().remove("encrypted_tokens").remove("last_sync").apply()
    }

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    private fun tokens(context: Context): JSONObject? {
        val value = prefs(context).getString("encrypted_tokens", null) ?: return null
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size > 32) { "Datos de autenticación no válidos" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }

    private fun saveTokens(context: Context, response: JSONObject, old: JSONObject? = null) {
        val refresh = response.optString("refresh_token").ifBlank { old?.optString("refresh_token").orEmpty() }
        require(refresh.isNotBlank() && response.optString("access_token").isNotBlank()) {
            "La autorización no entregó acceso renovable"
        }
        val value = JSONObject()
            .put("access_token", response.getString("access_token"))
            .put("refresh_token", refresh)
            .put("expires_at", System.currentTimeMillis() + response.optLong("expires_in", 3600) * 1000 - 120000L)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val raw = cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8))
        require(prefs(context).edit().putString("encrypted_tokens",
            Base64.encodeToString(raw, Base64.NO_WRAP)).commit()) { "No se guardó la sesión protegida" }
    }

    private fun encodeForm(vararg args: Pair<String, String>): ByteArray =
        args.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }.toByteArray(Charsets.UTF_8)

    private fun readLimited(input: InputStream): String {
        input.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                require(output.size() + n < 3_000_000) { "Respuesta demasiado grande" }
                output.write(buffer, 0, n)
            }
            return output.toString("UTF-8")
        }
    }

    private fun request(url: String, form: ByteArray? = null, bearer: String? = null): Pair<Int, JSONObject> {
        require(url.startsWith(BASE_AUTH + "/") || url.startsWith("https://graph.microsoft.com/v1.0/")) {
            "Destino de red no permitido"
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 25000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Accept", "application/json")
            if (bearer != null) {
                conn.setRequestProperty("Authorization", "Bearer $bearer")
                conn.setRequestProperty("Prefer", "outlook.body-content-type=\"text\"")
            }
            if (form != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                conn.outputStream.use { it.write(form) }
            }
            val code = conn.responseCode
            val input = if (code in 200..299) conn.inputStream else conn.errorStream
            val content = input?.let(::readLimited).orEmpty()
            return code to if (content.isNotBlank()) JSONObject(content) else JSONObject()
        } finally { conn.disconnect() }
    }

    private fun remoteError(data: JSONObject): String =
        data.optString("error_description").ifBlank {
            data.optString("error").ifBlank { "Microsoft no aceptó la solicitud" }
        }.take(220).replace(Regex("""[\r\n]+"""), " ")

    suspend fun beginLogin(context: Context): OutlookDeviceCode = withContext(Dispatchers.IO) {
        val appId = clientId(context)
        require(appId.isNotBlank()) { "Configura primero el ID público de la aplicación" }
        val (http, data) = request("$BASE_AUTH/devicecode",
            encodeForm("client_id" to appId, "scope" to SCOPES))
        require(http in 200..299) { remoteError(data) }
        OutlookDeviceCode(
            code = data.getString("user_code"),
            verificationUrl = data.getString("verification_uri"),
            privateCode = data.getString("device_code"),
            intervalSeconds = data.optInt("interval", 5).coerceIn(5, 60),
            expiresAtMs = System.currentTimeMillis() + data.optLong("expires_in", 900) * 1000,
            clientId = appId
        )
    }

    suspend fun awaitLogin(context: Context, prompt: OutlookDeviceCode) {
        var seconds = prompt.intervalSeconds
        while (System.currentTimeMillis() < prompt.expiresAtMs) {
            delay(seconds * 1000L)
            val (http, data) = withContext(Dispatchers.IO) {
                request("$BASE_AUTH/token", encodeForm(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id" to prompt.clientId,
                    "device_code" to prompt.privateCode
                ))
            }
            if (http in 200..299) {
                withContext(Dispatchers.IO) { saveTokens(context, data) }
                return
            }
            when (data.optString("error")) {
                "authorization_pending" -> continue
                "slow_down" -> { seconds = (seconds + 5).coerceAtMost(60); continue }
                else -> error(remoteError(data))
            }
        }
        error("El código venció. Solicita uno nuevo.")
    }

    private fun accessToken(context: Context): String {
        val current = tokens(context) ?: error("Conecta Outlook antes de sincronizar")
        val access = current.optString("access_token")
        if (access.isNotBlank() && current.optLong("expires_at") > System.currentTimeMillis())
            return access
        val appId = clientId(context)
        val (http, response) = request("$BASE_AUTH/token",
            encodeForm(
                "client_id" to appId,
                "grant_type" to "refresh_token",
                "refresh_token" to current.getString("refresh_token"),
                "scope" to SCOPES
            ))
        require(http in 200..299) { remoteError(response) }
        saveTokens(context, response, current)
        return response.getString("access_token")
    }

    suspend fun sync(context: Context, dao: FinanceDao): OutlookSyncResult = withContext(Dispatchers.IO) {
        require(isConnected(context)) { "Primero conecta Outlook" }
        val suffix = accountLast4(context)
        require(Regex("""\d{4}""").matches(suffix)) { "Indica los últimos 4 dígitos de tu cuenta" }
        val token = accessToken(context)
        val history = dao.allMovements()
        val cutoff = LocalDate.parse(cutoffDate(context))
        val fromDate = maxOf(LocalDate.now().minusDays(30), cutoff).toString()
        var next: String? = Uri.parse(MAIL_ENDPOINT).buildUpon()
            .appendQueryParameter("\$top", "50")
            .appendQueryParameter("\$filter", "receivedDateTime ge ${fromDate}T00:00:00Z")
            .appendQueryParameter("\$orderby", "receivedDateTime desc")
            .appendQueryParameter("\$select", "id,subject,sender,body,bodyPreview,receivedDateTime")
            .build().toString()
        var pages = 0
        var scanned = 0
        var added = 0
        var flagged = 0
        while (next != null && pages < 6) {
            val (http, response) = request(next, bearer = token)
            require(http in 200..299) { "Graph: " + remoteError(response) }
            val list = response.optJSONArray("value") ?: error("Graph devolvió una respuesta inesperada")
            for (i in 0 until list.length()) {
                val email = list.optJSONObject(i) ?: continue
                scanned++
                val parsed = ChileBankEmailParser.parse(email, suffix) ?: continue
                if (!LocalDate.parse(parsed.operationDate).isAfter(cutoff)) continue
                val duplicate = ChileBankEmailParser.possibleDuplicate(parsed, history)
                val candidate = if (duplicate) parsed.copy(
                    status = "POSIBLE_DUPLICADO",
                    note = parsed.note + ". Mismo monto y fecha aproximada ya registrados."
                ) else parsed
                if (dao.insertMailCandidate(candidate) != -1L) {
                    added++
                    if (duplicate) flagged++
                }
            }
            pages++
            val candidateNext = response.optString("@odata.nextLink").takeIf { it.isNotBlank() }
            next = candidateNext?.takeIf {
                it.startsWith("https://graph.microsoft.com/v1.0/") &&
                    !it.contains('#')
            }
        }
        prefs(context).edit().putLong("last_sync", System.currentTimeMillis()).apply()
        OutlookSyncResult(scanned, added, flagged)
    }
}
