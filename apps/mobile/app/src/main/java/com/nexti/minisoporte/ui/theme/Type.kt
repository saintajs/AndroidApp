package com.nexti.minisoporte.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Approved typography tokens mapped onto the Material 3 type scale, sizes and weights
 * verbatim from config.yaml `design_tokens.typography`. Roboto via [FontFamily.Default]
 * everywhere — no custom font resources.
 *
 * Mapping (config token -> M3 style):
 *  - heading-xl  26sp/800 -> headlineSmall   (screen hero titles)
 *  - heading-lg  18sp/700 -> titleLarge      (section titles)
 *  - heading-md  15sp/700 -> titleMedium     (card titles)
 *  - label       13sp/600 -> titleSmall      (field labels, list subtitles)
 *  - body        15sp/400 -> bodyLarge
 *  - body-small  14sp/400 -> bodyMedium
 *  - caption     12.5sp/400 -> bodySmall
 *  - button      15sp/700 -> labelLarge      (buttons read labelLarge by default)
 *  - chip        13.5sp/600 -> labelMedium
 *  - bottom-nav  11.5sp/600 -> labelSmall    (bottom navigation reads labelSmall)
 *  - badge       12sp/700 -> [BadgeTextStyle] (status badges, exposed separately)
 *
 * Line heights are the config values (size x factor) carried through exactly.
 * Feature code reads typography via `MaterialTheme.typography.*` only (CA-03).
 */

private val AppFontFamily = FontFamily.Default

private val HeadingXl =
    TextStyle(
        fontFamily = AppFontFamily,
        fontSize = 26.sp,
        fontWeight = FontWeight.ExtraBold, // 800
        lineHeight = 31.2.sp, // 1.2
    )

private val HeadingLg =
    TextStyle(
        fontFamily = AppFontFamily,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold, // 700
        lineHeight = 23.4.sp, // 1.3
    )

private val HeadingMd =
    TextStyle(
        fontFamily = AppFontFamily,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold, // 700
        lineHeight = 21.sp, // 1.4
    )

private val Label =
    TextStyle(
        fontFamily = AppFontFamily,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold, // 600
        lineHeight = 17.55.sp, // 1.35
    )

private val Body =
    TextStyle(
        fontFamily = AppFontFamily,
        fontSize = 15.sp,
        fontWeight = FontWeight.Normal, // 400
        lineHeight = 22.5.sp, // 1.5
    )

private val BodySmall =
    TextStyle(
        fontFamily = AppFontFamily,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal, // 400
        lineHeight = 20.3.sp, // 1.45
    )

private val Caption =
    TextStyle(
        fontFamily = AppFontFamily,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Normal, // 400
        lineHeight = 17.5.sp, // 1.4
    )

private val Button =
    TextStyle(
        fontFamily = AppFontFamily,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold, // 700
        lineHeight = 18.sp, // 1.2
    )

private val Chip =
    TextStyle(
        fontFamily = AppFontFamily,
        fontSize = 13.5.sp,
        fontWeight = FontWeight.SemiBold, // 600
        lineHeight = 16.2.sp, // 1.2
    )

private val BottomNav =
    TextStyle(
        fontFamily = AppFontFamily,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold, // 600
        lineHeight = 13.8.sp, // 1.2
    )

// Material 3 typography assembled from the approved tokens above

/** Material 3 typography built from the approved tokens. */
val MiniSoporteTypography =
    Typography(
        headlineSmall = HeadingXl,
        titleLarge = HeadingLg,
        titleMedium = HeadingMd,
        titleSmall = Label,
        bodyLarge = Body,
        bodyMedium = BodySmall,
        bodySmall = Caption,
        labelLarge = Button,
        labelMedium = Chip,
        labelSmall = BottomNav,
    )

// Badge style (12sp / 700) — status badges read this named style directly

/** Badge typography token (12sp / 700): status badges read this named style directly. */
val BadgeTextStyle =
    TextStyle(
        fontFamily = AppFontFamily,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold, // 700
        lineHeight = 14.4.sp, // 1.2
    )
