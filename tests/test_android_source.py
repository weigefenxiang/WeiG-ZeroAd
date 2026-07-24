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
        self.assertIn("更新管理器", activity)
        self.assertIn("更新核心", activity)
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


if __name__ == "__main__":
    unittest.main()
