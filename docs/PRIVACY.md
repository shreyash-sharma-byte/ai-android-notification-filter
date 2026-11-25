# NotifyCleaner privacy policy

Plain language, and accurate. If the app changes, this file changes with it.

**Effective date:** 15 November 2026.

NotifyCleaner is published by the NotifyCleaner project. The project's GitHub repository — linked
from the README — is the contact point for privacy questions; open an issue there.

## In the default setup, nothing leaves the device

NotifyCleaner classifies notifications **on the phone**. The classifier is an 18 KB model that ships
inside the APK; it runs in the app's own process and makes no network request at all. There is no
account, no sign-in, no server belonging to this project, and no third-party SDK.

The app declares the `INTERNET` permission because the optional provider path uses it. In the
default (on-device) configuration that permission is never exercised: no socket is opened.

## If you choose a provider, this is what is sent

Using a provider is an opt-in in Settings, and it is the only thing that can make notification text
leave the phone. When it is on, and only then, a notification that has passed the built-in gate is
sent as:

- the posting app's **package name** (e.g. `com.amazon.mShop.android`)
- the **title**, truncated to 200 characters
- the **text**: up to 340 characters as-is; longer text is sent as its first 200 and last 140
  characters, with the omission marked — the decisive part of a real notification is often at the end
- the **classifier prompt** (the system prompt, sent with every request)

It goes to the API endpoint **you** configured, authenticated with **your** API key. There is no
intermediary, no proxy and no server belonging to this project.

One notification can produce **more than one request**: the classifier retries once after a
retryable provider error, and each attempt may make one additional request with a different
token-limit parameter if the provider rejects the first request shape. The worst case is four POSTs
for one notification. The request is HTTPS, except when you deliberately configure a plain-HTTP
address on your own network, which the app permits only for loopback and private-range hosts.

Before the first request, the app shows a second disclosure naming that provider and the host that
would receive the text, and switches engines only when you agree. Choosing a different provider voids
that agreement and asks again. "On this phone" switches back immediately, and nothing is sent from
that moment.

**Known gap:** consent is currently stored against the provider id, not the endpoint. Editing the
Endpoint field does not, in this build, ask you to agree again. Changing the endpoint must require a
new disclosure, and that requirement will only be true once the code enforces it. Until then, treat
the endpoint field as part of the agreement you gave.

## What never leaves the device

- **Everything, in the default setup.**
- The title and text of notifications from apps on your **Protected apps** list. The posting app's
  package name and label are still recorded in the remembered-senders list described below; they are
  never sent anywhere.
- Media players, calls, alarms, downloads, navigation, foreground services, group summaries and
  anything non-dismissible.
- Notification text that arrived while filtering was paused, before consent, or — with a provider —
  while it was unconfigured, not agreed to, rate limited, or had an open circuit breaker. (The
  listener gate decides when text is read; the package and label metadata described below can be
  recorded earlier.)
- Your **contact list, device identifiers, location, accounts and notification history** are never
  read, so they can never be sent.
- **Kept notifications are not stored by default.** Only notifications the app actually removed are
  recorded — app name, title and text, capped at the last 100. **Clear** deletes them.
- If you turn on **Save samples on this device** in Settings, the app also saves the app name, title
  and text of the notifications it judges — kept or removed — together with its verdict. Card
  numbers, phone numbers, email addresses and amounts are replaced before anything is written. The
  samples stay in app-private storage, are never uploaded, and **Clear saved samples** deletes them.
  This is off unless you turn it on.

## What is stored, and where

| Data | Where | Encrypted |
|---|---|---|
| API key (only if you set one) | app-private `SharedPreferences` | no |
| Engine choice, provider, model, base URL, prompt, protected list | app-private `SharedPreferences` | no |
| **Remembered senders:** package name and app label, up to 300 entries | app-private `SharedPreferences` | no |
| Last 100 removed notifications (app name, package, title, text) | app-private `SharedPreferences` | no |
| **Counters and last-check metadata:** lifetime removal count, count of notifications skipped while busy, last verdict, last error, time of the last notification received | app-private `SharedPreferences` | no |
| **Saved samples** (only if you turn it on): app name, package, redacted title and text, verdict, score, up to 600 rows | app-private `SharedPreferences` | no |
| Cached verdicts (in memory only) | RAM, lost on reboot | n/a |

The remembered-senders list exists so the protected-apps screen can offer real choices without a
permission that enumerates every installed app. There is currently no in-app control that clears it
separately; uninstalling the app does. The counters and the last-check metadata are what the home
screen and the self-test read; they never contain notification text.

App-private storage is readable only by this app on a non-rooted device. `allowBackup="false"` keeps
it out of Android cloud backups. On a rooted device, or via a debugging bridge, the preferences file
can be read — including the API key. If that matters to you, use a key scoped to a free tier with a
low spend limit, and revoke it when you stop using the app.

There is no encryption layer. The platform Keystore plus `javax.crypto` would add no dependency, so
this is a deliberate simplicity trade, not a technical constraint. It may change in a later release.

## What this app does not do

No analytics, no crash reporting, no advertising, no tracking identifiers, no accounts, no remote
config, no telemetry, no notification history access. The only networking code in the app is the
provider POST in `Classifier.Http`, and it is reached only when a provider is configured and agreed
to. You can verify that by reading one file.

## Deleting your data

**Clear** in the Removed section deletes the stored notification log. The lifetime count is a number,
not content, and stays. Uninstalling the app deletes everything, including the API key and the
remembered-senders list. To revoke access on the provider side, delete the key in your provider's
dashboard.

## Your provider's policy applies, if you use one

The model provider you choose receives the notification text and applies its own retention and
training policies. That is outside this app's control; read their terms. If that is unacceptable, stay
on the on-device classifier — which is the default — and nothing is sent anywhere.
