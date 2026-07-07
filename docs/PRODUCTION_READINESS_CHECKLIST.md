# Production Readiness — 12-Layer Standard

A reusable gate for **every** app (Nibhaus and future apps under the same umbrella). The point is not
that every app implements all twelve layers — most local-first apps won't — but that **no layer is
skipped silently**. Each layer is asked and given one explicit answer, with a reason.

## The rule

Every layer gets exactly one state:

| State | Meaning |
|---|---|
| ✅ **PRESENT** | Implemented. Note the tech and where it lives. |
| 🟡 **N/A BY DESIGN** | Deliberately excluded. State *why*, and the **trigger** that would flip it to required. |
| 🔴 **DEFERRED** | Needed eventually, not yet built. Name an **owner + revisit date**. |
| ⬜ **UNDECIDED** | Not yet answered. **Blocks "production"** — no blanks allowed. |

"N/A by design" must always name the design decision behind it. That is what stops *"we forgot X"*
from masquerading as *"X is N/A."*

## The 12 layers — the question, and when it applies

1. **Front-End Foundations** — What renders the UI, on what platform, with what design system? *Applies: always.*
2. **APIs & Back-End Logic** — Is there server-side logic / an API, and where does business logic run? *Applies: when clients share state, rules must be enforced server-side, or third parties are integrated.*
3. **Database & Storage** — Where does data live; what's the durability and backup model? *Applies: always (something persists).*
4. **Auth & Permissions** — Who can do what; what is the identity model? *Applies: when there are accounts, multiple users, or protected resources.*
5. **Hosting & Deployment** — How does the app reach users; what's the release pipeline? *Applies: always.*
6. **Cloud & Compute** — Any cloud compute; where do heavy workloads run? *Applies: when workloads exceed the device or must be centralized.*
7. **CI/CD & Version Control** — Automated build/test/release; VCS and branch strategy? *Applies: always.*
8. **Security & RLS** — Threat model, data isolation, secrets handling? (Row-Level Security specifically applies when a shared multi-tenant DB exists.) *Applies: always; RLS when multi-tenant.*
9. **Rate Limiting** — Abuse and cost protection on exposed endpoints? *Applies: when there's a public or multi-tenant API.*
10. **Caching & CDN** — Latency/cost optimization for served content? *Applies: when serving web assets/content at scale.*
11. **Load Balancing & Scaling** — Handling concurrent load; horizontal scale? *Applies: when one server handles many concurrent users.*
12. **Error Tracking & Logs** — How are production failures observed? *Applies: always — but the **mechanism** must fit the app's privacy model. "N/A" here is almost always wrong; the honest answer is "yes, via ___."*

---

## Standardized default stack

For the layers where a third-party service is the usual answer, these are the **org defaults** — the
tool you reach for unless there's a specific reason not to. Picking a default still counts as answering
the layer; **deviating requires a stated recommendation + approval** (and a one-line why in that app's
assessment).

| Concern | Layer(s) | Default | Deviate when… |
|---|---|---|---|
| **Error Tracking & Logging** | 12 | **Sentry** | privacy model forbids sending errors off-device, or there's no server to instrument. |
| **Caching & Rate Limiting** | 9, 10 | **Upstash** (serverless Redis) | there's no public/multi-tenant API or served content to cache or throttle. |
| **Hosting & Deployment** | 5 | **Railway** | the app ships as a client artifact (APK/binary) with no backend to host. |

A deviation is not a skip: the layer is still marked (PRESENT via the alternative, or N/A-by-design
with its trigger). The default just names what "PRESENT" would normally mean.

### Nibhaus deviation — **approved** (2026-07-03)

Nibhaus's local-first, no-telemetry ethos deviates from **all three** defaults, approved on that basis:

- **Sentry → deviated.** No cloud error telemetry (privacy rule). Replaced by local `logcat`, the
  user-facing `FailureDiagnosis` layer, and opt-in feedback (email + GitHub issues). Named trade-off,
  see Layer 12 below.
- **Upstash → N/A by design.** No public API and nothing web-served to cache or rate-limit; the only
  endpoint is single-tenant on the user's own tailnet. **Trigger →** a hosted/public API.
- **Railway → N/A by design.** Distributed as a signed **APK** (Play Store / sideload) built in CI; no
  backend to host. **Trigger →** a hosted backend or web companion.

---

## Assessment: Nibhaus — 2026-07-03

Local-first native Android app for Ncode smartpens. Founding rule: *no telemetry, no accounts, no ads;
outbound network only to sync/OCR targets the user picks.* That ethos is why most layers are N/A —
each with a named trigger that would flip it.

| # | Layer | State | Answer |
|---|---|---|---|
| 1 | Front-End | ✅ PRESENT | Jetpack Compose + Material 3 (native Android). Material You dynamic color **disabled** for brand consistency. |
| 2 | APIs / Back-End | 🟡 N/A BY DESIGN | Logic runs on-device (Kotlin). The only server is the **optional, self-hosted** OCR host + sync endpoint (premium, user-run on their own tailnet). **Trigger →** a hosted/multi-user tier. |
| 3 | Database / Storage | ✅ PRESENT | On-device Room/SQLite + local file exports (SVG/JSON/MD). Durable outbox; persist-first invariant (1 stored stroke == 1 queued export). Optional sync to the user's own storage. No cloud DB. |
| 4 | Auth / Permissions | 🟡 N/A BY DESIGN | No accounts (privacy rule). Auth surfaces that *do* exist: pen BLE password, sync-endpoint bearer token, Android runtime permissions. Premium entitlement = advisory local flag. **Trigger →** hosted/multi-user. |
| 5 | Hosting / Deployment | ✅ PRESENT | Distributed as an **APK** (Play Store / sideload). Freemium variant built in CI. No web hosting. |
| 6 | Cloud / Compute | 🟡 N/A BY DESIGN | On-device compute (ML Kit; llama.cpp on-device VLM for premium). Optional user-hosted OCR box. **Trigger →** a managed compute/inference service. |
| 7 | CI/CD / Version Control | ✅ PRESENT | GitHub + Actions (unit + instrumented + detekt + Semgrep + CodeQL). Open-core **public** repo + **private** premium repo; branch protection on `master`. |
| 8 | Security / RLS | ✅ PRESENT (RLS 🟡 N/A) | Device security + sync-endpoint auth (constant-time token compare, fail-closed) + secrets scanning + `PRE_SHIP_CHECKLIST.md` + no-telemetry. **RLS N/A** (no shared DB). **Trigger →** hosted DB. |
| 9 | Rate Limiting | 🟡 N/A BY DESIGN | No public API. The optional self-hosted endpoint is single-tenant on the user's tailnet. **Trigger →** a hosted/public API. |
| 10 | Caching / CDN | 🟡 N/A BY DESIGN | Nothing web-served; local reads are already local. **Trigger →** a web companion or served assets. |
| 11 | Load Balancing / Scaling | 🟡 N/A BY DESIGN | Scales by **distribution** — every user runs independently; no server fleet. **Trigger →** a hosted backend. |
| 12 | Error Tracking / Logs | 🟡 CONSTRAINED BY DESIGN | **Not "N/A."** No cloud telemetry/Sentry (privacy rule). Instead: local `logcat`, a user-facing failure-diagnosis layer (`FailureDiagnosis`), and opt-in feedback (email + GitHub issues). **Known trade-off:** no aggregate production-error visibility — reliance is on user-reported issues. Revisit if crash rates can't be diagnosed from reports. |

**Verdict:** 5 PRESENT, 6 N/A-by-design (each with a trigger), 1 constrained-by-design (Layer 12). No
UNDECIDED, no DEFERRED. Nibhaus passes the gate — the one layer worth watching is **12**: the privacy
stance means production failures are only as visible as users choose to report, which is a deliberate,
named trade-off rather than an oversight.
