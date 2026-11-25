# Privacy

Plain language, and accurate. If the app changes, this file changes with it.

## In the default setup, nothing leaves the device

AI Android Notification Filter classifies notifications **on the phone**. The classifier is an 18 KB model that ships
inside the APK; it runs in the app's own process and makes no network request at all. There is no
account, no sign-in, no server belonging to this project, and no third-party SDK.

The app requests the `INTERNET` permission because the optional provider path uses it. In the default
(on-device) configuration that permission is never exercised: no socket is opened.

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
intermediary, no proxy and no server belonging to this project. The request is a single POST over
HTTPS. The response is expected to be one word.

Before any request, the app shows a second disclosure naming that provider and the host that would
receive the text, and switches engines only when you agree. Choosing a different provider voids that
agreement and asks again. "Use on this device" switches back immediately, and nothing is sent from
that moment.

## What never leaves the device

- **Everything, in the default setup.**
- Notifications from apps on your **Protected apps** list.
- Media players, calls, alarms, downloads, navigation, foreground services, group summaries and
  anything non-dismissible.
- Anything that arrived while the app was paused, before consent, or — with a provider — while it was
  unconfigured, not agreed to, rate limited, or had an open circuit breaker.
- Your **contact list, device identifiers, location, accounts and notification history** are never
  read, so they can never be sent.
- **Kept notifications are never stored.** Only notifications the app actually removed are recorded
  — app name, title and text, capped at the last 100. **Clear** deletes them.

## What is stored, and where

| Data | Where | Encrypted |
|---|---|---|
| API key (only if you set one) | app-private `SharedPreferences` | no |
| Engine choice, provider, model, base URL, prompt, protected list | app-private `SharedPreferences` | no |
| Last 100 removed notifications (app name, title, text) | app-private `SharedPreferences` | no |
| Cached verdicts (in memory only) | RAM, lost on reboot | n/a |

App-private storage is readable only by this app on a non-rooted device. `allowBackup="false"` keeps
it out of Android cloud backups. On a rooted device, or via a debugging bridge, the preferences file
can be read — including the API key. If that matters to you, use a key scoped to a free tier with a
low spend limit, and revoke it when you stop using the app.

There is no encryption layer because it would require a Jetpack dependency (`security-crypto`), and
this build's whole premise is zero dependencies and a six-command build. That is a trade, not an
oversight.

## What this app does not do

No analytics, no crash reporting, no advertising, no tracking identifiers, no accounts, no remote
config, no telemetry. The only networking code in the app is the single POST in `Classifier.Http`, and
it is reached only when a provider is configured and agreed to. You can verify that by reading one
file.

## Deleting your data

**Clear** in the Removed section deletes the stored notification log. Uninstalling the app deletes
everything, including the API key. To revoke access on the provider side, delete the key in your
provider's dashboard.

## Your provider's policy applies, if you use one

The model provider you choose receives the notification text and applies its own retention and
training policies. That is outside this app's control; read their terms. If that is unacceptable, stay
on the on-device classifier — which is the default — and nothing is sent anywhere.
