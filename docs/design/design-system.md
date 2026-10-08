## Visual Design System

This design system defines the visual language for MiniSoporte (native Android, Kotlin + Jetpack Compose, portrait phone, Spanish-language UI, package `com.nexti.minisoporte`). All values below are extracted verbatim from the user-approved Pre-Design draft `docs/design/pre_design_mobile.html` (mobile mode, 390×844 baseline). Production screens implement these tokens natively in Compose (Material 3 theming driven by `Color.kt` / `Type.kt` / `Shape.kt` equivalents of the values below).

### Color Palette

| Token | Hex | Usage |
|---|---|---|
| primary | #1E3A2A | App bar / hero band background, primary buttons, selected chips & choice options, active states |
| primary-hover | #2A4E39 | Pressed/hover state of primary buttons and selected controls |
| secondary (accent) | #EFC64E | FAB background, brand accent, demo-hint border highlights |
| accent-soft | #FBF1D4 | Demo hint box background, pending status badge background |
| background | #F5F7F5 | Screen background behind cards and bars |
| surface | #FFFFFF | Cards, bottom navigation bar, form fields, sheet backgrounds |
| text-primary | #1C2B22 | Primary text: titles, body, field values |
| text-muted | #5C6B62 | Secondary text: subtitles, metadata, hints, counters |
| border | #DDE4DF | Card borders, field outlines, dividers, bottom-nav top border |
| danger / error | #B3261E | Field error borders/text, destructive semantics |
| danger-soft | #FBEAE9 | Error field background |
| status-pending-bg | #FBF1D4 | "Pendiente" status badge background |
| status-pending-fg | #7A5B10 | "Pendiente" status badge text |
| status-in-progress-bg | #E1EAF6 | "En progreso" status badge background |
| status-in-progress-fg | #2A4E79 | "En progreso" status badge text |
| status-resolved-bg | #DFEFE3 | "Resuelta" status badge background |
| status-resolved-fg | #1E5B36 | "Resuelta" status badge text |
| text-on-primary | #FFFFFF | Text and icons on primary surfaces (app bar, primary buttons) |
| priority-high-border | #C4463E | "Alta" priority pill border |
| priority-high-text | #A32E27 | "Alta" priority pill text |
| priority-medium-border | #B98A1E | "Media" priority pill border |
| priority-medium-text | #7A5B10 | "Media" priority pill text |
| priority-low-border | #6B8F7A | "Baja" priority pill border |
| priority-low-text | #446B54 | "Baja" priority pill text |

**Status and priority are always encoded as color + text (INSTR-012):** every status badge shows its label ("Pendiente" / "En progreso" / "Resuelta") on its paired background; priority pills show their label inside a bordered pill — never color alone.

**Source:** values extracted verbatim from the user-approved Pre-Design draft `docs/design/pre_design_mobile.html`.

### Button Styles

| Variant | Background | Color | Border | Border-radius | Padding |
|---|---|---|---|---|---|
| Primary (filled) | #1E3A2A | #FFFFFF | none | 8px | 14px 16px, min-height 48px |
| Secondary / outline (retry) | #FFFFFF | #1E3A2A | 1px solid #1E3A2A | 8px | 10px 24px, min-height 44px |
| Text-link / app-bar button ("Salir", "Volver") | rgba(255,255,255,0.12) on the #1E3A2A bar | #FFFFFF | 1px solid rgba(255,255,255,0.25) | 8px | 8px 14px, min-height 40px |
| Danger | #B3261E (where a destructive action is used; the draft uses danger mainly as field-error styling: 1px solid #B3261E border on #FBEAE9 background) | #FFFFFF | none | 8px | 14px 16px, min-height 48px |
| Filter chip (pill) | unselected: #FFFFFF; selected: #1E3A2A with #FFFFFF text | #1C2B22 unselected / #FFFFFF selected | 1px solid #DDE4DF unselected; 1px solid #1E3A2A selected | 999px (pill) | 8px 14px, min-height 36px |
| Choice group option (Categoría / Prioridad) | unselected: #FFFFFF; selected: #1E3A2A with #FFFFFF text | #1C2B22 unselected / #FFFFFF selected | 1px solid #DDE4DF unselected; 1px solid #1E3A2A selected | 8px | 10px 14px, min-height 40px |
| FAB (centered, bottom nav) | #EFC64E | icon/text #1E3A2A | 3px solid #F5F7F5 | 50% (circle) | 52×52dp, raised shadow 0 4px 16px rgba(30,58,42,0.18) |

All buttons: disabled state at reduced opacity (~0.5) with the submit label switching to a progress/locked state (e.g. "Guardando…") while a submission is in flight (duplicate-submission lock). Minimum touch target 48dp.

### Typography

Font family for every scale: system sans stack — `-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, system-ui, sans-serif` (on Android this maps to **Roboto**, so the Compose implementation uses the Roboto `FontFamily.Default` throughout). Sizes are given in px as extracted from the draft with the rem conversion (1rem = 16px).

| Scale | Font-family | Size | Weight | Line-height |
|---|---|---|---|---|
| heading-xl | system sans (Roboto on Android) | 26px / 1.625rem | 800 | 1.2 |
| heading-lg | system sans (Roboto on Android) | 18px / 1.125rem | 700 | 1.3 |
| heading-md | system sans (Roboto on Android) | 15px / 0.9375rem | 700 | 1.4 |
| body | system sans (Roboto on Android) | 15px / 0.9375rem | 400 | 1.5 |
| body-small | system sans (Roboto on Android) | 14px / 0.875rem | 400 | 1.45 |
| caption | system sans (Roboto on Android) | 12.5px / 0.78rem | 400 | 1.4 |
| label | system sans (Roboto on Android) | 13px / 0.8125rem | 600 | 1.35 |
| button | system sans (Roboto on Android) | 15px / 0.9375rem | 700 | 1.2 |
| badge | system sans (Roboto on Android) | 12px / 0.75rem | 700 | 1.2 |
| chip | system sans (Roboto on Android) | 13.5px / 0.84rem | 600 | 1.2 |
| bottom-nav | system sans (Roboto on Android) | 11.5px / 0.72rem | 600 | 1.2 |

Usage: heading-xl = login hero title; heading-lg = screen titles; heading-md = card and section titles; label = field labels; caption = meta text (creation dates, timestamps, counters); badge = status badges; chip = filter chips; bottom-nav = navigation item labels.

### Spacing & Layout Tokens

- **Base grid:** 8px unit. Steps: space-1 = 8px, space-2 = 16px, space-3 = 24px, space-4 = 32px.
- **Card style:** surface #FFFFFF, border 1px solid #DDE4DF, radius 8px, shadow `0 1px 2px rgba(30,58,42,0.08), 0 2px 8px rgba(30,58,42,0.06)`, internal padding 16px.
- **Phone-portrait layout:** full-width single column, content padding 16px, portrait-only (390×844 baseline; scales to other phone widths as a single column). No container max-width beyond the phone frame — the only cap is the login card at 480px.
- **App bar:** background #1E3A2A, white text (#FFFFFF), brand mark left, optional app-bar button right (rgba(255,255,255,0.12) background with rgba(255,255,255,0.25) border).
- **Bottom navigation bar** (list screen): surface #FFFFFF with top border 1px solid #DDE4DF, centered accent FAB (#EFC64E, 52dp circle, 3px #F5F7F5 border).
- **Sign-in screen:** full-width primary hero band (#1E3A2A) with brand mark and title/subtitle, then the centered white login card on #F5F7F5.
- **Corners:** 8px radius everywhere except pills/chips/badges (999px pill) and the FAB (circle).
- **Focus ring:** 3px rgba(30,58,42,0.15) around focused fields and controls (maps to Compose focus/indication styling for keyboard and TalkBack navigation).

### Icon Set

The Pre-Design draft uses inline Material-style line SVG icons (2px stroke, 24px viewBox) plus a text "M" brand mark and emoji glyphs in state placeholders. For the native app the icon set is **Material Icons (outlined style, bundled via `androidx.compose.material:material-icons-extended`, version BOM-managed)** — no external icon library and no custom icon fonts. The "M" brand mark is rendered as text in the app-bar/hero, matching the draft.
