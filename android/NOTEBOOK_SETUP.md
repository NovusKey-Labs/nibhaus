# Adding a new notebook type

How to register a new Ncode notebook product (e.g. the 2026 Planner) so it gets correct
full-page geometry and type-driven export filing. Two parts: **measure once per product** (you, with
a pen), then **register** (one paste into `NotebookType.kt`). Per-physical-copy labels (Work, School)
are set in-app — no code.

The smartpen reads position from the Ncode dot pattern, **not** from ink — so faint/dry traces
capture perfectly. Don't worry about ink flow or photos; the logged data is all that matters.

---

## 1. Measure (8 traces, once per product)

Open **Capture Lab** in the app and record these 8 strokes on a fresh page, then export the
measurement `.xlsx`. Push the pen as far into each corner/edge as it still draws — wherever ink
begins is the writable boundary, which is the coordinate we want.

- **4 corner→centre diagonals** — start hard in each physical corner, draw toward the centre
  (length/centre don't matter; only the start point is used). Gives the four corners.
- **4 edge-to-edge lines** — 2 horizontal (full width), 2 vertical (full height), each spanning
  one writable dimension. Cross-checks the rectangle and confirms it isn't skewed.

Wake-taps at the start of a stroke are filtered automatically.

> Scale (mm/unit) is already calibrated globally (`MM_PER_UNIT = 2.36`, isotropic). You only need
> to re-run the ruler-trace scale fit (`calibrate_ncode.py <xlsx>`) if you suspect a different
> pen/paper changes it.

## 2. Derive the geometry

```fish
# OCR host (fish shell): physical sheet size in cm — measure the paper with a ruler.
python3 android/tools/calibrate_ncode.py --page-extent measurements.xlsx <width_cm> <height_cm> \
    planner2026 "2026 Planner" plnr
```

It prints a paste-ready block, e.g.:

```kotlin
        val PLANNER_2026 = NotebookType(
            id = "planner2026",
            displayName = "2026 Planner",
            code = "plnr",
            geometry = PageGeometry(3.9f, 3.7f, 62.5f, 90.0f, 137.5f, 210.0f),
        )
```

## 3. Register (one edit in `export/NotebookType.kt`)

1. Replace the stub `NotebookType` with the printed block (keep `isPlanner = true` for planners).
2. Add its Ncode **book id** to the registry — find it in the measurement `.xlsx` `book` column:
   ```kotlin
   private val BY_BOOK: Map<Int, NotebookType> = mapOf(438 to PROFESSIONAL, <book> to PLANNER_2026)
   ```
3. **Planner only** — set its page→date layout so pages file under `plnr/2026/06_June/…`. Read a few
   printed dated pages to get the first page number + pages-per-month, then:
   ```kotlin
   plannerLayout = PlannerLayout.monthly(firstPage = 100, pagesPerMonth = 30, startYear = 2026),
   // or list the sections explicitly if months aren't equal length:
   // PlannerLayout(listOf(PlannerLayout.Section(fromPage = 100, year = 2026, month = 6), …))
   ```

That's the whole code change. Everything downstream (full-page SVG, export paths, the sidecar, the
OCR round-trip) already consumes it.

## In the app

The first time you write in a notebook the app hasn't set up, a dialog asks for its **type** (picker,
pre-selected to the registered product) and a **label** for this copy (Work, School). The type is
remembered by book id — once per product; the label is saved on that notebook and drives the export
folder/filename (`pnb/Work/PNB_Work_Pg038`). Until a product is registered, its pages file flat under
their page id, so nothing is ever lost.
