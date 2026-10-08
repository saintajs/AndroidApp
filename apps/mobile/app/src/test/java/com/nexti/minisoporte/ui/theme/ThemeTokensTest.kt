/*
 * Plain JVM unit tests for the approved design tokens (design.md Decisions 2 and 7,
 * spec "Material 3 theme mapping the approved design tokens").
 *
 * Compose composition cannot run on the plain JVM and Robolectric is not available
 * (CA-01, offline gate), so token fidelity is asserted at the VALUE level: the same
 * `lightColorScheme` mapping Theme.kt uses is rebuilt here from the Color.kt named
 * constants, and every approved hex/radius/spacing/typography value is asserted
 * exactly. `Color`, `lightColorScheme`, `Shapes`, `Typography`, `TextStyle`, `Dp`
 * and `TextUnit` are all pure JVM-safe values — no Android runtime required.
 */
package com.nexti.minisoporte.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

class ThemeTokensTest {
    // The exact role mapping Theme.kt applies to the approved tokens (LightColorScheme
    // is private there, so the test re-derives it from the same named constants).
    private val lightScheme =
        lightColorScheme(
            primary = Primary,
            onPrimary = TextOnPrimary,
            primaryContainer = PrimaryHover,
            onPrimaryContainer = TextOnPrimary,
            inversePrimary = AccentSoft,
            secondary = Secondary,
            onSecondary = TextPrimary,
            secondaryContainer = AccentSoft,
            onSecondaryContainer = TextPrimary,
            tertiary = PrimaryHover,
            onTertiary = TextOnPrimary,
            background = Background,
            onBackground = TextPrimary,
            surface = Surface,
            onSurface = TextPrimary,
            surfaceVariant = Background,
            onSurfaceVariant = TextMuted,
            surfaceTint = Primary,
            inverseSurface = TextPrimary,
            inverseOnSurface = Surface,
            error = Danger,
            onError = TextOnPrimary,
            errorContainer = DangerSoft,
            onErrorContainer = Danger,
            outline = Border,
            outlineVariant = Border,
            scrim = TextPrimary,
        )

    @Test
    fun brandTokensMatchTheApprovedHexValues() {
        assertEquals(Color(0xFF1E3A2A), Primary)
        assertEquals(Color(0xFF2A4E39), PrimaryHover)
        assertEquals(Color(0xFFEFC64E), Secondary)
        assertEquals(Color(0xFFFBF1D4), AccentSoft)
    }

    @Test
    fun surfaceAndTextTokensMatchTheApprovedHexValues() {
        assertEquals(Color(0xFFF5F7F5), Background)
        assertEquals(Color(0xFFFFFFFF), Surface)
        assertEquals(Color(0xFF1C2B22), TextPrimary)
        assertEquals(Color(0xFF5C6B62), TextMuted)
        assertEquals(Color(0xFFDDE4DF), Border)
        assertEquals(Color(0xFFFFFFFF), TextOnPrimary)
    }

    @Test
    fun dangerTokensMatchTheApprovedHexValues() {
        assertEquals(Color(0xFFB3261E), Danger)
        assertEquals(Color(0xFFFBEAE9), DangerSoft)
    }

    @Test
    fun statusBadgePairsMatchTheApprovedHexValues() {
        assertEquals(Color(0xFFFBF1D4), StatusPendingBg)
        assertEquals(Color(0xFF7A5B10), StatusPendingFg)
        assertEquals(Color(0xFFE1EAF6), StatusInProgressBg)
        assertEquals(Color(0xFF2A4E79), StatusInProgressFg)
        assertEquals(Color(0xFFDFEFE3), StatusResolvedBg)
        assertEquals(Color(0xFF1E5B36), StatusResolvedFg)
    }

    @Test
    fun priorityPillPairsMatchTheApprovedHexValues() {
        assertEquals(Color(0xFFC4463E), PriorityHighBorder)
        assertEquals(Color(0xFFA32E27), PriorityHighText)
        assertEquals(Color(0xFFB98A1E), PriorityMediumBorder)
        assertEquals(Color(0xFF7A5B10), PriorityMediumText)
        assertEquals(Color(0xFF6B8F7A), PriorityLowBorder)
        assertEquals(Color(0xFF446B54), PriorityLowText)
    }

    @Test
    fun lightSchemeMapsTheBrandTokensOntoMaterialRoles() {
        assertEquals(Primary, lightScheme.primary)
        assertEquals(TextOnPrimary, lightScheme.onPrimary)
        assertEquals(PrimaryHover, lightScheme.primaryContainer)
        assertEquals(TextOnPrimary, lightScheme.onPrimaryContainer)
        assertEquals(AccentSoft, lightScheme.inversePrimary)
        assertEquals(Secondary, lightScheme.secondary)
        assertEquals(TextPrimary, lightScheme.onSecondary)
        assertEquals(AccentSoft, lightScheme.secondaryContainer)
        assertEquals(TextPrimary, lightScheme.onSecondaryContainer)
        assertEquals(PrimaryHover, lightScheme.tertiary)
        assertEquals(TextOnPrimary, lightScheme.onTertiary)
    }

    @Test
    fun lightSchemeMapsTheSurfaceTextAndOutlineTokensOntoMaterialRoles() {
        assertEquals(Background, lightScheme.background)
        assertEquals(TextPrimary, lightScheme.onBackground)
        assertEquals(Surface, lightScheme.surface)
        assertEquals(TextPrimary, lightScheme.onSurface)
        assertEquals(Background, lightScheme.surfaceVariant)
        assertEquals(TextMuted, lightScheme.onSurfaceVariant)
        assertEquals(Primary, lightScheme.surfaceTint)
        assertEquals(TextPrimary, lightScheme.inverseSurface)
        assertEquals(Surface, lightScheme.inverseOnSurface)
        assertEquals(Border, lightScheme.outline)
        assertEquals(Border, lightScheme.outlineVariant)
        assertEquals(TextPrimary, lightScheme.scrim)
    }

    @Test
    fun lightSchemeMapsTheDangerTokensOntoMaterialRoles() {
        assertEquals(Danger, lightScheme.error)
        assertEquals(TextOnPrimary, lightScheme.onError)
        assertEquals(DangerSoft, lightScheme.errorContainer)
        assertEquals(Danger, lightScheme.onErrorContainer)
    }

    @Test
    fun lightSchemeResolvesTheApprovedHexValuesExactly() {
        assertEquals(Color(0xFF1E3A2A), lightScheme.primary)
        assertEquals(Color(0xFFEFC64E), lightScheme.secondary)
        assertEquals(Color(0xFFF5F7F5), lightScheme.background)
        assertEquals(Color(0xFFFFFFFF), lightScheme.surface)
        assertEquals(Color(0xFF1C2B22), lightScheme.onSurface)
        assertEquals(Color(0xFF5C6B62), lightScheme.onSurfaceVariant)
        assertEquals(Color(0xFFDDE4DF), lightScheme.outline)
        assertEquals(Color(0xFFB3261E), lightScheme.error)
        assertEquals(Color(0xFFFBEAE9), lightScheme.errorContainer)
    }

    @Test
    fun everyShapeBucketUsesTheApproved8dpCornerRadius() {
        val corner = RoundedCornerShape(8.dp)
        assertEquals(corner, MiniSoporteShapes.extraSmall)
        assertEquals(corner, MiniSoporteShapes.small)
        assertEquals(corner, MiniSoporteShapes.medium)
        assertEquals(corner, MiniSoporteShapes.large)
        assertEquals(corner, MiniSoporteShapes.extraLarge)
    }

    @Test
    fun pillShapeIsTheFullRoundingShape() {
        assertEquals(RoundedCornerShape(percent = 50), PillShape)
    }

    @Test
    fun spacingGridIsTheApproved8dpBase() {
        assertEquals(8.dp, Spacing.space1)
        assertEquals(16.dp, Spacing.space2)
        assertEquals(24.dp, Spacing.space3)
        assertEquals(32.dp, Spacing.space4)
    }

    @Test
    fun everyTypographyStyleUsesTheDefaultRobotoFontFamily() {
        val styles =
            listOf(
                MiniSoporteTypography.headlineSmall,
                MiniSoporteTypography.titleLarge,
                MiniSoporteTypography.titleMedium,
                MiniSoporteTypography.titleSmall,
                MiniSoporteTypography.bodyLarge,
                MiniSoporteTypography.bodyMedium,
                MiniSoporteTypography.bodySmall,
                MiniSoporteTypography.labelLarge,
                MiniSoporteTypography.labelMedium,
                MiniSoporteTypography.labelSmall,
                BadgeTextStyle,
            )
        styles.forEach { style ->
            assertEquals(FontFamily.Default, style.fontFamily)
        }
    }

    @Test
    fun typographyStylesCarryTheApprovedSizesWeightsAndLineHeights() {
        val approved =
            listOf(
                ApprovedStyle(MiniSoporteTypography.headlineSmall, 26.sp, FontWeight.ExtraBold, 31.2.sp),
                ApprovedStyle(MiniSoporteTypography.titleLarge, 18.sp, FontWeight.Bold, 23.4.sp),
                ApprovedStyle(MiniSoporteTypography.titleMedium, 15.sp, FontWeight.Bold, 21.sp),
                ApprovedStyle(MiniSoporteTypography.titleSmall, 13.sp, FontWeight.SemiBold, 17.55.sp),
                ApprovedStyle(MiniSoporteTypography.bodyLarge, 15.sp, FontWeight.Normal, 22.5.sp),
                ApprovedStyle(MiniSoporteTypography.bodyMedium, 14.sp, FontWeight.Normal, 20.3.sp),
                ApprovedStyle(MiniSoporteTypography.bodySmall, 12.5.sp, FontWeight.Normal, 17.5.sp),
                ApprovedStyle(MiniSoporteTypography.labelLarge, 15.sp, FontWeight.Bold, 18.sp),
                ApprovedStyle(MiniSoporteTypography.labelMedium, 13.5.sp, FontWeight.SemiBold, 16.2.sp),
                ApprovedStyle(MiniSoporteTypography.labelSmall, 11.5.sp, FontWeight.SemiBold, 13.8.sp),
                ApprovedStyle(BadgeTextStyle, 12.sp, FontWeight.Bold, 14.4.sp),
            )
        approved.forEach { token ->
            assertEquals(token.fontSize, token.style.fontSize)
            assertEquals(token.fontWeight, token.style.fontWeight)
            assertEquals(token.lineHeight, token.style.lineHeight)
        }
    }

    @Test
    fun headlineSmallTextStyleEqualsTheHeadingXlTokenVerbatim() {
        val expected =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 31.2.sp,
            )
        assertEquals(expected, MiniSoporteTypography.headlineSmall)
    }

    @Test
    fun badgeTextStyleEqualsTheBadgeTokenVerbatim() {
        val expected =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 14.4.sp,
            )
        assertEquals(expected, BadgeTextStyle)
    }

    private data class ApprovedStyle(
        val style: TextStyle,
        val fontSize: TextUnit,
        val fontWeight: FontWeight,
        val lineHeight: TextUnit,
    )
}
