# Wei.G ZeroAd

[English](README.en.md)

Wei.G ZeroAd 是面向 Android 12–16 的轻量 Root 去广告工具。原生管理器控制
systemless hosts 核心，兼容 Magisk、KernelSU 和 APatch。

## 主要功能

- 境内、境外规则可分别选择关闭、精简、平衡或严格。
- 奖励广告规则与普通规则完全分离，默认启用，可临时放行 10 分钟。
- 显示当前运行规则数量，支持自定义拦截、放行和停用域名。
- 规则、APK 和核心可分别检查并更新，下载后再次校验。
- 白天、黑暗模式自动跟随系统；右上角图标可直接切换，长按恢复跟随系统。
- 提供不记录访问域名的轻量运行日志、管理器闪退记录和安全 GitHub Issue 诊断。
- 卸载时可删除核心、挂载、官方规则、自定义规则和全部运行状态。

## 安装

普通用户下载正式版 `WeiG-ZeroAd-v0.1.5.zip`，使用 MMRL、Magisk、KernelSU
或 APatch 安装，然后重启设备。该安装包同时包含核心和管理器。

- `WeiG-ZeroAd-v0.1.5-core-only.zip`：仅核心。
- `WeiG-ZeroAd-Manager-v0.1.5.apk`：单独安装或更新 APK。

`v0.1.5` 优化管理器、核心脚本和规则工具的 I/O 与进程开销，并修复
Android toybox awk 对多行参数不兼容导致核心无法启动的问题。核心更新为 `0.1.3`。

首次安装核心以及以后更新核心都需要重启。切换过滤模式、更新规则和更新 APK
不需要重启。

默认配置：境内精简、境外关闭、四组奖励广告规则全部启用。

## 使用与更新

管理器首页显示当前保护状态和已加载规则数量。点击“检查更新”后，规则、APK
和核心分别显示版本与状态；只有存在更新的按钮才可点击，并以绿色显示。

规则由独立仓库
[`WeiG-ZeroAd-Rules`](https://github.com/weigefenxiang/WeiG-ZeroAd-Rules)
维护。规则 ZIP 只包含数据，管理器和 Root 核心都会再次检查文件哈希、数量、
配置包含关系、区域隔离和奖励广告隔离，然后原子替换当前规则。

如果规则下载或校验失败，设备继续使用当前规则。首次安装无法获得在线规则时，
使用内置 Wei.G 20260723 基础规则，奖励广告域名仍保持独立。

## 运行日志与问题反馈

“支持与卸载”中的运行日志记录核心启动、规则版本、过滤模式、保护启停、已加载
规则数量及最近 10 次管理器闪退。核心事件最多保留最近 200 条。日志不会记录
实际访问域名、应用、Cookie 或 HTTPS 内容。

“问题反馈 · 提交 GitHub Issue”会预填 APK、核心、Android、Root、规则版本、
过滤模式、运行规则数量及少量安全事件，便于排查问题。

## 限制

hosts 无法处理广告与正文共用同一域名、硬编码 IP，或应用完全绕过系统解析器的
内置加密 DNS。ZeroAd 不修改其他应用的私有数据，也不会禁用其组件。

## 引用与致谢

[StevenBlack](https://github.com/StevenBlack/hosts) ·
[anti-AD](https://github.com/privacy-protection-tools/anti-AD) ·
[HaGeZi](https://github.com/hagezi/dns-blocklists) ·
[217heidai](https://github.com/217heidai/adblockfilters)

GPL-3.0

<details>
<summary>开发者：直接从 Git 发布</summary>

推送 `main` 会更新滚动测试版；版本标签会创建不可变的正式 Release。

```bash
git add .
git commit -m "Update Wei.G ZeroAd"
git push origin main

git tag v0.1.5
git push origin v0.1.5
```

本地验证与构建：

```bash
python -m rule_tools.build_rules
python -m unittest discover -s tests -v
bash tests/runtime_module.sh
gradle :app:assembleDebug
python -m rule_tools.build_module --output dist/WeiG-ZeroAd-v0.1.5-core-only.zip
python -m rule_tools.build_module --output dist/WeiG-ZeroAd-v0.1.5.zip \
  --manager-apk app/build/outputs/apk/debug/app-debug.apk
```

Android 构建环境：JDK 17、Gradle 9.6、AGP 9.2、`compileSdk 36`、
`targetSdk 36`、`minSdk 31`。正式发布前需配置
[规则维护与签名说明](docs/RULE_MAINTENANCE.md)中的签名密钥。

</details>
