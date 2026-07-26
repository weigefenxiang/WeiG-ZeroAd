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
        self.assertIn("bash tests/runtime_module.sh", workflow)

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
        self.assertIn("attachBaseContext(Context base)", activity)
        self.assertIn("base.createConfigurationContext(themed)", activity)
        self.assertIn(".commit();", activity)
        self.assertNotIn("applyOverrideConfiguration", activity)
        self.assertNotIn("setSingleChoiceItems", activity)

    def test_theme_recreation_reuses_status_without_a_root_refresh(self) -> None:
        activity = (ROOT / "app/src/main/java/com/weig/rootad/MainActivity.java").read_text(
            encoding="utf-8"
        )
        self.assertIn("record RetainedState", activity)
        self.assertIn("onRetainNonConfigurationInstance()", activity)
        self.assertIn("skipResumeRefresh = saved.themeRecreation()", activity)
        self.assertIn("showStatus(saved.status())", activity)
        self.assertIn("if (skipResumeRefresh)", activity)
        self.assertIn("themeRecreation = true", activity)
        self.assertIn("if (!destroyed && !isFinishing()) action.run()", activity)

    def test_large_updates_are_streamed_and_temporary_files_are_cleaned(self) -> None:
        client = (ROOT / "app/src/main/java/com/weig/rootad/ReleaseClient.java").read_text(
            encoding="utf-8"
        )
        updater = (ROOT / "app/src/main/java/com/weig/rootad/RuleUpdater.java").read_text(
            encoding="utf-8"
        )
        self.assertIn("downloadToFile(", client)
        self.assertIn("MessageDigest sha256", client)
        self.assertIn('safeName(asset.name) + ".part"', client)
        self.assertNotIn("byte[] bytes = get(new URL(asset.url)", client)
        self.assertIn("try (BufferedReader reader", updater)
        self.assertIn("finally {", updater)
        self.assertIn("deleteTree(extracted)", updater)
        self.assertIn('RootShell.run("rm -rf " + RootShell.quote(remote))', updater)

    def test_core_serializes_operations_and_caches_status_counts(self) -> None:
        core = (ROOT / "module/bin/rulectl").read_text(encoding="utf-8")
        uninstall = (ROOT / "module/uninstall.sh").read_text(encoding="utf-8")
        self.assertIn("acquire_lock()", core)
        self.assertIn("another rule operation is still running", core)
        self.assertIn("ACTIVE_STATS=$STATE_DIR/active.stats", core)
        self.assertIn("new rules failed; previous rules were restored", core)
        self.assertIn('umount "$HOSTS_TARGET"', core)
        self.assertNotIn("keep-user-rules)", core)
        self.assertNotIn("keep_user_rules", uninstall)

    def test_core_awk_never_receives_multiline_v_assignments(self) -> None:
        # Toybox awk on Android rejects -v values containing newlines
        # ("newline in string"); newline-separated lists must use ENVIRON.
        core = (ROOT / "module/bin/rulectl").read_text(encoding="utf-8")
        self.assertIn('ENVIRON["COMPOSE_PROFILES"]', core)
        self.assertIn('ENVIRON["COMPOSE_PACK_ALL"]', core)
        self.assertIn('ENVIRON["COMPOSE_PACK_ENABLED"]', core)
        self.assertIn('ENVIRON["CHECK_RELATIONS"]', core)
        self.assertNotIn("-v profile_list=", core)
        self.assertNotIn("-v pack_all=", core)
        self.assertNotIn("-v pack_enabled=", core)
        self.assertNotIn("-v relations=", core)

    def test_support_section_exposes_safe_logs_and_issue_diagnostics(self) -> None:
        activity = (ROOT / "app/src/main/java/com/weig/rootad/MainActivity.java").read_text(
            encoding="utf-8"
        )
        core = (ROOT / "module/bin/rulectl").read_text(encoding="utf-8")
        crash_log = (
            ROOT / "app/src/main/java/com/weig/rootad/CrashLog.java"
        ).read_text(encoding="utf-8")
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        self.assertIn("查看运行日志", activity)
        self.assertIn("问题反馈 · 提交 GitHub Issue", activity)
        self.assertIn('RootShell.runControl("events")', activity)
        self.assertIn("最近事件 / Recent events", activity)
        self.assertIn("管理器闪退", activity)
        self.assertIn("CrashLog.latest(this)", activity)
        self.assertIn('android:name=".ZeroAdApplication"', manifest)
        self.assertIn("Thread.setDefaultUncaughtExceptionHandler", crash_log)
        self.assertIn("MAX_RECORDS = 10", crash_log)
        self.assertIn("<redacted>", crash_log)
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
        self.assertIn("ACTION_PREFIX + UUID.randomUUID()", installer)
        self.assertIn("new Intent(resultAction).setPackage", installer)

    def test_manager_and_core_fixes_have_new_update_versions(self) -> None:
        gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        module = (ROOT / "module/module.prop").read_text(encoding="utf-8")
        self.assertIn('versionName = "0.1.5"', gradle)
        self.assertIn("versionCode = 8", gradle)
        self.assertIn("version=0.1.3", module)
        self.assertIn("versionCode=5", module)

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
