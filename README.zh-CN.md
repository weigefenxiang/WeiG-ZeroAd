# Wei.G ZeroAd

[English](README.md)

Wei.G ZeroAd 是面向 Android 12–16 的轻量开源 Root 去广告工具。原生管理器通过一套 systemless hosts 核心兼容 Magisk、KernelSU 和 APatch。

## 主要功能

- 境内、境外规则分别提供精简、平衡、严格三档。
- 奖励广告域名不会进入普通规则；四个奖励广告包默认全选，需要领奖时可临时放行十分钟。
- 显示实际运行规则数，切换后即时反馈，支持自定义拦截、放行和手动关闭单条规则。
- 一个“检查更新”入口同时检查规则、管理器 APK 和核心，三项分别确认、
  分别校验并分别更新；完整卸载会清除全部规则和状态。
- 境内和境外规则都使用关闭、精简、平衡、严格四个对称按钮。
- 自动跟随系统深浅色，并可一键打开预填信息的 GitHub Issue。

规则由独立仓库 [`WeiG-ZeroAd-Rules`](https://github.com/weigefenxiang/WeiG-ZeroAd-Rules) 维护和发布。规则 ZIP 只包含数据，管理器与 Root 核心都会校验后再原子替换。

请先发布规则仓库。管理器/核心的 Release 流程会下载并校验最新规则 ZIP 后嵌入安装包，所以首次重启就有真正不同的六档规则；以后仍可只更新规则。

规则下载或校验失败时，已安装设备继续使用当前规则，不会被空规则覆盖；首次安装或发布流程拿不到有效规则 ZIP 时，自动回退到内置的 Wei.G 20260723 基础规则，奖励广告域名仍保持独立。

## 安装

普通用户从滚动测试 Release 下载 `WeiG-ZeroAd-test.zip`，使用 MMRL、Magisk、KernelSU 或 APatch 安装并重启即可。它同时包含核心与管理器。

高级用户也可选择：

- `WeiG-ZeroAd-test-core-only.zip`：仅核心。
- `WeiG-ZeroAd-Manager-test.apk`：独立管理器。

只有首次安装核心或更新核心后需要重启。切换模式、更新规则和更新管理器均不需要重启。

默认配置：境内精简、境外关闭、奖励广告包全部开启。

## 直接从 Git 发布

推送 `main` 会自动刷新 `test-latest` 滚动测试版；推送版本标签会创建不可覆盖的正式版。

```bash
git add .
git commit -m "Update Wei.G ZeroAd"
git push origin main

git tag v0.1.0
git push origin v0.1.0
```

## 本地构建

```bash
python -m rule_tools.build_rules
python -m unittest discover -s tests -v
gradle :app:assembleDebug
python -m rule_tools.build_module --output dist/WeiG-ZeroAd-v0.1.0-core-only.zip
python -m rule_tools.build_module --output dist/WeiG-ZeroAd-v0.1.0.zip \
  --manager-apk app/build/outputs/apk/debug/app-debug.apk
```

Android 工程使用 JDK 17、Gradle 9.6、AGP 9.2、`compileSdk 36`、`targetSdk 36` 和 `minSdk 31`。发布前按[规则维护说明](docs/RULE_MAINTENANCE.md)配置签名 secrets。

## 限制

hosts 无法处理广告和正文共用同一域名、硬编码 IP，或通过应用内置加密 DNS 绕过系统解析的情况。ZeroAd 不修改其他应用私有数据，也不禁用它们的组件。

项目采用 GPL-3.0。主仓库内的 Wei.G 基础规则只作为离线兜底，在线六档规则来自独立规则仓库。
