package com.osone.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Conta OSTIE: login no Firebase do OSONE (Google ou e-mail) e plano da assinatura, que o servidor grava
 * no login como "ostiePlan" quando a Stripe confirma o pagamento. Sem a configuração do Firebase na
 * compilação ([configured] falso), a conta some da tela e os limites dos planos ficam desligados.
 */
object OstieAccount {
    /** Plano vale sem internet por esse tempo depois da última conferência. */
    const val OFFLINE_GRACE_MS = 7L * 24 * 60 * 60 * 1000

    var email by mutableStateOf<String?>(null)
        private set
    var status by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
        private set
    private var lastRefresh = 0L

    /** Login e assinatura existem nesta versão: Firebase configurado e servidor da assinatura (Vercel) informado. */
    fun configured(): Boolean = BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() &&
        BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
        BuildConfig.OSTIE_API_URL.startsWith("https://")

    private fun auth(context: Context): FirebaseAuth? {
        if (!configured()) return null
        return try {
            val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context.applicationContext,
                FirebaseOptions.Builder().setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID).setApiKey(BuildConfig.FIREBASE_API_KEY).build())
            FirebaseAuth.getInstance(app)
        } catch (failure: Exception) {
            AppDiagnostics.get(context).record("Conta", "Firebase não iniciou (${failure.javaClass.simpleName}).")
            null
        }
    }

    fun load(context: Context) {
        email = auth(context)?.currentUser?.email
    }

    /** Login com a conta Google do celular (a mesma do OSONE). */
    suspend fun signInWithGoogle(activity: Activity) = work(activity) {
        val auth = auth(activity) ?: error("Conta indisponível nesta versão.")
        require(BuildConfig.FIREBASE_WEB_CLIENT_ID.isNotBlank()) { "Login com Google ainda não configurado; use o e-mail." }
        val option = GetGoogleIdOption.Builder().setServerClientId(BuildConfig.FIREBASE_WEB_CLIENT_ID)
            .setFilterByAuthorizedAccounts(false).build()
        val result = CredentialManager.create(activity).getCredential(activity,
            GetCredentialRequest.Builder().addCredentialOption(option).build())
        val credential = result.credential
        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            "O Google não devolveu a conta."
        }
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        signedIn(activity)
    }

    /** Login com e-mail e senha; [create] cria a conta. */
    suspend fun signInWithEmail(context: Context, address: String, password: String, create: Boolean) = work(context) {
        val auth = auth(context) ?: error("Conta indisponível nesta versão.")
        require(address.contains('@') && password.length >= 6) { "Informe o e-mail e uma senha de pelo menos 6 caracteres." }
        if (create) auth.createUserWithEmailAndPassword(address.trim(), password).await()
        else auth.signInWithEmailAndPassword(address.trim(), password).await()
        signedIn(context)
    }

    private suspend fun signedIn(context: Context) {
        email = auth(context)?.currentUser?.email
        refreshPlan(context, force = true)
        status = "Conta conectada: ${email.orEmpty()}."
    }

    fun signOut(context: Context) {
        auth(context)?.signOut()
        email = null
        PlanStore.saveSubscribed(context, Plan.GRATIS)
        status = "Você saiu da conta."
    }

    /** Lê o plano gravado no login (declaração "ostiePlan"). Sem conta, fica o Grátis. */
    suspend fun refreshPlan(context: Context, force: Boolean = false) {
        val user = auth(context)?.currentUser ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastRefresh < 60_000) return
        lastRefresh = now
        try {
            val token = user.getIdToken(true).await()
            PlanStore.saveSubscribed(context, Plan.fromId(token.claims["ostiePlan"] as? String))
        } catch (failure: Exception) {
            // Sem internet: o plano salvo continua valendo por OFFLINE_GRACE_MS.
            AppDiagnostics.get(context).record("Conta", "Plano não conferido (${failure.javaClass.simpleName}).")
        }
    }

    /** Abre o checkout da Stripe: PRO_MENSAL, PRO_ANUAL ou EMPRESA. */
    suspend fun subscribe(context: Context, option: String) = work(context) { openFrom(context, "checkout", JSONObject().put("plano", option)) }

    /** Portal da Stripe para trocar cartão, mudar de plano ou cancelar. */
    suspend fun manage(context: Context) = work(context) { openFrom(context, "portal", JSONObject()) }

    private suspend fun openFrom(context: Context, route: String, body: JSONObject) {
        val user = auth(context)?.currentUser ?: error("Entre com a sua conta antes de assinar.")
        val token = user.getIdToken(false).await().token ?: error("Login expirado; entre de novo.")
        val answer = withContext(Dispatchers.IO) { post(route, token, body) }
        val url = answer.optString("url")
        require(url.startsWith("https://")) { answer.optString("erro").ifBlank { "O servidor não devolveu o link de pagamento." } }
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        status = "Conclua no navegador; o plano libera sozinho quando voltar ao OSTIE."
    }

    private fun post(route: String, token: String, body: JSONObject): JSONObject {
        val url = URL(BuildConfig.OSTIE_API_URL.trimEnd('/') + "/api/$route")
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            try { JSONObject(text) } catch (_: Exception) { JSONObject().put("erro", "Servidor do OSTIE indisponível (HTTP ${connection.responseCode}).") }
        } finally { connection.disconnect() }
    }

    /** Roda uma ação da conta mostrando "ocupado" e a mensagem de erro, sem derrubar o app. */
    private suspend fun work(context: Context, block: suspend () -> Unit) {
        if (busy) return
        busy = true
        status = null
        try { block() } catch (failure: Exception) {
            AppDiagnostics.get(context).record("Conta", failure.javaClass.simpleName)
            status = when {
                failure is androidx.credentials.exceptions.GetCredentialCancellationException -> "Login cancelado."
                failure is androidx.credentials.exceptions.NoCredentialException -> "Nenhuma conta Google neste celular."
                failure.message.isNullOrBlank() -> "Não consegui agora (${failure.javaClass.simpleName})."
                else -> failure.message
            }
        } finally { busy = false }
    }

    /** Espera uma tarefa do Firebase sem bibliotecas extras. */
    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            val error = task.exception
            if (error != null) continuation.resumeWithException(error)
            else continuation.resume(task.result)
        }
    }
}
