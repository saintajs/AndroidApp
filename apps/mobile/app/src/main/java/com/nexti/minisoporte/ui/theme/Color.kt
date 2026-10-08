package com.nexti.minisoporte.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * Approved design tokens, hex values VERBATIM from config.yaml `design_tokens.color-palette`.
 *
 * This file is the ONLY place raw hex values exist in the codebase (design.md Decision 2,
 * CA-03 / BR-036). Feature code reads colors exclusively through `MaterialTheme.colorScheme.*`
 * or, for badge-specific colors that are not part of the Material scheme, through these
 * named constants — never a raw hex literal (INSTR-012).
 */

// ---------------------------------------------------------------------------
// Brand
// ---------------------------------------------------------------------------

/** App bar / hero band background, primary buttons, selected chips & choice options, active states. */
val Primary = Color(0xFF1E3A2A)

/** Pressed / hover state of primary buttons and selected controls. */
val PrimaryHover = Color(0xFF2A4E39)

/** FAB background, brand accent, demo-hint border highlights. */
val Secondary = Color(0xFFEFC64E)

/** Demo hint box background, "Pendiente" status badge background. */
val AccentSoft = Color(0xFFFBF1D4)

// ---------------------------------------------------------------------------
// Surfaces & text
// ---------------------------------------------------------------------------

/** Screen background behind cards and bars. */
val Background = Color(0xFFF5F7F5)

/** Cards, bottom navigation bar, form fields, sheet backgrounds. */
val Surface = Color(0xFFFFFFFF)

/** Primary text: titles, body, field values. */
val TextPrimary = Color(0xFF1C2B22)

/** Secondary text: subtitles, metadata, hints, counters. */
val TextMuted = Color(0xFF5C6B62)

/** Card borders, field outlines, dividers, bottom-nav top border. */
val Border = Color(0xFFDDE4DF)

/** Text and icons on primary surfaces (app bar, primary buttons). */
val TextOnPrimary = Color(0xFFFFFFFF)

// ---------------------------------------------------------------------------
// Danger
// ---------------------------------------------------------------------------

/** Field error borders/text, destructive semantics. */
val Danger = Color(0xFFB3261E)

/** Error field background. */
val DangerSoft = Color(0xFFFBEAE9)

// ---------------------------------------------------------------------------
// Status badge pairs — badge-specific, NOT part of MaterialTheme.colorScheme.
// StatusBadge (later story) reads these named constants, never raw hex.
// ---------------------------------------------------------------------------

/** "Pendiente" status badge background. */
val StatusPendingBg = Color(0xFFFBF1D4)

/** "Pendiente" status badge text. */
val StatusPendingFg = Color(0xFF7A5B10)

/** "En progreso" status badge background. */
val StatusInProgressBg = Color(0xFFE1EAF6)

/** "En progreso" status badge text. */
val StatusInProgressFg = Color(0xFF2A4E79)

/** "Resuelta" status badge background. */
val StatusResolvedBg = Color(0xFFDFEFE3)

/** "Resuelta" status badge text. */
val StatusResolvedFg = Color(0xFF1E5B36)

// ---------------------------------------------------------------------------
// Priority pill pairs — pill-specific, NOT part of MaterialTheme.colorScheme.
// ---------------------------------------------------------------------------

/** "Alta" priority pill border. */
val PriorityHighBorder = Color(0xFFC4463E)

/** "Alta" priority pill text. */
val PriorityHighText = Color(0xFFA32E27)

/** "Media" priority pill border. */
val PriorityMediumBorder = Color(0xFFB98A1E)

/** "Media" priority pill text. */
val PriorityMediumText = Color(0xFF7A5B10)

/** "Baja" priority pill border. */
val PriorityLowBorder = Color(0xFF6B8F7A)

/** "Baja" priority pill text. */
val PriorityLowText = Color(0xFF446B54)
