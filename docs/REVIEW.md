# Pre-publication review — NotifyCleaner 2.3.5

> **Status (same day):** the findings in §0.1, §1, §2, §3.1, §3.5 and §6 have been implemented in the
> working tree. The classifier work — including the before/after measurements, rejected options and
> residual risks — is recorded in [CLASSIFIER.md](CLASSIFIER.md). This document is kept as the record
> of what was found, because a review that is quietly rewritten after the fact is not a review.
>
> One correction to it: §0.1 cause 4 originally named `is`, `your`, `this` and `was` as positive
> weights. Three of those are negative; the error came from a mis-sorted inspection of the asset and
> is corrected in place. The conclusion — generic English dominates the positive weights — stands.
>
> Still blocking publication after this work: `targetSdkVersion` (35; Play requires 36), no AAB path,
> no hosted privacy policy or in-app link, no re-captured store assets, and the Play Console
> declarations. See §4 and §8.

**Date:** 2026-11-15 · **Reviewed at:** commit `7789492` · **Scope:** Play Store and F-Droid release readiness, with emphasis on user comfort, confusion and trust.

**How this was done.** The whole source tree, manifest, resources, docs and the four screenshots were read. `./test.sh` was run (280 passed, 0 failed). The shipped model asset was loaded and exercised directly on a plain JDK, the URL allowlist and log codec were compiled and probed, and the screenshots were pixel-scanned to check what actually renders. No device or emulator was used, so anything about runtime appearance is inferred from code plus those screenshots.

**Verdict.** The engineering underneath this app is unusually careful and the writing is unusually good. The *product* is not ready to publish, for three reasons that are independent of each other:

1. **It removes real personal messages.** Not a rare edge case — 16 of 42 ordinary chat messages I tested were classified JUNK. This is the single thing that will generate one-star reviews and refund demands, and it contradicts the app's central promise.
2. **The home screen says "Status · live" without knowing whether the listener is alive**, and there is no notice, undo or easy record when something is removed.
3. **It reads and stores the name of every app that posts, before consent and while paused**, while the first screen says "nothing is read until you agree".

On top of that there are four hard publishing blockers (target SDK, AAB, hosted privacy policy, a trademarked app name) and a rendering bug that makes every section rule in the app invisible.

The good news: #1 has a cheap, principled fix; #2 is a few hours; #3 is moving one line. The design work is already done and is genuinely distinctive — it needs to stop being *clever* at the moments where it must be *plain*.

---

## 0. The three things to fix before anything else

### 0.1 It deletes real messages (Critical)

**Evidence.** I loaded `assets/local_model.tsv.gz` through `LocalClassifier.classify` and ran everyday messages through it:

| App | Title | Text | Verdict | Score |
|---|---|---|---|---|
| Signal | Alice | Call me when you see this | **JUNK** | 0.540 |
| Signal | Bob | Are you free tonight? | **JUNK** | 0.890 |
| Signal | Team | Meeting at 4pm in room 2 | **JUNK** | 0.588 |
| Signal | Alice | dinner at 8? | **JUNK** | 0.315 |
| Slack | #general | standup in 10 | **JUNK** | 0.093 |
| Messenger | Zoe | where are you | **JUNK** | 0.403 |
| Signal | Alice | can you pick up milk | **JUNK** | 0.046 |
| Messages | Mum | are we still on for lunch? | **JUNK** | 0.005 |
| Zomato | Zomato | 50% OFF for you | JUNK | 0.844 |
| ICICI Bank | ICICI | 482913 is your OTP | KEEP (rule: otp) | — |

Over a 42-message corpus of ordinary conversational texts, **16 (38%) were JUNK.**

**Why it happens.** Four causes, all fixable:

1. `NotifService.FUNCTIONAL_CATEGORIES` (`NotifService.java:49-51`) omits `"msg"`, `"email"` and `"event"`. Almost every chat app posts `CATEGORY_MESSAGE`, so the cheapest and most reliable guard in the app never fires for chat.
2. `Prefs.DEFAULT_ALLOW` (`Prefs.java:41-45`) covers WhatsApp, Telegram, Google/Samsung Messages and dialers — but not Signal, Slack, Discord, Messenger, Instagram, Viber, LINE, Teams or AOSP MMS.
3. `LocalClassifier.MARGIN` is `0` (`LocalClassifier.java:79`), so a score of **0.005** is treated as a confident decision. The `UNCLEAR` branch at line 185 is dead code because it requires `MARGIN > 0f`.
4. The model's positive weights are dominated by generic English rather than promotional language: `get` +2.25, `off` +2.23, `week` +2.11, `this` +1.98, `up` +1.44, `and` +1.29, `on` +1.28, `now` +1.14, `today` +1.05, `free` +1.14. (`is`, `your` and `was` are strongly *negative*, which is why some short messages were kept and others were not.) A sentence that happens to contain "free" and "tonight", or "up"/"on"/"now", drifts above zero with no promotional intent at all.

**Fix, in order of effort:**

- Add `"msg"`, `"email"`, `"event"` to `FUNCTIONAL_CATEGORIES`. This alone protects essentially all chat apps, because they set the category. Keep a promo SMS from a brand? Yes — and that is the correct trade for a tool that must never eat a message.
- Expand `DEFAULT_ALLOW` to the common messengers (`org.thoughtcrime.securesms`, `com.Slack`, `com.discord`, `com.facebook.orca`, `com.instagram.android`, `com.viber.voip`, `jp.naver.line.android`, `com.microsoft.teams`, `com.android.mms`).
- Require **positive promotional evidence** before dismissing: dismiss only when `score > 0` **and** the text contains at least one high-precision promo cue (`%`, `off`, `sale`, `offer`, `deal`, `coupon`, `code`, `cashback`, `download`, `install`, `limited`, `win`, `free trial`, `shop now`…). This inverts the default to "keep unless there is evidence of junk", which is what the app already claims to do.
- Raise `MARGIN` to a real band (e.g. 0.25) and return `UNCLEAR` inside it, so genuinely marginal cases are kept and counted rather than guessed.
- Retrain or prune: drop document-frequency stopwords (`get`, `this`, `up`, `and`, `on`, `now`, `today`, `free`, `week`) from the feature set, or enforce a minimum feature precision. Retraining is the real fix; the evidence gate in §0.1 is the safe stopgap.

Add fixtures for exactly these sentences to `test/LogicTest.java`; today there is no personal-message fixture at all.

### 0.2 "Status · live" is a preference, not a connection (High)

`HomeActivity.java:151-152` computes `running` from `prefs.enabled()`; `notificationAccessGranted()` (`Screen.java:847-855`) only checks a string in `Settings.Secure`. Nothing asks the service whether it is bound. `NotifService.onListenerDisconnected()` logs and does nothing (`NotifService.java:332-335`), and there is no `onListenerConnected`.

So the app can show **"Status · live"** with a turning pointer while nothing is being judged — OEM battery managers (MIUI, ColorOS, Funtouch, One UI) kill listeners routinely, and a force-stop leaves `enabled_notification_listeners` intact. On a tool whose entire job is silently deleting things, claiming to work when it doesn't is the worst possible failure mode.

**Fix.** Track connection state in the service (`onListenerConnected`/`onListenerDisconnected` → a static volatile plus a timestamp), and derive the home state from *bound* plus *last notification seen*. Show `Filtering is on · last checked 2 min ago`, and when stale: `Filtering is on, but Android has stopped it. Tap to restart.` Never print "live" from a stored flag.

### 0.3 It reads and stores app names before consent and while paused (High)

`NotifService.java:132-135`:

```java
final Prefs settings = new Prefs(this);
if (!settings.hasSeenApp(pkg)) settings.recordSeenApp(pkg, label(pkg));  // package + label stored
if (!settings.enabled()) return;                                        // consent gate is below
```

Every posting app's package and display name is written to `SharedPreferences` (`Prefs.java:239-246`, up to 300 entries) before the consent check, before the "ready" check, before the protected-app check, and while filtering is paused. There is no UI to view or clear that list.

That directly contradicts three published statements: `HomeActivity.java:110` ("Nothing is read, and nothing leaves this phone, until you agree"), `Screen.java:675` ("Before anything is read"), and `PRIVACY.md` ("What never leaves the device … anything that arrived while the app was paused"). It is also the kind of finding Play's notification-access review looks for.

**Fix.** Move `recordSeenApp` below `enabled() && ready()` (or at least below `consented()`), add a "Forget remembered apps" action, and disclose the list in `PRIVACY.md` — see §6.

---

## 1. User comfort and confusion

### 1.1 The first run never says what will actually happen

The cover's headline is **"Your notifications, edited."** and the lede says promotions, sales and newsletters **"go"**. Nothing says *removed from the shade, automatically, without asking, and cannot be brought back*. "Edited" reads like a digest app. The consequence sentence — "Filtering stays off until you turn it on yourself" — is set at 11.5sp in `c.faint` (2.53:1 contrast; see §2.2), i.e. the least legible line on the screen, beneath a 54dp full-bleed button.

There is also no way to decline: the cover offers one button, "I agree", and leaving means pressing Back or killing the app. A consent screen with only an accept path invites the "forced consent" reading even when the intent is the opposite.

**Fix.** Say the thing: *"If a notification looks like an ad, a sale or a newsletter, this app removes it from your notification shade automatically. It cannot be brought back. Messages, codes, payments and anything it is unsure about are left alone."* Then the three-step next: *grant access → turn filtering on → watch the Removed list*. Put the "filtering stays off until you turn it on" line **above** the button at ≥14sp in `c.muted`. Add "Not now" beneath it (leaves the app read-only, stores nothing).

### 1.2 The vocabulary requires a legend

Every one of these is a small decoding task placed in front of a user who wants to know one of three things: is it on, what did it delete, how do I stop it.

| Where | Current | Suggested |
|---|---|---|
| `HomeActivity:78` | "No. 2.3.5" | delete (version belongs in Settings → About) |
| `HomeActivity:94` | "Before anything is read" | "Before you agree" |
| `HomeActivity:172-175` | "Status · live" / "Status · ready" / "Status · needs attention" | "Filtering is on" / "Filtering is off" / "Filtering needs attention" |
| `HomeActivity:231` | "Status · not set up" | "Finish setup" |
| `HomeActivity:317` | "this hour" | "removed in the last hour" |
| `HomeActivity:352` | "3 in this hour, against a peak of 9." | "3 removed in the last hour. Busiest hour in 12: 9." |
| `HomeActivity:374-379` | "Notifications / removed", "Protected / never read", "Watched / apps seen" | "Removed", "Apps protected", "Apps seen" |
| `HomeActivity:207` | "Activity · last twelve hours" | "Removed per hour, last 12 hours" |
| `HomeActivity:211` | "Removed by sender" | "Which apps lost the most" |
| `HomeActivity:412-419` | "12h / 6h / Now" | "12 hours ago / 6 hours ago / now" |
| `HomeActivity:515` | "Where answers come from" | "How notifications are judged" |
| `HomeActivity:545-547` | "The rule" + italic promise | "What it never removes" |
| `RemovedActivity:83` | "the model answered JUNK" | "the app judged it an advert" |
| `SettingsActivity:89` | "Answers from: On this device" | "Judged by: On this phone" |
| `SettingsActivity:239` | "Set in Noto Serif and Roboto" | delete |
| `AnswersActivity:265` | "Testing…" | "Sending a test request…" |

"JUNK" is a model label being shown to a person who just lost a notification. So is "kept by rule: otp" in the self-test dialog (`SettingsActivity:134-136`). Both should be translated into outcomes, with the technical detail available behind a "Details" expander if you want to keep it.

Keep the editorial voice for *prose*. Never for a label, a button, or a status.

### 1.3 States that lie, and one dead end

- **"Status · ready" means filtering is OFF** (`HomeActivity:174` vs the button at `:190`). "Ready" reads as success. Two states need two sentences, not two adjectives.
- **Paused + last check failed** shows "Status · ready" with "Last check … failed" underneath (`HomeActivity:172` gates only on `running`).
- **"Watched · apps seen 0"** on a fresh install is a zero that explains nothing. `HomeActivity:345-353` already knows `t.watched` and `t.lastAt`; use them: *"Watching 42 apps. Nothing removed yet. Last notification checked 2 minutes ago."*
- **Reachable dead end.** Turn filtering on, then go to Settings → Answers from → choose a provider and leave the form incomplete. `ready()` is now false, so Home renders `buildContents` — which has **no pause button** (`HomeActivity:228-279`). The user is told "cannot run" and cannot turn it off. The same happens if notification access is revoked while filtering is on.
- **The protected list cannot be emptied, and the toggle then lies.** `AllowList.remove` can produce `""`; `Prefs.allowList()` (`Prefs.java:186-190`) maps `""` back to `DEFAULT_ALLOW`; `ProtectedAppsActivity` does not re-render rows on toggle, so the last app stays visually **off** while the service keeps protecting all 11 defaults. Fix with an explicit "none" sentinel (or a `configured` flag) and re-read the list after every toggle.
- **"Add by name" creates invisible entries.** Any text ≥3 chars is stored (`ProtectedAppsActivity:222-251`) and counted in "N apps protected", but rows only render apps from `loadApps()`. A typo is therefore permanent, uncounted-for, and unremovable.

### 1.4 The one irreversible action has no undo, no notice and no front door

`NotifService.java:242-257` dismisses silently. There is no toast, no notification channel, no `POST_NOTIFICATIONS`, nowhere in the project. The only record is `RemovedActivity`, reachable only from row 01 of the "Contents" list — after the dial, the counters, the trace and the sender table, roughly 1.5 screens down a page whose top half has no navigation at all.

Recommendations, in descending value:

1. **Watch-only first run.** Default to *"Watch only — don't remove anything yet"* for the first 24 hours (or the first ~20 decisions), logging what *would* have been removed. This is the single best trust-building change available, and it lets a reviewer verify the classifier without risking their own notifications.
2. **A removal notice.** An optional low-priority notification: *"Removed 2: Amazon, Myntra — tap to review"*, with a **Protect this app** action. It also gives the app a reason to hold the listener alive on aggressive OEMs.
3. **Make the evidence reachable.** The removed counter, each sender row, and the dial should open `RemovedActivity`; add a "Removed" row to Settings.
4. **Say it once, plainly:** *"Removed notifications cannot be brought back. Everything removed is listed here so you can check it."*

### 1.5 The counter saturates at 100 and is presented as all-time

`LogCodec.MAX_ENTRIES = 100`; `Telemetry.of` computes `removed = events.size()` from that capped log (`Telemetry.java:201`); `HomeActivity:374` and `RemovedActivity:133` print it as "Notifications removed". After the 101st removal the app says "100" forever. The `value >= 1000` and `>= 10000` branches at `HomeActivity:387` are unreachable — a fossil of the intent. The 12-hour trace and the sender ranking are likewise computed from the last 100 events only.

**Fix.** Keep a monotonic all-time counter in `Prefs`, and label honestly: *"1,284 removed all time · showing the last 100"*.

### 1.6 Two product contradictions worth resolving

- **Gmail is protected by default** (`Prefs.java:44`), so Gmail newsletters — the most common newsletter on earth — are never filtered, while the consent screen promises "newsletters go". Either drop Gmail from the defaults or say on the cover which 11 apps are pre-protected and why.
- **Strictness only exists for the cloud path.** `SettingsActivity:44` builds the "How strict" section only when `usesProvider()`; the offline branch of `NotifService.judge` ignores `Strictness` entirely. So the default engine — the one almost everyone will use — has no aggressiveness control at all, and the control silently disappears when you switch to it. Either wire `Strictness` into `LocalClassifier` (threshold per level) and show it always, or state plainly: *"The on-device classifier cannot be tuned. If it removes something you want, protect that app."*

---

## 2. Visual and accessibility

### 2.1 Every section rule renders at zero height (verified)

`Screen.margins()` (`Screen.java:578-586`) always returns height `WRAP_CONTENT`. `rule()` (`Screen.java:367-373`) and `accentRule()` (`:376-381`) put their intended height in their **own** `LayoutParams`, which are discarded when a caller passes `margins()` to `addView`. Inside the `ScrollView`, content is measured with an `UNSPECIFIED` height spec, so a background-only `View` with `WRAP_CONTENT` resolves to **0 px**.

Call sites affected: `Screen.java:316, 412-416`, `HomeActivity.java:105 (the 44dp accent rule), 199, 206, 210, 508, 544, 125 is fine`, `SettingsActivity.java:106, 201, 220`.

I pixel-scanned `docs/screenshots/consent.jpg`: the only full-width line-coloured row in the entire 1170 px page is at y=936 — the footer rule at `HomeActivity:125`, the one call that does **not** pass `margins()`. The masthead rule and the vermilion accent rule are simply absent, which is why the screenshot shows a large unexplained gap between the lede and "NOTHING LEAVES YOUR DEVICE".

**Fix.** Give the rules `setMinimumHeight(...)` so `WRAP_CONTENT` resolves to the intended thickness, and add margins to the view rather than replacing its params (`((LinearLayout.LayoutParams) v.getLayoutParams()).topMargin = ...`). Re-capture the screenshots afterwards — the app currently looks unstructured, and this is the single highest-value visual fix.

### 2.2 Contrast

Computed with the WCAG 2.1 formula against `Screen.Palette`:

| Pair | Light | Dark | |
|---|---|---|---|
| ink on bg | 16.36 | 15.91 | AAA |
| muted on bg | 4.75 | 6.55 | AA |
| **faint on bg** | **2.53** | **3.56** | **fails AA** |
| **faint on surface (dial face)** | **2.64** | **3.31** | **fails** |
| accent on bg | 4.77 | 5.01 | AA |
| onAccent on accent (button) | 4.84 | 5.00 | AA |
| good on bg | 5.59 | 7.22 | AA |
| **bad on bg** | 6.03 | **4.44** | dark fails at 10–13sp |

`faint` carries the version line, the dial's "this hour", the trace axis, the timestamps, the sender package names and the consent caveat — at 9.5–12sp. It is the app's meaning layer and it is the one that fails.

**Fix.** Set `faint` to `#6F6657` (5.29 light) / `#968A7C` (5.63 dark) and use it only for decoration; nothing informational below 4.5:1. Raise the 9.5–11.5sp floor to 12sp for anything a user must read, and darken `bad` in the dark palette.

### 2.3 Type scale, layout and font scaling

- The dial reading is a 44sp figure inside a **fixed** `min(246dp, width-48dp)` square (`HomeActivity:298-330`), with a 9.5sp unit and an 11sp word under it. At font scale 1.3 the stack crowds the inner ring; at 2.0 it overflows the fixed frame and the number can wrap inside the circle.
- The three counters (`HomeActivity:368-394`) are 1.25/1/1 weighted columns holding a 48sp figure and 10sp tracked caps. At 2.0 "NOTIFICATIONS" needs ~169dp in a ~123dp column.
- The masthead's 29-character name at 10.5sp with 0.14em tracking and no ellipsize clips mid-word at 320dp or font scale 1.5 (`Screen.java:302-315`).
- `LogCodec.cut` (`LogCodec.java:113-116`) uses `String.substring`, which can split a surrogate pair at the 80/120/300 caps — see §3.9.

**Fix.** `setAutoSizeTextTypeUniformWithConfiguration` on the dial figure and the counters, a font-scale branch that drops the dial to 180dp / 32sp above ~1.3, `setSingleLine` + autosize on figures, a two-row counter layout, and a shorter product name.

### 2.4 Touch targets, roles and TalkBack

- Every button is a `TextView` with an `OnClickListener` (`Screen.java:525-555`, `HomeActivity:553-570`). TalkBack announces the text but not the role. No `AccessibilityDelegate`, `AccessibilityNodeInfo` or `setRoleDescription` exists anywhere in the project.
- The header actions (`CLEAR`, `TEST`, `ADD`, `RESET`) are ~38dp tall (`Screen.action`, `Screen.java:525-533`) — the destructive action is the smallest target on the screen.
- `Ornaments.Toggle` is 44×26dp (`Ornaments.java:120-122`), is not a real `Checkable`, and never fires an accessibility event on change, so TalkBack cannot say "switch, on" or announce the new state.
- The dial sets a long prose `contentDescription` (`HomeActivity:305`) *and* overlays three real TextViews, so TalkBack reads the same information twice.
- `supportsRtl="true"` is declared but every inset uses `left`/`right`, the chevron is a literal "→", and the back mark a literal "←".

**Fix.** One `makeButton(view, role)` helper that sets `className = Button` and a role description; 48dp minimum via `setMinHeight`/`TouchDelegate`; make `Toggle` `Checkable` and announce `"WhatsApp, protected, on"`; group each counter into a single accessible node ("0 notifications removed"); either implement start/end padding and mirrored arrows, or set `supportsRtl="false"`.

### 2.5 Motion

`DialView` runs an **infinite** 9s `ValueAnimator` while filtering is on; `Screen.revealChildren` staggers nine views on every `setScreenContent`; `countUp` animates 0→N over 620ms on every render. None of them consult `ValueAnimator.areAnimatorsEnabled()`.

Worse, `HomeActivity.render()` is called from `onResume()` and rebuilds the entire view tree via `setContentView`. So every return to the app (and every button press) **resets the scroll position, replays the entrance animation, and re-counts every counter from zero**. In an app that deletes things, "57 removed" visibly falling to 0 and climbing back reads as data loss.

`Screen.revealed` (`Screen.java:91-92`) exists to prevent exactly this and is never read — dead code with a comment that promises the opposite of the behaviour.

**Fix.** Build once in `onCreate`, update values in `onResume`; preserve scroll; gate all animations on `areAnimatorsEnabled()`; stop the dial when the window is not visible.

---

## 3. Correctness and security

Findings below are ordered by severity; each was verified by reading the code, and where marked ✔ by running it.

### 3.1 The provider engine drops all five guard rules (High)

`LocalClassifier.classify` applies the OTP/transaction/order/security/deadline regexes before the model. `NotifService.judge` calls that only on the offline branch; the provider branch goes straight to `Classifier.classify` (`NotifService.java:224-240`), which applies only `Decision.parse` to the model's one-word answer.

So switching to a provider removes the code-level safety net entirely: a provider that answers JUNK for an OTP dismisses it. This contradicts `README.md:24-26` ("A rule hit means KEEP, before any model is consulted") and `NotifService.java:31-35` ("nothing about dismissal changes with the engine").

**Fix.** Call the guards in `NotifService` *before* the engine split (or expose `LocalClassifier.guard` as a pure helper) so both paths share them, and add a test.

### 3.2 The plain-HTTP allowlist is bypassable (Medium) ✔

`Urls.isPrivateHost` (`Urls.java:43-52`) is a set of string prefixes. Verified:

```
allowed("http://127.evil.com/v1")      -> true
allowed("http://10.evil.com/v1")       -> true
allowed("http://192.168.evil.com/v1")  -> true
allowed("http://172.16.evil.com/v1")   -> true
allowed("http://evil.com/v1")          -> false   <- correct
```

All four are ordinary public DNS names. The documented invariant — "a public host over plain HTTP is always refused" — therefore does not hold, and with `usesCleartextTraffic="true"` the code gate is the only defence. `test/LogicTest.java:360-378` tests only literal addresses.

**Fix.** Require literal dotted-quad/IPv6 for the private ranges, or resolve and require every returned address to be loopback/site-local/link-local. Better still: delete the cleartext exception entirely — the "Local server" preset is no longer offered (`Providers.LOCAL` is dead), so the only way to reach it is typing a private URL into Custom. Removing it kills this bug, the Play scanner warning and the Data Safety inconsistency in one change.

### 3.3 Redirects are followed, unrevalidated, and resend text and key (Medium)

`Classifier.Http.post` (`Classifier.java:299-328`) never calls `setInstanceFollowRedirects(false)` and never validates the final URL. A 307 from an approved host re-sends the POST body and custom headers to the new host — including `x-api-key` or `Authorization`. Combined with `usesCleartextTraffic`, an `https → http` redirect is permitted.

**Fix.** `setInstanceFollowRedirects(false)`; treat any 3xx as a failed request (KEEP). Add a local-server test with 301/302/307/308 to another host.

### 3.4 Provider consent is bound to the provider id, not the endpoint (Medium)

`Prefs.providerAgreed()` (`Prefs.java:120-122`) compares only the provider id. The Endpoint field writes through on every keystroke (`AnswersActivity.java:131`) and does **not** clear consent. So: choose Custom → set `https://api.openai.com/v1` → agree → later change it to `https://attacker.example/v1` → no new disclosure, and notification text goes to the new host. `PRIVACY.md:30-33` promises the opposite. For Custom, `Screen.hostFor` uses `Providers.baseFor(preset)`, which is empty, so the disclosure says "Custom" rather than naming the host — also contrary to the promise.

**Fix.** Include the normalised base (or just the host) in the consent fingerprint, clear consent on `setBase`, and have `hostFor` use the actual stored base for Custom.

### 3.5 Unbounded text and unbounded responses (Medium)

The on-device path passes the full notification text, `BIG_TEXT` and all `TEXT_LINES` into `LocalClassifier.render`/`tokenize`/`score` (`NotifService.java:165-176`, `LocalClassifier.java:190-237`), building a HashMap of unigrams and bigrams. The cloud path truncates (`Prompt.body`); the offline path does not. Any app can post a ~1 MB body; two workers plus a 30-deep queue is a plausible OOM. It also contradicts `Screen.java:682-683` ("Long text is shortened before it is classified").

`Http.read` (`Classifier.java:330-342`) appends until EOF with no cap; a hostile or misconfigured provider can return hundreds of MB, then `new JSONObject(...)` copies it.

**Fix.** Cap title/text/lines on read (a few thousand chars), reuse `Prompt.body` for the offline render, and cap the HTTP response at ~64 KB.

### 3.6 A rate-limited probe can wedge the circuit breaker (Medium)

`CircuitBreaker.allowsRequest` sets `probeInFlight = true` when transitioning to HALF_OPEN (`CircuitBreaker.java:26-44`). `Classifier.classify` then checks the rate limiter and, if it is empty, returns `Outcome.keep(...)` **without** calling `recordSuccess`/`recordFailure` (`Classifier.java:81-88`). The breaker stays HALF_OPEN with the probe held forever, so every later request is refused until the process restarts.

**Fix.** Check the limiter before the breaker, or release the probe on every early return.

### 3.7 Unprotecting one app can silently unprotect others (Medium) ✔

`AllowList.remove` (`AllowList.java:29-45`) drops every entry that is a substring of the target:

```
remove("google,com.amazon", "com.google.android.gm") -> "com.amazon"
```

The broad `google` entry silently disappears, so every other package it protected loses protection — a fail-open change to the only mechanism that keeps OTP and bank apps out of the classifier. `test/LogicTest.java:414-419` currently asserts this behaviour as correct.

**Fix.** Remove only exact matches or parent-package prefixes (`target.startsWith(entry + ".")`); if a broader entry still protects the app, say so instead of deleting it.

### 3.8 XML-illegal characters can wipe the preferences file (Medium) ✔

`LogCodec.escape` handles C0, DEL, U+2028/2029 but not U+FFFE, U+FFFF or unpaired surrogates:

```
escape("a\uFFFFb\uD800c") -> contains raw U+FFFF: true, raw lone surrogate: true
```

`String.substring` in `cut()` can create a lone surrogate at a field boundary. `recordError` (`Prefs.java:262-267`) is not escaped at all, and a JSON error body may legally contain `\u0000`. The file's own javadoc explains the consequence: an illegal XML character makes Android fail to parse the prefs and silently fall back to an empty map — losing the API key, model, prompt and protected list.

**Fix.** Extend the escaper to U+FFFE/FFFF and unpaired surrogates, apply it to `recordError`, cut on code-point boundaries, and add tests.

### 3.9 Smaller but real

- **API key follows a provider switch.** `setProvider` clears consent but not the key (`Prefs.java:147`), so configuring OpenAI then switching to DeepSeek sends the OpenAI key to DeepSeek. Store keys per provider, or clear on switch.
- **Provider errors are shown raw.** `Classifier.errorMessage` falls back to the first 160 chars of the raw body; `HomeActivity.lastLine` prints it on the dashboard, truncated at 90 chars mid-word. A Cloudflare HTML page becomes a paragraph in the middle of the home screen. Map known failures to sentences and keep raw text behind "Details". Redact the configured key from any stored error string.
- **The cache has no TTL and gaps in its key.** A JUNK verdict lives for the process lifetime, is applied on a cache hit without `stillCurrent` (`NotifService.java:189-193`), omits a full-title hash, the app label and the provider id, and concatenates config fields with an unescaped `|`. Prefer caching KEEP; make JUNK short-lived.
- **`FLAG_SECURE` is not set on `RemovedActivity`**, whose whole purpose is displaying notification text, so it appears in the recents thumbnail and can be screenshotted by other apps.
- **`notificationAccessGranted()`** is a substring test on the settings string (`Screen.java:851`); compare full `ComponentName`s.
- **Dead safety helpers.** `Decision.isDismissable`, `Prompt.mentionsBothAnswers`, `Backoff.withJitter` and `Prompt.wasElided` are documented as load-bearing and are never called in production. `ARCHITECTURE.md` credits jitter that the retry path does not use.
- **Weak signing default.** `build.sh:106` defaults `KS_PASS=android` and auto-generates the keystore. Fine for a local dev loop, dangerous as a habit: anyone who rebuilds elsewhere gets a different key, and a leaked keystore lets someone sign "updates" for your GitHub users.

---

## 4. Google Play

### 4.1 Blockers

| # | Blocker | Fix |
|---|---|---|
| 1 | `targetSdkVersion="35"` (`AndroidManifest.xml:7`). Play requires API 36 for new apps and updates; the extension window passed on 1 Nov 2026. | Set 36, install platform/build-tools 36, rebuild, re-test edge-to-edge and large screens. `docs/PLAY.md` already describes this. |
| 2 | The build emits an APK (`build.sh:104-126`). New apps must ship an **AAB**. | Add a release-only AGP module pointing at this tree (`manifest.srcFile 'AndroidManifest.xml'`, `java.srcDirs=['src']`, `res.srcDirs=['res']`, `assets.srcDirs=['assets']`, `buildConfig true`), keep `build.sh` for contributors, F-Droid and GitHub. |
| 3 | No hosted privacy policy and no in-app link. Play requires both. | Publish `docs/PRIVACY.md` (after §6) at a public HTTPS URL, add a "Privacy policy" row in Settings → About, enter the URL in Play Console. |
| 4 | **The app name contains "Android".** `strings.xml:3` = "AI Android Notification Filter". Google's brand guidelines forbid "Android" in app names (use "for Android" in prose); F-Droid's inclusion policy rejects trademark infringement. | Rename to `NotifyCleaner` (already the package and APK name), and use the descriptive phrase in the store listing instead. |

### 4.2 High risk

- **Cleartext + "encrypted in transit: yes" is inconsistent.** `usesCleartextTraffic="true"` plus the private-HTTP exception means the honest Data Safety answer today is **No**. Removing the exception (§3.2) makes it Yes.
- **"No network" and "exactly one permission" in the README** are both false and are exactly what a reviewer checks against the manifest. See §6.
- **Notification access is discretionary review.** There is no declaration form (the repo's analysis is right), but the feature must be proportionate to the listing. Prepare review notes: the offline default, how to grant access, and a low-limit test key only if you want the provider path tested.
- **New-developer requirements** are missing from `docs/PLAY.md`: identity verification, and — for personal accounts created after 13 Nov 2023 — a closed test with **12 testers for 14 continuous days** before production. Also note Android's developer-verification rollout for sideloaded apps (from 30 Sep 2026, Brazil/Indonesia/Singapore/Thailand first), which affects the "download the APK from GitHub" channel.
- **"AI" in the name** is not automatically deceptive, but the default engine is a TF-IDF linear model — your own README argues an LLM was worse. Prefer "on-device classifier" in the title and mention the optional provider in the full description.
- **The benchmark table** points at `~/tmp/ncfilter-bench`, which is not in the repo, and its "1 destroyed notification" claim is not supported by §0.1. Keep it out of the listing until the corpus and harness are published.

### 4.3 Data Safety — the answers to enter

| Question | Answer |
|---|---|
| Collect or share user data? | **Yes** |
| Messages → other in-app messages | Collected: **Yes** · Shared: **No** (user-initiated transfer with prominent disclosure; mark Yes and name the providers if you prefer maximum caution) · Optional · Purpose: App functionality · Ephemeral: No |
| Installed apps | Collected: **Yes**, Optional, App functionality — the posting package is sent |
| App activity → other actions | **No** — the removed list never leaves the device |
| App info and performance / device IDs / location / contacts / photos / health / financial | **No** |
| Encrypted in transit? | **No today** (cleartext LAN path). **Yes after** §3.2 is fixed |
| Deletion request mechanism? | **No server-side data exists.** Answer "No" for a request mechanism unless you publish a privacy contact; the in-app Clear button is a local control, not a request channel. `docs/PLAY.md`'s "Yes — clearing the Removed list" is wrong here |
| Required or optional? | **Optional** — the app works fully offline. `docs/PLAY.md`'s "Yes — it is the core function" is wrong |
| Advertising or tracking? | **No** |

Account deletion is N/A (no accounts).

### 4.4 Listing assets

| Asset | Requirement | Status |
|---|---|---|
| Title | ≤30 chars | Present but 30/30, contains "Android", rename |
| Short description | ≤80 chars | Missing. Draft: *"Dismisses junk notifications on-device; keeps anything uncertain"* (64) |
| Full description | ≤4000 chars | Missing. Must disclose notification access, the offline default, and what the optional provider receives |
| Icon | 512×512 32-bit PNG with alpha | Missing (`docs/icon/proof-render.png` is 1024×1024 RGB, a design proof) |
| Feature graphic | 1024×500 JPEG/24-bit PNG | Missing |
| Screenshots | ≥2; min dim 320; **max dim ≤ 2× min** | **All four are invalid**: 540×1170 and 591×1280 are 2.17:1. Re-capture at 1080×1920 (or 1080×2160) from a real device, covering the disclosure, the protected list and the removed list |
| Content rating, ads declaration, target audience, Data Safety | Required before publishing | Not done; add them to `docs/PLAY.md`'s checklist |

The screenshots are also stale: they show **v2.2.3**, while the manifest is 2.3.5, and `settings.jpg` predates the "Test the on-device classifier" row.

---

## 5. F-Droid

This is the better home for the app, and it is close. What is needed:

1. **An unsigned build mode.** `build.sh` must not sign on the buildserver: `SIGN=0` should skip `keytool`, `apksigner sign` and `apksigner verify`, and leave exactly one APK at a predictable path so `output:` matches. F-Droid signs with its own key.
2. **No default keystore password.** Require `KS_PASS` from the environment for local signed builds; never auto-generate a keystore non-interactively.
3. **Correct the docs' F-Droid claims.** Reproducible builds are *optional* for F-Droid, not "the whole model" (`docs/PLAY.md:127-129`). This build is not reproducible today: `find` lists are unsorted, ZIP entry timestamps vary, and `apksigner` ≥ 35.0.0-rc1 breaks `apksigcopier` verification.
4. **Metadata.** Add `metadata/io.github.shreyashsharmabyte.notifycleaner.yml` with `RepoType: git`, a full commit hash, a custom `build:` that exports `ANDROID_HOME=$$SDK$$` and the build-tools/platform versions, `output:` for one APK, `License: MIT`, and `AntiFeatures: NonFreeNet` — the optional provider presets promote proprietary services. `INTERNET` alone is not an anti-feature. `TetheredNet` does not apply because Custom endpoints exist.
5. **Fastlane metadata** for the store listing: `short_description.txt` (≤80), `full_description.txt`, `images/icon.png` 512×512, `images/phoneScreenshots/`, `changelogs/38.txt`.
6. **Model provenance.** `assets/local_model.tsv.gz` is a prebuilt data blob with no in-repo training or export script; the benchmark harness lives outside the repo. Be ready to explain it (or publish `tools/train_model.py` and the corpus), otherwise reviewers may treat it as an opaque binary.
7. **Changelog.** There are no entries between 2.1.5 and 2.3.5, so there is nothing to put in `changelogs/38.txt`.

---

## 6. Documentation that contradicts the code

A reviewer will read the README and then the manifest. Six claims do not survive that:

| Claim | Where | Reality |
|---|---|---|
| "no network" | `README.md:3-4` | `INTERNET` is declared (`AndroidManifest.xml:9`); `Classifier.Http` opens sockets. True *by default*, false as written |
| "The APK requests exactly one permission: `BIND_NOTIFICATION_LISTENER_SERVICE`" | `README.md:55-56` | The only `<uses-permission>` is `INTERNET`; `BIND_NOTIFICATION_LISTENER_SERVICE` is the service's `android:permission`, held by the system, not requested by the app. Both halves are wrong |
| "Five guard rules … before any model is consulted" | `README.md:24-26` | True on-device only; the provider branch has none (§3.1) |
| "Nothing is read … until you agree" | `HomeActivity.java:110`, `Screen.java:675` | Package name and app label are read and stored before consent (§0.3) |
| "Long text is shortened before it is classified" | `Screen.java:682-683` | Only on the cloud path; the offline path uses the full text (§3.5) |
| "Protected apps are never read" | `docs/PLAY.md:101` | Their package and label are read and recorded; only title and text are skipped |

Privacy policy additions, all real gaps:

- The **remembered senders** list (package + label, up to 300) is stored, is never mentioned, and cannot be cleared.
- `lastVerdict`, `lastError` and the dropped counter are stored and unlisted.
- The reasoning "no encryption because it would require a Jetpack dependency" is false: the platform Keystore plus `javax.crypto` needs no dependency. Say it is a deliberate simplicity trade.
- "The request is a single POST over HTTPS" — it can be 1–3 POSTs (retry loop plus the `max_completion_tokens` fallback), over HTTP on a private host.
- "Before any request, the app shows a second disclosure naming … the host" — not true after an endpoint edit (§3.4), and Custom does not name the host at all.
- Add developer identity, a contact, an effective date, and a "Privacy Policy" heading.

Also: `docs/ARCHITECTURE.md:25` says "twelve classes"; `test.sh` lists 14. `CHANGELOG.md` stops at 2.1.5 while the app is 2.3.5. `CHANGELOG.md` says 154 assertions; the README says 280; the harness prints 280.

---

## 7. What is genuinely good — do not change it

- **The fail-open plumbing.** `Decision.parse` → UNCLEAR for null, empty, both tokens, negation and hedging; UNCLEAR is never cached and never dismisses. Missing key, bad base URL, open breaker, rate limit, timeout, 4xx/5xx, empty answer — all KEEP.
- **Consent is separated from activation.** `enabled()` requires both, the flag defaults to false, and reading the disclosure writes nothing. That is a bug class the changelog says you already fixed once, and it is fixed properly.
- **No key in URLs or logs.** Sent only in `Authorization`/`x-api-key`; no logging of it anywhere.
- **Manifest hygiene.** `allowBackup="false"`, activities `exported="false"`, listener protected by `BIND_NOTIFICATION_LISTENER_SERVICE`, app-private prefs, package visibility via `<queries>` instead of `QUERY_ALL_PACKAGES`.
- **`LogCodec`.** The escaping and per-field caps exist because of a real corruption bug; it needs the two extra character classes (§3.8), not a replacement.
- **The guard rules themselves.** Narrow, defensively compiled, and they kept all 78 transactional/OTP/bank/order texts used in testing — including the OTP sample above. The problem is that they are too few and only wired to one engine, not that they are wrong.
- **The test harness.** 280 assertions on a bare JDK, a build that refuses to produce an APK when they fail, and a guard that fails the build if a "pure" class imports Android. That discipline is rare and worth protecting.
- **The writing.** The disclosure is clear, honest and specific, and the code comments explain *why* rather than *what*. Keep that voice; just use it for the labels the user needs, not only for the essays.

---

## 8. Recommended order of work

**Before any public release**

1. Fix the classifier: add `msg`/`email`/`event` to functional categories, expand the default protected list, require positive promo evidence, raise `MARGIN`. Add personal-message fixtures.
2. Move `recordSeenApp` behind consent; add "Forget remembered apps"; update the disclosure and privacy policy.
3. Make the status line connection-aware; add a pause control in every home state.
4. Fix the zero-height rules; re-capture screenshots at ≥1080px and ≤2:1.
5. Rename the app; fix README/PRIVACY/PLAY claims; publish the privacy policy.

**Before Play submission**

6. targetSdk 36 + AAB path; Play App Signing; backup the upload key with a real password.
7. Data Safety, content rating, ads, target audience; feature graphic, 512 icon, descriptions.
8. Review notes explaining the offline default and how to grant access.

**Before F-Droid submission**

9. `SIGN=0`; strict `KS_PASS`; sorted file lists for reproducibility.
10. Metadata YAML, fastlane assets, changelog entries for 2.1.6–2.3.5, model provenance.

**Then, in order of user value**

11. Watch-only first day, and a removal notice with "Protect this app".
12. Plain-language status, counters, and navigation; make the removed record reachable.
13. All-time counter; honest "last 100" labelling.
14. Contrast (`faint`), font-scale layout, 48dp targets, TalkBack roles.
15. Wire `Strictness` into the on-device engine, or say it cannot be tuned.
16. Redirects, response caps, text caps, breaker probe release, endpoint-bound consent, per-provider keys.
17. Remove the cleartext exception entirely; drop `usesCleartextTraffic`.

---

## Policy sources

- [Target API level requirement](https://developer.android.com/google/play/requirements/target-sdk)
- [Android App Bundle requirement](https://developer.android.com/guide/app-bundle)
- [Permissions and APIs that Access Sensitive Information](https://support.google.com/googleplay/android-developer/answer/16558241)
- [User Data policy — prominent disclosure, privacy policy, account deletion](https://support.google.com/googleplay/android-developer/answer/10144311)
- [Data safety form](https://support.google.com/googleplay/android-developer/answer/10787469)
- [Metadata policy](https://support.google.com/googleplay/android-developer/answer/9898842) · [Deceptive behaviour](https://support.google.com/googleplay/android-developer/answer/17006354)
- [Store listing preview assets](https://support.google.com/googleplay/android-developer/answer/9866151) · [App content declarations](https://support.google.com/googleplay/android-developer/answer/9859455)
- [Closed testing requirement for new personal accounts](https://support.google.com/googleplay/android-developer/answer/14151465) · [Developer identity verification](https://support.google.com/googleplay/android-developer/answer/9198012)
- [Android developer verification](https://developer.android.com/developer-verification) · [Open-source app registration](https://developer.android.com/developer-verification/guides/open-source-app-registration)
- [Android brand guidelines — using "Android" in app names](https://developer.android.com/distribute/marketing-tools/brand-guidelines)
- [F-Droid inclusion policy](https://f-droid.org/docs/Inclusion_Policy/) · [Build metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/) · [Anti-features](https://f-droid.org/docs/Anti-Features/) · [Reproducible builds](https://f-droid.org/docs/Reproducible_Builds/) · [Submission guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)

---

*This review is a snapshot of commit `7789492`. It contains no changes to application code.*
