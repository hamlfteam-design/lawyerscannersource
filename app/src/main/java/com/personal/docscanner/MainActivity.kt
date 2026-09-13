package com.personal.docscanner

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import com.personal.docscanner.ui.common.Biometrics
import com.personal.docscanner.ui.common.FolderLockViewModel
import com.personal.docscanner.ui.lock.LockScreen
import com.personal.docscanner.ui.nav.AppNav
import com.personal.docscanner.ui.theme.DocScannerTheme
import kotlinx.coroutines.flow.first

// FragmentActivity, not ComponentActivity: BiometricPrompt's constructor
// requires one, and it is still a ComponentActivity for setContent().
class MainActivity : FragmentActivity() {

    private val app: DocScannerApp get() = application as DocScannerApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            DocScannerTheme {
                var lockRequired by remember { mutableStateOf<Boolean?>(null) }
                var unlocked by remember { mutableStateOf(false) }

                // Resolved once per launch: reading it inside the composable would
                // flash the library before the lock screen could cover it.
                LaunchedEffect(Unit) {
                    val enabled = app.prefs.settings.first().appLockEnabled
                    lockRequired = enabled && canAuthenticate()
                    if (lockRequired == false) unlocked = true
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when {
                        lockRequired == null -> Unit // brief, intentionally blank
                        unlocked -> AppNav()
                        else -> LockScreen(
                            onUnlockRequested = { promptBiometric { unlocked = true } }
                        )
                    }
                }
            }
        }
    }

    private fun canAuthenticate(): Boolean = Biometrics.canAuthenticate(this)

    private fun promptBiometric(onSuccess: () -> Unit) {
        Biometrics.prompt(
            activity = this,
            title = getString(R.string.unlock),
            subtitle = getString(R.string.unlock_prompt),
            onSuccess = onSuccess
        )
    }

    /**
     * Folder unlocks last only as long as the app is in front. A phone put down
     * on a desk, or handed over, closes the locked folders behind it.
     */
    override fun onStop() {
        super.onStop()
        ViewModelProvider(this)[FolderLockViewModel::class.java].lockAll()
    }

}
