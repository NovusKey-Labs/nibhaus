# Notebook physical specifications

**Single source of truth** for the physical measurements of every supported Ncode notebook product —
sheet size, dot-area geometry, printed ruling, action-button zones, page number, and per-page layout.
`export/NotebookType.kt` + `export/PaperLayout.kt` are the code-side mirror of this file: when a number
here changes, update the corresponding constant there (and vice-versa). All positions are measured from
the **physical sheet edges** unless stated otherwise.

**PROVENANCE — mark every value (M) or (D):** **(M)** = user ruler-measured on the physical notebook
(ground truth). **(D)** = agent-derived (corner/edge pen traces × `MM_PER_UNIT`, or sheet-centering
assumptions) — plausible but NOT ground truth; a (D) value loses to a conflicting (M) value, and two
conflicting (D) values need an (M) tiebreak. Known (D)-vs-(D) conflict: the Professional sheet width —
builtin **137.5 mm (D)** vs a stale tablet-captured profile **145.0 mm (D)** (S24 uses the builtin; the
user-measured Planner sheet is 138 mm (M), making 145 the likely outlier). Tiebreak pending: one ruler
measurement of the Professional sheet.

## Coordinate systems & the calibration problem

- **Ncode units** — the pen reports strokes in the dot-pattern's abstract coordinate space. Isotropic.
- **MM_PER_UNIT = 2.36** (`ExportArtifacts.MM_PER_UNIT`) — Ncode units → millimetres. Re-derive with
  `android/tools/calibrate_ncode.py` if a pen/paper changes it.
- **Writable dot area** — the Ncode rectangle the pen can actually read (from corner/edge traces). Stored
  as `PageGeometry(writableX0, writableY0, writableX1, writableY1, sheetWmm, sheetHmm)`.
- **⚠ OPEN CALIBRATION ISSUE (paper-edge ↔ dot-origin):** the pen only reads *inside* the dot area — it
  goes blank at the paper margin — so the offset between the dot grid and the physical sheet edge cannot
  be traced directly. Current code assumes the dot area is **centred** in the sheet (`PaperLayout.sheetFrame`).
  That assumption appears to cause a **~one-row vertical offset** between the ruled-line overlay and the
  ink on Professional. The robust fix is to anchor the ruling to a **traced printed rule** (which sits on
  dots → exact Ncode Y), not to the assumed paper edge. Pending: deep-research on the standard method +
  a first-rule/last-rule trace to pin the anchor and true pitch. Do NOT treat the ruling Y placement as
  final until this is resolved.

### Resolution recipe (from deep-research w3xf07em1 — validated)

**Two-rule calibration (kills the offset):** have the user trace the **first** ruled line and the **last**
ruled line with the pen. Take each stroke's mean Ncode Y → `Yfirst`, `Ylast`. With `lineCount` rules:
`pitch = (Ylast − Yfirst) / (lineCount − 1)`; rule *i* = `Yfirst + i*pitch`. Store `Yfirst`/`Ylast` (or a
calibration profile) per notebook and have `rulingLinesUnits` use them **directly** — drop `sheetFrame`
centering entirely (no paper-edge assumption). Page number Y = `Ylast + pageNumBelowLastRuleMm/MM_PER_UNIT`;
X from the writable geometry / side margin. Action-zone Y anchors likewise offset from `Yfirst`.

**Fit-width (kills the margins):** research confirms every document viewer preserves aspect and never
stretches; note apps use **fit-width + vertical scroll**. So render the page to fill the available WIDTH
(`width` bound → `height = width / (sheetWmm/sheetHmm)`), **top-aligned**, wrapped in a `verticalScroll`
so a page taller than the viewport scrolls. Replace the current `aspectRatio(…, matchHeightConstraintsFirst
= true)` letterbox. Single affine transform (`fit.map`) still applies to BOTH ink and ruling — that part
is already correct; the classic overlay-offset bug (different transforms per layer, or a center pivot) is
NOT present here. Refinement done (2026-07-03): documented Neo scale is ~2.37 mm/unit (56/600 in);
re-deriving from the ruler-measured ruling pitch (7.0 mm/line) gives ~2.36, so `MM_PER_UNIT` moved
2.32 → 2.36 — much closer to Neo's figure, with a small residual gap left unexplained.

---

## Neo N Professional — Ncode book **438** (`NotebookType.PROFESSIONAL`, code `pnb`)

- **Page (visible/writable face): 138 × 205 mm (M)** — ruler-measured 2026-07-02. Note: the physical
  binding eats width where two pages meet, so this is the visible page face, not a loose-sheet width.
  RESOLVED the (D)-vs-(D) conflict: old derived sheet 137.5×210 had the height wrong by 5 mm (the 145 mm
  tablet profile was an outlier, deleted). Cross-check: 14 + 26×7.0 + 8.5 = 204.5 ≈ 205 — the user's (M)
  ruling measurements are self-consistent on a 205 mm page (on 210 the pitch skewed to 7.21 mm, which is
  what drifted the rules ~one row by the page bottom). Geometry constant update → `NotebookType.kt`
  `PageGeometry(…, 138f, 205f)`.
- **Writable dot area (Ncode):** X 3.9 → 62.5, Y 3.8 → 90.0 **(D)** (corner/cross-section traces).
  Cross-check: all captured ink stays inside this (observed 438 ink extent X 7.6–60.9, Y 5.4–89.0). ✓
- **Ruling:** first line **14 mm** from top, last line **8.5 mm** from bottom, **27 lines** total (pitch
  *derived* = (210−14−8.5)/26 ≈ **7.21 mm**), **9 mm** side margins. Model: `Ruling(topMm, bottomMm, lineCount, sideMm)`.
- **Page number:** 3-digit zero-padded (page 36 → "036"), glyph ≈ **3 mm** tall (measured ~0.1×0.3 cm),
  bottom-left, left edge **9 mm** from left (flush with ruling), centre **~6.5 mm** from the sheet bottom.
- **Action buttons** (top margin) → builtin `ActionZone`s for book 438 (Share/Email, PNG+PDF via a post-tap
  chooser):
  - **Share** — 4 × 4 mm, centre 9 mm from top, **23 mm from right**.
  - **Email** — 5 (w) × 3 (h) mm (envelope is WIDER than tall — user correction 2026-07-02), centre 9 mm from top, **13 mm from right**.
  - (Zones need the same paper-edge calibration; prefer tap-to-learn for exact pen coords.)
- **Per-page layout** (`pageBands`): page **001** = cover (logos, no ruling/number/buttons); **002–129** =
  full lined; **130–256** = footer-only (bottom line + page number + buttons, no interior rules).

---

## 2026 Neo Planner — Ncode book **3207** (`NotebookType.PLANNER_2026`, code `plnr`) — NOT YET WIRED

All cover/year-summary measurements in this section are **(M)** (user ruler). Still unmeasured: the
non-summary calendar pages (monthly/weekly/daily layouts) — pending user measurements.

- **Sheet:** 138 × 210 mm (13.8 × 21 cm) **(M)**.
- **Writable dot area:** TBD — derive from the captured corner marks (book 3207 page 4). Observed mark
  extent X 2.6–65.9, Y 6.6–94.1; corner marks ≈ TL (6.8, 7.3), TR (65.5, 7.3), BL (6.7, 93.4).
- **Cover (page 1, right-hand), no page number:** year centred, **8.8 cm from top**, box 0.4 × 1.2 cm;
  first of **5 centred lines** 0.5 cm below it, 4 cm L/R margins, 0.7 cm pitch; Neo logo + "Neosmartpen"
  centred **1 cm from bottom** (5.4 cm from left / 6.1 cm from right — logo offsets the centring).
- **Pages 2–3:** instructional, no page numbers (layout TBD).
- **Pages 4–5 — year summary** (p4 = months 1–6, p5 = 7–12), no page number but has a **share button**:
  - Year **1.4 cm from top**, centred, 0.35 × 1.1 cm.
  - Month-number row **1.4 cm below year**; centre number centred on the page; **4.5 cm** between adjacent
    number centres; left number 4 cm from left, right number 3.5 cm from right (binding offset). Observed
    Ncode: row-1 numbers Y≈19.8 at X 18.1/37.7/56.5; row-2 Y≈57.5 at X 18.5/37.4/56.4.
  - Mini-calendar **0.4 cm below each number**, centred under it: row 1 = `S M T W T F S`, then 4–6 rows for
    the month's **2026** day layout (Jan: 1st = Thu, 5 rows, 31 days; Feb: 4 rows, ends 28th Sat). ~0.3 cm
    row spacing; row margins 1.4 cm left / 1.0 cm right; 1.2–1.4 cm between months. Circled calendar centres
    (Ncode): row 1 ≈ (18.5,26.6)/(37.9,25.5)/(56.2,26.3), row 2 ≈ (18.8,64.5)/(37.7,65.2)/(56.5,64.5).
  - **7 fill lines** per month, 0.7 cm below the calendar, flush to the month width (3.45 cm), 0.7 cm pitch;
    next month row starts 1.3 cm below; the 2nd row's bottom fill line sits 0.7 cm from the sheet bottom.
  - **Share button** circled top-right ≈ Ncode X 63.7–65.9, Y 7.6–9.9.

---

## Adding a notebook

1. Trace the writable dot area (corner/edge traces) → `PageGeometry`. 2. Measure ruling, page number,
action buttons, and per-page layout from the sheet edges → add here. 3. Mirror into `NotebookType.BY_BOOK`
(+ `Ruling`, `pageBands`) and `PaperLayout`. 4. Verify on-device that the ruled overlay sits on real ink.
