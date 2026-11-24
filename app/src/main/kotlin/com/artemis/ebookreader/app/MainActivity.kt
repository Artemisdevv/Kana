package com.artemis.ebookreader.app

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.artemis.ebookreader.R
import com.artemis.ebookreader.feature.library.LibraryViewModel
import com.artemis.ebookreader.feature.reader.ReaderActivity
import com.artemis.ebookreader.feature.settings.InstallOutcome
import com.artemis.ebookreader.feature.settings.UpdateDialog
import com.artemis.ebookreader.ui.theme.EBookReaderTheme
import com.artemis.ebookreader.ui.theme.appColorScheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as EBookReaderApplication
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        app.updates.permissionResult(canInstallUpdates(), returnedFromSettings = true)
    }
    private val installPackage = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (!canInstallUpdates()) {
            app.updates.installResult(InstallOutcome.Cancelled)
            app.updates.permissionResult(false, returnedFromSettings = true)
        } else {
            app.updates.installResult(when (result.resultCode) {
                RESULT_OK -> InstallOutcome.Succeeded
                RESULT_CANCELED -> InstallOutcome.Cancelled
                else -> InstallOutcome.Failed
            })
        }
    }
    private val library: LibraryViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as EBookReaderApplication
                LibraryViewModel(app.library, app.libraryLayout)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appearance by app.appAppearance.appearance.collectAsStateWithLifecycle()
            val activityState by lifecycle.currentStateFlow.collectAsStateWithLifecycle()
            val dark = appearance.mode.isDark(isSystemInDarkTheme())
            val colors = appColorScheme(appearance.colorTheme, dark, appearance.amoledBlack)
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            EBookReaderTheme(colorScheme = colors) {
                ReaderShell(library, app.readerAppearance, app.appAppearance, app.updates) { bookId ->
                    val legacyTransition = if (Build.VERSION.SDK_INT < 34) {
                        ActivityOptions.makeCustomAnimation(this, R.anim.reader_fade_in, R.anim.reader_hold).toBundle()
                    } else null
                    startActivity(ReaderActivity.intent(this, bookId), legacyTransition)
                }
                if (activityState == Lifecycle.State.RESUMED) {
                    UpdateDialog(app.updates, ::requestUpdateInstall, ::openInstallPermission)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        app.updates.onForeground()
    }

    private fun canInstallUpdates(): Boolean =
        Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun openInstallPermission() {
        if (Build.VERSION.SDK_INT < 26 || canInstallUpdates()) {
            app.updates.permissionResult(true)
            return
        }
        try {
            installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", packageName, null)))
        } catch (_: ActivityNotFoundException) {
            app.updates.permissionResult(false, returnedFromSettings = true)
        } catch (_: SecurityException) {
            app.updates.permissionResult(false, returnedFromSettings = true)
        }
    }

    @Suppress("DEPRECATION") // The system installer returns cancellation and failure via EXTRA_RETURN_RESULT.
    private fun requestUpdateInstall() {
        if (!canInstallUpdates()) {
            app.updates.permissionResult(false)
            return
        }
        lifecycleScope.launch {
            val apk = app.updates.prepareInstall() ?: return@launch
            if (lifecycle.currentState != Lifecycle.State.RESUMED) {
                app.updates.installResult(InstallOutcome.Cancelled)
                return@launch
            }
            if (!canInstallUpdates()) {
                app.updates.installResult(InstallOutcome.Cancelled)
                app.updates.permissionResult(false)
                return@launch
            }
            try {
                val contentUri = FileProvider.getUriForFile(this@MainActivity, "$packageName.update-apk", apk)
                val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(contentUri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                val systemInstaller = packageManager.queryIntentActivities(
                    intent, PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY,
                ).firstOrNull()?.activityInfo ?: throw ActivityNotFoundException()
                intent.component = ComponentName(systemInstaller.packageName, systemInstaller.name)
                installPackage.launch(intent)
            } catch (_: ActivityNotFoundException) {
                app.updates.installResult(InstallOutcome.Failed)
            } catch (_: SecurityException) {
                app.updates.installResult(InstallOutcome.Failed)
            } catch (_: IllegalArgumentException) {
                app.updates.installResult(InstallOutcome.Failed)
            }
        }
    }
}

