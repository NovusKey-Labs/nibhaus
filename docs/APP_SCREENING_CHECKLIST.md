# App Screening — 6-Criterion Standard

A reusable **product-quality gate** for **every** app (Nibhaus and future apps under the NovusKey
Labs umbrella). Where the [12-Layer Production Readiness](PRODUCTION_READINESS_CHECKLIST.md) check
asks *"is the infrastructure accounted for?"*, App Screening asks *"is the product itself good enough
to ship?"* — judged **against the project's own stated intent**, not a generic ideal.

Run **both** gates before a release. Same ethos as the readiness check: every criterion is **asked and
answered** with an explicit grade — none skipped, no blanks.

## The six criteria

Each is graded A–F (see scale below) against the app's intent, with a one-line justification and, for
anything below the ship bar, a named remediation + owner.

### 1. User-Friendliness
Can the target user accomplish the core job without friction or a manual?
- First-run / empty states guide rather than dead-end; primary actions are discoverable.
- Error and permission states are recoverable and explained in the user's language.
- Touch targets, contrast, and motion respect accessibility basics.
- **Pass bar:** the core flow is completable by a new user unaided.

### 2. Security
Is user data and the trust boundary protected — and *proven* so, not assumed?
- Secrets handling, auth surfaces, input validation at every trust boundary, fail-closed defaults.
- **Required sub-step — local OWASP scan suite** (`~/scripts/appsec-scan.sh`, self-hosted, scan-by-us,
  never embedded in the shipped app):
  - **gitleaks** — secrets in tree + history
  - **Semgrep + CodeQL** — SAST
  - **OWASP Dependency-Check** — vulnerable dependencies (SCA)
  - **KICS** — IaC / config misconfig
  - **MobSF** against the built APK/IPA — mobile static+dynamic (graded to **OWASP MASVS/MASTG**)
  - **OWASP ZAP** against any exposed endpoint — DAST
- **Pass bar:** zero unresolved **High/Critical** findings; every trust boundary named and defended.

### 3. Functionality
Does it do what it claims, reliably, across the real device/input matrix?
- Core features work end-to-end; edge cases and failure paths handled without data loss.
- Automated tests cover the critical paths and green in CI.
- **Pass bar:** no known correctness or data-loss defect on the supported matrix.

### 4. UI ↔ Code Synergy
Does the shipped UI match the design intent, and does the code structure support it cleanly?
- Design system applied consistently; no drift between mockups/spec and rendered UI.
- Components are focused and reusable; screen logic isn't tangled into one megafile.
- **Pass bar:** UI is faithful to the design and the code behind it is maintainable.

### 5. Deployment Readiness
Can it be built, signed, and shipped repeatably by CI — not just from one laptop?
- Reproducible build; signed release artifact produced by the pipeline.
- Release variant(s) build in CI; rollback/version story exists.
- **Pass bar:** a clean checkout produces a shippable, signed artifact via CI.

### 6. Legal Safety
Is the legal exposure — to rights-holders **and** to users — driven as close to zero as practical?
- Licensing coherent and compatible (no copyleft contaminating proprietary parts); third-party marks
  used nominatively only; clean-room record for any reverse-engineered interop.
- User-facing EULA/Terms (AS-IS, liability cap, data-loss disclaimer) + privacy policy that matches
  actual data behavior; trademark and entity ownership sorted.
- **Pass bar:** no unlicensed/copyleft leakage in shipped artifacts; Terms + Privacy present and
  accurate. **A+ requires the human/legal steps** (entity formed, IP assigned to it, attorney sign-off,
  trademark filed) — those sit outside the repo.

## Grading scale

| Grade | Meaning | Ship implication |
|---|---|---|
| **A / A−** | Meets intent with margin; minor polish at most. | Ship. |
| **B+ / B / B−** | Solid; known gaps that don't block the core promise. | Ship if ≥ bar; log the gaps. |
| **C** | Real weaknesses a user or rights-holder would hit. | **Below bar** — remediate before ship. |
| **D / F** | Broken or exposed against its own intent. | **Do not ship.** |

**Ship bar:** no criterion below **B−**; **Security** and **Legal Safety** must be **≥ B**. Anything
under bar needs a named remediation + owner **before** release, not after.

---

## Assessment: Nibhaus — 2026-07-03

Graded against Nibhaus's intent: a **local-first, no-telemetry** Ncode-smartpen app, one-time $4.99
premium, open-core.

| Criterion | Grade | Note |
|---|---|---|
| User-Friendliness | **B+** | Core capture→view→export flow is unaided; empty/permission states hardened this pass. Gap: onboarding for pen pairing is still terse. |
| Security | **A−** | Device security + fail-closed sync-endpoint auth + secrets scanning; local OWASP suite run (gitleaks clean). Deeper MobSF/Dependency-Check/ZAP passes still queued. |
| Functionality | **A−** | Persist-first invariant, durable outbox; 479 freemium + 37 penble tests green in CI. |
| UI ↔ Code Synergy | **A−** | Material 3, per-screen files (not a megafile), design system v1; brand color locked (Material You disabled). |
| Deployment Readiness | **B+** | Freemium + premium variants build in CI with GPL-free release gate. Gap: signed-release/AAB CI job still to land. |
| Legal Safety | **C+ → A−** | Moved by the legal-hardening pass (Apache-2.0 relicense, EULA, clean-room record, GPL-free gate, trademark NOTICE, finalized privacy). **A+** pending the human legal steps (LLC, IP assignment, attorney, trademark filing). |

**Verdict:** passes the ship bar on every criterion (all ≥ B, Security & Legal ≥ B). Watch items:
the signed-release CI job (Deployment) and the deeper MobSF/Dependency-Check/ZAP scans (Security).
