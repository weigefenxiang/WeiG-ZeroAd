# Wei.G ZeroAd

[简体中文](README.md)

Wei.G ZeroAd is a lightweight root ad blocker for Android 12–16. Its native
manager controls a systemless-hosts core compatible with Magisk, KernelSU, and
APatch.

## Features

- Independent Domestic and Global profiles: Off, Lean, Balanced, and Strict.
- Reward-ad packs remain separate from normal profiles and can be temporarily
  allowed for ten minutes.
- Live loaded-rule count and custom block, allow, and disabled-domain lists.
- Separate, verified updates for rules, APK, and core.
- Automatic system light/dark theme with a direct theme toggle.
- Privacy-safe runtime logs and a prefilled GitHub Issue report.
- Complete removal of the core, mounts, rules, custom data, and runtime state.

## Install

For the official `v0.1.2` release, most users should download
`WeiG-ZeroAd-v0.1.2.zip` and install it with MMRL, Magisk, KernelSU, or APatch,
then reboot. The package includes both the core and manager.

- `WeiG-ZeroAd-v0.1.2-core-only.zip`: core only.
- `WeiG-ZeroAd-Manager-v0.1.2.apk`: standalone APK install or update.

`v0.1.2` is a manager installer hotfix. The core remains at `0.1.1` and does
not need a separate update for this fix.

Installing or updating the core requires a reboot. Profile changes, rule
updates, and APK updates do not.

Default configuration: Domestic Lean, Global Off, and all four reward-ad packs
enabled.

## Rules and updates

Rules are maintained in
[`WeiG-ZeroAd-Rules`](https://github.com/weigefenxiang/WeiG-ZeroAd-Rules).
The manager and Root core verify the rule ZIP before activating it atomically.
If a download or validation fails, the current rules remain active. A fresh
installation can fall back to the bundled Wei.G 20260723 base rules.

## Logs and support

Runtime logs contain core startup, rule version, selected profiles, protection
state, and loaded-rule counts. They retain at most 200 events and never record
visited domains, apps, cookies, or HTTPS content.

“Report issue · GitHub” prefills safe device and ZeroAd diagnostics to make
troubleshooting easier.

## Limitations

Hosts filtering cannot block ads that share a domain with first-party content,
use hard-coded IP addresses, or completely bypass the system resolver through
embedded encrypted DNS. ZeroAd does not modify other apps' private data or
disable their components.

## Credits

[StevenBlack](https://github.com/StevenBlack/hosts) ·
[anti-AD](https://github.com/privacy-protection-tools/anti-AD) ·
[HaGeZi](https://github.com/hagezi/dns-blocklists) ·
[217heidai](https://github.com/217heidai/adblockfilters)

GPL-3.0

<details>
<summary>Developer: publish from Git</summary>

Pushes to `main` refresh the rolling test release. The `v0.1.2` tag creates the
official immutable release.

```bash
git add .
git commit -m "Update Wei.G ZeroAd"
git push origin main

git tag v0.1.2
git push origin v0.1.2
```

Build environment: JDK 17, Gradle 9.6, AGP 9.2, `compileSdk 36`,
`targetSdk 36`, and `minSdk 31`.

</details>
