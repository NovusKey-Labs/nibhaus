# UI V3 — gap analysis & polish plan

Source of truth: the owner's mockup `~/Downloads/UI/inkvault-ui-mockups-improved.html`
(**"Design Direction · V3 Polish · Supersedes V2"**). I rendered it in a browser and extracted
the live design + **motion** specs (not just the source). This plan closes the gap between that
mockup and the current Compose app.

## The design language (observed)

**"Vault & Ink"** — handwriting, secured. Deep-navy vault ground; brushed-steel structure; the
blue→violet **gradient is the one bright "ink"** thread running through everything.

- **Palette:** bg `#070D1C`, surfaces `#142C4D`/`#0B1B3C`, ink/text `#EAF0FB`, muted `#8B98B6`,
  steel hairlines `#223863`; accent `#5566F4`; **ink gradient** `#2E83FC → #5B5BF6 → #8D4EFA`;
  status green `#22E07A`. *(All already in `Color.kt`/`InkTokens.kt`.)*
- **Type:** **Sora** display/titles · **Inter** UI/body · **IBM Plex Mono** telemetry & eyebrow
  labels. *(Already in `Type.kt`.)*
- **Components:** hex pen/vault iconography; **steel-edged cards** with a faint gradient core + soft
  rise; status badges (`CONNECTED`/`LIVE`); **mono telemetry chips** (`412.0 × 196.3 · 120 fps`,
  `AES-locked`, `VERBATIM · VAULT-LOCAL`, `neo1-v1 · local · 0.3s · 14 words`); indigo FAB;
  bottom nav **Pens / Library / Activity**.
- **Two themes:** signature **dark** + a **light** "cool paper, navy text, steel hairlines, the
  gradient stays the one bright thing."

## The motion system (the heart of it — extracted from the mockup CSS)

One signature easing **everywhere**: `cubic-bezier(0.2, 0.8, 0.2, 1)` → define once as
`VaultEasing` and use it as the app-wide default.

| Motion | Spec | Where | Compose mapping |
|---|---|---|---|
| **rise** (entrance) | translateY 14px→0 + fade, **0.6s**, staggered ~55–80 ms (appbar→cards→eyebrow→thumbs) | every screen populates in | `AnimatedVisibility`/`animateFloatAsState` + an index-based delay; one `Modifier.riseIn(index)` |
| **grad-pan** (living gradient) | gradient position pans, **7s** ease-in-out infinite | chips, toggles, badges, FAB, wordmark | animated `Brush.linearGradient` whose start/end offset is driven by an `infiniteTransition` |
| **draw** (ink) | strokeDashoffset 1400→0 @55%, **5.5s**, per-stroke stagger | live capture, transcript, page-open | animate a path-measure phase 0→1 on the stroke `Path` (PathEffect / partial path) |
| **tab-cycle** (nav) | translateX ±38px + scale 0.98 + fade, **7.5s** loop | Pens/Library/Activity | shared-axis `AnimatedContent` (`slideIn/Out` X + `scaleIn/Out` + fade) on tab change |
| **page-turn** | rotateY 0→−24°→0, **4.8s** | open notebook→page, page flip | `graphicsLayer { rotationY }` container transform between page composables |
| **unlock + haze** | scale 0.82→1 + ambient opacity 0.35↔0.7 | launch/vault unlock | already `VaultSplash` — swap ease to `VaultEasing`, add the haze loop |
| **spin / spin-rev** | rotate ±360°, 2.6s/6s linear | sync "securing pages" dial | already `DialSpinner` — add the counter-rotating second ring |
| **float-fab** | translateY 0↔−4px, 3.8s + grad-pan | FAB | `infiniteTransition` translate + the grad-pan brush |
| **sweep / route / pin-bob** | radar rotate; route dots scale 0.7↔1.28 staggered; pin bob ±6px | **find-my-pen** (new screen) | `Canvas` sweep + staggered `infiniteTransition` dots + bobbing pin |
| **lift** (press/hover) | transform/shadow 0.25–0.45s | cards, thumbs, FAB, nav | `animateFloatAsState` on press interaction |

All decorative loops must gate on `rememberReducedMotion()` (already present — honor it everywhere).

## Current state vs gap

**Have:** tokens, Sora/Inter/Mono, `WordmarkText` (Ink+Vault gradient), `VaultSplash` (launch),
`DialSpinner` (sync), a FAB gradient, `rememberReducedMotion()`.

**Gaps (the polish work):**
1. **Signature easing isn't global** — `VaultSplash` uses `FastOutSlowInEasing`; nothing uses
   `cubic-bezier(0.2,0.8,0.2,1)`. → add `VaultEasing`, make it the default for all tweens.
2. **No staggered `rise` entrance** — screens appear flat. → `Modifier.riseIn(index)` applied
   across every screen's children. *Biggest perceived-quality win.*
3. **Gradient is static** — chips/toggles/badges/FAB don't pan. → `gradPanBrush()` animated brush.
4. **Ink doesn't draw** — strokes hard-render. → path-phase `draw` on capture/transcript/page-open.
5. **Tabs use default Material transition** — not shared-axis. → `tab-cycle` `AnimatedContent`.
6. **No 3D page-turn** — notebook/page nav is a default slide. → `page-turn` container transform.
7. **Cards/chips below mockup fidelity** — build the steel pen-card + the mono telemetry chip set
   as reusable components, apply across Home/Capture/Library/Transcript/Settings.
8. **Find-my-pen screen doesn't exist** — build it (radar + route dots + pin + RSSI distance).
9. **Light theme** needs the "cool paper" fidelity pass.

## Suggested sequencing (each independently shippable + visually verifiable on device)

1. **Motion foundation** — `VaultEasing` + `Modifier.riseIn` stagger + `gradPanBrush()` + make them
   the defaults. (Highest perceived-quality-per-effort; touches every screen.)
2. **Component kit** — steel pen-card, mono telemetry chip, status badge, section eyebrow — as
   reusable composables; replace ad-hoc UI on the core screens.
3. **Navigation motion** — shared-axis tabs + 3D page-turn container transform.
4. **Ink-draw** — self-drawing strokes on capture/transcript/page-open.
5. **Find-my-pen** screen + **light-theme** fidelity pass.

> Visual verification is on-device (Compose previews help, but `connectedAndroidTest` stays
> FORBIDDEN — it wipes the tablet). Build with `@Preview` per component, validate the feel by
> installing the debug APK.

## Precise component specs (extracted from the rendered mockup — build to these)

**Canonical ink gradient:** `linearGradient(135°, #2E83FC, #5B5BF6 @0.52, #8D4EFA)`. Used on:
status chip, mono badge, toggle-on, FAB, active nav icon, handwriting strokes. Glow: `#5B5BF6 @0.4, blur 22`.

| Component | Specs |
|---|---|
| **Phone/screen frame** | radius 38; top radial glow `radial(120% 80% at 50% -10%, #5B5BF6@0.1, transparent 55%)`; inset top edge `#FFF@0.035 1px`, bottom vignette |
| **Steel card** (pen card, thumb, tcard) | fill `#142C4D`; radius **18** (thumb 15, tcard 16); padding **16** (tcard 15); 1px brushed-steel gradient border `linear(140°, #FFF@0.54, #94A0B6@0.22 @0.36, #5C6980@0.34 @0.70)`; shadow top `#FFF@0.04 0 1 0` + drop `#000@0.7 0 18 36 -22` |
| **Status chip** (CONNECTED/LIVE) | pill radius 20; padding 7×15; ink gradient; glow; Inter 500 12.5sp; white |
| **Mono badge** (VERBATIM·VAULT-LOCAL) | radius 7; padding 4×10; gap 6; ink gradient; glow; **IBM Plex Mono 10sp, ls +1.2, UPPERCASE**; white |
| **Eyebrow** (RECENT PAGES) | **IBM Plex Mono 10sp, ls +2.0, UPPERCASE**, color `#8B98B6` |
| **Toggle** | track 44×25 radius 14; ON = ink gradient + glow; OFF = steel `#223863` |
| **FAB** | 58dp **squircle radius 20** (not circular); ink gradient; shadow `#5B5BF6@0.6 0 14 30 -8` + glow; float ±4 + grad-pan |
| **Bottom nav** | height 66; top hairline `#223863` 1px; bg bottom-fade `transparent→#081026@0.3`; item = icon+label (Inter 600 10sp, gap 4), active tinted by the gradient |
| **Section band divider** | 1px `linear(90°, #2A3450, transparent)` |

**Motion constants:** `VaultEasing = CubicBezierEasing(0.2, 0.8, 0.2, 1)`. rise: ty 14→0 + fade, 600ms,
stagger 55–80ms by child index. grad-pan: 7s. draw: 5.5s (per-stroke stagger 0.5s). page-turn: rotateY −24°, 4.8s.
tab-cycle: tx ±38 + scale .98 + fade. All decorative loops gate on `rememberReducedMotion()`.
