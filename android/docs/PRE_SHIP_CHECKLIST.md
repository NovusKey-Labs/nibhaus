# Pre-Ship Security & Safety Criteria

The baseline we abide by before any public/Play-Store release. Treat every item as a
gate, not a suggestion.

**Read the architecture first — it changes how the web-flavoured items apply.** Nibhaus is
**local-first Android**: notes/strokes/voice live in an on-device Room DB inside the app
sandbox. There is **no cloud backend, no Supabase, no in-app account system.** The only data
egress is the *optional* native sync, which the user turns on and points at **their own**
endpoint (a self-hosted NAS over a Tailnet), authenticated with a **user-supplied** bearer
token. The premium gate is module-presence + an **advisory** DataStore bool — **not** a
security control. So: items written for web/Supabase apps map to one of three places here —
**[device]** the APK/on-device surface, **[server]** the user's own sync endpoint (and any
self-hosted web UI like Paperless behind NPM/Tailscale), or **[N/A]** no surface in this app
as built. Each rule notes which.

---

1. **Protect yourself, not just your app.** The moment you collect user data you're in legal
   territory (GDPR, CCPA). Have a privacy policy. Know where user data lives.
   - ↳ **[device]** Even local-first needs a Play Store privacy policy + an accurate Data
     Safety form. Where data lives: on-device Room DB + voice files in the app sandbox; with
     sync on, page images + transcripts go **only** to the user's own endpoint, nowhere else;
     no analytics/telemetry collected. State exactly that.

2. **Row Level Security.** Without RLS, anyone can open DevTools and read your entire
   database. Zero policies means your app is naked.
   - ↳ **[server]** No multi-tenant DB on our side — the on-device DB is protected by Android
     app-sandbox isolation (DB not exported, no content provider). The real analogue is the
     **sync endpoint**: it must authenticate *every* request, and the Tailnet must not leave
     it (or any sibling service) open unauthenticated. (Same class of bug as the homelab
     Ollama `:11434` left unauth on the tailnet — verify none of that recurs.) Gate: confirm
     the NAS sync endpoint rejects missing/wrong tokens.

3. **Test the failure path, not just the happy path.** Catches ~80% of auth bugs.
   - ↳ **[device]** Our auth-equivalent failure matrix: wrong/empty sync token; endpoint
     unreachable mid-export (backlog must drain on reconnect); OCR on a blank/garbage page
     (must never hang or hallucinate — RoutedInk never-throws; blank-page case already fixed);
     duplicate ingest (persist-first invariant: 1 stored stroke == 1 queued export); pen
     reconnect after power-cycle (LE address rotates → re-scan, don't redial). Exercise each.

4. **Security baseline in 2 min.** Strong security headers and a solid baseline posture.
   - ↳ **[server]** HTTP security headers belong to the sync endpoint + any self-hosted web UI
     (Paperless), not the Android app. **[device]** App baseline: release build keeps
     NetworkSecurityConfig cleartext **off** except the one explicit documented catch, and
     uses TLS wherever the endpoint supports it.

5. **OWASP.** Where SQL injection, XSS, and auth bugs actually get caught.
   - ↳ **[device]** Run OWASP **MASVS / MASTG** (mobile) against the release APK — insecure
     storage, input trust, reverse-engineering exposure. **[server]** Run OWASP **API Top 10**
     against the sync endpoint. Reminder during this pass: the premium gate is *advisory UI*,
     never a security boundary — don't audit it as one.

6. **Client-side validation is UX, not security.** Attackers disable JS and hit your API
   directly. Validate again on the server. Every time.
   - ↳ **[server]** The sync endpoint must independently validate/authorize every PUT/GET — it
     cannot assume the app "only sends good data." The app is untrusted from the server's view;
     the server is the trust boundary.

7. **AI code leaks data in 3 spots:** `.env` values in the frontend, API responses returning
   too much, secrets in logs.
   - ↳ **[device]** (a) No secrets baked into the APK/resources — the sync token is
     user-entered and stored in DataStore, never hardcoded; (b) sync responses return no more
     than needed; (c) no tokens or page/PII content in logcat. Gate: grep the release artifact
     and a live logcat for the token and for note text.

8. **API keys in the frontend means game over.** If it's in the browser, assume it's taken.
   Move it server-side or proxy it.
   - ↳ **[device]** The **APK is "the frontend"** — anything shipped in it is extractable.
     So we ship **no** shared/secret token; BYO endpoint + token are user-supplied, stay on
     the user's device, and reach only the user's own server. Gate: confirm no default token
     or endpoint credential leaks into the build.

9. **Rate limits before someone burns your API bill.** Cap every endpoint hitting a paid API.
   - ↳ **[N/A, by design]** No hosted/paid API on our side — OCR and translation run on-device
     or against the user's own BYO GPU (zero per-user marginal cost; a hosted tier is
     explicitly rejected in the spec). A BYO-server owner should rate-limit their *own*
     endpoint. Re-evaluate only if a hosted tier is ever introduced.

10. **CAPTCHA on public forms + CORS locked to your domain.** Kills bot floods.
    - ↳ **[N/A for the app]** No public web forms, no browser origin in the Android app, so
      CAPTCHA/CORS don't apply to it. **[server]** They apply only to any public-facing web
      surface (the family Paperless/web UI) — keep those bound to the Tailnet / locked to the
      domain.

11. **Error messages that don't leak.** "User not found", not "SELECT * FROM users failed".
    Log full errors server-side, show users generic messages.
    - ↳ **[device + server]** User-facing errors stay generic ("Sync failed — will retry");
      full detail goes to logcat / server logs only. No raw stack traces, SQL, or endpoint
      internals surfaced in the UI.

---

*Origin: forwarded by the project owner (2026-06-27) as the security baseline to enforce
before shipping. Items 2/4/5/6/10 are partly or wholly server-side (the user's own sync
endpoint / self-hosted web UI); 9 is N/A while OCR/translate stay on-device or BYO.*
