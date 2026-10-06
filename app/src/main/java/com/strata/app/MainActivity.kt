package com.strata.app

import android.os.Bundle
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.strata.app.data.db.StrataDatabase
import com.strata.app.ui.AppRoot
import com.strata.app.ui.lock.LockScreen
import com.strata.app.ui.lock.LockUi
import com.strata.app.ui.theme.StrataTheme
import javax.crypto.Cipher

class MainActivity : FragmentActivity() {
    private val container get() = (application as StrataApp).container
    private var lock by mutableStateOf(LockUi(firstRun = true))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep balances out of the recents thumbnail and screenshots.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge()
        lock = LockUi(firstRun = !container.vault.isInitialized)

        setContent {
            StrataTheme {
                val session by container.session.collectAsStateWithLifecycle()
                val relocked by container.relockRequired.collectAsStateWithLifecycle()
                val current = session
                when {
                    current == null -> LockScreen(lock, onUnlock = ::unlock, onReset = ::reset)
                    relocked -> LockScreen(LockUi(firstRun = false, relock = true), onUnlock = ::confirmPresence, onReset = {})
                    else -> AppRoot(current)
                }
            }
        }
        // Survive rotation and theme changes without asking again.
        if (!lock.firstRun && container.session.value == null) unlock()
    }

    private fun unlock() {
        val firstRun = !container.vault.isInitialized
        val cipher: Cipher = try {
            if (firstRun) {
                // A database without its key is unreadable; clear any leftover before starting fresh.
                if (StrataDatabase.exists(this)) deleteDatabase(StrataDatabase.FILE_NAME)
                container.vault.cipherForSetup()
            } else {
                container.vault.cipherForUnlock()
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            lock = lock.copy(keyLost = true, error = null)
            return
        } catch (e: Exception) {
            lock = lock.copy(error = e.message ?: "Could not prepare the key.")
            return
        }
        prompt(
            title = if (firstRun) "Create your encrypted ledger" else "Unlock Strata",
            crypto = BiometricPrompt.CryptoObject(cipher),
        ) { result ->
            try {
                val authed = result.cryptoObject?.cipher ?: error("No cipher returned")
                val passphrase = if (firstRun) container.vault.sealNewPassphrase(authed) else container.vault.openPassphrase(authed)
                container.unlock(passphrase)
                lock = LockUi(firstRun = false)
            } catch (e: Exception) {
                lock = lock.copy(error = "Unlock failed: ${e.message}")
            }
        }
    }

    private fun confirmPresence() {
        prompt(title = "Unlock Strata", crypto = null) { container.relockRequired.value = false }
    }

    private fun reset() {
        container.eraseEverything()
        lock = LockUi(firstRun = true)
    }

    private fun prompt(title: String, crypto: BiometricPrompt.CryptoObject?, onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("Fingerprint, face or screen lock")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess(result)
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode != BiometricPrompt.ERROR_USER_CANCELED && errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_CANCELED) {
                    lock = lock.copy(error = errString.toString())
                }
            }
        }
        val biometric = BiometricPrompt(this, ContextCompat.getMainExecutor(this), callback)
        if (crypto != null) biometric.authenticate(info, crypto) else biometric.authenticate(info)
    }
}
