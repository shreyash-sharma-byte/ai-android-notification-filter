# Architecture

This is the short tour. The point of the design is that **most of the code is not about the AI** — it
is about containment: deciding what may be sent, what happens when the provider misbehaves, and how
to be certain a mistake cannot delete something important.

## The split

```
                 ┌──────────────────────── pure core (no android.*) ────────────────────────┐
                 │  Decision   Backoff   CircuitBreaker   RateLimiter   Providers            │
                 │  LogCodec   Filters   Urls   Strictness   AllowList   Prompt   LocalModels│
                 │  Telemetry  LocalClassifier   Categories   Evidence   Guards   Senders     │
                 └────────────────────────────────────────────────────────────────────────────┘
                                                   ▲
        ┌──────────────────────────────────────────┴───────────────────────────────────┐
        │                          Android shell (thin)                                │
        │  NotifService ── reads notifications, applies the gate, runs the queue        │
        │  Classifier   ── builds requests, calls HTTPS, walks the response             │
        │  Prefs        ── the single preferences file, typed                           │
        │  HomeActivity ── consent, checklist and state, all on one screen              │
        │  Screen       ── palette, type scale, rows and hairlines for every screen     │
        └──────────────────────────────────────────────────────────────────────────────┘
```

Eighteen classes have no `android.*` import. That is not tidiness for its own sake: it is the
only reason the interesting logic is testable at all on a machine with no emulator. `test.sh`
compiles those eighteen against a bare JDK and **fails the build if any of them ever imports
Android** — the guard is what keeps the core honest over time.

Everything Android-specific lives in the listener, the classifier transport, the preference file
and the screens, and is deliberately dull.

## The on-device judgement order

The default engine answers in this order. The first three steps can only keep:

1. **Functional gate.** Media, calls, alarms, progress, navigation, status — and now
   `msg`, `email` and `event` — are never classified. `Categories` holds the list, and the
   service checks it before it reads title or text.
2. **Protected apps.** `Filters.isProtected` is a deliberately generous substring match.
3. **Guard rules.** `Guards` checks OTPs, money movement, order/delivery state, security events
   and account deadlines. The service calls it **before the engine split**, so both the
   on-device model and the provider path get exactly the same five rules, and guarded text is
   never put in a provider request at all.
4. **The linear model** (`LocalClassifier`). A dot product: positive leans promotional,
   negative leans ordinary.
5. **The evidence gate** (`Evidence`). A positive score is not a dismissal. The text must also
   contain actual promotional language; a model that learned `free` and `get` as promotional must
   not be allowed to dismiss "Are you free tonight?" on that basis.
6. **The uncertainty band.** A score inside ±`LocalClassifier.MARGIN` (0.25) is not a confident
   answer. With promotional evidence present the result is UNCLEAR — kept and labelled "unsure".
   Without evidence it is KEEP, because the gate has already settled the case.

The provider path uses steps 1–3 and then the provider's own one-word answer, contained by
`Decision`. It has no dot product, so the evidence gate does not apply to it: a provider's
explicit JUNK is contained by parsing and the guard rules, not by a lexical score.


## Fail-open invariants

These are the rules the code exists to keep. Any change that breaks one is a bug regardless of how
good it looks.

1. Every uncertain path keeps the notification. No key, no model, bad base URL, breaker open, rate
   limited, queue full, timeout, HTTP 4xx/5xx, unparseable body, empty answer, `null` anywhere —
   all KEEP.
2. Only an unambiguous JUNK dismisses. `Decision.parse` returns JUNK, KEEP or UNCLEAR, and UNCLEAR
   is treated as KEEP by the caller. Negations (`not junk`, `don't remove`) and answers containing
   both tokens are UNCLEAR on purpose: if the model contradicts itself, no bet is made. On the
   on-device engine, JUNK additionally requires a positive score **and** promotional evidence.
3. Protected-app matching over-protects. It is a substring test, so an entry that is too broad
   protects too much. Entries shorter than 3 characters are ignored so a stray `a` cannot blanket
   the device.
4. Functional notifications never leave the device. The gate is a list of categories and flags, not
   a judgement call, and it runs before any notification text is read or any request is built. The
   list includes `msg`, `email` and `event`; a brand's promotional SMS that uses `msg` is kept, and
   that is the accepted trade. (The service does read whether a key *exists* before this point, to
   avoid queueing work that cannot run; it never reads the key's value there.)
5. An UNCLEAR verdict is never cached. It is usually transient — a flaky provider, a rate limit, a
   model that rambled — so the next identical notification deserves a fresh attempt. An on-device
   UNCLEAR is recorded as a normal "kept (unsure)" success, not as an error; only a provider
   failure (`Classifier.Outcome.problem`) is reported as "needs attention".
6. Guard rules are layer 1 for both engines. They live in `Guards` and are called once in
   `NotifService.inspect`, before the engine split. `LocalClassifier` delegates to the same class
   only so direct callers (the Settings self-test and the tests) keep the same protection.
7. A positive model score is never sufficient on its own. The evidence gate (`Evidence`) must
   fire; a model score is only evidence of shared vocabulary with the training corpus.

## The reliability policy, with numbers

| Mechanism | Value | Why that value |
|---|---|---|
| Workers / queue | 2 / 30 | Two in-flight calls per notification would be a burst; 30 is ~a minute of a very chatty phone |
| Queue overflow | `AbortPolicy` + counter | Silently discarding hides an overload; the counter surfaces it in the UI |
| Circuit breaker | 5 failures → open 60 s → 1 probe | Five is enough to distinguish a broken key from a blip; a minute is short enough that a fixed key recovers by itself |
| Rate limiter | 40 calls / 60 s, token bucket | Bounds cost. A normal phone never gets near it |
| Connect / read timeout | 8 s / 15 s | Long enough for a cold model, short enough that a notification is still fresh |
| Retry | at most 1, only if the wait ≤ 5 s | A verdict that arrives 30 s late is worthless; the notification has been read by then |
| Backoff | `min(30 s, 1 s · 2^attempt)`, no jitter | Two workers and at most one retry per notification; the retry path uses the deterministic delay. `Backoff.withJitter` exists and is tested, but **is not called in production** — earlier versions of this document wrongly credited jitter to the retry path |
| Retry-After | seconds form honoured | The date form is ignored: clock skew turns it into an absurd wait |
| Answer budget | `max_tokens` 1024 | Non-reasoning models emit one word and are billed for one word; reasoning models need headroom or they return nothing |
| Cache | 300 entry LRU, keyed on content + config hash | Config is in the key, so changing the prompt invalidates every verdict |
| Removed log | 100 entries, escaped | Escaped because a tab or newline in a title used to shift every following column |

## Two decisions worth explaining

**Why the cache key includes a hash of `model|base|prompt`.** Without it, editing the prompt would
leave verdicts computed under the old policy in place, and the app would silently disagree with the
prompt the user is looking at. Including the config in the key makes a policy change take effect
immediately and costs nothing.

**Why `LogCodec` exists.** The original stored `millis \t app \t title \t text` joined by newlines.
A notification titled `Sale\t50% off` moved every field one column to the right, and a title with a
newline split one entry into two broken ones. Escaping the fields first makes the format lossless
and the parser impossible to confuse, and it is cheap enough to test exhaustively.

## Why there is no Gradle

The build is six commands: `aapt2 compile`, `aapt2 link`, `javac`, `d8`, `zipalign`, `apksigner`.
The app has no dependencies beyond the platform, so a dependency-aware build system has nothing to
resolve. The cost is that `BuildConfig` must be generated by hand (which `build.sh` does, from the
manifest) and that resources must be compiled explicitly. The benefit is a build anyone can read,
reproduce and audit in a minute, with no version-catalog mystery and no toolchain download.

If you need an AAB for Google Play, that is the point at which adding AGP is worth it —
see [PLAY.md](PLAY.md).

## Where to look first

| Question | File |
|---|---|
| Why wasn't my notification filtered? | `NotifService.inspect` — the gate, top to bottom |
| Why did it answer that way? | `LocalClassifier.classify`, `Evidence` and `Guards`; the measurements and the retraining recipe are in [CLASSIFIER.md](CLASSIFIER.md) |
| What counts as an answer? | `Decision.parse` and the assertions in `test/LogicTest.java` |
| Why is it keeping everything? | `Prefs.lastError`, then the circuit breaker and rate limiter |
| Where does a new user get stuck? | `HomeActivity` — the consent state, then the checklist of what is still missing |
