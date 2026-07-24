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

    def test_rule_update_requires_check_and_user_confirmation(self) -> None:
        activity = (ROOT / "app/src/main/java/com/weig/rootad/MainActivity.java").read_text(
            encoding="utf-8"
        )
        updater = (ROOT / "app/src/main/java/com/weig/rootad/RuleUpdater.java").read_text(
            encoding="utf-8"
        )
        self.assertIn("检查规则更新", activity)
        self.assertIn("RuleUpdater.checkLatest()", activity)
        self.assertIn("setPositiveButton", activity)
        self.assertIn("RuleUpdater.install(this, available)", activity)
        self.assertIn("rulesDownloaded && available.version() <= currentVersion", activity)
        self.assertLess(activity.index("RuleUpdater.checkLatest()"),
                        activity.index("RuleUpdater.install(this, available)"))
        self.assertIn("Release tag and rule manifest version differ", updater)

    def test_status_contract_supports_domestic_off_and_downloaded_rules(self) -> None:
        schema = json.loads((ROOT / "api/status.schema.json").read_text(encoding="utf-8"))
        self.assertIn("off", schema["properties"]["cn_profile"]["enum"])
        self.assertIn("rules_downloaded", schema["required"])


if __name__ == "__main__":
    unittest.main()
