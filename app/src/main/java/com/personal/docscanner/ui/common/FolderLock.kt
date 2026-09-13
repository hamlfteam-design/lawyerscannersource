package com.personal.docscanner.ui.common

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.personal.docscanner.ui.theme.findActivity

/**
 * Fingerprint, face, or the device PIN.
 *
 * The PIN is always accepted alongside the biometric so that a cut finger or a
 * failed sensor never locks the owner out of their own case files.
 */
object Biometrics {

    private const val ALLOWED =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(ALLOWED) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) = onSuccess()

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    // A cancelled prompt means "not now", not "let me in".
                    if (code == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        code == BiometricPrompt.ERROR_USER_CANCELED
                    ) {
                        return
                    }
                    // The hardware disappeared after the folder was locked.
                    // Refusing entry forever would be worse than opening it.
                    if (code == BiometricPrompt.ERROR_HW_NOT_PRESENT ||
                        code == BiometricPrompt.ERROR_NO_BIOMETRICS
                    ) {
                        onSuccess()
                    }
                }
            }
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(ALLOWED)
                .build()
        )
    }
}

/**
 * Which locked folders have been opened since the app was last foregrounded.
 *
 * Held for the life of the activity rather than per screen: asking again every
 * time you step out of a folder and back into it would make the lock something
 * to switch off rather than something to use. It is cleared when the app goes
 * to the background, so a phone handed to someone else is closed again.
 */
class FolderLockViewModel : ViewModel() {

    private val unlocked = mutableSetOf<String>()

    fun isUnlocked(folderId: String): Boolean = folderId in unlocked

    fun markUnlocked(folderId: String) {
        unlocked += folderId
    }

    fun lockAll() {
        unlocked.clear()
    }
}

/**
 * A view model scoped to the activity rather than to the navigation entry, for
 * state that has to outlive moving between screens.
 */
@Composable
inline fun <reified T : ViewModel> rememberActivityViewModel(): T {
    val activity = requireNotNull(LocalContext.current.findActivity()) {
        "this view model needs an activity to be scoped to"
    }
    return viewModel(viewModelStoreOwner = activity as ViewModelStoreOwner)
}
