package cl.personal.finanzas

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.SignInParameters
import com.microsoft.identity.client.exception.MsalException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** La clave de cliente es pública; MSAL conserva las credenciales en su propia caché. */
object OutlookMicrosoftAuth {
    private val scopes = listOf("Mail.Read")
    private const val AUTHORITY = "https://login.microsoftonline.com/consumers"
    private fun app(context: Context): ISingleAccountPublicClientApplication =
        PublicClientApplication.createSingleAccountPublicClientApplication(
            context.applicationContext, R.raw.outlook_auth)

    suspend fun signIn(activity: Activity) {
        val client = withContext(Dispatchers.IO) { app(activity) }
        val current = withContext(Dispatchers.IO) { client.currentAccount.currentAccount }
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val callback = object : AuthenticationCallback {
                    override fun onSuccess(result: IAuthenticationResult) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }
                    override fun onError(exception: MsalException) {
                        if (continuation.isActive) continuation.resumeWithException(exception)
                    }
                    override fun onCancel() {
                        if (continuation.isActive) continuation.resumeWithException(
                            IllegalStateException("Inicio de sesión cancelado"))
                    }
                }
                val params = SignInParameters.builder().withActivity(activity)
                    .withScopes(scopes).withCallback(callback).build()
                if (current == null) client.signIn(params) else client.signInAgain(params)
            }
        }
    }

    fun accessToken(context: Context): String {
        val client = app(context)
        val account = client.currentAccount.currentAccount ?: error("Conecta Outlook nuevamente")
        val params = AcquireTokenSilentParameters.Builder().withScopes(scopes)
            .forAccount(account).fromAuthority(AUTHORITY).build()
        return client.acquireTokenSilent(params).accessToken
    }

    suspend fun signOut(context: Context) = withContext(Dispatchers.IO) {
        val client = app(context)
        if (client.currentAccount.currentAccount != null) {
            check(client.signOut()) { "No se pudo desconectar Outlook" }
        }
    }
}

