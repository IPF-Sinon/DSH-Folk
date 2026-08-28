package me.bmax.apatch.util

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import kotlin.coroutines.resume

object BiometricUtils {

    suspend fun authenticate(activity: FragmentActivity): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val executor = ContextCompat.getMainExecutor(activity)
                val biometricPrompt = BiometricPrompt(activity, executor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            super.onAuthenticationSucceeded(result)
                            if (cont.isActive) {
                                cont.resume(true)
                            }
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            super.onAuthenticationError(errorCode, errString)
                            if (cont.isActive) {
                                cont.resume(false)
                            }
                        }

                        override fun onAuthenticationFailed() {
                            super.onAuthenticationFailed()
                            // Don't resume here, let the user try again
                        }
                    })

                val promptInfo = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(activity.getString(R.string.action_biometric))
                    .setSubtitle(activity.getString(R.string.msg_biometric))
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()

                biometricPrompt.authenticate(promptInfo)

                cont.invokeOnCancellation {
                    biometricPrompt.cancelAuthentication()
                }
            }
        }
    }

    fun isBiometricAvailable(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }
}
