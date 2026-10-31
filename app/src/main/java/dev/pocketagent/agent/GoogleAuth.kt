package dev.pocketagent.agent

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import dev.pocketagent.core.tools.GoogleException
import dev.pocketagent.core.tools.GoogleTokens
import dev.pocketagent.data.SecureSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Google sign-in for Gmail, Calendar and Drive using the Google accounts already
 * on the phone (Google Play services). The app must be registered as an Android
 * OAuth client (package + signing SHA-1) in the user's Google Cloud project;
 * no client secret ships in the APK. Tokens live in memory only and Play
 * services refreshes them.
 */
class GoogleAuth(private val context: Context, private val settings: SecureSettings) : GoogleTokens {
    private val cache = ConcurrentHashMap<String, Pair<Long, String>>()

    override val accounts: List<String> get() = settings.googleAccounts

    /** Blocking; call off the main thread. */
    fun tokenBlocking(account: String): String {
        cache[account]?.let { (at, token) -> if (System.currentTimeMillis() - at < TOKEN_TTL_MS) return token }
        val token = try {
            GoogleAuthUtil.getToken(context, Account(account, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE), SCOPES)
        } catch (e: UserRecoverableAuthException) {
            throw GoogleException("$account needs to approve access again. Open Settings ▸ Connectors ▸ Google and tap it.")
        } catch (e: GoogleAuthException) {
            throw GoogleException(describe(e))
        }
        cache[account] = System.currentTimeMillis() to token
        return token
    }

    override suspend fun token(account: String): String = withContext(Dispatchers.IO) { tokenBlocking(account) }

    override fun invalidate(account: String, token: String) {
        cache.remove(account)
        runCatching { GoogleAuthUtil.clearToken(context, token) }
    }

    /**
     * Tries to get a token for [account]. Returns null when access is granted,
     * or the consent screen to show when the user still has to approve.
     */
    suspend fun authorize(account: String): Intent? = withContext(Dispatchers.IO) {
        cache.remove(account)
        try {
            val token = GoogleAuthUtil.getToken(context, Account(account, GoogleAuthUtil.GOOGLE_ACCOUNT_TYPE), SCOPES)
            cache[account] = System.currentTimeMillis() to token
            null
        } catch (e: UserRecoverableAuthException) {
            e.intent ?: throw GoogleException("Google needs you to approve access for $account, but gave no screen to do it. Try again.")
        } catch (e: GoogleAuthException) {
            throw GoogleException(describe(e))
        }
    }

    fun forget(account: String) {
        cache.remove(account)?.second?.let { runCatching { GoogleAuthUtil.clearToken(context, it) } }
    }

    private fun describe(e: GoogleAuthException): String {
        val m = e.message.orEmpty()
        return when {
            m.contains("UNREGISTERED_ON_API_CONSOLE", true) || m.contains("INVALID_AUDIENCE", true) ->
                "Google doesn't recognise this app yet. In Google Cloud, create an Android OAuth client with package dev.pocketagent and this app's SHA-1, and add yourself as a test user."
            m.contains("BadUsername", true) -> "That Google account isn't on this phone."
            else -> "Google sign-in failed: ${m.ifBlank { e.javaClass.simpleName }}"
        }
    }

    companion object {
        const val SCOPES = "oauth2:https://mail.google.com/ https://www.googleapis.com/auth/calendar.events https://www.googleapis.com/auth/drive.readonly"
        private const val TOKEN_TTL_MS = 45 * 60_000L
    }
}
