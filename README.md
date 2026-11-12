# AI Android Notification Filter

An Android app that dismisses junk notifications **on your phone**. No account, no API key, no
network, no analytics, no third-party SDKs.

It ships with a small classifier in the APK: an 18 KB linear model over TF-IDF word unigrams and
bigrams, sitting behind guard rules for the things that must never be dismissed. That is what
answers by default. A benchmark on 100 hand-labelled notifications decided it. Two small models
(gemma3:270m, smollm2:360m) answered KEEP to everything and removed none of 47 promotions. A third
(qwen3:0.6b) removed 31 promotions and also destroyed 22 real notifications, among them an OTP, a
SIP debit, a margin-call warning and a payment-declined alert. This classifier removed 39 of 47
promotions and dismissed one real notification, a cashback reward. If you would rather use a cloud
model, that is available in Settings as an opt-in.

Every notification is judged on its own. Only clear `JUNK` verdicts are dismissed. Anything the
classifier does not recognise, any failure, and every uncertain answer keeps the notification. What
was removed is listed in the app, so a wrong call is always visible. Identical repeats of text
already removed are not listed again — they are dismissed from the cache.

**[Why this exists](#why-this-exists)** · **[How it decides](#how-it-decides)** ·
**[Install](#install)** · **[Build](#build-from-source)** · **[Privacy](docs/PRIVACY.md)**

---

## Screens

Six small screens, but the launcher opens one and the rest are one tap from it. There is no wizard:
setup is a checklist that shrinks as it is satisfied and disappears when it is done.

| Screen | What it is for |
|---|---|
| **Home** | First run: the disclosure and one button. After that: the state of filtering, one button, and the counts. While anything is missing, the missing items are listed here as the setup checklist. |
| **Where answers come from** | Advanced: your own provider, API key, model, endpoint, and a Test button. Not part of first run — Settings offers it, and it requires a second disclosure. |
| **Settings** | Answers from (on this device, or your provider), how strict (providers only), notification access, protected apps, and the disclosure again. |
| **Protected apps** | Real apps with switches, searchable. Not a comma-separated text field. |
| **Removed** | The record of what was dismissed, newest first. |
| **Advanced prompt** | The raw instructions, for anyone who wants them. Out of the way on purpose. |

**Answers come from the phone.** A fresh install needs no key, no provider and no network: the
classifier is in the APK and runs in-process. Settings' "Answers from" row reads **On this device**,
and the only alternative offered there is your own provider — an opt-in that shows a second,
provider-specific disclosure and needs an explicit agreement before a single request is made.
There is no local-model download list and no LAN server preset; those were removed in 2.1.7.

**How strict** applies only when a provider is in use. Cautious, Balanced or Aggressive — you pick an
intent, the app owns the instructions that implement it. The safety rules are appended to whichever
you choose and cannot be removed. The section is not shown at all in offline mode, because the
on-device classifier uses fixed rules and a control that did nothing would be worse than no control.

**Setup is a checklist, not a ceremony.** In the default path there is exactly one requirement —
notification access from Android — and it is a row on Home that disappears when it is done. The
provider row appears only if you switched to a provider and did not finish it. Closing the app
halfway through loses nothing; coming back from Android's settings screen updates the list by itself.
The old four-step wizard made configuration and activation one linear seven-minute path before
anything happened, and implied that stopping was failing.

**Turning it on is one explicit button.** Consenting to the disclosure only allows the app to read
notifications. Filtering starts when you press Turn on filtering, never as a side effect of agreeing,
reading the notice, or editing a field.

## Why this exists

Notification filters usually ship as a list of keywords. Keyword lists rot: every campaign invents
new wording, and the rules that catch "50% OFF" also catch "Your OTP is 50% off your next bill".
This app replaces the list with a model that can read the whole notification:

> Is this promotional, or is it something the user might need?

The interesting engineering is not the classifier call. It is everything around it — deciding what
may be looked at at all, what happens when a classifier is unsure, and making sure a bad answer can
never destroy something that mattered.

An earlier version asked a language model over the network. The benchmark that ended that idea is in
[the changelog](CHANGELOG.md): three sub-1B models, told to answer KEEP when unsure, took the escape
hatch on every one of 47 promotions. A weak model told to be careful is a model that removes nothing.
The on-device classifier is smaller, instant, works in a tunnel, and does not send your messages to
anyone.

## How it decides

```
notification posted
        │
        ├─ consent not given, or filtering paused? ──────────► stop, keep
        ├─ ongoing / non-clearable / group summary ─────────► stop, keep
        ├─ media, call, alarm, progress, navigation ────────► stop, keep
        ├─ reminders, indeterminate progress, full-screen ──► stop, keep
        ├─ app on the Protected list ───────────────────────► stop, keep
        ├─ cached verdict for identical text ───────────────► apply it
        │
        ▼
   queue (2 workers, 30 deep)
        │
        ├─ engine = on this device (default) ───────────────► guard rules, then linear model
        │                                                     no socket, no key, no rate limit
        └─ engine = your provider (opt-in) ─────────────────► breaker ─► limiter ─► HTTPS POST
        │
        ▼
   verdict ── "JUNK" ─► still the same notification? ─► dismiss + record in Removed
           ── "KEEP"  ─► leave alone
           ── anything else ─► leave alone, show why
```

Four things in that diagram are worth calling out, because they are where most of the work went:

**The gate before the classifier.** Media players, calls, alarms, active downloads, navigation, group
summaries, foreground services and anything on your Protected list are never classified at all. This
is not a filter on quality; it is a filter on *category*, and it is the reason the app cannot mute
your Timer or your incoming call.

**The on-device path.** `LocalClassifier` runs narrow guard regexes first — OTPs, money movements,
order and delivery state, security events, account deadlines — and then a linear model whose decision
is a dot product. A guard is not a guess: it protects the classes the app must never get wrong. Below
the margin the answer is KEEP, and nothing unrecognised can score above it.

**The reliability policy, when a provider is chosen.** A dead endpoint would otherwise cost one failed
request per notification forever. A circuit breaker opens after 5 consecutive failures and stays open
for 60 seconds. A token bucket caps the app at 40 calls a minute, so a chatty app cannot quietly run
up a bill. A burst of identical notifications costs one call, not ten. None of this machinery is
touched in offline mode, because there is nothing to protect against.

**The answer parser, for providers.** `Decision.parse` accepts `JUNK`, `junk`, `"JUNK"`, `` `KEEP` ``,
`**JUNK**`. It refuses to accept `JUNK KEEP`, `JUNKY`, or `not junk`, and returns UNCLEAR for anything
else. UNCLEAR never dismisses.

## What is never touched

- Apps on the **Protected apps** list (editable; messaging and dialer apps by default).
- Media players, calls, alarms, active downloads and file transfers, navigation, foreground
  services, group summaries, and every non-dismissible notification.
- Anything with an indeterminate progress bar, or a full-screen intent.
- Anything the on-device classifier does not recognise, or scores below its margin.
- Anything that arrives while the queue is full, a provider's breaker is open, or its rate limit is
  spent.
- Anything that arrives before you have accepted the in-app disclosure.

## Install

The APK is built and signed locally. Enable **Install unknown apps** for your file manager, install,
and open it. The first screen is the disclosure; agreeing allows the app to read notifications, and
filtering stays **off** until you press Turn on filtering. Home then asks for the one thing still
missing — notification access — and that row opens exactly that. There is no key to create, no
provider to choose and no connection to test.

On Samsung: *App info → ⋮ → Allow restricted settings* is required before the notification-access
toggle takes effect. On any OEM with aggressive battery management, set the app to **Unrestricted**
so the listener is not killed.

> v2.0.0 uses the package name `io.github.shreyashsharmabyte.notifycleaner`. If you installed v1.x
> (`com.example.notifycleaner`), it will appear as a second app; you will need to grant notification
> access again and can then uninstall the old one.

> Upgrading from 2.1.6: the engine defaults to on-device, so an install that had a provider
> configured will start classifying locally. The key and provider are kept; Settings → Answers from
> switches back, and asks for the provider disclosure again on the way.

## On the device, by default

The classifier is `assets/local_model.tsv.gz` — about 18 KB, gzipped, loaded once per process into a
static field. It is a linear model over TF-IDF word unigrams and bigrams exported from scikit-learn,
plus five narrow guard rules for OTPs, money movements, order/delivery state, security events and
account deadlines. Inference is a dot product over the tokens in the notification; there is no
allocation-heavy pipeline and no warm-up.

| | |
|---|---|
| Cost | none |
| Latency | well under a millisecond per notification |
| Network | none; the app makes no request at all |
| Personal data | never leaves the process |
| Fail-open | unrecognised text scores at or below zero → KEEP |

## Using your own provider

Optional, in Settings → **Answers from** → *Your own provider*. Any OpenAI-compatible
`/chat/completions` endpoint over HTTPS works, plus Anthropic's Messages API.

| Provider | Base URL | Model example |
|---|---|---|
| Anthropic | *(fixed)* | `claude-haiku-5-5` |
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-flash` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |
| Groq | `https://api.groq.com/openai/v1` | `llama-3.3-70b-versatile` |
| OpenRouter | `https://openrouter.ai/api/v1` | any model id |
| Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | `gemini-3.6-flash` |
| Custom | any endpoint you type | any model id |

The provider is a stored choice, not something inferred from the URL — an earlier version guessed,
and because both "Anthropic" and "Custom" meant an empty base URL, choosing Custom silently read back
as Anthropic.

**Choosing a provider is the only way notification text leaves this phone.** It is not a default and
it is not a side effect: the app shows a second disclosure naming the host that would receive the
text, and switches engines only when you agree. Choosing a different provider voids that agreement
and asks again. "Use on this device" switches back immediately, and from that moment nothing is sent.
Until a provider is both configured and agreed to, the listener keeps everything and sends nothing.

Plain HTTP is permitted **only** for loopback and private addresses (`127.*`, `10.*`, `172.16–31.*`,
`192.168.*`, `*.local`) on the custom endpoint, because such a server cannot present a certificate. A
public host over HTTP is refused, and that rule is unit tested.

**Cost.** One classification is roughly a 120-token prompt and a one-word answer. With a small model
that is a fraction of a cent per thousand notifications; the 40/minute cap bounds the worst case.
Prefer a small, non-reasoning model — a reasoning model works, but pays for thinking tokens on every
notification.

## Build from source

No Gradle, no Android Studio, no root. A JDK plus ~500 MB of Android SDK:

```bash
./tools/install-sdk.sh     # platform 35 + build-tools 35.0.0 into ~/android-sdk
./test.sh                  # 280 logic assertions, plain JDK, no SDK needed
./build.sh                 # -> build/NotifyCleaner-2.1.7.apk
```

`build.sh` generates `BuildConfig`, compiles resources with `aapt2`, compiles Java against
`android.jar`, dexes with `d8`, aligns with `zipalign` and signs with `apksigner`. The first run
creates `release.jks` — **keep that file**, since Android will only accept updates signed with the
same key. It is in `.gitignore` and must never be committed.

Override the toolchain with environment variables:

```bash
ANDROID_HOME=/opt/android-sdk BUILD_TOOLS_VERSION=35.0.0 PLATFORM=android-35 ./build.sh
KS_PASS=… KS_ALIAS=… ./build.sh
```

## Tests

`./test.sh` runs a hand-rolled harness — no JUnit, no Gradle — over the fourteen classes that contain
the decisions worth getting wrong:

| Class | What it proves |
|---|---|
| `Decision` | Only unambiguous JUNK dismisses; negation and word boundaries are handled |
| `Backoff` | Retry-After parsing, exponential cap, refusal to retry 4xx |
| `CircuitBreaker` | Opens at threshold, allows exactly one probe, reopens on probe failure |
| `RateLimiter` | Token bucket spends, blocks, and refills across a window |
| `LogCodec` | Tabs, newlines and backslashes survive a round trip; the cap holds |
| `Filters` | Short entries cannot blanket-protect the device; cache keys cannot be forged |
| `Prompt` | Long text keeps head **and** tail; notification content cannot close its own data tags |
| `Providers` | URL normalisation, HTTPS enforcement, and no local-server preset in the picker |
| `LocalClassifier` | The shipped weights dismiss promotions and never dismiss an OTP, a payment, an order, a security alert or a deadline |
| `Telemetry` | The dashboard's figures are derived from the log and cannot be invented |

The harness asserts that none of those classes imports `android.*`. If one does, the test script
fails — that guard is what keeps the core testable.

## Limitations

Honest ones, because the alternative is a bug report later.

- **The sound has already happened.** Android delivers the notification, plays its sound and shows
  any heads-up banner *before* a listener can dismiss it. This app removes junk one to three seconds
  late. It is not a mute, and Android offers no hook to make it one.
- **The on-device classifier is small and narrow.** It was trained on English-language promotional
  and transactional notifications, mostly Indian commerce and banking senders. It will not recognise
  a campaign written in a style it has never seen; unrecognised text is kept, so the failure mode is
  a missed removal, not a lost message. Use the **Removed** list to check its calls.
- **A provider can still make bad calls.** If you switch to one, the prompt tells it to keep
  anything needed to log in, pay or reach someone and to answer KEEP when unsure. That is mitigation,
  not a guarantee. Keep banking and messaging apps on the Protected list and check **Removed**.
- **Notification text leaves the device only if you choose a provider.** App package, title and the
  notification text — up to 340 characters, and for longer text the beginning and the end — go to
  that provider and nowhere else. The default setup transmits nothing. See
  [docs/PRIVACY.md](docs/PRIVACY.md).
- **The API key is stored unencrypted** in app-private storage. `allowBackup` is off, so it is not
  in cloud backups, but a rooted device can read it. Encrypted storage needs a Jetpack dependency,
  which this build deliberately does not have.
- **Google Play needs two things this build does not produce**: an AAB (Play has required bundles
  since 2021) and `targetSdk 36`, which is the current floor for new submissions. Both are documented
  in [docs/PLAY.md](docs/PLAY.md), along with why F-Droid is the better store for this app.

## Architecture

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the reasons behind the pure-core/shell split,
the exact numeric policy, and the fail-open invariants that every change must preserve.

## Licence

MIT. See [LICENSE](LICENSE).
