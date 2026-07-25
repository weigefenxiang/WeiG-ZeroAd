from pathlib import Path
import json
import unittest


ROOT = Path(__file__).resolve().parents[1]


class AndroidStartupSourceTests(unittest.TestCase):
    def test_content_view_exists_before_window_insets_controller(self) -> None:
        source = (ROOT / "app/src/main/java/com/weig/rootad/MainActivity.java").read_text(
            encoding="utf-8"
        )
        content_view = source.index("setContentView(buildScreen())")
        insets_controller = source.index("getWindow().getInsetsController()")
        self.assertLess(content_view, insets_controller)

    def test_manager_targets_separate_zeroad_rules_repository(self) -> None:
        gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        self.assertIn('"WeiG-ZeroAd"', gradle)
        self.assertIn('"WeiG-ZeroAd-Rules"', gradle)

    def test_rule_updater_accepts_six_profiles(self) -> None:
        source = (ROOT / "app/src/main/java/com/weig/rootad/RuleUpdater.java").read_text(
            encoding="utf-8"
        )
        for profile in (
            "cn-lean.domains", "cn-balanced.domains", "cn-strict.domains",
            "global-lean.domains", "global-balanced.domains", "global-strict.domains",
        ):
            self.assertIn(profile, source)

    def test_rule_updater_uses_android_compatible_manifest_reader(self) -> None:
        source = (ROOT / "app/src/main/java/com/weig/rootad/RuleUpdater.java").read_text(
            encoding="utf-8"
        )
        self.assertNotIn("Files.readString", source)
        self.assertIn("Files.readAllBytes", source)

    def test_unified_update_check_keeps_three_install_actions_separate(self) -> None:
        activity = (ROOT / "app/src/main/java/com/weig/rootad/MainActivity.java").read_text(
            encoding="utf-8"
        )
        updater = (ROOT / "app/src/main/java/com/weig/rootad/RuleUpdater.java").read_text(
            encoding="utf-8"
        )
        self.assertIn("检查更新", activity)
        self.assertIn("RuleUpdater.checkLatest()", activity)
        self.assertIn("CodeUpdater.check(this)", activity)
        self.assertIn("更新规则", activity)
        self.assertIn("更新 APK", activity)
        self.assertIn("更新核心", activity)
        self.assertIn("styleUpdateButton", activity)
        self.assertIn("updateAvailable", activity)
        self.assertIn("RuleUpdater.install(this, available)", activity)
        self.assertLess(activity.index("RuleUpdater.checkLatest()"),
                        activity.index("RuleUpdater.install(this, available)"))
        self.assertIn("Release tag and rule manifest version differ", updater)

    def test_domestic_and_global_profiles_have_symmetric_off_buttons(self) -> None:
        activity = (ROOT / "app/src/main/java/com/weig/rootad/MainActivity.java").read_text(
            encoding="utf-8"
        )
        self.assertIn('cnOffButton = actionButton', activity)
        self.assertIn('"cn-profile off"', activity)
        self.assertIn('globalOffButton = actionButton', activity)
        self.assertNotIn("cnEnabled", activity)

    def test_release_publishes_a_checksum_bound_update_manifest(self) -> None:
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        updater = (ROOT / "app/src/main/java/com/weig/rootad/CodeUpdater.java").read_text(
            encoding="utf-8"
        )
        self.assertIn("dist/update-manifest.json", workflow)
        self.assertIn('"sha256": digest', workflow)
        self.assertIn("component.sha256()", updater)

    def test_status_contract_supports_domestic_off_and_downloaded_rules(self) -> None:
        schema = json.loads((ROOT / "api/status.schema.json").read_text(encoding="utf-8"))
        self.assertIn("off", schema["properties"]["cn_profile"]["enum"])
        self.assertIn("rules_downloaded", schema["required"])
        self.assertIn("core_version", schema["required"])
        self.assertIn("core_version_code", schema["required"])

    def test_theme_icon_toggles_directly_and_can_restore_system_mode(self) -> None:
        activity = (ROOT / "app/src/main/java/com/weig/rootad/MainActivity.java").read_text(
            encoding="utf-8"
        )
        self.assertIn("R.drawable.ic_theme", activity)
        self.assertIn("theme.setOnClickListener(view -> toggleTheme())", activity)
        self.assertIn("theme.setOnLongClickListener", activity)
        self.assertIn("followSystemTheme()", activity)
        self.assertIn("applyThemeOverride();", activity)
        self.assertNotIn("setSingleChoiceItems", activity)

    def test_support_section_exposes_safe_logs_and_issue_diagnostics(self) -> None:
        activity = (ROOT / "app/src/main/java/com/weig/rootad/MainActivity.java").read_text(
            encoding="utf-8"
        )
        core = (ROOT / "module/bin/rulectl").read_text(encoding="utf-8")
        self.assertIn("查看运行日志", activity)
        self.assertIn("问题反馈 · 提交 GitHub Issue", activity)
        self.assertIn('RootShell.runControl("events")', activity)
        self.assertIn("最近事件 / Recent events", activity)
        self.assertIn("EVENT_LOG=$STATE_DIR/events.log", core)
        self.assertIn("tail -n 200", core)
        self.assertNotIn("query.log", core)

    def test_apk_installer_closes_streams_before_committing_session(self) -> None:
        installer = (ROOT / "app/src/main/java/com/weig/rootad/ApkInstaller.java").read_text(
            encoding="utf-8"
        )
        stream_scope = installer.index("try (FileInputStream input")
        fsync = installer.index("session.fsync(output)", stream_scope)
        stream_scope_end = installer.index(
            "// PackageInstaller rejects commit()", fsync
        )
        commit = installer.index("session.commit(", stream_scope_end)
        self.assertLess(fsync, stream_scope_end)
        self.assertLess(stream_scope_end, commit)
        self.assertIn("installer.abandonSession(sessionId)", installer)
        self.assertIn("APK 安装失败：", installer)

    def test_manager_hotfix_has_a_new_update_version(self) -> None:
        gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        module = (ROOT / "module/module.prop").read_text(encoding="utf-8")
        self.assertIn('versionName = "0.1.2"', gradle)
        self.assertIn("versionCode = 5", gradle)
        self.assertIn("version=0.1.1", module)
        self.assertIn("versionCode=3", module)

    def test_readme_is_chinese_first_and_hides_git_publish_details(self) -> None:
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        english = (ROOT / "README.en.md").read_text(encoding="utf-8")
        self.assertIn("## 主要功能", readme)
        self.assertIn("[English](README.en.md)", readme)
        self.assertIn("<summary>开发者：直接从 Git 发布</summary>", readme)
        self.assertNotIn("Wei.G ZeroAd is a lightweight root ad blocker", readme)
        self.assertIn("Wei.G ZeroAd is a lightweight root ad blocker", english)
        self.assertFalse((ROOT / "README.zh-CN.md").exists())


if __name__ == "__main__":
    unittest.main()
