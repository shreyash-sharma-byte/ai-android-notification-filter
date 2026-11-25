# Architecture

This is the short tour. The point of the design is that **most of the code is not about the AI** — it
is about containment: deciding what may be sent, what happens when the provider misbehaves, and how
to be certain a mistake cannot delete something important.

## The split

```
                 ┌──────────────────────── pure core (no android.*) ────────────────────────┐
                 │  Decision   Backoff   CircuitBreaker   RateLimiter   Providers            │
                 │  LogCodec   Filters   Urls   Strictness   AllowList   Prompt   LocalModels│
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

Twelve classes have no `android.*` import. That is not tidiness for its own sake: it is the only
reason the interesting logic is testable at all on a machine with no emulator. `test.sh` compiles
those twelve against a bare JDK and **fails the build if any of them ever imports Android** — the
guard is what keeps the core honest over time.

Everything Android-specific lives in the listener, the classifier, the preference file and the
screens, and is deliberately dull.

## Fail-open invariants

These are the rules the code exists to keep. Any change that breaks one is a bug regardless of how
good it looks.

1. Every uncertain path keeps the notification. No key, no model, bad base URL, breaker open, rate
   limited, queue full, timeout, HTTP 4xx/5xx, unparseable body, empty answer, `null` anywhere —
   all KEEP.
2. Only an unambiguous JUNK dismisses. `Decision.parse` returns JUNK, KEEP or UNCLEAR, and UNCLEAR
   is treated as KEEP by the caller. Negations (`not junk`, `don't remove`) and answers containing
   both tokens are UNCLEAR on purpose: if the model contradicts itself, no bet is made.
3. Protected-app matching over-protects. It is a substring test, so an entry that is too broad
   protects too much. Entries shorter than 3 characters are ignored so a stray `a` cannot blanket
   the device.
4. Functional notifications never leave the device. The gate is a list of categories and flags, not
   a judgement call, and it runs before any notification text is read or any request is built. (The
   service does read whether a key *exists* before this point, to avoid queueing work that cannot
   run; it never reads the key's value there.)
5. An UNCLEAR verdict is never cached. It is usually transient — a flaky provider, a rate limit, a
   model that rambled — so the next identical notification deserves a fresh attempt.

## The reliability policy, with numbers

| Mechanism | Value | Why that value |
|---|---|---|
| Workers / queue | 2 / 30 | Two in-flight calls per notification would be a burst; 30 is ~a minute of a very chatty phone |
| Queue overflow | `AbortPolicy` + counter | Silently discarding hides an overload; the counter surfaces it in the UI |
| Circuit breaker | 5 failures → open 60 s → 1 probe | Five is enough to distinguish a broken key from a blip; a minute is short enough that a fixed key recovers by itself |
| Rate limiter | 40 calls / 60 s, token bucket | Bounds cost. A normal phone never gets near it |
| Connect / read timeout | 8 s / 15 s | Long enough for a cold model, short enough that a notification is still fresh |
| Retry | at most 1, only if the wait ≤ 5 s | A verdict that arrives 30 s late is worthless; the notification has been read by then |
| Backoff | `min(30 s, 1 s · 2^attempt)` + ≤ 250 ms jitter | Jitter keeps two workers from retrying in lockstep |
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
| Why did it answer that way? | `Strictness.prompt` plus `Prompt.safetyRules`, then **Test** in Where answers come from |
| What counts as an answer? | `Decision.parse` and the assertions in `test/LogicTest.java` |
| Why is it keeping everything? | `Prefs.lastError`, then the circuit breaker and rate limiter |
| Where does a new user get stuck? | `HomeActivity` — the consent state, then the checklist of what is still missing |
