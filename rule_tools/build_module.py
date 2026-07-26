from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

from rule_tools.rules import DOMAIN_RE


TEXT_SUFFIXES = {".sh", ".prop", ".txt", ".hosts", ".domains", ".json"}
EXECUTABLE_NAMES = {"action.sh", "customize.sh", "service.sh", "uninstall.sh", "update-binary", "rulectl"}
ZIP_TIMESTAMP = (2026, 1, 1, 0, 0, 0)
PROFILE_NAMES = (
    "cn-lean", "cn-balanced", "cn-strict",
    "global-lean", "global-balanced", "global-strict",
)
REWARD_FILES = (
    "reward-ads.domains", "reward-tencent.domains", "reward-wechat.domains",
    "reward-short-video.domains", "reward-other.domains",
)
LIVE_RUNTIME_FILES = tuple(f"{name}.domains" for name in PROFILE_NAMES) + REWARD_FILES + (
    "manifest.json", "packs.json",
)
LIVE_RELEASE_FILES = set(LIVE_RUNTIME_FILES) | {
    *(f"{name}.hosts" for name in PROFILE_NAMES), "health-summary.json",
}


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def normalize_text_files(root: Path) -> None:
    for path in root.rglob("*"):
        if path.is_file() and (path.suffix in TEXT_SUFFIXES or path.name == "update-binary"):
            data = path.read_bytes()
            normalized = data.replace(b"\r\n", b"\n")
            if normalized != data:
                path.write_bytes(normalized)


def exact_domains(data: bytes, name: str) -> set[str]:
    domains: set[str] = set()
    for line in data.decode("utf-8-sig").splitlines():
        value = line.strip()
        if not value or value.startswith("#"):
            continue
        if len(value) > 253 or not DOMAIN_RE.fullmatch(value):
            raise SystemExit(f"Invalid exact domain in {name}: {value[:80]}")
        if value in domains:
            raise SystemExit(f"Duplicate exact domain in {name}: {value}")
        domains.add(value)
    if len(domains) > 500_000:
        raise SystemExit(f"Too many domains in {name}")
    return domains


def install_live_rules(staging: Path, rules_zip: Path) -> None:
    if not rules_zip.is_file():
        raise SystemExit(f"Rules ZIP not found: {rules_zip}")
    with zipfile.ZipFile(rules_zip) as archive:
        infos = archive.infolist()
        names = {info.filename for info in infos}
        if len(names) != len(infos) or names != LIVE_RELEASE_FILES:
            raise SystemExit("Live rules ZIP has an unexpected or duplicate file set")
        total = 0
        blobs: dict[str, bytes] = {}
        for info in infos:
            mode = (info.external_attr >> 16) & 0o170000
            if info.is_dir() or mode == 0o120000 or info.file_size > 64 * 1024 * 1024:
                raise SystemExit(f"Unsafe live rules entry: {info.filename}")
            total += info.file_size
            if total > 160 * 1024 * 1024:
                raise SystemExit("Expanded live rules exceed 160 MiB")
            blobs[info.filename] = archive.read(info)

    try:
        manifest = json.loads(blobs["manifest.json"].decode("utf-8"))
        packs_file = json.loads(blobs["packs.json"].decode("utf-8"))
        json.loads(blobs["health-summary.json"].decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise SystemExit(f"Invalid live rules metadata: {error}") from error
    if manifest.get("schema") != 3 or not isinstance(manifest.get("version"), int):
        raise SystemExit("Live rules manifest must use schema 3 and a numeric version")

    domains_by_name: dict[str, set[str]] = {}
    for profile in PROFILE_NAMES:
        region, level = profile.split("-", 1)
        metadata = manifest["profiles"][region][level]
        domain_name = f"{profile}.domains"
        hosts_name = f"{profile}.hosts"
        if metadata.get("domains_file") != domain_name or metadata.get("hosts_file") != hosts_name:
            raise SystemExit(f"Manifest file mismatch for {profile}")
        domains = exact_domains(blobs[domain_name], domain_name)
        if metadata.get("rules") != len(domains):
            raise SystemExit(f"Manifest count mismatch for {profile}")
        if metadata.get("domains_sha256") != hashlib.sha256(blobs[domain_name]).hexdigest():
            raise SystemExit(f"Manifest checksum mismatch for {domain_name}")
        if metadata.get("hosts_sha256") != hashlib.sha256(blobs[hosts_name]).hexdigest():
            raise SystemExit(f"Manifest checksum mismatch for {hosts_name}")
        domains_by_name[profile] = domains

    if not (
        domains_by_name["cn-lean"] <= domains_by_name["cn-balanced"]
        <= domains_by_name["cn-strict"]
    ) or not (
        domains_by_name["global-lean"] <= domains_by_name["global-balanced"]
        <= domains_by_name["global-strict"]
    ):
        raise SystemExit("Live rule profiles are not monotonic")
    if domains_by_name["cn-strict"] & domains_by_name["global-strict"]:
        raise SystemExit("Live domestic and global rules overlap")

    reward = exact_domains(blobs["reward-ads.domains"], "reward-ads.domains")
    reward_meta = manifest["reward"]
    if reward_meta.get("rules") != len(reward) or reward_meta.get(
        "domains_sha256"
    ) != hashlib.sha256(blobs["reward-ads.domains"]).hexdigest():
        raise SystemExit("Live reward rules do not match the manifest")
    if any(domains & reward for domains in domains_by_name.values()):
        raise SystemExit("Reward rules overlap a normal live profile")

    manifest_packs = manifest.get("packs")
    if not isinstance(manifest_packs, list):
        raise SystemExit("Live rules manifest packs are invalid")
    if packs_file.get("packs") != manifest_packs:
        raise SystemExit("packs.json does not match manifest.json")
    pack_union: set[str] = set()
    expected_pack_files = set(REWARD_FILES[1:])
    actual_pack_files: set[str] = set()
    for metadata in manifest_packs:
        name = metadata.get("file")
        if name not in expected_pack_files or name in actual_pack_files:
            raise SystemExit("Unknown or duplicate reward pack")
        actual_pack_files.add(name)
        domains = exact_domains(blobs[name], name)
        if pack_union & domains:
            raise SystemExit("Reward packs overlap")
        pack_union.update(domains)
        if metadata.get("rules") != len(domains) or metadata.get(
            "domains_sha256"
        ) != hashlib.sha256(blobs[name]).hexdigest():
            raise SystemExit(f"Reward pack metadata mismatch: {name}")
    if actual_pack_files != expected_pack_files or pack_union != reward:
        raise SystemExit("Reward packs are incomplete")

    rules_dir = staging / "rules"
    for name in LIVE_RUNTIME_FILES:
        (rules_dir / name).write_bytes(blobs[name])


def install_offline_rules(staging: Path, generated: Path) -> None:
    rules_dir = staging / "rules"
    for name in (
        "manifest.json", "packs.json",
        "reward-tencent.domains", "reward-wechat.domains",
        "reward-short-video.domains", "reward-other.domains",
    ):
        shutil.copy2(generated / name, rules_dir / name)
    # Safe first-install fallback built only from Wei.G 20260723 and local overrides.
    shutil.copy2(generated / "balanced.domains", rules_dir / "cn-lean.domains")
    shutil.copy2(generated / "balanced.domains", rules_dir / "cn-balanced.domains")
    shutil.copy2(generated / "strict.domains", rules_dir / "cn-strict.domains")
    for name in ("global-lean.domains", "global-balanced.domains", "global-strict.domains"):
        (rules_dir / name).write_text(
            "# Offline fallback; update rules to install this global profile.\n",
            encoding="utf-8",
            newline="\n",
        )
    shutil.copy2(generated / "reward.domains", rules_dir / "reward-ads.domains")


def fallback_warning(error: BaseException) -> None:
    detail = str(error).replace("\r", " ").replace("\n", " ") or type(error).__name__
    message = (
        "Live rule update was unavailable or invalid; packaging the verified "
        f"Wei.G 20260723 offline fallback instead. Reason: {detail}"
    )
    print(f"warning: {message}", file=sys.stderr, flush=True)
    if os.environ.get("GITHUB_ACTIONS") == "true":
        print(f"::warning title=Using offline rule fallback::{message}", flush=True)


def build(
    root: Path,
    output: Path,
    manager_apk: Path | None = None,
    rules_zip: Path | None = None,
    allow_rules_fallback: bool = False,
) -> str:
    module = root / "module"
    generated = root / "rules/generated"
    required = [generated / name for name in (
        "strict.domains", "balanced.domains", "strict.hosts", "reward.domains",
        "manifest.json", "packs.json",
        "reward-tencent.domains", "reward-wechat.domains", "reward-short-video.domains",
        "reward-other.domains",
    )]
    missing = [str(path) for path in required if not path.exists()]
    if missing:
        raise SystemExit(f"Run build_rules first; missing: {', '.join(missing)}")

    with tempfile.TemporaryDirectory(prefix="weig-zeroad-module-") as temp_dir:
        staging = Path(temp_dir) / "module"
        shutil.copytree(module, staging)
        (staging / "rules").mkdir(parents=True, exist_ok=True)
        rules_mode = "offline-fallback"
        if rules_zip is not None:
            try:
                install_live_rules(staging, rules_zip)
                rules_mode = "verified-live-release"
            except (Exception, SystemExit) as error:
                if not allow_rules_fallback:
                    raise
                # Overwrites every runtime file in case copying the live set
                # was interrupted partway through.
                install_offline_rules(staging, generated)
                fallback_warning(error)
        else:
            install_offline_rules(staging, generated)
        shutil.copy2(generated / "strict.hosts", staging / "system/etc/hosts")
        if manager_apk is not None:
            if not manager_apk.is_file():
                raise SystemExit(f"Manager APK not found: {manager_apk}")
            manager_dir = staging / "manager"
            manager_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy2(manager_apk, manager_dir / "WeiG-ZeroAd-Manager.apk")
        normalize_text_files(staging)

        output.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for path in sorted(staging.rglob("*")):
                if path.is_file() and path.name != ".gitkeep":
                    relative = path.relative_to(staging).as_posix()
                    mode = 0o755 if path.name in EXECUTABLE_NAMES else 0o644
                    info = zipfile.ZipInfo(relative, ZIP_TIMESTAMP)
                    info.create_system = 3
                    info.external_attr = mode << 16
                    info.compress_type = zipfile.ZIP_DEFLATED
                    archive.writestr(info, path.read_bytes(), compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)

    checksums = output.parent / "SHA256SUMS"
    checksums.write_text(
        f"{file_sha256(output)}  {output.name}\n", encoding="utf-8", newline="\n"
    )
    return rules_mode


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build a WeiG ZeroAd core-only or manager-inclusive module ZIP"
    )
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument(
        "--output", type=Path, default=Path("dist/WeiG-ZeroAd-v0.1.4-core-only.zip")
    )
    parser.add_argument(
        "--manager-apk", type=Path,
        help="Embed this signed manager APK and let customize.sh install/update it",
    )
    parser.add_argument(
        "--rules-zip", type=Path,
        help="Embed the latest validated data-only WeiG-ZeroAd-Rules release",
    )
    parser.add_argument(
        "--allow-rules-fallback",
        action="store_true",
        help="Use the bundled Wei.G 20260723 base if the live rules ZIP is invalid",
    )
    args = parser.parse_args()
    root = args.root.resolve()
    output = args.output if args.output.is_absolute() else root / args.output
    manager_apk = args.manager_apk
    if manager_apk is not None and not manager_apk.is_absolute():
        manager_apk = root / manager_apk
    rules_zip = args.rules_zip
    if rules_zip is not None and not rules_zip.is_absolute():
        rules_zip = root / rules_zip
    rules_mode = build(
        root,
        output,
        manager_apk,
        rules_zip,
        allow_rules_fallback=args.allow_rules_fallback,
    )
    print(json.dumps({
        "module": str(output),
        "variant": "all-in-one" if manager_apk else "core-only",
        "rules_mode": rules_mode,
        "live_rules": str(rules_zip) if rules_mode == "verified-live-release" else None,
        "sha256": file_sha256(output),
    }))


if __name__ == "__main__":
    main()
