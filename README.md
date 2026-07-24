# Wei.G ZeroAd

[简体中文](README.zh-CN.md)

Wei.G ZeroAd is a lightweight, open-source root ad blocker for Android 12–16. One native manager controls a systemless-hosts core compatible with Magisk, KernelSU, and APatch.

## Highlights

- Separate Domestic and Global profiles: Lean, Balanced, and Strict.
- Reward-ad domains never enter normal profiles; four reward packs are enabled by default and can be temporarily allowed for ten minutes.
- Live running-rule count, instant profile feedback, custom block/allow/disable lists, and complete removal.
- Independent one-tap updates for rules, manager APK, and core.
- Rule updates are checked first and require confirmation before download/install.
- Domestic filtering has an independent on/off switch.
- Automatic light/dark theme and a prefilled GitHub Issue report.

Rules are maintained and released separately by [`WeiG-ZeroAd-Rules`](https://github.com/weigefenxiang/WeiG-ZeroAd-Rules). Rule ZIPs are data-only and are validated again by both the manager and root core before atomic activation.

Publish the rules repository first. The manager/core Release workflow downloads its latest verified rule ZIP and embeds it, so first boot already has all six real profiles; later rule-only updates remain independent.

If downloading or validating a rule update fails, an installed device keeps its current rules. A first install, or a release build without a valid rule ZIP, falls back to the bundled Wei.G 20260723 baseline; reward-ad domains remain separate.

## Install

Most users should download `WeiG-ZeroAd-test.zip` from the rolling test Release, install it with MMRL, Magisk, KernelSU, or APatch, and reboot. It contains both the core and manager.

Advanced users may use:

- `WeiG-ZeroAd-test-core-only.zip`: core only.
- `WeiG-ZeroAd-Manager-test.apk`: standalone manager.

Only the first core installation and later core updates require a reboot. Profile changes, rule updates, and manager updates do not.

Default configuration: Domestic Lean, Global Off, and all reward-ad packs enabled.

## Publish from Git

Every push to `main` refreshes the rolling `test-latest` pre-release. A version tag creates an immutable stable release.

```bash
git add .
git commit -m "Update Wei.G ZeroAd"
git push origin main

git tag v0.1.0
git push origin v0.1.0
```

## Build

```bash
python -m rule_tools.build_rules
python -m unittest discover -s tests -v
gradle :app:assembleDebug
python -m rule_tools.build_module --output dist/WeiG-ZeroAd-v0.1.0-core-only.zip
python -m rule_tools.build_module --output dist/WeiG-ZeroAd-v0.1.0.zip \
  --manager-apk app/build/outputs/apk/debug/app-debug.apk
```

Android builds use JDK 17, Gradle 9.6, AGP 9.2, `compileSdk 36`, `targetSdk 36`, and `minSdk 31`. Configure the signing secrets described in [Rule maintenance](docs/RULE_MAINTENANCE.md) before publishing.

## Limits

Hosts filtering cannot block ads that share a domain with first-party content, use hard-coded IPs, or bypass system DNS through an embedded encrypted-DNS client. ZeroAd does not modify other apps' private data or disable their components.

GPL-3.0. The bundled Wei.G baseline is an offline fallback; live profiles come from the independent rules repository.
