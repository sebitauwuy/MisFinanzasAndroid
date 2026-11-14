package cl.personal.finanzas

import android.accounts.Account
import android.content.Context
import android.net.Uri
import android.text.Html
import android.util.Base64
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Locale

data class GmailScanResult(
    val mailbox: String, val scanned: Int, val recognized: Int,
    val queued: Int, val flagged: Int, val unknownTenpo: Int
)

/** Una cuenta Gmail adicional al Outlook actual. Tokens solo en memoria. */
object GmailBankSync {
    const val READ_SCOPE = "https://www.googleapis.com/auth/gmail.readonly"
    private const val PREFERENCES = "gmail_bank_notifications_v1"
    private const val BASE = "https://gmail.googleapis.com/gmail/v1/"
    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun email(context: Context): String = prefs(context).getString("email", "").orEmpty()
    fun linked(context: Context) = email(context).isNotBlank()
    fun cutoff(context: Context) = prefs(context).getString(
        "cutoff", LocalDate.now().minusDays(30).toString()
    ).orEmpty()
    fun setCutoff(context: Context, value: String): Boolean {
        val date = runCatching { LocalDate.parse(value) }.getOrNull() ?: return false
        if (date.isAfter(LocalDate.now()) || date.isBefore(LocalDate.now().minusYears(3)))
            return false
        return prefs(context).edit().putString("cutoff", date.toString()).commit()
    }
    fun isAfterCutoff(context: Context, candidate: MailCandidate): Boolean {
        val date = runCatching { LocalDate.parse(candidate.operationDate) }.getOrNull()
            ?: return false
        return date.isAfter(LocalDate.parse(cutoff(context)))
    }
    fun isGmailCandidate(candidate: MailCandidate) = candidate.note.startsWith("[Gmail]")
    fun saveEmail(context: Context, value: String): Boolean =
        prefs(context).edit().putString("email", value).commit()
    fun forget(context: Context) { prefs(context).edit().remove("email").apply() }

    fun authorization(context: Context, switchAccount: Boolean = false): AuthorizationRequest {
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(READ_SCOPE)))
        val saved = email(context)
        if (saved.isNotBlank() && !switchAccount) {
            builder.setAccount(Account(saved, "com.google"))
        } else {
            builder.setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT)
        }
        return builder.build()
    }

    fun revoke(context: Context, onFinish: (Boolean) -> Unit) {
        val saved = email(context)
        if (saved.isBlank()) { onFinish(true); return }
        val request = RevokeAccessRequest.builder()
            .setAccount(Account(saved, "com.google"))
            .setScopes(listOf(Scope(READ_SCOPE))).build()
        Identity.getAuthorizationClient(context).revokeAccess(request)
            .addOnSuccessListener { forget(context); onFinish(true) }
            .addOnFailureListener { onFinish(false) }
    }

    private fun fetch(url: String, token: String): JSONObject {
        require(url.startsWith(BASE) && !url.contains('#')) { "URL de Gmail no autorizada" }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12000
            conn.readTimeout = 18000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            val status = conn.responseCode
            require(status in 200..299) {
                if (status == 401 || status == 403)
                    "Permiso Gmail vencido o pendiente. Vuelve a autorizar la cuenta."
                else "Gmail respondió HTTP $status"
            }
            val buffer = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val chunk = ByteArray(4096)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    require(buffer.size() + n <= 1_000_000) {
                        "Respuesta Gmail demasiado grande"
                    }
                    buffer.write(chunk, 0, n)
                }
            }
            return JSONObject(buffer.toString("UTF-8"))
        } finally { conn.disconnect() }
    }

    private fun encodedBody(node: JSONObject): String {
        val data = node.optJSONObject("body")?.optString("data").orEmpty()
        if (data.isNotBlank()) {
            return runCatching {
                String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
            }.getOrDefault("")
        }
        val parts = node.optJSONArray("parts") ?: return ""
        var html = ""
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val text = encodedBody(part)
            if (part.optString("mimeType").equals("text/plain", true) &&
                text.isNotBlank()) return text
            if (html.isBlank() && text.isNotBlank()) html = text
        }
        return html
    }

    private fun header(headers: JSONArray, key: String): String {
        for (i in 0 until headers.length()) {
            val h = headers.optJSONObject(i) ?: continue
            if (h.optString("name").equals(key, true)) return h.optString("value")
        }
        return ""
    }

    private fun toGraphMessage(mail: JSONObject): JSONObject {
        val headers = mail.optJSONObject("payload")?.optJSONArray("headers")
            ?: JSONArray()
        val from = header(headers, "From")
        val sender = Regex("""[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""", RegexOption.IGNORE_CASE)
            .find(from)?.value.orEmpty()
        val content = encodedBody(mail.optJSONObject("payload") ?: JSONObject())
            .take(150_000)
        return JSONObject()
            .put("id", "gmail:" + mail.optString("id"))
            .put("subject", header(headers, "Subject"))
            .put("bodyPreview", mail.optString("snippet").take(400))
            .put("body", JSONObject().put("content", content))
            .put("sender", JSONObject().put("emailAddress",
                JSONObject().put("address", sender)))
    }

    private fun isTenpo(sender: String): Boolean {
        val domain = sender.substringAfter('@', "").lowercase(Locale.ROOT)
        return domain == "tenpo.cl" || domain.endsWith(".tenpo.cl")
    }

    private fun tenpoCandidate(message: JSONObject): MailCandidate? {
        val id = message.optString("id").takeIf { it.startsWith("gmail:") } ?: return null
        val sender = message.optJSONObject("sender")
            ?.optJSONObject("emailAddress")?.optString("address").orEmpty()
        if (!isTenpo(sender)) return null
        val subject = message.optString("subject")
        val raw = message.optJSONObject("body")?.optString("content").orEmpty()
        val normalized = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY)
            .toString().replace(Regex("""\s+"""), " ").lowercase(Locale.ROOT)
        if (!subject.contains("transferencia", true) &&
            !normalized.contains("transferencia")) return null

        // No decidir el signo por el remitente o el asunto: exigir una frase explícita.
        val outgoing = listOf("realizaste una transferencia", "transferencia realizada",
            "transferiste un monto").any { it in normalized }
        val incoming = listOf("recibiste una transferencia", "recibiste dinero",
            "transferencia recibida").any { it in normalized }
        if (outgoing == incoming) return null

        val amount = Regex(
            """(?:monto(?:\s+transferido|\s+recibido)?|por un monto de)\s*:?\s*\$?\s*([0-9][0-9.,]*)"""
        ).find(normalized)?.groupValues?.getOrNull(1)
            ?.filter(Char::isDigit)?.toLongOrNull() ?: return null
        if (amount !in 1..999_999_999_999L) return null

        val dateText = Regex(
            """fecha\s*:?\s*(\d{1,2}/\d{1,2}/\d{4})"""
        ).find(normalized)?.groupValues?.getOrNull(1) ?: return null
        val date = runCatching {
            val part = dateText.split('/')
            LocalDate.of(part[2].toInt(), part[1].toInt(), part[0].toInt())
        }.getOrNull() ?: return null
        if (date.isAfter(LocalDate.now()) || date.isBefore(LocalDate.now().minusYears(3)))
            return null
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return MailCandidate(
            sourceId = hash, operationDate = date.toString(),
            description = if (outgoing) "Transferencia enviada desde Tenpo"
                else "Transferencia recibida en Tenpo",
            amountClp = if (outgoing) -amount else amount,
            account = "Tenpo", category = "Transferencia",
            status = "PENDIENTE",
            note = "Verifica la operación Tenpo con la cartola antes de aprobar."
        )
    }

    suspend fun sync(
        context: Context, dao: FinanceDao, accessToken: String
    ): GmailScanResult = withContext(Dispatchers.IO) {
        require(accessToken.isNotBlank()) { "Falta autorización Gmail." }
        val profile = fetch(BASE + "users/me/profile", accessToken)
        val mailbox = profile.getString("emailAddress")
        val saved = email(context)
        require(saved.isBlank() || saved.equals(mailbox, true)) {
            "La cuenta autorizada no coincide con la vinculada. Desconecta Gmail primero."
        }
        val since = maxOf(LocalDate.now().minusDays(30),
            LocalDate.parse(cutoff(context))).toString().replace("-", "/")
        val search = "{from:tenpo.cl from:bancochile.cl from:correo.bancoestado.cl} after:$since"
        val url = Uri.parse(BASE + "users/me/messages").buildUpon()
            .appendQueryParameter("maxResults", "75")
            .appendQueryParameter("q", search).build().toString()
        val list = fetch(url, accessToken).optJSONArray("messages") ?: JSONArray()
        val existing = dao.allMovements()
        val pending = dao.allMailCandidates().toMutableList()
        var recognized = 0
        var added = 0
        var duplicates = 0
        var unknownTenpo = 0
        for (i in 0 until list.length()) {
            val id = list.optJSONObject(i)?.optString("id").orEmpty()
            if (!Regex("""[A-Za-z0-9_-]{10,100}""").matches(id)) continue
            val mail = fetch(BASE + "users/me/messages/" + id + "?format=full", accessToken)
            val graph = toGraphMessage(mail)
            val sender = graph.optJSONObject("sender")
                ?.optJSONObject("emailAddress")?.optString("address").orEmpty()
            val parsed = ChileBankEmailParser.parse(graph, OutlookGraph.accountLast4(context))
                ?: tenpoCandidate(graph)
            if (parsed == null) {
                if (isTenpo(sender)) unknownTenpo++
                continue
            }
            recognized++
            if (!isAfterCutoff(context, parsed)) continue
            val duplicate = ChileBankEmailParser.possibleDuplicate(parsed, existing) ||
                pending.any {
                    it.account == parsed.account &&
                        it.operationDate == parsed.operationDate &&
                        it.amountClp == parsed.amountClp &&
                        it.sourceId != parsed.sourceId &&
                        it.status != "OMITIDO"
                }
            val ready = parsed.copy(
                note = "[Gmail] " + parsed.note +
                    if (duplicate) " · Posible duplicado en otra fuente." else "",
                status = if (duplicate) "POSIBLE_DUPLICADO" else "PENDIENTE"
            )
            if (dao.insertMailCandidate(ready) != -1L) {
                pending.add(ready)
                added++
                if (duplicate) duplicates++
            }
        }
        if (saved.isBlank()) require(saveEmail(context, mailbox)) {
            "No se pudo guardar el correo autorizado."
        }
        GmailScanResult(mailbox, list.length(), recognized, added, duplicates, unknownTenpo)
    }
}
