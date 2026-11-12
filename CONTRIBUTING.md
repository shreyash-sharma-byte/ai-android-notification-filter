# Contributing

Small project, short rules.

## Before you write code

- Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). The fail-open invariants there are not
  suggestions; a change that lets an uncertain path dismiss a notification will be rejected
  regardless of how well it is written.
- **No new dependencies.** The app uses the Android platform and nothing else. That is what makes
  the six-command build possible. A pull request that adds a library (Jetpack included) needs to
  argue why the build system should grow with it.
- Keep the pure core pure. `Decision`, `Backoff`, `CircuitBreaker`, `RateLimiter`, `Providers`,
  `LogCodec` and `Filters` must not import `android.*` — `./test.sh` fails if they do.

## Build and test

```bash
./tools/install-sdk.sh   # once: platform 35 + build-tools 35.0.0
./test.sh                # logic assertions, plain JDK
./build.sh               # signed APK in build/
```

Both scripts are the only supported entry points. There is no Gradle wrapper to run, and adding one
would defeat the purpose of the project.

## What a good change looks like

- **Test the logic you touched.** New behaviour in the pure core needs assertions in
  `test/LogicTest.java`. Tests for Android-side code are not expected; keeping Android-side code
  boring enough not to need them is the point.
- **Name the failure mode.** In the commit message and the code comment, say what breaks without the
  change. "Safer" is not a reason; "429s were retried immediately at the same broken endpoint" is.
- **Keep the UI honest.** If the app stops filtering for any reason, the status line must say so in
  one sentence. Silent degradation is the bug this app is built to avoid.
- **Style:** four spaces, no tabs, lines under ~110 characters, `final` where it is free. Comments
  explain *why*, not *what* — the code already says what.

## Reporting a bug

Include: Android version and OEM, the provider and model, whether the app had notification access
consent, and the last two lines under the status card (they name the reason it stopped). If a
notification was wrongly removed, say what it was and paste the prompt you are using.

Never paste your API key.

## Licence

By contributing you agree your work is released under the MIT licence in [LICENSE](LICENSE).
