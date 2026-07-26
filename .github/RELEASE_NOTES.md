**Most users: download `WeiG-ZeroAd-{{TAG}}.zip` and install it with MMRL, Magisk, KernelSU, or APatch. It includes both the core and manager.**

**普通用户：只需下载 `WeiG-ZeroAd-{{TAG}}.zip`，使用 MMRL、Magisk、KernelSU 或 APatch 安装，核心与管理器会一起安装。**

`v0.1.4` 切换主题时复用现有状态，不再重复读取 Root 核心；规则操作增加互斥与失败回滚，下载和规则校验改为流式处理。核心更新为 `0.1.2`，更新核心后请重启。

`v0.1.4` reuses the current status while switching themes, serializes core operations with rollback on rule activation failures, and streams downloads and rule validation. The core is updated to `0.1.2`; reboot after updating it.

- `WeiG-ZeroAd-{{TAG}}-core-only.zip`: core only, for advanced users.
- `WeiG-ZeroAd-Manager-{{TAG}}.apk`: standalone manager install/update.

- `WeiG-ZeroAd-{{TAG}}-core-only.zip`：仅核心。
- `WeiG-ZeroAd-Manager-{{TAG}}.apk`：单独安装或更新 APK。
