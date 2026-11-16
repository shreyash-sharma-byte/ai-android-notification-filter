# Publishing on Google Play

Short version: **the APK in `build/` is for sideloading and GitHub releases. It cannot be uploaded to
Play as-is.** Several hard blockers, all fixable; the target-SDK and AAB ones are build problems, not
app-code problems.

If you only ever want this on your own phone and on GitHub, stop reading — nothing here applies.

## Blocker 1 — Play requires targetSdk 36

Play's current requirement for *new* apps and updates is **Android 16 (API 36) or higher**. This
project targets **35**, which is correct for sideloading (it runs on Android 8 through 16) but is an
upload-time rejection on Play.

The change is one line plus one SDK download:

```xml
<!-- AndroidManifest.xml -->
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36" />
```

```bash
~/android-sdk/cmdline-tools/latest/bin/sdkmanager "platforms;android-36"
PLATFORM=android-36 ./build.sh
```

Then check the two behaviour changes that targeting 36 switches on:

| Change | Effect here |
|---|---|
| Edge-to-edge enforcement | Already handled — `Screen.applyInsets` pads for system bars *and* the IME, which is required either way |
| Predictive back | The app never intercepts back; nothing to do |
| Large-screen resize ignored (`configChanges`) | `orientation\|screenSize` is in `configChanges`, so the layout is never rebuilt on resize. On a tablet or unfolded device the single-column layout simply stretches — worth a visual check, not a blocker |

## Blocker 2 — Play requires an AAB, not an APK

Play has required **Android App Bundles** for new apps since 2021. `bundletool` cannot rescue this:
it *assembles* bundles from AGP-produced module zips, it does not build them. A bundle can be
hand-rolled (`aapt2 link --proto-format` + `resources.pb` + `classes.dex` + `BundleConfig.pb`), but it
is undocumented, unvalidated, and you would be maintaining a build system to avoid maintaining a
build system.

**Recommendation:** keep `build.sh` as the contributor and sideload path — it is the point of this
project — and add a *release-only* `build.gradle` (about thirty lines, no app-code changes, `src/`
untouched) that emits `app-release.aab` when you actually submit. A minimal AGP module can point at
this exact source tree and resource directory, so nothing forks.

## Signing — read this before publishing anything

Play enrols new apps in **Play App Signing**: Google holds the app signing key, and your keystore
becomes the *upload* key. Three consequences matter:

1. Losing the upload keystore is recoverable through Play Console; losing it on a GitHub-only
   distribution is not. **Back up `release.jks` off-machine either way.**
2. **Play re-signs the artifact.** An APK you sign locally and attach to a GitHub release is *not*
   signature-compatible with the Play build. A user cannot move between them without uninstalling,
   which wipes the stored API key and settings. Pick one primary channel.
3. `build.sh` creates `release.jks` with the password `android` by default so the build runs without
   ceremony. That is fine for a local key; it is not fine as an upload key. For a release build:

   ```bash
   KS_PASS='<a real password>' ./build.sh
   ```

   The keystore is gitignored. Never commit it.

`versionCode` must increase on every uploaded artifact and is never reusable, even after a rejection.
It lives in `AndroidManifest.xml` and `build.sh` reads it from there, so there is one place to bump.

## Notification access is a restricted permission

`BIND_NOTIFICATION_LISTENER_SERVICE` is the whole point of the app and also the thing Play reviews
most suspiciously. It falls under *Permissions and APIs that Access Sensitive Information*: access
must be **necessary for the core functionality promoted in the listing**, and anything obtained
through it is personal and sensitive user data.

There is no dedicated declaration form for notification access, which is worse than a form — approval
is discretionary reviewer judgement. What has to exist for a realistic chance:

**Implemented in this app already**

- **In-app disclosure with affirmative consent.** The first screen a new install shows is the
  disclosure, before any other content. It states plainly that classification happens on the device,
  that nothing leaves the phone in the default setup, and that only choosing a provider can change
  that — with a second, provider-specific disclosure and agreement at that moment. Filtering is
  **off** until the disclosure is accepted and then explicitly switched on — `Prefs.enabled()`
  requires `consented()`, so the listener cannot act before consent.
- **A pause control on Home**, one tap away whenever filtering is on.
- **A protected-apps list on by default**, including messaging, dialer, Gmail, authenticator and
  common banking/UPI apps. Data minimisation the reviewer can see.
- **No network in the default configuration.** A reviewer can exercise the entire core feature
  without a key, an account or a connection. That is unusual for this permission and worth saying.
- **Fail-open behaviour** that is honest in the description and real in the code.

**Still needed on your side, at submission time**

- A **hosted privacy policy URL** (required twice: the store listing and the Data Safety form). Use
  [PRIVACY.md](PRIVACY.md) as the text — it is written to be published as-is, but it must live at a
  public URL, and a URL in the app's listing is not the same as a URL in a repo file.
- A full description that says plainly: reads notification content; classification runs on the
  device and sends nothing; a provider is optional, and if chosen, app name, title and text go to the
  provider *the user* configures; the developer runs no server; the package and label of posting apps
  are recorded, but a protected app's title and text are never read or classified; nothing is
  dismissed when the classifier is unsure or fails.
- Four or more screenshots showing the disclosure screen, the settings, the protected list and the
  removed list. The access should look proportionate at a glance.
- **The screenshots must be re-captured.** Play requires a minimum dimension of 320px, and the longest
  side must be no more than twice the shortest. All four screenshots in `docs/screenshots/` are
  540×1170 or 591×1280 — a ratio of 2.17:1 — so they will be rejected or cropped. Capture them at
  1080×1920 (or 1080×2160) from a real device, and capture the current build: the existing ones show
  an older version.
- The declarations in Play Console: **Data Safety**, **content rating**, **ads**, and **target
  audience and content**. They are required before any production release and are not optional
  paperwork.
- For a **new personal developer account** created after 13 November 2023: a closed test with **12
  testers for 14 continuous days** before you can apply for production. Plan for it; it cannot be
  shortened.
- A **temporary test API key in the review notes** only if you want the optional provider path
  reviewed. The core feature no longer needs one — say so, and note that the on-device path is the
  default. "Non-functional" is a rejection reason, and this build is functional offline.

**An honest risk assessment:** this app's shape — read every notification, transmit the text — is
exactly the pattern notification-access review exists to catch, and "notification cleaner" is a
category with a history of abuse (OTP harvesting). Approval is plausible but not guaranteed, and no
amount of code quality changes that.

## The better store fit: F-Droid

If the goal is "an open-source app people can install", F-Droid is a far better target than Play for
this particular app, and it costs you much less:

| | Google Play | F-Droid |
|---|---|---|
| Artifact | AAB, produced by a Gradle path you must add | source build — F-Droid's metadata supports a custom `build:` command, so `build.sh` stays canonical |
| Signing | Play re-signs; your key becomes an upload key | F-Droid signs with its own key, reproducibly |
| Restricted permission review | discretionary, no declaration form, high suspicion for notification access | no equivalent gate |
| Launch requirement | closed testing before production — 12 testers for 14 continuous days for new personal accounts | none |
| Audience | mass market | exactly the people who want a BYO-key, no-telemetry filter |

The zero-dependency, no-Gradle, single-command build is a liability on Play and an asset on F-Droid,
where reproducible source builds are the whole model. If rejection on Play would cost you anything,
ship here and there instead.

## Data Safety form

Answer against `docs/PRIVACY.md` and the code, not against intuition:

| Question | Answer |
|---|---|
| Does your app collect or share user data? | **Yes** — notification content is transmitted off-device |
| Data types | **Messages → other in-app messages** (notification text, only when a provider is configured), and **Installed apps** (the posting package, only on the provider path). **App activity → other actions is No** — the removed list never leaves the device. *App info and performance* is **No**. |
| Shared with third parties? | **Yes** — the model provider the user configures |
| Is it encrypted in transit? | **No today.** HTTPS is enforced for public hosts, but plain HTTP is still permitted for loopback and private-range hosts because a local model server cannot present a certificate. Answer **No** until the cleartext exception is removed; then answer **Yes**. |
| Can users request deletion? | **No** — there is no server-side data. The in-app Clear button is a local control, not a request channel. |
| Is any of it required? | **Optional** — the app works fully offline; a provider is an opt-in. |
| Is data collected for advertising or tracking? | **No** |

The API key is user credentials of *their* account, stored on-device; it is not collected by you. Say
so if asked, but do not list it as collected data.

## Checklist

- [ ] `targetSdkVersion` raised to 36, platform 36 installed, rebuilt and re-tested
- [ ] AAB produced by a release-only Gradle path
- [ ] `release.jks` backed up off-machine, real password, not committed
- [ ] Privacy policy hosted at a public URL
- [ ] Store listing text prepared
- [ ] At least 4 screenshots re-captured at 1080×1920 (or 1080×2160) — longest side no more than
      2× the shortest; the current 2.17:1 images will be rejected
- [ ] Data Safety form filled from the table above
- [ ] Content rating questionnaire completed
- [ ] Ads declaration completed (**No ads**)
- [ ] Target audience and content declaration completed
- [ ] New personal developer account (created after 13 Nov 2023): closed test with 12 testers for
      14 continuous days completed before applying for production
- [ ] Temporary test key ready for the review notes
- [ ] Decide channel: Play, GitHub APK, or F-Droid — and state the signing consequence
