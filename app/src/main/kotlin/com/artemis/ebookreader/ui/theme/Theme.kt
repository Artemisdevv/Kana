package com.artemis.ebookreader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.artemis.ebookreader.data.preferences.AppColorTheme

@Composable
internal fun EBookReaderTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    EBookReaderTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

@Composable
internal fun EBookReaderTheme(colorScheme: ColorScheme, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}

@Composable
internal fun appColorScheme(theme: AppColorTheme, dark: Boolean, amoledBlack: Boolean): ColorScheme {
    val base = if (theme == AppColorTheme.MaterialYou && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        val configuration = LocalConfiguration.current
        remember(context, configuration, dark) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
    } else {
        fixedAppColors(theme, dark)
    }
    return remember(base, dark, amoledBlack) {
        if (dark && amoledBlack) base.withAmoledSurfaces() else base
    }
}

