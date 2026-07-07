# Design System: "Ink & Ncode" — Implementation Spec

> **⚠️ SUPERSEDED (v1).** This document describes the original "Ink & Ncode" v1 spec — teal
> `#0E6B73` ink, Newsreader/IBM Plex Sans — kept below for historical reference only. **It is not
> what the app implements.** The governing design system is **v3 "Vault & Ink"** (deep-navy ground,
> the blue→violet ink gradient `#5566F4`-family, Sora/Inter/IBM Plex Mono), specified in
> [`android/docs/UI_V3_GAP_AND_PLAN.md`](../docs/UI_V3_GAP_AND_PLAN.md) and implemented in
> `android/app/src/main/java/com/nibhaus/ui/theme/` (`Color.kt`, `Type.kt`, `Shape.kt`,
> `InkTokens.kt`). Read that doc first; treat everything below as archive, not instruction.

Companion to `inkwell-ui-mockups.html` (visual ground truth) and `PROJECT_BRIEF.md`. This file specifies every token so the UI can be built in **Jetpack Compose + Material 3** exactly as mocked. Implement these as a `Theme` (`ColorScheme`, `Typography`, `Shapes`) plus a few custom tokens. **Disable Material You dynamic color** — the brand identity must hold regardless of device wallpaper.

Design intent: the app treats the page as what it physically is — a coordinate dot-grid the pen reads — and renders writing in fountain-pen ink. Palette and type are deliberately *not* the default cream-serif-terracotta look. Boldness lives in exactly one place: **ink on the Ncode grid**. Everything else stays quiet.

---

## 1. Color tokens → Material3 `ColorScheme`

Base palette (names → hex): **Paper** `#ECEDE8` · **Surface** `#F6F6F2` · **Ink** `#191D26` · **Ink Teal** `#0E6B73` · **Brass** `#B5872E` · **Slate** `#586172` · **Hairline** `#D5D7D2`.

Teal is the primary brand/ink color; Brass is the *tertiary* accent used sparingly (live indicator, highlighter, active selection). Slate is neutral-interactive.

### Light `lightColorScheme`
| Role | Hex | Role | Hex |
|---|---|---|---|
| primary | `#0E6B73` | onPrimary | `#F4F5F1` |
| primaryContainer | `#CFE4E5` | onPrimaryContainer | `#06363A` |
| secondary | `#586172` | onSecondary | `#F4F5F1` |
| secondaryContainer | `#DDE1E6` | onSecondaryContainer | `#2B313B` |
| tertiary | `#B5872E` | onTertiary | `#FFFFFF` |
| tertiaryContainer | `#F0E2C4` | onTertiaryContainer | `#4A3608` |
| background | `#ECEDE8` | onBackground | `#191D26` |
| surface | `#F6F6F2` | onSurface | `#191D26` |
| surfaceVariant | `#E2E3DE` | onSurfaceVariant | `#586172` |
| outline | `#586172` | outlineVariant | `#D5D7D2` |
| error | `#8E3B36` | onError | `#FFFFFF` |

### Dark `darkColorScheme`
| Role | Hex | Role | Hex |
|---|---|---|---|
| primary | `#3FB4BE` | onPrimary | `#06262A` |
| primaryContainer | `#0E4A50` | onPrimaryContainer | `#BDE7EA` |
| secondary | `#8A93A3` | onSecondary | `#1B2129` |
| secondaryContainer | `#2E3540` | onSecondaryContainer | `#DCE0E7` |
| tertiary | `#D7A23F` | onTertiary | `#3A2A06` |
| tertiaryContainer | `#5A4413` | onTertiaryContainer | `#F3DEB0` |
| background | `#101319` | onBackground | `#E7E8E3` |
| surface | `#181C24` | onSurface | `#E7E8E3` |
| surfaceVariant | `#2A2F38` | onSurfaceVariant | `#8A93A3` |
| outline | `#8A93A3` | outlineVariant | `#262B34` |
| error | `#E0928C` | onError | `#2B0E0C` |

**Usage rule:** Teal at small sizes is for accents, icons, and ≥16sp text only — never body copy (use onBackground/onSurface for body). Brass appears only on: the live/connected pen indicator, the highlighter ink, and the active selection tool. If Brass shows up anywhere else, remove it.

---

## 2. Typography → Material3 `Typography`

Three families via **Compose downloadable Google Fonts** (`androidx.compose.ui:ui-text-google-fonts`, a `GoogleFont.Provider`):
- **Newsreader** (serif) — display/headline "writing" moments.
- **IBM Plex Sans** — all UI and body.
- **IBM Plex Mono** — data readouts (coordinates, page numbers, fps, sync %, model names, timestamps) and the uppercase eyebrow labels.

| Material slot | Family / weight | Size / line / tracking |
|---|---|---|
| displayLarge | Newsreader 400 | 48 / 52 / -0.5 |
| displaySmall | Newsreader 400 | 32 / 38 / 0 |
| headlineMedium | Newsreader 400 | 26 / 30 / -0.2 |
| headlineSmall | Newsreader 400 | 22 / 27 / 0 |
| titleLarge | IBM Plex Sans 600 | 18 / 24 / 0 |
| titleMedium | IBM Plex Sans 600 | 15 / 20 / 0.1 |
| bodyLarge | IBM Plex Sans 400 | 15 / 22 / 0.1 |
| bodyMedium | IBM Plex Sans 400 | 14 / 20 / 0.1 |
| bodySmall | IBM Plex Sans 400 | 12.5 / 18 / 0.2 |
| labelLarge | IBM Plex Sans 600 | 14 / 18 / 0.3 |
| labelMedium | IBM Plex Sans 500 | 12 / 16 / 0.4 |

**Custom (non-Material) styles** to define and reuse:
- `monoData` — IBM Plex Mono 500, 11–12sp, tracking 0.4sp. For coordinates/battery/fps/page counts.
- `monoEyebrow` — IBM Plex Mono 500, 10sp, tracking 2.0sp, UPPERCASE, color onSurfaceVariant. Section labels ("SYNC", "RECENT PAGES").

Screen titles ("Pens", "Library", "Settings") use **headlineMedium (Newsreader)**. Notebook group headers in the library use **headlineSmall (Newsreader)**.

---

## 3. Shape → Material3 `Shapes`

| Token | Radius | Used for |
|---|---|---|
| extraSmall | 6dp | chips' inner, small tags |
| small | 10dp | inline fields |
| medium | 14dp | thumbnails, secondary cards, buttons |
| large | 18dp | primary cards, capture canvas |
| extraLarge | 28dp | bottom sheets, dialogs |

FAB radius **20dp** (rounded square, not circular). Toggle/switch and chips are fully rounded (stadium). Phone-content uses no full-bleed cards — cards inset 16dp from screen edges.

---

## 4. Spacing & layout

4dp base scale: **4 / 8 / 12 / 16 / 20 / 24 / 32**. Screen horizontal padding **16dp**. Card internal padding **16dp**. Row vertical padding **15dp**. Gap between stacked cards **12dp**. Section label top margin **18dp**.

---

## 5. Elevation

Prefer **flat + hairline** over heavy shadow. Cards: tonal elevation 0–1dp, with a 1dp `outlineVariant` (Hairline) bottom edge — not a drop shadow. FAB: 3dp. Bottom navigation bar: 2dp with a 1dp top `outlineVariant`. Floating edit toolbar: a real shadow is OK here (it sits over the canvas) — `0 14 30 -14 rgba(0,0,0,.45)`.

---

## 6. Signature 1 — the Ncode dot-grid

The defining motif. Render behind ink on the **capture canvas**, **page detail**, and **library thumbnails**.
- Dot color: `onBackground @ 13%` (light) / `onSurface @ 10%` (dark).
- Dot radius **1.1dp**, grid spacing **15dp**, offset **8dp**.
- Implement as a `Canvas`/`drawBehind` tiling (draw circles on a 15dp lattice) or a tiled `ShaderBrush`. It must scale with zoom on the page-detail view.

## 7. Signature 2 — ink rendering

- Stroke color = `primary` (Teal). Base width **2.6dp**, pressure-modulated: `width = lerp(1.4dp, 3.6dp, pressure)` where pressure is 0–1 from the pen. `StrokeCap.Round`, `StrokeJoin.Round`, anti-aliased.
- Build each stroke as a `Path` with Catmull-Rom→Bézier smoothing between captured points (no jagged polylines).
- **Highlighter pass** = `tertiary` (Brass) at **32% alpha**, width ~**7dp**, drawn *under* ink strokes.
- Optional draw-on animation: as points arrive, animate `PathMeasure` trim over ~200ms (disable under reduced-motion).

---

## 8. Iconography & motion

- Icons: **Material Symbols Outlined**, 24dp, weight ~300–400 (thin, to match the refined type). Default tint onSurface @ ~80%. Active/selected: `primary` (nav) or `tertiary` (selection tool).
- Motion (Material 3): durations 150 / 250 / 400ms (small / medium / emphasized), standard & emphasized easing.
- Live indicator: Brass dot with an expanding-ring pulse, 1.8s loop.
- Library thumbnail → page detail: **container transform**. Tab changes: shared-axis X.
- **Reduced motion:** disable the ink draw-on and the live pulse; keep instant state changes.

---

## 9. Component specs

- **Top app bar:** transparent over background; left = back chevron or nothing; center/left title in headlineMedium (Newsreader); a mono sub-label (monoEyebrow) under the title for context ("2 PAIRED", "PAGE 42 · A5 RULED"); right = one or two outlined actions. Export action tinted Teal.
- **Pen-status card (Home):** surface card, 18dp radius, 16dp padding. Left: 38dp circular "nib" badge — `primaryContainer` fill with a 9dp center dot that is **Brass when connected/live**, **Slate when idle**. Middle: pen name (titleMedium) + a mono status tag (`CONNECTED` teal / `TAP TO CONNECT` slate) + a bodySmall sub-line. Right: battery in `monoData`.
- **Capture canvas:** large-radius surface filling the body, dot-grid behind, ink on top. Below it a row: coordinates (monoData, Slate) left, `streaming · 120 fps` (monoData, Teal) right.
- **Library thumbnail:** 14dp-radius surface, dot-grid, a clipped ink preview, page number top-right (monoData Teal), a mono caption bottom-left (Slate). 2-column grid, 12dp gap.
- **Filter chips:** stadium; selected = `Ink`/onSurface fill with Paper text; unselected = transparent with `outlineVariant` border, Slate text.
- **Settings list:** grouped in surface cards; each row = title (titleMedium) + description (bodySmall, Slate) on the left, control on the right. Dropdown control = current value in Teal (labelLarge) + a chevron-down; tapping opens an `ExposedDropdownMenuBox`. Switches use `primary` when on. Rows divided by 1dp `outlineVariant`. A selected dropdown reveals its contextual field directly below the card as an inline `small`-radius field showing a mono key + mono value (e.g., `FOLDER  …/Documents/Inkwell`).
- **Dropdowns (`ExposedDropdownMenuBox`):** anchor styled as above (not a boxed text field — a value+chevron row). Menu items in bodyLarge; the active item marked with a Teal check. This is the component the Sync-method and OCR-trigger settings use.
- **Edit toolbar (page detail):** floating bar, `Ink`/onSurfaceInverse dark fill, 18dp radius, inset 14dp, bottom 16dp. Left group: selection (lasso) tool + three ink swatches (Teal / Ink / Brass, 18dp circles). Right group: thickness, eraser, undo. Active tool tinted Brass.
- **Transcript card:** surface card; a "● Verbatim" badge (monoEyebrow, Teal text on `primaryContainer`); transcript in bodyLarge; a mono footer with model name left (`qwen3-vl · local`) and `0.4s · 64 words` right.
- **FAB:** Teal, 56dp, 20dp radius, Paper "+" icon, for "new capture".
- **Bottom nav:** 3 destinations (Pens / Library / Activity), outlined icons, selected tinted Teal with labelMedium.
- **Empty state:** centered; a `primaryContainer` ring with a pen-nib icon, a Newsreader headline ("Pair a pen to begin"), a bodyMedium Slate line, and an Ink primary button. Errors/empties speak in the interface's voice, name the fix, never apologize.

---

## 10. Per-screen layout notes

1. **Pens / Home** — app bar ("Pens" + "2 PAIRED"); two pen-status cards; "RECENT PAGES" eyebrow + 2-up thumbnails; Teal FAB; bottom nav.
2. **Live capture (hero)** — compact app bar (notebook + page mono sub, Brass `● LIVE`); full dot-grid canvas with live teal ink + a brass highlight; coordinate/fps mono row beneath.
3. **Library** — app bar + search; filter chips (All / Neo M1+ / LAMY); Newsreader notebook headers; 2-up ink thumbnails per group.
4. **Page + edit** — back + centered mono page id + Teal export; large page on dot-grid with an active Brass dashed lasso selection; floating dark edit toolbar.
5. **Settings** — Newsreader "Settings"; grouped cards under monoEyebrow sections (Sync / Transcription / Pen); the two dropdowns with inline contextual fields; switches in Teal (incl. "Keep connected in background", which is the fix for the tablet reconnect issue).
6. **Transcript** — back + Newsreader "Transcript" + copy; ink image card on top; transcript card with Verbatim badge, body text, mono model footer.

---

## 11. Accessibility floor (non-negotiable)

- Body text uses onBackground/onSurface (Ink) — never Teal — for AA contrast. Teal/Brass only for accents, icons, and ≥16sp.
- Touch targets ≥ **48dp**; visible keyboard/focus indicators; honor system font scaling (no fixed-height text rows that clip at large type).
- Respect reduced-motion (Section 8). Provide content descriptions for all icon-only controls.

---

## 12. Compose implementation notes

- Dependencies: `androidx.compose.material3:material3`, `androidx.compose.ui:ui-text-google-fonts`, Material Symbols (or `androidx.compose.material:material-icons-extended`).
- Define `InkwellTheme { }` wrapping `MaterialTheme(colorScheme, typography, shapes)`; pick light/dark by `isSystemInDarkTheme()`.
- **`dynamicColor = false`** — do not pull Material You wallpaper colors; the identity above must always render.
- Centralize tokens in a `theme/` package: `Color.kt`, `Type.kt`, `Shape.kt`, plus `InkTokens.kt` for the custom bits (dot-grid params, ink widths, monoData/monoEyebrow styles). No hardcoded hex or sp scattered in screens — everything references the theme.
- Build the dot-grid and ink rendering as reusable composables (`DotGridCanvas`, `InkStrokeLayer`) so capture, page detail, and thumbnails share one implementation.
