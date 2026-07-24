from __future__ import annotations

import json
import hashlib
import tempfile
import unittest
import zipfile
from pathlib import Path

from rule_tools.build_module import LIVE_RELEASE_FILES, build, file_sha256
from rule_tools.rules import compile_profiles, domains_from_line, normalize_domain


ROOT = Path(__file__).resolve().parents[1]


class RuleParsingTests(unittest.TestCase):
    def test_normalizes_exact_domains(self) -> None:
        self.assertEqual(normalize_domain("SDK.E.QQ.COM."), "sdk.e.qq.com")
        self.assertIsNone(normalize_domain("*.example.com"))
        self.assertIsNone(normalize_domain("120.232.202.5"))
        self.assertIsNone(normalize_domain("not a domain"))

    def test_reads_supported_formats(self) -> None:
        self.assertEqual(domains_from_line("0.0.0.0 ads.example.com"), {"ads.example.com"})
        self.assertEqual(domains_from_line("||ads.example.com^"), {"ads.example.com"})
        self.assertEqual(domains_from_line("@@||safe.example.com^$important"), {"safe.example.com"})
        self.assertEqual(domains_from_line("# comment"), set())


class GeneratedRuleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        version = int((ROOT / "rules/version.txt").read_text(encoding="utf-8").strip())
        cls.profiles = compile_profiles(ROOT, version)

    def test_profiles_are_monotonic(self) -> None:
        self.assertTrue(self.profiles["balanced"] <= self.profiles["strict"])
        self.assertFalse(self.profiles["reward"] & self.profiles["strict"])
        self.assertFalse(self.profiles["reward"] & self.profiles["balanced"])

    def test_source_is_nontrivial(self) -> None:
        self.assertEqual(len(self.profiles["strict"]), 17_041)
        self.assertEqual(len(self.profiles["reward"]), 74)

    def test_reward_packs_are_disjoint_and_complete(self) -> None:
        manifest = json.loads((ROOT / "rules/generated/manifest.json").read_text(encoding="utf-8"))
        combined: set[str] = set()
        for pack in manifest["packs"]:
            domains = set()
            for line in (ROOT / "rules/generated" / pack["file"]).read_text(encoding="utf-8").splitlines():
                domains.update(domains_from_line(line))
            self.assertFalse(combined & domains)
            combined.update(domains)
        self.assertEqual(combined, self.profiles["reward"])

    def test_manifest_exposes_running_rule_count_contract(self) -> None:
        manifest = json.loads((ROOT / "rules/generated/manifest.json").read_text(encoding="utf-8"))
        self.assertIn("running_rules", manifest["status_fields"])
        self.assertIn("pending_reboot", manifest["status_fields"])
        self.assertIn("core_version", manifest["status_fields"])
        self.assertIn("core_version_code", manifest["status_fields"])
        self.assertIn("rules_downloaded", manifest["status_fields"])
        self.assertIn("reward_temporarily_allowed", manifest["status_fields"])
        self.assertTrue(manifest["manual_rule_control"])


class ModuleBuildTests(unittest.TestCase):
    @staticmethod
    def make_live_rules_zip(path: Path) -> None:
        profile_sets = {
            "cn-lean": {"lean.cn"},
            "cn-balanced": {"balanced.cn", "lean.cn"},
            "cn-strict": {"balanced.cn", "lean.cn", "strict.cn"},
            "global-lean": {"lean.global"},
            "global-balanced": {"balanced.global", "lean.global"},
            "global-strict": {"balanced.global", "lean.global", "strict.global"},
        }
        pack_sets = {
            "reward-tencent.domains": {"reward.tencent.example"},
            "reward-wechat.domains": {"reward.wechat.example"},
            "reward-short-video.domains": {"reward.video.example"},
            "reward-other.domains": {"reward.other.example"},
        }
        blobs: dict[str, bytes] = {}
        profiles: dict[str, dict[str, object]] = {"cn": {}, "global": {}}
        for name, domains in profile_sets.items():
            domain_name = f"{name}.domains"
            hosts_name = f"{name}.hosts"
            blobs[domain_name] = ("# test\n" + "\n".join(sorted(domains)) + "\n").encode()
            blobs[hosts_name] = ("# test\n" + "\n".join(
                f"0.0.0.0 {domain}" for domain in sorted(domains)
            ) + "\n").encode()
            region, level = name.split("-", 1)
            profiles[region][level] = {
                "rules": len(domains),
                "domains_file": domain_name,
                "hosts_file": hosts_name,
                "domains_sha256": hashlib.sha256(blobs[domain_name]).hexdigest(),
                "hosts_sha256": hashlib.sha256(blobs[hosts_name]).hexdigest(),
            }
        reward = set().union(*pack_sets.values())
        blobs["reward-ads.domains"] = ("# test\n" + "\n".join(sorted(reward)) + "\n").encode()
        packs = []
        ids = ["reward.tencent", "reward.wechat", "reward.short-video", "reward.other"]
        for pack_id, (name, domains) in zip(ids, pack_sets.items()):
            blobs[name] = ("# test\n" + "\n".join(sorted(domains)) + "\n").encode()
            packs.append({
                "id": pack_id,
                "file": name,
                "rules": len(domains),
                "domains_sha256": hashlib.sha256(blobs[name]).hexdigest(),
            })
        manifest = {
            "schema": 3,
            "version": 2026072301,
            "profiles": profiles,
            "reward": {
                "rules": len(reward),
                "domains_file": "reward-ads.domains",
                "domains_sha256": hashlib.sha256(blobs["reward-ads.domains"]).hexdigest(),
            },
            "packs": packs,
        }
        blobs["manifest.json"] = json.dumps(manifest).encode()
        blobs["packs.json"] = json.dumps({"schema": 1, "packs": packs}).encode()
        blobs["health-summary.json"] = b'{"schema":1}'
        self_names = set(blobs)
        if self_names != LIVE_RELEASE_FILES:
            raise AssertionError((self_names, LIVE_RELEASE_FILES))
        with zipfile.ZipFile(path, "w") as archive:
            for name, data in blobs.items():
                archive.writestr(name, data)

    def test_module_zip_is_reproducible_and_executable(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            first = Path(temp_dir) / "first.zip"
            second = Path(temp_dir) / "second.zip"
            build(ROOT, first)
            build(ROOT, second)
            self.assertEqual(file_sha256(first), file_sha256(second))

            with zipfile.ZipFile(first) as archive:
                names = archive.namelist()
                self.assertTrue(all(not name.startswith("/") and ".." not in Path(name).parts for name in names))
                self.assertNotIn("manager/WeiG-ZeroAd-Manager.apk", names)
                self.assertIn("rules/cn-lean.domains", names)
                self.assertIn("rules/global-strict.domains", names)
                self.assertIn("rules/reward-ads.domains", names)
                self.assertEqual((archive.getinfo("bin/rulectl").external_attr >> 16) & 0o777, 0o755)
                self.assertEqual((archive.getinfo("module.prop").external_attr >> 16) & 0o777, 0o644)

    def test_all_in_one_zip_embeds_manager_without_changing_core_layout(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            manager = Path(temp_dir) / "manager.apk"
            manager.write_bytes(b"signed-apk-placeholder")
            output = Path(temp_dir) / "all-in-one.zip"
            build(ROOT, output, manager)

            with zipfile.ZipFile(output) as archive:
                self.assertEqual(
                    archive.read("manager/WeiG-ZeroAd-Manager.apk"),
                    b"signed-apk-placeholder",
                )
                self.assertIn("module.prop", archive.namelist())
                self.assertIn("system/etc/hosts", archive.namelist())

    def test_module_embeds_validated_live_six_profile_rules(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            rules_zip = Path(temp_dir) / "rules.zip"
            output = Path(temp_dir) / "core.zip"
            self.make_live_rules_zip(rules_zip)
            build(ROOT, output, rules_zip=rules_zip)
            with zipfile.ZipFile(output) as archive:
                self.assertIn(b"lean.cn", archive.read("rules/cn-lean.domains"))
                manifest = json.loads(archive.read("rules/manifest.json"))
                self.assertEqual(manifest["schema"], 3)

    def test_invalid_live_rules_can_fall_back_to_owned_base(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            invalid_rules = Path(temp_dir) / "invalid-rules.zip"
            output = Path(temp_dir) / "core.zip"
            with zipfile.ZipFile(invalid_rules, "w") as archive:
                archive.writestr("unexpected.txt", "broken update")

            mode = build(
                ROOT,
                output,
                rules_zip=invalid_rules,
                allow_rules_fallback=True,
            )

            self.assertEqual(mode, "offline-fallback")
            with zipfile.ZipFile(output) as archive:
                manifest = json.loads(archive.read("rules/manifest.json"))
                self.assertEqual(manifest["schema"], 2)
                cn_lean = archive.read("rules/cn-lean.domains").decode("utf-8")
                self.assertIn("0127.adsame.com", cn_lean)
                self.assertNotIn("adsmind.gdtimg.com", cn_lean)


if __name__ == "__main__":
    unittest.main()
