package com.artemis.ebookreader.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.artemis.ebookreader.data.preferences.AppColorTheme

internal fun fixedAppColors(theme: AppColorTheme, dark: Boolean): ColorScheme = when (theme) {
    AppColorTheme.MaterialYou -> materialFallback
    AppColorTheme.Lavender -> lavender
    AppColorTheme.AppleGreen -> appleGreen
    AppColorTheme.Ocean -> ocean
    AppColorTheme.WarmSand -> warmSand
    AppColorTheme.Rosewood -> rosewood
    AppColorTheme.Graphite -> graphite
    AppColorTheme.Midnight -> midnight
}.let { if (dark) it.dark else it.light }

internal fun ColorScheme.withAmoledSurfaces(): ColorScheme {
    val tint = surfaceContainer
    fun elevated(gray: Color) = lerp(gray, tint, 0.12f)

    return copy(
        background = Color.Black,
        surface = elevated(Color(0xFF101010)),
        surfaceDim = elevated(Color(0xFF080808)),
        surfaceBright = elevated(Color(0xFF282828)),
        surfaceVariant = elevated(Color(0xFF252525)),
        surfaceContainerLowest = Color(0xFF050505),
        surfaceContainerLow = elevated(Color(0xFF111111)),
        surfaceContainer = elevated(Color(0xFF171717)),
        surfaceContainerHigh = elevated(Color(0xFF1D1D1D)),
        surfaceContainerHighest = elevated(Color(0xFF242424)),
    )
}

private class Accent(tone: Long, container: Long, ink: Long) {
    val tone = Color(tone)
    val container = Color(container)
    val ink = Color(ink)
}

private class Palette(
    background: Long,
    container: Long,
    text: Long,
    mutedText: Long,
    outline: Long,
    outlineVariant: Long,
    val primary: Accent,
    val secondary: Accent,
    val tertiary: Accent,
) {
    val background = Color(background)
    val container = Color(container)
    val text = Color(text)
    val mutedText = Color(mutedText)
    val outline = Color(outline)
    val outlineVariant = Color(outlineVariant)

    fun scheme(dark: Boolean, inverse: Palette, fixed: Palette): ColorScheme {
        val base = if (dark) darkColorScheme() else lightColorScheme()
        val lowest = lerp(background, if (dark) Color.Black else Color.White, 0.35f)
        val low = lerp(background, container, if (dark) 0.65f else 0.30f)
        val high = lerp(container, outlineVariant, 0.18f)
        val highest = lerp(container, outlineVariant, 0.32f)
        fun onAccent(accent: Accent) = if (dark) accent.ink else Color.White
        fun onContainer(accent: Accent) = if (dark) accent.tone else accent.ink
        fun fixedDim(accent: Accent) = lerp(accent.container, accent.tone, 0.20f)

        return base.copy(
            primary = primary.tone,
            onPrimary = onAccent(primary),
            primaryContainer = primary.container,
            onPrimaryContainer = onContainer(primary),
            inversePrimary = inverse.primary.tone,
            secondary = secondary.tone,
            onSecondary = onAccent(secondary),
            secondaryContainer = secondary.container,
            onSecondaryContainer = onContainer(secondary),
            tertiary = tertiary.tone,
            onTertiary = onAccent(tertiary),
            tertiaryContainer = tertiary.container,
            onTertiaryContainer = onContainer(tertiary),
            background = background,
            onBackground = text,
            surface = background,
            onSurface = text,
            surfaceVariant = highest,
            onSurfaceVariant = mutedText,
            surfaceTint = primary.tone,
            inverseSurface = inverse.background,
            inverseOnSurface = inverse.text,
            error = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A),
            onError = if (dark) Color(0xFF690005) else Color.White,
            errorContainer = if (dark) Color(0xFF93000A) else Color(0xFFFFDAD6),
            onErrorContainer = if (dark) Color(0xFFFFDAD6) else Color(0xFF410002),
            outline = outline,
            outlineVariant = outlineVariant,
            scrim = Color.Black,
            surfaceBright = if (dark) highest else lowest,
            surfaceDim = if (dark) lowest else highest,
            surfaceContainer = container,
            surfaceContainerHigh = high,
            surfaceContainerHighest = highest,
            surfaceContainerLow = low,
            surfaceContainerLowest = lowest,
            primaryFixed = fixed.primary.container,
            primaryFixedDim = fixedDim(fixed.primary),
            onPrimaryFixed = fixed.primary.ink,
            onPrimaryFixedVariant = fixed.primary.ink,
            secondaryFixed = fixed.secondary.container,
            secondaryFixedDim = fixedDim(fixed.secondary),
            onSecondaryFixed = fixed.secondary.ink,
            onSecondaryFixedVariant = fixed.secondary.ink,
            tertiaryFixed = fixed.tertiary.container,
            tertiaryFixedDim = fixedDim(fixed.tertiary),
            onTertiaryFixed = fixed.tertiary.ink,
            onTertiaryFixedVariant = fixed.tertiary.ink,
        )
    }
}

private class AppColorSchemes(light: Palette, dark: Palette) {
    val light = light.scheme(dark = false, inverse = dark, fixed = light)
    val dark = dark.scheme(dark = true, inverse = light, fixed = light)
}

private val materialFallback = AppColorSchemes(
    light = Palette(
        background = 0xFFF9F9F4, container = 0xFFECEFE8, text = 0xFF1A1C19,
        mutedText = 0xFF424942, outline = 0xFF6F796F, outlineVariant = 0xFFBEC9BF,
        primary = Accent(0xFF315C4E, 0xFFC6ECDB, 0xFF0A3024),
        secondary = Accent(0xFF496158, 0xFFD9EBE3, 0xFF16382D),
        tertiary = Accent(0xFF4E607D, 0xFFD8E3FF, 0xFF152F4E),
    ),
    dark = Palette(
        background = 0xFF141713, container = 0xFF20261F, text = 0xFFE2E5DC,
        mutedText = 0xFFBFC9C0, outline = 0xFF89968B, outlineVariant = 0xFF434B43,
        primary = Accent(0xFFA3D1BE, 0xFF28483D, 0xFF17372D),
        secondary = Accent(0xFFB1CBBF, 0xFF2B473C, 0xFF1C352B),
        tertiary = Accent(0xFFB5C8E9, 0xFF304460, 0xFF243754),
    ),
)

private val lavender = AppColorSchemes(
    light = Palette(
        background = 0xFFF9F5FF, container = 0xFFEDE4FA, text = 0xFF32283E,
        mutedText = 0xFF514763, outline = 0xFF786A87, outlineVariant = 0xFFC6B9D4,
        primary = Accent(0xFF7959B0, 0xFFECDCFF, 0xFF341953),
        secondary = Accent(0xFF64597A, 0xFFE8DEF6, 0xFF342944),
        tertiary = Accent(0xFF895472, 0xFFFFD9EC, 0xFF4A2139),
    ),
    dark = Palette(
        background = 0xFF201C2B, container = 0xFF302A40, text = 0xFFE8DFF5,
        mutedText = 0xFFC8BED9, outline = 0xFF9D91B1, outlineVariant = 0xFF534968,
        primary = Accent(0xFFBCA7E8, 0xFF4D3B69, 0xFF382951),
        secondary = Accent(0xFFD1C1E4, 0xFF493F59, 0xFF362E47),
        tertiary = Accent(0xFFE9B5D0, 0xFF573B4D, 0xFF412839),
    ),
)

private val appleGreen = AppColorSchemes(
    light = Palette(
        background = 0xFFF5F9EF, container = 0xFFE4EED8, text = 0xFF26351E,
        mutedText = 0xFF46543D, outline = 0xFF69775E, outlineVariant = 0xFFB7C7A9,
        primary = Accent(0xFF527A35, 0xFFD5EAB9, 0xFF253F16),
        secondary = Accent(0xFF536846, 0xFFDCE8CD, 0xFF283B1D),
        tertiary = Accent(0xFF386C62, 0xFFBFEDE0, 0xFF123D35),
    ),
    dark = Palette(
        background = 0xFF19231B, container = 0xFF29352A, text = 0xFFE0EBD6,
        mutedText = 0xFFBAC9B0, outline = 0xFF91A187, outlineVariant = 0xFF485945,
        primary = Accent(0xFFA8D477, 0xFF344F27, 0xFF243C18),
        secondary = Accent(0xFFBECFA9, 0xFF3B4D33, 0xFF2C3B25),
        tertiary = Accent(0xFF94D1C1, 0xFF295148, 0xFF193D35),
    ),
)

private val ocean = AppColorSchemes(
    light = Palette(
        background = 0xFFF0F8FA, container = 0xFFDCEEF2, text = 0xFF203840,
        mutedText = 0xFF435861, outline = 0xFF647680, outlineVariant = 0xFFB0C6CF,
        primary = Accent(0xFF256D85, 0xFFC7EBF6, 0xFF113D4D),
        secondary = Accent(0xFF486572, 0xFFD7EAF0, 0xFF1F3D49),
        tertiary = Accent(0xFF4C6091, 0xFFDAE3FF, 0xFF263752),
    ),
    dark = Palette(
        background = 0xFF15232C, container = 0xFF23343E, text = 0xFFD8EAF0,
        mutedText = 0xFFBACFD8, outline = 0xFF899EAA, outlineVariant = 0xFF445965,
        primary = Accent(0xFF80C8DC, 0xFF224B5B, 0xFF173C48),
        secondary = Accent(0xFFACCBD7, 0xFF344D5A, 0xFF253D49),
        tertiary = Accent(0xFFB4C6F1, 0xFF384968, 0xFF293955),
    ),
)

private val warmSand = AppColorSchemes(
    light = Palette(
        background = 0xFFFAF5EB, container = 0xFFEFE3D1, text = 0xFF403329,
        mutedText = 0xFF5B4D41, outline = 0xFF7D6D60, outlineVariant = 0xFFCAB9A7,
        primary = Accent(0xFF89633D, 0xFFF5DFC4, 0xFF472F17),
        secondary = Accent(0xFF705C49, 0xFFECE0CD, 0xFF422F20),
        tertiary = Accent(0xFF80555A, 0xFFFADDDD, 0xFF4A2931),
    ),
    dark = Palette(
        background = 0xFF241E19, container = 0xFF393027, text = 0xFFEFE0CE,
        mutedText = 0xFFCFC0AF, outline = 0xFFA89A88, outlineVariant = 0xFF5D4E40,
        primary = Accent(0xFFD7B68A, 0xFF573F2A, 0xFF422E1A),
        secondary = Accent(0xFFD0BBA2, 0xFF504234, 0xFF3F3023),
        tertiary = Accent(0xFFE4B6BD, 0xFF563D43, 0xFF422C31),
    ),
)

private val rosewood = AppColorSchemes(
    light = Palette(
        background = 0xFFFFF3F5, container = 0xFFF4DDE3, text = 0xFF432A33,
        mutedText = 0xFF644B54, outline = 0xFF826773, outlineVariant = 0xFFD5B7C1,
        primary = Accent(0xFFA34F68, 0xFFFFD8E4, 0xFF502134),
        secondary = Accent(0xFF765563, 0xFFF3DBE4, 0xFF442A35),
        tertiary = Accent(0xFF825641, 0xFFFEDBC9, 0xFF46291C),
    ),
    dark = Palette(
        background = 0xFF281C22, container = 0xFF3D2A33, text = 0xFFF1DDE5,
        mutedText = 0xFFD5BAC5, outline = 0xFFAC8E9B, outlineVariant = 0xFF614551,
        primary = Accent(0xFFE5A0B5, 0xFF5C3343, 0xFF4B2434),
        secondary = Accent(0xFFD9BAC8, 0xFF533E49, 0xFF412C36),
        tertiary = Accent(0xFFEEB9A2, 0xFF593D30, 0xFF472D21),
    ),
)

private val graphite = AppColorSchemes(
    light = Palette(
        background = 0xFFF5F5F5, container = 0xFFE5E5E5, text = 0xFF222222,
        mutedText = 0xFF484848, outline = 0xFF6D6D6D, outlineVariant = 0xFFBDBDBD,
        primary = Accent(0xFF555555, 0xFFD9D9D9, 0xFF252525),
        secondary = Accent(0xFF606060, 0xFFDDDDDD, 0xFF2F2F2F),
        tertiary = Accent(0xFF696969, 0xFFE0E0E0, 0xFF343434),
    ),
    dark = Palette(
        background = 0xFF191919, container = 0xFF2B2B2B, text = 0xFFE8E8E8,
        mutedText = 0xFFC5C5C5, outline = 0xFF999999, outlineVariant = 0xFF4C4C4C,
        primary = Accent(0xFFCCCCCC, 0xFF434343, 0xFF303030),
        secondary = Accent(0xFFBEBEBE, 0xFF3D3D3D, 0xFF292929),
        tertiary = Accent(0xFFD6D6D6, 0xFF4B4B4B, 0xFF373737),
    ),
)

private val midnight = AppColorSchemes(
    light = Palette(
        background = 0xFFF1F4FF, container = 0xFFDFE5F7, text = 0xFF242D49,
        mutedText = 0xFF48536F, outline = 0xFF6C7794, outlineVariant = 0xFFB7C1DE,
        primary = Accent(0xFF465BA5, 0xFFDCE3FF, 0xFF23346B),
        secondary = Accent(0xFF525E7B, 0xFFDDE5FA, 0xFF2B3652),
        tertiary = Accent(0xFF675789, 0xFFEDE0FF, 0xFF3D2C5D),
    ),
    dark = Palette(
        background = 0xFF141A2C, container = 0xFF242E46, text = 0xFFE0E6FA,
        mutedText = 0xFFBDC8E2, outline = 0xFF919DBE, outlineVariant = 0xFF455170,
        primary = Accent(0xFFA5B7F5, 0xFF354575, 0xFF253661),
        secondary = Accent(0xFFBCC8EA, 0xFF3D4863, 0xFF2C3753),
        tertiary = Accent(0xFFD3C0F4, 0xFF4D4068, 0xFF3C2E55),
    ),
)
