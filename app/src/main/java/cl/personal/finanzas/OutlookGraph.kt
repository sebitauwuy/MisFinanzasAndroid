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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.time.LocalDate
import java.time.LocalTime
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
data class OutlookSyncResult(val scanned: Int, val queued: Int, val possibleDuplicates: Int, val bankSender: Int, val parsed: Int, val excludedByDate: Int, val unrecognized: Int)

/**
 * Prototipo de cliente público OAuth con código de dispositivo.
 * Requiere registrar una aplicación Microsoft para obtener su client_id público.
 * Tokens almacenados cifrados con Android Keystore; nunca se guarda la contraseña.
 */
object OutlookGraph {
    const val APP_CLIENT_ID = "3db82c7e-2887-4ade-af49-855e769ec42d"
    // Evita solapar la búsqueda automática, el gesto de actualizar y la búsqueda en Ajustes.
    private val syncMutex = Mutex()
    private const val BASE_AUTH = "https://login.microsoftonline.com/consumers/oauth2/v2.0"
    private const val MAIL_ENDPOINT = "https://graph.microsoft.com/v1.0/me/messages"
    private const val KEY_ALIAS = "misfinanzas-outlook-oauth-key"
    private const val SCOPES = "Mail.Read offline_access"

    private fun prefs(context: Context) = context.getSharedPreferences("outlook_sync_config", Context.MODE_PRIVATE)

    fun clientId(context: Context): String = prefs(context).getString("client_id", "").orEmpty()
    fun accountLast4(context: Context): String = prefs(context).getString("account_last4", "").orEmpty()
    fun cutoffDate(context: Context): String =
        prefs(context).getString("cutoff_date", LocalDate.now().toString()).orEmpty()
    fun cutoffTime(context: Context): String =
        prefs(context).getString("cutoff_time", "23:59").orEmpty()

    fun isAfterCutoff(context: Context, candidate: MailCandidate): Boolean {
        val day = try { LocalDate.parse(candidate.operationDate) } catch (_: Exception) { return false }
        val cutoff = LocalDate.parse(cutoffDate(context))
        if (day.isAfter(cutoff)) return true
        if (day.isBefore(cutoff)) return false
        val time = ChileBankEmailParser.operationTime(candidate) ?: return false
        return time.isAfter(LocalTime.parse(cutoffTime(context)))
    }

    fun lastSuccessfulSyncMs(context: Context): Long = prefs(context).getLong("last_sync", 0L)
    fun bancoEstadoReceiptsSeen(context: Context): Boolean =
        prefs(context).getLong("bancoestado_receipt_detected", 0L) > 0L

    fun enabled(context: Context): Boolean = prefs(context).getBoolean("auto_on_open", true)
    fun setEnabled(context: Context, enabled: Boolean) { prefs(context).edit().putBoolean("auto_on_open", enabled).apply() }
    fun saveSettings(context: Context, clientId: String, suffix: String, cutoff: String, time: String = cutoffTime(context)): Boolean {
        val id = clientId.trim()
        val last4 = suffix.trim()
        val cutoffParsed = try { LocalDate.parse(cutoff.trim()) } catch (_: Exception) { null }
        val cutoffClock = try { LocalTime.parse(time.trim()) } catch (_: Exception) { null }
        if (cutoffClock == null) return false
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
            .putString("cutoff_date", cutoffParsed.toString())
            .putString("cutoff_time", cutoffClock.toString()).commit()
    }

    fun isConnected(context: Context): Boolean = try {
        prefs(context).getBoolean("msal_connected", false) ||
            (tokens(context)?.optString("refresh_token").orEmpty().isNotBlank() && clientId(context).isNotBlank())
    } catch (_: Exception) { false }

    fun usesMicrosoftAuth(context: Context): Boolean = prefs(context).getBoolean("msal_connected", false)

    fun markMicrosoftConnected(context: Context) {
        prefs(context).edit().remove("encrypted_tokens").putBoolean("msal_connected", true).commit()
    }

    fun forget(context: Context) {
        prefs(context).edit().remove("encrypted_tokens").remove("last_sync").remove("msal_connected").apply()
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
        val (http, data) = try {
            request("$BASE_AUTH/devicecode",
                encodeForm("client_id" to appId, "scope" to SCOPES))
        } catch (e: java.net.UnknownHostException) {
            error("Android no pudo resolver login.microsoftonline.com. Comprueba DNS privado, VPN o filtros de red; el navegador puede usar un DNS distinto.")
        }
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
            val response = try {
                withContext(Dispatchers.IO) {
                    request("$BASE_AUTH/token", encodeForm(
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                        "client_id" to prompt.clientId,
                        "device_code" to prompt.privateCode
                    ))
                }
            } catch (e: java.io.IOException) {
                // No descartar un código autorizado por una falla transitoria de DNS/red.
                continue
            }
            val (http, data) = response
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
        if (usesMicrosoftAuth(context)) return OutlookMicrosoftAuth.accessToken(context)
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

    suspend fun sync(context: Context, dao: FinanceDao): OutlookSyncResult = syncMutex.withLock {
        withContext(Dispatchers.IO) {
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
        var bankSender = 0
        var parsedCount = 0
        var excludedByDate = 0
        var unrecognized = 0
        while (next != null && pages < 6) {
            val (http, response) = request(next, bearer = token)
            require(http in 200..299) { "Graph: " + remoteError(response) }
            val list = response.optJSONArray("value") ?: error("Graph devolvió una respuesta inesperada")
            for (i in 0 until list.length()) {
                val email = list.optJSONObject(i) ?: continue
                scanned++
                val sender = email.optJSONObject("sender")?.optJSONObject("emailAddress")?.optString("address").orEmpty()
                val knownBankSender = sender.equals("enviodigital@bancochile.cl", true) ||
                    sender.equals("serviciodetransferencias@bancochile.cl", true) ||
                    BancoEstadoEmailParser.isSupportedSender(sender)
                if (knownBankSender) bankSender++
                val parsed = ChileBankEmailParser.parse(email, suffix)
                if (parsed == null) {
                    if (knownBankSender) unrecognized++
                    continue
                }
                parsedCount++
                if (parsed.account == "BancoEstado") {
                    // Solo se muestra como reconocido si un correo pasó el parser;
                    // no implica conexión directa ni autorización con BancoEstado.
                    prefs(context).edit()
                        .putLong("bancoestado_receipt_detected", System.currentTimeMillis()).apply()
                }
                if (!isAfterCutoff(context, parsed)) {
                    excludedByDate++
                    continue
                }
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
        OutlookSyncResult(scanned, added, flagged, bankSender, parsedCount, excludedByDate, unrecognized)
        }
    }
}

