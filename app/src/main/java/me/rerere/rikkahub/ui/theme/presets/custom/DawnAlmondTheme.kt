/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.ui.theme.presets.custom

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

val DawnAlmondThemePreset by lazy {
    PresetTheme(
        id = "dawnalmond",
        name = {
            Text(stringResource(id = R.string.theme_name_dawnalmond))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

// ── Light ──────────────────────────────────────────────────────────────
// 沉稳高级的暮烟紫主强调色
private val primaryLight = Color(0xFF755288)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFEDE4F5)
private val onPrimaryContainerLight = Color(0xFF2E143E)

private val secondaryLight = Color(0xFF866996)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFF4EDF8)
private val onSecondaryContainerLight = Color(0xFF3B254A)

// 暖金点缀（连击/高光）
private val tertiaryLight = Color(0xFF8C5E1B)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFFCECCE)
private val onTertiaryContainerLight = Color(0xFF2E1C00)

private val errorLight = Color(0xFFBA1A1A)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFFFDAD6)
private val onErrorContainerLight = Color(0xFF93000A)

// 统一极简象牙暖杏底色：background 与 surface 与 surfaceContainer 一致，彻底消灭顶栏割裂横线
private val backgroundLight = Color(0xFFFAF6F0)
private val surfaceLight = Color(0xFFFAF6F0)
private val surfaceContainerLight = Color(0xFFFAF6F0)
private val surfaceContainerLowLight = Color(0xFFF9F6EA)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF) // 纯白卡片
private val surfaceContainerHighLight = Color(0xFFF8EAD0)
private val surfaceContainerHighestLight = Color(0xFFEAE2D6)
private val surfaceBrightLight = Color(0xFFFFFFFF)
private val surfaceDimLight = Color(0xFFECE4D8)
private val surfaceVariantLight = Color(0xFFEDE5D9)

// 暖黛墨黑主字（高对比度，极致清晰护眼）与深暮霭灰副字（清晰不发虚）
private val onBackgroundLight = Color(0xFF262028)
private val onSurfaceLight = Color(0xFF262028)
private val onSurfaceVariantLight = Color(0xFF554C59)

private val outlineLight = Color(0xFF7E7382)
private val outlineVariantLight = Color(0xFFE0D8CE) // 柔和浅驼卡片边框
private val scrimLight = Color(0xFF000000)

private val inverseSurfaceLight = Color(0xFF322C35)
private val inverseOnSurfaceLight = Color(0xFFF7F1F8)
private val inversePrimaryLight = Color(0xFFD5BAE6)

// ── Dark ───────────────────────────────────────────────────────────────
private val primaryDark = Color(0xFFD4BAE6)
private val onPrimaryDark = Color(0xFF3B234D)
private val primaryContainerDark = Color(0xFF563968)
private val onPrimaryContainerDark = Color(0xFFF2E6F9)

private val secondaryDark = Color(0xFFD2BEE0)
private val onSecondaryDark = Color(0xFF392745)
private val secondaryContainerDark = Color(0xFF503D5C)
private val onSecondaryContainerDark = Color(0xFFF0E5F7)

private val tertiaryDark = Color(0xFFEFC368)
private val onTertiaryDark = Color(0xFF422E00)
private val tertiaryContainerDark = Color(0xFF604505)
private val onTertiaryContainerDark = Color(0xFFFFDF9E)

private val errorDark = Color(0xFFFFB4AB)
private val onErrorDark = Color(0xFF690005)
private val errorContainerDark = Color(0xFF93000A)
private val onErrorContainerDark = Color(0xFFFFDAD6)

private val backgroundDark = Color(0xFF1E1A20)
private val surfaceDark = Color(0xFF1E1A20)
private val surfaceContainerDark = Color(0xFF1E1A20)
private val surfaceContainerLowDark = Color(0xFF231F26)
private val surfaceContainerLowestDark = Color(0xFF131015)
private val surfaceContainerHighDark = Color(0xFF2E2931)
private val surfaceContainerHighestDark = Color(0xFF353039)
private val surfaceBrightDark = Color(0xFF38323B)
private val surfaceDimDark = Color(0xFF18151A)
private val surfaceVariantDark = Color(0xFF47404B)

private val onBackgroundDark = Color(0xFFECE4ED)
private val onSurfaceDark = Color(0xFFECE4ED)
private val onSurfaceVariantDark = Color(0xFFCCC2CF)

private val outlineDark = Color(0xFF968D9B)
private val outlineVariantDark = Color(0xFF4A434F)
private val scrimDark = Color(0xFF000000)

private val inverseSurfaceDark = Color(0xFFECE4ED)
private val inverseOnSurfaceDark = Color(0xFF322C35)
private val inversePrimaryDark = Color(0xFF755288)

private val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

private val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)
