package com.artemis.ebookreader.app

import android.app.ActivityOptions
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.artemis.ebookreader.R
import com.artemis.ebookreader.feature.library.LibraryViewModel
import com.artemis.ebookreader.feature.reader.ReaderActivity
import com.artemis.ebookreader.ui.theme.EBookReaderTheme
import com.artemis.ebookreader.ui.theme.appColorScheme

class MainActivity : ComponentActivity() {
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
        val app = application as EBookReaderApplication
        setContent {
            val appearance by app.appAppearance.appearance.collectAsStateWithLifecycle()
            val dark = appearance.mode.isDark(isSystemInDarkTheme())
            val colors = appColorScheme(appearance.colorTheme, dark, appearance.amoledBlack)
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            EBookReaderTheme(colorScheme = colors) {
                ReaderShell(library, app.readerAppearance, app.appAppearance) { bookId ->
                    val legacyTransition = if (Build.VERSION.SDK_INT < 34) {
                        ActivityOptions.makeCustomAnimation(this, R.anim.reader_fade_in, R.anim.reader_hold).toBundle()
                    } else null
                    startActivity(ReaderActivity.intent(this, bookId), legacyTransition)
                }
            }
        }
    }
}

