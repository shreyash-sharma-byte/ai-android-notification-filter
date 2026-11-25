# The on-device classifier: what was wrong, what changed, what remains

This document is the change record for the classifier fix that followed the pre-publication
review (`docs/REVIEW.md` §0.1 and §3.1). It is deliberately blunt: the shipped model removed real
personal messages, and the first job of this work was to prove that with numbers rather than
argue about it.

**Headline result.** Before: 16 of 46 conversational texts were dismissed, including five named in
the review, and the cloud-provider path had no guard rules at all. After: 0 of 46 conversational
texts are dismissed, all 16 transactional texts still keep, and promotional removal falls from
17/18 to 16/18 on the measured corpus. The one lost promotion is named below, and so is the reason.

| Corpus | Before | After |
|---|---:|---:|
| Personal, 42 ordinary conversational texts | 15 dismissed, 27 kept (64.3% kept) | **0 dismissed, 42 kept (100%)** |
| Personal, 4 borderline texts with promo-ish words | 1 dismissed, 3 kept (75%) | **0 dismissed, 4 kept (3 KEEP + 1 UNCLEAR)** |
| All personal, 46 texts | 16 dismissed, 30 kept (65.2% kept) | **0 dismissed, 46 kept (45 KEEP + 1 UNCLEAR)** |
| Promotions, 18 texts | 17 removed (94.4%) | **16 removed (88.9%)** |
| Transactional, 16 texts (OTP, bank, order, security, deadline) | 16 kept (100%) | **16 kept (100%)** |

In false-positive terms: 16 notifications the app must never touch were being destroyed, now 0.
In promo terms: one promotion that used to be removed is now kept. That is the whole trade, and it
is the correct direction for an app whose promise is "messages, codes and payments are left alone."

---

## 1. How the numbers were produced

The review's finding was reproduced before anything was changed, with a throwaway harness under
`/tmp` (not committed, as required). It:

1. reads the shipped asset with `LocalClassifier.load(new FileInputStream("assets/local_model.tsv.gz"))`;
2. calls the *real* `LocalClassifier.classify`, so the numbers include the shipped guard rules and
   the real tokeniser;
3. prints verdict, raw score, guard name and (after the change) whether the evidence gate fires;
4. is run before and after the change against the same corpora, so the comparison is
   like-for-like.

The corpora are now committed in `test/LogicTest.java` as `PERSONAL`, `PERSONAL_BORDERLINE`,
`PROMOS_REMOVED`, `PROMOS_MISSED` and `TRANSACTIONAL`, so the numbers can be re-derived with
`./test.sh`. The harness itself is described in §11. The shipped model has **2,589 features**
(1,823 bigrams and 766 unigrams), bias **−0.310376731**. The asset is 18,032 bytes compressed /
73,576 bytes uncompressed, SHA-256
`a5a6c5a121a17945483787f748d118f93ecca3bf7cacf69440660bb7032e8a40`; it was **not modified** by this
work, which is why the raw scores are identical before and after.

Scores below are raw dot products, not probabilities. They are not comparable across models and
they are not calibrated; a score of +0.25 does not mean "25% likely to be junk."

---

## 2. The failing corpus, before and after

### 2.1 Personal messages

The first 42 rows are ordinary conversational texts, including the exact five named in the review
and the rest of the review's personal-message list. Rows 43–46 are deliberately borderline: real
personal messages that contain promotional vocabulary. "Before" is the shipped 2.3.5 behaviour;
"After" is this change.

| # | App / sender | Text | Score | Before | After |
|---|---|---|---:|---|---|
| 1 | Signal | Are you free tonight? | +0.890 | JUNK | KEEP |
| 2 | Signal | Call me when you see this | +0.540 | JUNK | KEEP |
| 3 | Signal | Meeting at 4pm in room 2 | +0.588 | JUNK | KEEP |
| 4 | Signal | dinner at 8? | +0.315 | JUNK | KEEP |
| 5 | Slack | standup in 10 | +0.093 | JUNK | KEEP |
| 6 | Messenger | where are you | +0.403 | JUNK | KEEP |
| 7 | Signal | can you pick up milk | +0.046 | JUNK | KEEP |
| 8 | Messages | are we still on for lunch? | +0.027 | JUNK | KEEP |
| 9 | WhatsApp | Running 10 minutes late | -0.636 | KEEP | KEEP |
| 10 | Gmail | Did you get my email? | +0.351 | JUNK | KEEP |
| 11 | Signal | Thanks, that works for me | -0.331 | KEEP | KEEP |
| 12 | WhatsApp | See you tomorrow | -0.206 | KEEP | KEEP |
| 13 | Signal | I'll call you back | -0.114 | KEEP | KEEP |
| 14 | WhatsApp | On my way home | +0.049 | JUNK | KEEP |
| 15 | Slack | What time does it start? | +0.154 | JUNK | KEEP |
| 16 | WhatsApp | Happy birthday! | +0.000 | KEEP | KEEP |
| 17 | Signal | Miss you | +0.180 | JUNK | KEEP |
| 18 | WhatsApp | Can we talk? | -0.774 | KEEP | KEEP |
| 19 | Gmail | The meeting moved to Thursday | -0.487 | KEEP | KEEP |
| 20 | Slack | Got it, thanks | -0.005 | KEEP | KEEP |
| 21 | Signal | Let me check and get back to you | +1.649 | JUNK | KEEP |
| 22 | WhatsApp | You around this weekend? | +1.364 | JUNK | KEEP |
| 23 | Signal | Lunch tomorrow? | -0.865 | KEEP | KEEP |
| 24 | Gmail | I sent you the file | -1.016 | KEEP | KEEP |
| 25 | WhatsApp | No worries | -0.571 | KEEP | KEEP |
| 26 | Slack | Sounds good | +0.000 | KEEP | KEEP |
| 27 | WhatsApp | Are you coming to the party? | -0.179 | KEEP | KEEP |
| 28 | Signal | I'm at the station | -0.175 | KEEP | KEEP |
| 29 | Messages | Please send the address | -0.397 | KEEP | KEEP |
| 30 | Gmail | How did the interview go? | -0.711 | KEEP | KEEP |
| 31 | WhatsApp | We should catch up soon | +0.236 | JUNK | KEEP |
| 32 | Signal | Did you see the news? | -0.214 | KEEP | KEEP |
| 33 | Gmail | Can you send me the link? | -0.508 | KEEP | KEEP |
| 34 | WhatsApp | This is great news! | -0.116 | KEEP | KEEP |
| 35 | Signal | I'll be there in five | -0.312 | KEEP | KEEP |
| 36 | WhatsApp | Where should we meet? | -0.883 | KEEP | KEEP |
| 37 | Slack | Let's do it | -0.240 | KEEP | KEEP |
| 38 | Signal | Is your mom okay? | -1.457 | KEEP | KEEP |
| 39 | WhatsApp | I got the job! | -0.126 | KEEP | KEEP |
| 40 | Gmail | Thank you so much | -0.234 | KEEP | KEEP |
| 41 | WhatsApp | The kids are asleep | -0.238 | KEEP | KEEP |
| 42 | Signal | Just landed, will text you | -0.638 | KEEP | KEEP |
| 43 | WhatsApp | I got a great deal on the tickets | -0.056 | KEEP | KEEP |
| 44 | Gmail | The offer still stands if you want it | +0.131 | JUNK | KEEP |
| 45 | Signal | Is the free trial worth it? | -0.113 | KEEP | UNCLEAR |
| 46 | Slack | Who won the match? | -0.418 | KEEP | KEEP |

**Corpus note.** The review's own 42-message corpus reported 16 JUNK. The 42 ordinary rows here
report 15, and adding the 4 borderline rows gives 16 of 46 — the same failure rate, not necessarily
the same rows, because the two corpora share shapes but not identical texts. The one score that
differs from the review's table is the lunch message (0.005 there, +0.027 here, most likely a
title/render difference); the verdict was JUNK in both. Every score here is reproducible from the
shipped asset via `LocalClassifier.score`.

**What changed in these rows.** Every row whose score is positive but which has no promotional cue
is now KEEP: a positive dot product is no longer allowed to be the sole reason for dismissal. That
is rows 1–8, 10, 14, 15, 17, 21, 22 and 31 in the table. The model still scores several of them
*above* any sensible threshold (up to +1.649), which is exactly why the evidence gate, not a
threshold tweak, is the fix.

The single UNCLEAR row is honest uncertainty, and it is still kept: "Is the free trial worth it?"
contains a strong promotional phrase ("free trial"), the model scores it −0.113, and that is a
mixed signal. It is not a dismissal.

### 2.2 Promotions

| # | App | Title | Text | Score | Cue? | Before | After |
|---|---|---|---|---:|:---:|---|---|
| 1 | Zomato | 50% OFF for you | Get 50% off up to Rs 120 on your next 3 orders. Use code CRAVE50. | +1.873 | true | JUNK | JUNK |
| 2 | Myntra | End of season sale | Flat 60 percent off on 5000 styles. Shop now. | +1.794 | true | JUNK | JUNK |
| 3 | Amazon | Great Indian Festival | Up to 70 percent off on electronics. Sale ends tonight. | +1.787 | true | JUNK | JUNK |
| 4 | Swiggy | Hungry? | Craving something? Get 50% off up to Rs 120. Use code CRAVE50. | +1.592 | true | JUNK | JUNK |
| 5 | Flipkart | Big Billion Days | Deals starting at Rs 99. Limited period offer. | +0.952 | true | JUNK | JUNK |
| 6 | Uber | A ride on us | Get 50% off your next 3 rides. Use code RIDE50. | +1.171 | true | JUNK | JUNK |
| 7 | Nykaa | Beauty sale is live | Flat 20% off + free delivery on all orders. | +1.911 | true | JUNK | JUNK |
| 8 | Domino's | Buy 1 get 1 free | Buy 1 get 1 free on all pizzas. Order now. | +1.627 | true | JUNK | JUNK |
| 9 | Spotify | Premium for less | Premium at just Rs 99 for 3 months. Upgrade now. | +0.841 | true | JUNK | JUNK |
| 10 | LinkedIn | 12 new jobs for you | Senior Java Developer roles in Pune that match your profile. | +1.240 | false | JUNK | KEEP |
| 11 | CRED | Cashback waiting | You have Rs 250 cashback. Claim now. | +1.066 | true | JUNK | JUNK |
| 12 | AJIO | Last chance | Last chance: extra 30% off. Sale ends at midnight. | +0.976 | true | JUNK | JUNK |
| 13 | MakeMyTrip | Your dream vacation | Your dream vacation is on sale. Download the app. | -0.810 | true | KEEP | KEEP |
| 14 | Gmail | Your weekly newsletter | The best reads of the week are here. Unsubscribe. | +0.285 | true | JUNK | JUNK |
| 15 | ICICI Bank | A reward for you | Get 10% cashback on your next purchase with your ICICI card. | +1.293 | true | JUNK | JUNK |
| 16 | Meesho | Mega sale | Upto 80% off on fashion. Free delivery on your first order. | +1.769 | true | JUNK | JUNK |
| 17 | BigBasket | Weekend offer | Flat Rs 100 off on orders above Rs 999. Use coupon SAVE100. | +2.270 | true | JUNK | JUNK |
| 18 | Zomato | Gold membership | Get 30 days of free delivery with Zomato Gold. Join now. | +0.899 | true | JUNK | JUNK |

The two "After = KEEP" rows are the measured recall cost:

- **LinkedIn, "12 new jobs for you".** A job digest with no promotional cue. The model scores it
  +1.240, but the text contains no percentage, no discount noun, no call to action. Under the new
  rule it is kept. The old rule dismissed it. There is no honest way to call "12 new jobs for you"
  *promotional evidence* without also catching personal mail.
- **MakeMyTrip, "Your dream vacation is on sale. Download the app."** This one was already kept
  before the change: the model scores it −0.810. The evidence gate does not override a model that
  says "not junk", by design (§5.3). It remains a miss in both columns, not a regression.

### 2.3 Transactional texts

Each row is kept by the guard rule named, on both sides of the change. The rules themselves were
not weakened; they were moved so both engines use them.

| # | App | Text | Guard rule | Before | After |
|---|---|---|---|---|---|
| 1 | ICICI Bank | 482913 is your OTP for a transaction of Rs 1299. Do not share this with anyone. | otp | KEEP | KEEP |
| 2 | HDFC Bank | Rs 2499 debited from A/C XX4412 at Amazon. Available balance Rs 48220. | transaction | KEEP | KEEP |
| 3 | Zomato | Your order from Zomato has been delivered. Enjoy your meal! | order | KEEP | KEEP |
| 4 | Gmail | New sign-in to your Google Account from a Windows device in Pune. | security | KEEP | KEEP |
| 5 | Airtel | Your prepaid balance is low. Recharge to avoid interruption. | deadline | KEEP | KEEP |
| 6 | Bank | Your KYC expires today. Complete re-KYC to avoid suspension. | deadline | KEEP | KEEP |
| 7 | Amazon | Your package is out for delivery and will arrive today. | order | KEEP | KEEP |
| 8 | UPI | Your payment of Rs 499 failed. No money was deducted. | transaction | KEEP | KEEP |
| 9 | HDFC | Rs 15000 credited to your account. Ref no 88213. | transaction | KEEP | KEEP |
| 10 | IRCTC | Your PNR 4567890123 is confirmed. Boarding at 6:15 AM. | order | KEEP | KEEP |
| 11 | Google | A new login was blocked. Was this you? If not, re-verify. | security | KEEP | KEEP |
| 12 | PhonePe | Your refund of Rs 340 has been processed to your bank. | transaction | KEEP | KEEP |
| 13 | CRED | Your credit card bill is due on 12 Nov. Pay now to avoid late fees. | deadline | KEEP | KEEP |
| 14 | Amazon | Your order has been placed. We'll notify you when it ships. | order | KEEP | KEEP |
| 15 | Myntra | We have cancelled your order. The amount will be refunded in 3 days. | transaction | KEEP | KEEP |
| 16 | Bank | Margin shortfall in your trading account. Add funds to avoid square-off. | transaction | KEEP | KEEP |

---

## 3. Root causes, with the actual model weights

### 3.1 Chat, mail and calendar were not functional categories

`NotifService` had:

```java
private static final Set<String> FUNCTIONAL_CATEGORIES = new HashSet<String>(Arrays.asList(
        "call", "alarm", "progress", "transport", "sys", "service", "status", "err",
        "navigation", "reminder"));
```

`Notification.CATEGORY_MESSAGE` is `"msg"`, `CATEGORY_EMAIL` is `"email"` and `CATEGORY_EVENT` is
`"event"`. They were absent. Almost every chat app posts with `CATEGORY_MESSAGE`, so the cheapest,
most reliable guard in the app — "never classify this at all" — never fired for chat. Signal, Slack
and Messages were therefore relying entirely on the classifier.

The fix is `Categories.isFunctional`, with all 13 categories (the original 10 plus the three
missing ones), unit-tested by name. The trade-off is documented in §7: a brand's promotional SMS
that uses `CATEGORY_MESSAGE` is now kept. That is the correct trade.

### 3.2 `MARGIN == 0` made 0.005 a confident dismissal

The old code was:

```java
static final float MARGIN = 0f;

float score = score(model, render(app, title, text));
if (score > MARGIN) return new Result(Decision.Verdict.JUNK, null, score);
if (-score < MARGIN && MARGIN > 0f) return new Result(Decision.Verdict.UNCLEAR, null, score);
return new Result(Decision.Verdict.KEEP, null, score);
```

With `MARGIN == 0`, `score > 0` dismissed, and the `UNCLEAR` branch required `MARGIN > 0`, so it
was unreachable. "are we still on for lunch?" scored **+0.027** and was a confident JUNK. The
uncertainty band was not merely too narrow — it did not exist.

`MARGIN` is now **0.25**, positive, and the band produces `UNCLEAR` under the conditions in §5.3.

### 3.3 The weights: generic words dominate, and the review's signs were wrong

The review said `is` +1.98, `your` +1.98, `this` +1.98, `was` +2.06. Three of those four are
**negative** in the shipped asset (`is` −1.870, `your` −1.978, `was` −2.057); only `this` is positive
(+1.978). The actual top coefficients are:

| Token | IDF | Coefficient |
|---|---:|---:|
| percent | 2.846 | **+2.530** |
| get | 3.522 | **+2.247** |
| off | 3.082 | **+2.226** |
| week | 3.305 | +2.111 |
| this week | 3.305 | +2.015 |
| this | 2.900 | **+1.978** |
| percent off | 3.334 | +1.741 |
| up to | 3.200 | +1.728 |
| upgrade | 4.586 | +1.474 |
| up | 2.864 | **+1.439** |
| with | 3.128 | +1.378 |
| and | 2.828 | +1.287 |
| on | 2.571 | +1.276 |
| get rs | 4.250 | +1.257 |
| and get | 3.944 | +1.250 |
| flat | 4.404 | +1.152 |
| free | 4.809 | **+1.140** |
| now | 3.557 | +1.138 |
| flat rs | 4.491 | +1.082 |
| today | 3.670 | +1.046 |

| Token | IDF | Coefficient |
|---|---:|---:|
| was | 3.128 | **−2.057** |
| your | 1.417 | **−1.978** |
| is | 2.730 | **−1.870** |
| your order | 3.175 | −1.822 |
| has | 3.225 | −1.694 |
| payment | 3.670 | −1.605 |
| to your | 3.522 | −1.590 |
| order | 2.730 | −1.443 |
| delivered | 4.404 | −1.145 |
| credited | 4.404 | −1.132 |
| for your | 4.181 | −1.102 |
| confirmed | 4.404 | −1.098 |
| of rs | 3.844 | −1.077 |
| nov | 4.116 | −1.040 |
| of | 3.593 | −1.014 |

The review's conclusion was right even though its signs were not: **function words dominate both
directions**. `is`, `your` and `was` are strong negatives, which is why transactional texts score
below zero; `this`, `get`, `up`, `on`, `and` and `with` are strong positives, which is why ordinary
sentences drift above zero. The model has no feature for "this is a conversation" beyond the
frequency of these words, and L2 normalisation amplifies the problem on short texts.

The score decompositions make the mechanism concrete. Each row shows the contribution of the
feature to the final score (`x·coef/‖x‖`); the bias is added on top.

**"Are you free tonight?" — score +0.890, ‖x‖ = 9.095**

| Feature | Coefficient | Contribution |
|---|---:|---:|
| free | +1.140 | **+5.480** |
| you | +0.653 | +1.848 |
| are | +0.422 | +1.794 |
| tonight | +0.310 | +1.793 |

`free` is not a generic word in this model; it is one of the strongest promotional signals in the
vocabulary (IDF 4.809). "Are you free tonight?" is therefore structurally close to "free delivery",
and no threshold can separate them without also keeping real promotions.

**"Call me when you see this" — score +0.540, ‖x‖ = 10.779**

| Feature | Coefficient | Contribution |
|---|---:|---:|
| this | +1.978 | **+5.737** |
| you | +0.653 | +1.848 |
| see | +0.300 | +1.739 |
| call | −0.266 | −1.402 |
| when | +0.201 | +1.248 |

**"Meeting at 4pm in room 2" — score +0.588, ‖x‖ = 4.413**

| Feature | Coefficient | Contribution |
|---|---:|---:|
| at | +0.625 | +2.001 |
| in | +0.646 | +1.964 |

Only two known features, so the norm is small and two ordinary prepositions produce +0.588. This is
the short-text amplification in its purest form.

**"standup in 10" — score +0.093, ‖x‖ = 8.068**

| Feature | Coefficient | Contribution |
|---|---:|---:|
| in | +0.646 | +1.964 |
| in 10 | +0.154 | +0.952 |
| 10 | +0.081 | +0.339 |

**"are we still on for lunch?" — score +0.027, ‖x‖ = 11.163**

| Feature | Coefficient | Contribution |
|---|---:|---:|
| on | +1.276 | **+3.282** |
| we | −0.573 | −2.358 |
| are | +0.422 | +1.794 |
| still | +0.245 | +1.521 |
| for | −0.527 | −1.313 |
| messages | +0.135 | +0.837 |

**"Let me check and get back to you" — score +1.649, ‖x‖ = 9.272**

| Feature | Coefficient | Contribution |
|---|---:|---:|
| get | +2.247 | **+7.913** |
| and get | +1.250 | +4.930 |
| and | +1.287 | +3.641 |
| you | +0.653 | +1.848 |
| to | −0.742 | −1.414 |
| back | +0.202 | +1.250 |

This is the row that proves the point: an ordinary sentence crosses any reasonable threshold
because "get" is one of the largest positive weights in the model. A threshold-only fix would have
to be set above +1.649, which would also keep most promotions. The problem is the feature, not the
cut.

### 3.4 The provider path dropped all five guard rules

`LocalClassifier.classify` ran `guard(...)` before scoring. `NotifService.judge` called that only
on the offline branch; the provider branch went straight to `Classifier.classify`, which has no
guard step, only `Decision.parse`. A provider that answered `JUNK` for an OTP dismissed it. That
contradicted the README's "a rule hit means KEEP, before any model is consulted."

The guards now live in `Guards` and are called in `NotifService.inspect` **before the engine split
and before the cache**. Consequences:

- both engines get the same five rules;
- guarded text (a debit, a sign-in alert, an OTP) is never put into a provider request at all;
- `LocalClassifier` delegates to `Guards` rather than keeping a duplicate copy, so the Settings
  self-test and the tests keep the same protection.

### 3.5 App names were stored before consent and while paused

`inspect` called `recordSeenApp(pkg, label(pkg))` before the `enabled()` gate. Now it runs after
`enabled()` — which itself requires `consented()` — so package names are not written before the
user agrees, nor while filtering is paused. The notification liveness timestamp still records after
consent only. Both are metadata, never content.

### 3.6 `UNCLEAR` was being reported as an error

`judge` ended with:

```java
} else {
    settings.recordError(note, now);
}
```

Anything that was neither JUNK nor KEEP became "needs attention" on the home screen. With a real
uncertainty band that is wrong: an on-device "I am not sure" is a normal, successful keep. The
branch now records `"KEPT (unsure)"` as a success. The provider path is different: its failures
also arrive as `UNCLEAR`, but `Classifier.Outcome.problem` marks them, and those still record as
errors so a broken key, timeout or rate limit stays visible. Fail-open means keep; it does not mean
hide.

---

## 4. What was changed, file by file

### 4.1 New: `src/.../Categories.java`

A pure-JDK class holding the 13 functional categories and `isFunctional(String)`. It trims, lowercases
and rejects null/blank. `Categories.count()` lets the test pin the size. No `android.*`, so `test.sh`
guards it like the rest of the core.

### 4.2 New: `src/.../Evidence.java`

A pure-JDK, lexically explicit gate. It normalises title + text (lowercase, every non-word run
collapsed to one space, `%` preserved), then:

- one **strong** cue is enough;
- two distinct **weak** cues are enough.

**Strong phrases** (normalised, whole-word/phrase matches):
`free trial`, `free delivery`, `free shipping`, `shop now`, `buy now`, `order now`, `download now`,
`install now`, `claim now`, `join now`, `sign up now`, `upgrade now`, `limited time`,
`limited period`, `today only`, `last chance`, `while stocks last`, `you have won`, `flat rs`,
`just rs`, `starting at rs`, `up to rs`, `use code`, `promo code`, `coupon code`, `buy 1 get 1`,
`special offer`, `mega sale`, `flash sale`, `season sale`, `clearance sale`, `sale is live`,
`sale ends`, `deal of the day`, `deals of the day`, `price drop`, `new arrivals`.

**Strong words:** `cashback`, `coupon`, `download`, `install`, `upto`, `unsubscribe`.
A single `%` is also strong.

**Weak words** (need two distinct): `off`, `sale`, `offer`, `deal`, `discount`, `promo`, `voucher`,
`limited`, `redeem`, `newsletter`, `prize`, `winner`, `hurry`, `exclusive`, `upgrade`, `flat`,
`percent`, `savings`, `bargain`.

Why this shape:

- **Two tiers are what buy precision without losing the obvious cases.** "Great deal and a big
  sale this weekend" is an advert; "I got a great deal on the tickets" is a friend. One weak cue
  cannot tell them apart; two can often.
- **`free` alone is not a cue.** It is the single most common false positive in the shipped model
  ("Are you free tonight?"), so only the phrases `free trial` / `free delivery` /
  `free shipping` count.
- **`code` alone is not a cue.** "Use code CRAVE50" is caught by `use code`; a bare `code` would
  collide with verification codes (which the OTP guard already protects).
- **`rs` + digits is not a cue.** "Rs 500 debited" is a transaction and "I sent you Rs 500" is a
  message. Only the promotional shapes (`flat rs`, `just rs`, `starting at rs`, `up to rs`) count.
- **`win` / `won` are not cues.** "Who won the match?" is a conversation; only `you have won`
  counts.
- **`today`, `now`, `new` are not cues on their own.** They are in ordinary conversation constantly.
- **No regex.** The gate uses a hand-written word-boundary scan, so Android's ICU-backed regex
  engine cannot reject a pattern at class load (the failure mode that once broke `Guards`).

### 4.3 New: `src/.../Guards.java`

The five regex rules, copied unchanged in meaning, plus a per-rule compile guard. `Guards.NAMES` is
`{"otp", "transaction", "order", "security", "deadline"}` so the Settings self-test's names remain
stable. `check(app, title, text)` returns the first matching name or null. A rule that fails to
compile is dropped individually; the class never throws at load.

### 4.4 `LocalClassifier.java`

- removed the private guard copy; `guard(...)` now delegates to `Guards.check` for direct callers;
- `MARGIN` is **0.25f**;
- the decision policy is now:

| Score | Evidence gate | Verdict |
|---|---|---|
| any | — (guard matched) | KEEP, with `Result.guard` naming the rule |
| `score <= −0.25` | any | KEEP |
| `−0.25 < score <= 0.25` | no | KEEP |
| `−0.25 < score <= 0.25` | yes | **UNCLEAR** (kept) |
| `score > 0.25` | no | KEEP |
| `score > 0.25` | yes | **JUNK** |

The slightly unusual part is the fourth row: a score inside the band is only `UNCLEAR` when
promotional evidence is present. The naive rule "any score in the band is UNCLEAR" would label
"standup in 10" (score +0.093) as unsure. That is noise, not uncertainty: with no promotional cue
the gate has already settled the case on the keep side, and the report explicitly requires those
messages to be KEEP. `UNCLEAR` is reserved for the genuinely mixed case — advert language, weak
model score — which is exactly what a human reviewer would call unsure.

### 4.5 `NotifService.java`

- **Functional categories** use `Categories.isFunctional(n.category)`.
- **Guards run in `inspect`**, after text extraction and before the cache/queue/engine split. A hit
  records an ordinary `"KEEP"` success and returns. No worker, no provider call, no cache entry.
  The rule name remains available to the Settings self-test through
  `LocalClassifier.Result.guard`; the service deliberately keeps the existing `"KEEP"` string so
  the home screen's wording does not change for a guard hit.
- **`recordSeenApp` moved** below `settings.enabled()` (and therefore below consent). The
  notification liveness timestamp remains after `consented()` only.
- **`judge`** distinguishes on-device `UNCLEAR` (success, `"KEPT (unsure)"`) from a provider
  `UNCLEAR` with `Outcome.problem == true` (still `recordError`).
- The class comment now says the guards are shared, instead of implying only the model has them.

### 4.6 `test/LogicTest.java`

280 assertions became **440**. New coverage:

- every functional category name, including `msg`, `email` and `event`;
- `Categories` null/blank/case/trim/unknown handling;
- strong/weak evidence, the two-tier threshold, and the deliberate omissions (`free`, `office`,
  `coffee`, one weak offer/deal);
- all five guard names, plus `LocalClassifier` delegation;
- the 42 ordinary personal messages must be **KEEP** (not merely "not JUNK");
- the five named messages asserted individually;
- the 16 must-remove promotions, plus the two known misses asserted as kept;
- the full 16-row transactional corpus with its expected guard name;
- the evidence-free positive text: its score is checked to be above `MARGIN`, and its verdict KEEP;
- the uncertainty band with a synthetic model, independent of the shipped weights;
- a bare "nothing recognised" text asserted to be **KEEP**, replacing the old tautological
  `classify(...) != null`;
- the old `cacheKey(x) == cacheKey(x)` tautology replaced with a same-prefix/different-length check.

### 4.7 `test.sh`

One line: `Categories Evidence Guards` added to `PURE`. The lead authorised this single-line change;
the existing 14 entries are untouched. All three classes have zero `android.*` imports.

### 4.8 `docs/ARCHITECTURE.md`

Corrected: "twelve classes" → seventeen; the pure-core diagram; the false jitter claim in the
retry table; the functional-gate description (now includes `msg`/`email`/`event`); the guard rules
as a shared pre-engine step; the evidence gate and uncertainty band in the fail-open invariants.
Added a link to this document.

---

## 5. What it costs

### 5.1 Promotion recall

On the 18-promotion corpus: **17/18 (94.4%) before, 16/18 (88.9%) after**. The lost promotion is
LinkedIn's "12 new jobs for you", which has no promotional cue. MakeMyTrip's "on sale" text scored
negative before and still does; it was already a miss and is not caused by this change.

That is a 5.6-point recall drop on this sample. It bought the elimination of 16 false dismissals on
the 46-message sample. A false dismissal of a message is unrecoverable; a kept promotion is one
extra row in the shade. The exchange rate is not close.

### 5.2 Short and no-language promotions (a deliberately hard set)

A stress set of 11 short promotions, most of them headlines with no discount vocabulary, removes
only 3. This set is not representative — it was built to show the floor, not the average — but it
is honest about the gate's conservative bias:

| Promotion text | Cue? | Verdict |
|---|:---:|---|
| SALE | no | KEEP |
| Special offer inside | yes | JUNK |
| Deals of the day | yes | KEEP (model −0.292) |
| New arrivals just for you | yes | JUNK |
| Weekend offer | no (one weak cue) | KEEP |
| Your cart is waiting | no | KEEP |
| We miss you! Come back for 20% off | yes | JUNK |
| Re: your subscription | no | KEEP |
| Big Billion Days starts now | no | KEEP |
| 12 new jobs for you | no | KEEP |
| Your dream vacation is on sale. Download the app. | yes | KEEP (model −0.867) |

Two distinct limitations are visible: a single weak cue is not enough by design, and evidence does
not override a confidently negative model (that is the "positive score **and** evidence" rule).

### 5.3 What the trade buys

| Metric | Before | After |
|---|---:|---:|
| False dismissals on the 46 personal texts | 16 (34.8%) | **0 (0%)** |
| False dismissals on the 42 ordinary texts | 15 (35.7%) | **0 (0%)** |
| Transactional texts kept | 16/16 | 16/16 |
| Promotions removed | 17/18 (94.4%) | 16/18 (88.9%) |
| Guard coverage of transactional corpus | 16/16 | 16/16 |

---

## 6. Residual risks that remain

1. **A personal message that explicitly talks like an advert will still be dismissed.** Measured
   examples:
   - "I got 50% off on the tickets!" → **JUNK** (score +0.823, `%` + `off`).
   - "The free trial is great, you should try it" → UNCLEAR (kept; score +0.201).
   - "Download this file before the meeting" → UNCLEAR (kept; score +0.232).
   - "I saved 30% on my train ticket" → UNCLEAR (kept; score +0.085).
   - "50% of the team is out sick" → KEEP (score −1.333).
   - "There's a sale at the shop near you" → KEEP (no strong cue, score +1.080).
   - "The offer ends tonight, should we take it?" → KEEP (one weak cue, score +0.358).
   - "Check out this deal I found" → KEEP (one weak cue, score +0.311).
   - "My coupon expires today, remind me" → KEEP (deadline guard).

   The first example is the unavoidable one: a message containing "50% off" is lexically an advert.
   Only a semantic model, or user-level context, could tell a brag from a promotion. The fix reduces
   the class of failures from "any sentence with `is`/`this`" to "a person quoting a discount"; it
   does not eliminate it.

2. **Promotional SMS/RCS with `CATEGORY_MESSAGE` is kept.** Accepted trade, documented in
   `Categories`. A brand that wants to bypass the filter can set `msg`; that is still better than
   the app eating a message.

3. **Recall loss on cue-less promotions.** LinkedIn digests, "Your cart is waiting", "Big Billion
   Days starts now", one-word "SALE", and short "Weekend offer" are kept. See §5.2.

4. **The provider path still relies on the provider's judgement.** Guards now protect it, but the
   evidence gate does not apply (there is no score). A provider that answers JUNK for a personal
   message will still dismiss it. The containment for that path is `Decision.parse`, the guard
   rules and the user's choice of provider; the prompt's safety rules are not code.

5. **The evidence gate is lexical and English-centric.** It is tuned on the corpus below, and it
   was tuned *while looking at that corpus* — the 88.9% and 0% figures are therefore optimistic
   in-sample numbers, not a held-out estimate. Non-English promotions (no English cues) are kept;
   non-English personal messages are likely kept too, because the model also knows little about
   them. The next evaluation must use fresh, held-out text.

6. **The guard regexes remain narrow and English.** They were not changed in substance. A
   rephrased bank SMS or a non-English OTP can still miss all five; the fail-open default keeps it,
   but the protection is not complete.

7. **Functional categories are only as good as the posting app.** An app can post a promotion with
   `CATEGORY_MESSAGE` (kept) or a real message with no category (goes to the classifier). The
   category gate is a strong prior, not a guarantee.

8. **Scores are not calibrated and `MARGIN = 0.25` is not validated.** The band was chosen on a
   small corpus, in line with the review's suggestion. A retrained model needs a held-out threshold
   sweep, not a copied constant.

9. **UNCLEAR and guard hits are not cached.** That is deliberate (fresh look next time) but it
   means a repeatedly posted guarded notification writes prefs and re-runs regex each time. The
   cost is negligible for a notification stream; it is noted so nobody "optimises" it into caching
   a verdict across a config change.

10. **The model asset is still an opaque binary.** There is no in-repo training script or corpus.
    This change reduces the damage it can do; it does not make the model trustworthy. The recipe is
    in §9.

11. **Privacy follow-through.** `recordSeenApp` is now behind consent + activation, but package
    names and labels are still stored once filtering is on. `docs/PRIVACY.md` and the README still
    need to disclose the remembered-sender list (the review's §6 list). That is outside this
    change's file ownership.

---

## 7. Options considered and rejected

| Option | Why rejected |
|---|---|
| **Retrain the model now** | The repo has no training corpus, no export script and no provenance for `assets/local_model.tsv.gz`, and the benchmark harness was outside the repo. A retrain done now would be unverifiable and riskier than the gate. §9 is the recipe so the next person can do it properly. This is the right long-term fix. |
| **Threshold-only (raise `MARGIN`)** | To keep "Are you free tonight?" (+0.890) and "Let me check and get back to you" (+1.649) the threshold would have to exceed +1.649, which would keep most promotions. A threshold cannot distinguish "positive because `free`/`get`" from "positive because `50% off`". It was still worth setting `MARGIN` to a real band, but it is not sufficient. |
| **Stopword pruning at inference only** | Dropping `is`/`this`/`get` from the tokeniser changes the denominator (`‖x‖`) and the meaning of the exported coefficients. It would silently break the "mirrors scikit-learn" property and make scores incomparable. Pruning must happen at training time and be re-exported; see §9. |
| **Drop/expand the protected-app allowlist only** | The lead already expanded `DEFAULT_ALLOW` to the common messengers, which is useful. It cannot enumerate every chat app, and a chat app can also send marketing. The category and evidence layers are needed regardless. |
| **Blocklist apps that send promotions** | Brittle, unfair to apps that send both, and impossible to maintain. A user-protected list is the right control; an automatic app verdict is not. |
| **Keyword-only classifier (evidence gate without the model)** | High precision, poor recall: many promotions do not use the exact cues or use them in languages the list does not cover. The model still adds value on recall; the gate adds precision. |
| **Let evidence override a negative score** | Contradicts the required rule (JUNK only when the score is positive) and would dismiss "Deals of the day" (score −0.292) on the strength of a phrase. Keeping is the safe side. |
| **Apply the evidence gate to the provider path** | The provider returns a discrete verdict, not a score; there is no dot product to gate. Doing it anyway would override an explicit provider answer with a lexical heuristic and is a separate policy decision. Guards are the shared safety net; the evidence gate is model-specific. |
| **Keep `MARGIN = 0` / make the band enormous** | `MARGIN = 0` leaves the UNCLEAR branch dead and treats 0.005 as confident. An enormous band labels ordinary chat "unsure", which is noise. 0.25 is a real band without swallowing the useful range. |
| **Return UNCLEAR for every score in the band** | Would label "standup in 10" (+0.093) and "are we still on for lunch?" (+0.027) as unsure. They are not uncertain: there is no promotional evidence at all. It also conflicts with the requirement that the reported personal messages be KEEP. |
| **Treat `msg`/`email`/`event` as non-functional** | This is what the app did, and it is why chat was classified at all. No. |
| **Add `social` / `recommendation` to functional categories** | `CATEGORY_SOCIAL` can be a brand post and `CATEGORY_RECOMMENDATION` is explicitly a recommendation. Adding them would keep more promotions for no safety gain on messages. Deliberately not added. |
| **Use an LLM as the default engine** | The README's own benchmark found the small models either useless or dangerous; it adds network, cost, latency and privacy surface. The on-device model plus a gate is the right default. |

---

## 8. What was deliberately not done

- No retraining and no change to the asset. The weights are unchanged.
- No stopword pruning at inference (see §7 and §9).
- No provider-side evidence gate.
- No changes to `Prefs`, the activities, `Screen`, resources, the manifest, `build.sh`, the README,
  the review, the privacy policy or the Play/F-Droid docs.
- No change to the guard regexes themselves, and no new functional categories beyond the three
  required.
- No cache of UNCLEAR or guard hits.
- No attempt to make scores calibrated or to expose a confidence number to the user.

---

## 9. A concrete recipe for retraining the model properly

The following is the recipe the next person should follow rather than guessing. It is ordered so
that the evaluation decides the export, not the other way round.

### 9.1 Define the task and the success criterion first

A notification is **JUNK** only if it is a commercial promotion, sale, marketing message,
newsletter, engagement bait or similar, and it is not transactional, personal, or a security /
account / delivery update. Everything else is **KEEP**. Ambiguous cases are **UNSURE** and are
excluded from the dismissal class (and may be excluded from training entirely).

The primary metric is **false dismissal rate on KEEP**: the fraction of held-out KEEP
notifications that the full pipeline (functional gate + guards + model + evidence gate) would
dismiss. It must be as close to zero as the corpus can measure. The secondary metric is promotion
recall at that false-dismissal rate. Report both, never recall alone.

### 9.2 Collect data

- **Sources, in order of preference:** consented user contributions (explicit opt-in, content
  only, no package identifiers or hashed), a public labelled notification corpus with a compatible
  licence, and synthetic/semi-synthetic texts written to cover gaps. Do not scrape private
  messages.
- **Fields:** `app_label` (or `category` if the app must not be identified), `title`, `body`,
  `android_category`, `language`, `label` (JUNK/KEEP/UNSURE), `source`, `split`.
- **Hard negatives are the point.** Include: ordinary chat with function words; chat containing
  "free", "deal", "offer", "sale", "%"; security and order texts already covered by guards (to
  measure guard coverage); one-line messages; messages with emoji; short texts (2–5 tokens) because
  L2 normalisation amplifies them; non-English texts.
- **Volume:** at least 2,000 KEEP and 2,000 JUNK, with 20–30% held out; aim for 5,000+ per class if
  the sources allow. More important than raw volume is coverage of the failure shapes above.
- **Deduplicate** near-identical texts (template spam) before splitting, or the same template will
  appear in train and test and inflate the score.
- **Split by app and by time**, not randomly: a random split lets the model memorise an app's
  template and reports a number that will not hold on a new app.
- **Privacy:** strip names, phone numbers, account numbers and codes where possible; keep raw text
  only as long as needed; document consent and retention.

### 9.3 Label

- Write a one-page codebook with the JUNK/KEEP/UNSURE definitions and 10–20 examples per class.
- Two annotators label independently; adjudicate disagreements; report Cohen's kappa. A kappa
  below ~0.7 means the task or the codebook is unclear, not that the model is bad.
- Never label by app ("this comes from Zomato so it is junk"). Label the text, and keep the app as
  a feature only for analysis, not as a target.
- Keep a separate **adversarial KEEP** set of personal messages containing promo vocabulary; it is
  the set this change is designed to protect.

### 9.4 Handle stopwords and feature precision (the actual bug)

The current asset learned `free` (+1.140), `get` (+2.247), `this` (+1.978), `up` (+1.439), `on`
(+1.276), `and` (+1.287) as strong positive features. That is the root cause; the evidence gate is a
bandage. In training:

- Use `min_df` and `max_df` so tokens that appear in almost every document cannot dominate
  (`max_df≈0.5`, `min_df≈5`, tuned).
- Add a **feature precision filter**: for each candidate token/bigram, compute
  `P(JUNK | token)` with smoothing (or a chi-square / mutual-information statistic) on the training
  split. Drop features whose precision is near the base rate, or require positive features to have
  `P(JUNK | token) ≥ 0.65` and negative features `≤ 0.35`. This removes function words
  automatically and keeps `percent off`, `cashback`, `coupon`, etc.
- If a manual English stopword list is used, apply it **in training and in
  `LocalClassifier.tokenize` together**, then re-export the IDF and coefficients. Applying it only
  at inference changes the L2 norm and invalidates the weights.
- Keep bigrams only when they add precision; many bigrams are just two generic words.
- Re-check the top-20 coefficients before exporting. If any of them is a word a person uses in a
  normal sentence, the feature filter failed.

### 9.5 Train and calibrate

- `TfidfVectorizer(ngram_range=(1, 2), sublinear_tf=True, norm="l2", lowercase=True,
  token_pattern=r"(?u)\b\w\w+\b", min_df=..., max_df=...)`, then a linear model
  (`LogisticRegression` with L2, or `LinearSVC` plus a calibration set).
- The Java inference does `tf' = 1 + ln(tf)`, `x = tf' · idf`, then L2-normalises `x` and computes
  `bias + x̂·coef`. Keep `norm="l2"` in the vectorizer so this matches; if you use
  `norm=None`, remove the Java normalisation instead.
- Choose the decision threshold from a **held-out precision/recall curve**, with the false-dismissal
  constraint first. Do not copy 0.25; measure it.
- Keep a margin around the chosen threshold (the code's `MARGIN`) and evaluate the UNCLEAR band as
  part of the pipeline.

### 9.6 Evaluate

Evaluate the **whole pipeline**, not just the model: functional categories, guards, score, evidence
gate. Report:

- confusion matrix at the chosen threshold;
- false dismissal rate on the KEEP held-out set (the primary number);
- promotion recall and precision (with confidence intervals, e.g. bootstrap over texts);
- per-app and per-category breakdowns, and a per-language breakdown;
- an ablation: model only, model + guards, model + guards + evidence gate, to show what each layer
  contributes;
- the adversarial KEEP set, separately — it is the set that matters most;
- guard coverage on the transactional corpus (which rules fire, and which texts escape all five).

Keep the current `test/LogicTest.java` corpus as a fixed regression set, but treat it as *one* set,
not the evaluation. The numbers in this document are in-sample for the evidence list and must not
be quoted as a held-out estimate.

### 9.7 Export format and verification

The asset format is fixed by `LocalClassifier.load`:

```
#bias<TAB><float>
token<TAB>idf<TAB>coef
...
```

- UTF-8, gzip-compressed, tab-separated; no header row beyond `#bias`.
- The first line must start with `#bias`; the second field is the intercept.
- Tokens are lowercase; unigrams are runs of at least two letters/digits; bigrams are two such
  tokens joined by a single space. No tabs or newlines inside a token.
- Export **all** features the model uses, with the IDF values from the training vectorizer and the
  coefficients from the linear model. Do not re-normalise the coefficients by hand; the Java code
  does the L2 normalisation.
- Verify against the training stack: for every held-out document, `LocalClassifier.score` must
  match `model.decision_function(vectorizer.transform([doc]))` to within ~1e-4. If it does not, the
  tokeniser, the render format or the norm is wrong.
- `render(app, title, text)` is `app + ": " + title + ". " + text`. If the training text uses a
  different shape, change both sides or the weights will not mean what they were trained to mean.
- Ship the asset, bump its size in the docs, and record the training command, corpus hash and
  evaluation numbers next to it.

### 9.8 Deployment checklist

1. Run `./test.sh` — the 440 assertions include the real asset.
2. Update the corpora in `test/LogicTest.java` if the new model's behaviour changes the expected
   rates; keep the old rows so regressions are visible.
3. Re-run the harness (`/tmp` or a committed `tools/` equivalent) on the before/after corpora and
   this document's tables.
4. Update `docs/CLASSIFIER.md` with the new numbers and the model provenance.
5. Re-check `MARGIN` and the evidence cues against the new model; a retrain can change which words
   are strong.
6. Only then update the README's benchmark table and its "39/47" claims (see §10).

---

## 10. README sentences the lead must reconcile

`README.md` was being edited by another agent while this work landed, so these are the sentences in
the version in the tree at the time of writing. Items already corrected there (the product name, the
"no network" phrasing and the `INTERNET`-vs-`BIND_…` permission paragraph) are **not** listed.

1. **"Every notification is judged on its own, in three layers, cheapest first:"** (under "How it
   decides") — the pipeline is now functional categories → protected apps → guards → model →
   evidence gate → uncertainty band. "Three layers" is no longer an accurate description.
2. **"2. An 18 KB linear model … decides the rest."** — the model no longer decides alone; a positive
   score also requires promotional evidence.
3. **"3. Nothing else. No cloud call, no second opinion, no retry."** — "Nothing else" omits the
   functional gate (`msg`/`email`/`event`), the protected-app list, the evidence gate and the
   uncertainty band. "No retry" is true of the on-device engine, not the provider.
4. **"Anything the model does not recognise, any failure, and every uncertain answer keeps the
   notification. Only a clear `JUNK` dismisses."** — now: only a clear JUNK **with promotional
   evidence** dismisses on the on-device engine, and an UNCLEAR is recorded as "kept (unsure)"
   rather than as an error.
5. **"1. Five guard rules … A rule hit means KEEP, before any model is consulted."** — this is now
   true for **both** engines (it was previously only true on-device). It can stay, but saying "both
   engines" and noting that guarded text is no longer sent to a provider would be accurate.
6. **The benchmark table row `| this classifier | 18 KB | 39 / 47 | 1, an arguable cashback reward |`**
   — the review rejected the "1 destroyed notification" claim, and this change measures 16/18
   promotions removed with 0/46 personal messages dismissed. Update the table with the current
   corpus and numbers, or remove it.
7. **"The full harness is in `~/tmp/ncfilter-bench` and the methodology is described in
   [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)."** — the harness is not in the repository, and the
   methodology now lives in `docs/CLASSIFIER.md`.
8. **"`./test.sh`     # 280+ plain-JDK assertions"** — now 440.
9. The top paragraph (**"no account, no API key, no analytics, no third-party SDKs"**) and the
   Privacy section's "Nothing leaves the phone unless you explicitly configure a provider" remain
   true; no dependency or network path was added. The privacy section may optionally note that
   guarded (OTP/transaction/order/security/deadline) text is never sent to a provider even when one
   is configured.

---

## 11. Reproducing these numbers

The throwaway harness is not committed (the instruction was explicit: `/tmp`, not the repo). Its
essential logic is now in `test/LogicTest.java`:

- `PERSONAL` — the 42 ordinary texts (must all be KEEP);
- `PERSONAL_BORDERLINE` — the 4 promo-vocabulary messages (must not be JUNK);
- `PROMOS_REMOVED` — the 16 promotions that must still be JUNK;
- `PROMOS_MISSED` — the 2 known misses (must not be JUNK);
- `TRANSACTIONAL` — the 16 guarded texts with their expected rule names.

`./test.sh` runs them against the real asset. A future harness can simply load
`assets/local_model.tsv.gz` through `LocalClassifier.load` and call
`LocalClassifier.classify` with the same rows; that is exactly what produced every table here.
The before numbers were produced by the same harness against the pre-change sources, before any
edit — the scores are unchanged by this work because the model asset was not touched.

---

## 12. Bottom line

- The app no longer dismisses ordinary conversations on the measured corpus: 16 → 0.
- Promotions are still removed at 88.9% on the measured corpus (16/18), down 5.6 points from
  94.4%; the lost promotion is a job digest with no promotional language.
- The five guard rules now protect both engines and run before any text can be sent to a provider.
- Transactional texts remain 16/16 protected, and the guard names the Settings self-test prints are
  unchanged.
- The model itself is still the underlying problem: it scores ordinary English positive and
  promotional English on the same generic words. This change contains that without pretending to
  fix it. §9 is how to fix it for real.

---

## 13. Change made at integration, after this document was written

One addition, and its measurement, recorded here so the document stays the record of the code:

`Evidence.STRONG_PHRASES` gained `"refer a friend"`, `"refer friends"` and `"earn rewards"`.

**Why.** The independent integration corpus (30 personal texts, 12 promotions, 8 transactional)
found "Refer a friend and earn rewards" scoring **+1.395** but being kept, because none of the
first-pass cues appeared. That is a common promotional phrasing, and the cost of a cue-less miss is
a promotion the user still sees.

**Measured, not assumed.** On the same corpus: personal kept **30/30 before and after**, promotions
removed **10/12 → 11/12**, transactional **8/8**. Phrases were used rather than the words `refer`
and `reward`, so "I was referred by a friend" and "reward points" in an ordinary context do not
open the gate.

**Still missed:** "Big Billion Days are live" (score +0.157, inside the uncertainty band, so the
model — not the evidence gate — is the limiting factor). This is the class of cue-less campaign
name that a retrained model should catch and a cue list should not try to.


---

## 14. The sender as a signal (added after the text work)

### 14.1 Why text alone cannot finish the job

Two failures survive every text rule, in opposite directions:

| Message | Sender | Correct | Text model says |
|---|---|---|---|
| "I got 50% off on the tickets" | a chat app | person | **JUNK** (+0.639) — reads exactly like an advert |
| "Big Billion Days are live" | a shop | campaign | kept (+0.301) — contains no offer wording at all |

No threshold and no cue list separates those, because the deciding fact is not in the words. It is
in who posted them. So the sender is now part of the decision (`Senders.java`), used in two
directions and only where it is safe.

### 14.2 What changed

- **`Senders.PERSONAL`** — chat, mail, dialer, authenticators, calendars, UPI. These build the
  default protected list instead of a hand-typed string, so the table is the one place that says
  "this app carries a person's words or money", and it is visible and editable in Protected apps.
  *A default, not a rule*: a user who removes an app there has asked for it to be filtered, and
  that is respected.
- **`Senders.COMMERCE`** — shops, food delivery, quick commerce, travel, ticketing. For these the
  promotional-evidence gate is not also required: the sender supplies the context the words lack.
  Decision becomes `score > MARGIN` → JUNK, inside the band → UNCLEAR, below → KEEP.
- **Everything else is unknown** and keeps the strict rule (score *and* promotional language).
  Social, video and professional networks are deliberately **not** commerce: they also carry direct
  messages and comments, and relaxing the rule for them would dismiss a person's words.

### 14.3 Measurements

Corpus: 9 personal, 11 important/transactional and 13 promotional notifications across 20 real
package names, plus a 14-row risk set of important commerce notifications (returns, delays, account
locks, refunds, reschedules, delivery-partner messages).

| | Before | After |
|---|---:|---:|
| Promotions removed | 6 / 13 | **8 / 13** |
| Important commerce notifications kept | 11 / 11 | **11 / 11** |
| Risk set kept | 14 / 14 | **14 / 14** |
| Personal texts kept by the *classifier* | 8 / 9 | 8 / 9 |

The personal row is unchanged on purpose: the classifier still scores "I got 50% off on the
tickets" as JUNK. In the app that message is never classified, because WhatsApp is on the protected
list. The sender table protects it; the model would not.

**The safety margin.** Across all 25 important/transactional notifications, the highest model score
was **−0.088** ("Call me when you reach", from a food-delivery app) against a JUNK threshold of
**+0.25** — a margin of 0.34. That message now comes back UNCLEAR inside the band, which is kept,
never dismissed. Anything a commerce app sends that is urgent, transactional or person-to-person
scores negative, which is what makes the relaxation defensible rather than lucky.

**Still missed (deliberate):** "Your cart is waiting" (Nykaa, −0.294 — the model sees no promotion),
and the LinkedIn/YouTube/Spotify engagement notifications, which stay strict because those apps carry
direct messages. Catching those needs a better model, not a looser rule.

### 14.4 Residual risks

1. **The tables rot.** They are hand-curated package fragments; a new app is unknown and gets the
   strict rule, which is the safe direction, but it also gets no benefit. Review the tables at each
   release.
2. **Commerce apps have in-app chat.** "I am at your gate" scores −0.716 and "Call me when you
   reach" −0.088. Both are kept today, but the second is inside the band; if the model is retrained,
   re-measure this class first.
3. **A personal message quoting a discount is still dismissed by the classifier** if its app is
   removed from Protected apps. That is an explicit user choice, not an accident, but it is worth
   knowing.
4. **None of this replaces retraining.** The sender prior contains the model's weaknesses; §9 is how
   to remove them.
