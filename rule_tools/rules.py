from __future__ import annotations

import hashlib
import ipaddress
import json
import re
from pathlib import Path
from typing import Iterable


BLOCK_IPS = {"0.0.0.0", "127.0.0.1", "::", "::1"}
ADBLOCK_DOMAIN_RE = re.compile(r"^(?:@@)?\|\|([A-Za-z0-9._-]+)\^")
DOMAIN_RE = re.compile(r"^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+$")

REWARD_PACKS = (
    {
        "id": "reward.tencent",
        "file": "reward-tencent.domains",
        "title_en": "Tencent / QQ reward ads",
        "title_zh": "腾讯 / QQ 奖励广告",
    },
    {
        "id": "reward.wechat",
        "file": "reward-wechat.domains",
        "title_en": "WeChat reward ads",
        "title_zh": "微信奖励广告",
    },
    {
        "id": "reward.short-video",
        "file": "reward-short-video.domains",
        "title_en": "Short-video reward ads",
        "title_zh": "短视频奖励广告",
    },
    {
        "id": "reward.other",
        "file": "reward-other.domains",
        "title_en": "Other reward ads",
        "title_zh": "其他奖励广告",
    },
)


def normalize_domain(value: str) -> str | None:
    domain = value.strip().lower().rstrip(".")
    if not domain or len(domain) > 253 or not DOMAIN_RE.fullmatch(domain):
        return None
    try:
        ipaddress.ip_address(domain)
        return None
    except ValueError:
        pass
    if any(len(label) > 63 for label in domain.split(".")):
        return None
    return domain


def domains_from_line(line: str) -> set[str]:
    stripped = line.strip()
    if not stripped or stripped.startswith(("#", "!")):
        return set()

    adblock_match = ADBLOCK_DOMAIN_RE.match(stripped)
    if adblock_match:
        domain = normalize_domain(adblock_match.group(1))
        return {domain} if domain else set()

    tokens = stripped.split()
    if tokens and tokens[0] in BLOCK_IPS:
        domains = {normalize_domain(token) for token in tokens[1:]}
        return {domain for domain in domains if domain}

    domain = normalize_domain(tokens[0]) if len(tokens) == 1 else None
    return {domain} if domain else set()


def load_domains(path: Path) -> set[str]:
    if not path.exists():
        return set()
    domains: set[str] = set()
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        domains.update(domains_from_line(line))
    return domains


def write_domains(path: Path, domains: Iterable[str], header: Iterable[str] = ()) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = [f"# {line}" for line in header]
    lines.extend(sorted(set(domains)))
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def write_hosts(path: Path, domains: Iterable[str], profile: str, version: int) -> None:
    normalized = sorted(set(domains))
    lines = [
        "# Wei.G ZeroAd generated hosts file. DO NOT EDIT.",
        f"# profile={profile}",
        f"# rule_version={version}",
        f"# rule_count={len(normalized)}",
        "127.0.0.1 localhost",
        "::1 localhost ip6-localhost ip6-loopback",
    ]
    lines.extend(f"0.0.0.0 {domain}" for domain in normalized)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8", newline="\n")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def reward_pack_id(domain: str) -> str:
    if domain.startswith(("wxsns", "wxa.", "wximg.", "wxsmw.")) or ".wxs.qq.com" in domain:
        return "reward.wechat"
    if any(part in domain for part in (
        "pangolin-sdk", "kuaishou.com", "gifshow.com", "snssdk.com", "adukwai.com"
    )):
        return "reward.short-video"
    if any(part in domain for part in (
        ".gdt.qq.com", ".e.qq.com", ".mdt.qq.com", ".gtimg.cn", ".gdtimg.com",
        "tencentmusic.com", ".y.qq.com", ".tc.qq.com",
    )):
        return "reward.tencent"
    return "reward.other"


def compile_profiles(root: Path, version: int) -> dict[str, set[str]]:
    rules = root / "rules"
    vendor = load_domains(rules / "vendor/wei.G/260723.txt")
    local_block = load_domains(rules / "local/block.txt")
    local_allow = load_domains(rules / "local/allow.txt")
    reward = load_domains(rules / "vendor/wei.G/reward-ads.domains")

    # Reward endpoints are opt-in blocking packs. They must never leak into either
    # base profile, even when a future baseline source adds them again.
    strict = ((vendor | local_block) - local_allow) - reward
    # These two files are only an offline compatibility fallback bundled with
    # the core. The six live profiles come from WeiG-ZeroAd-Rules.
    balanced = set(strict)
    profiles = {"strict": strict, "balanced": balanced}

    if strict & reward or balanced & reward:
        raise ValueError("reward domains must be disjoint from strict and balanced")
    if not balanced <= strict:
        raise ValueError("balanced profile must be a subset of strict")

    generated = rules / "generated"
    generated.mkdir(parents=True, exist_ok=True)
    for name, domains in profiles.items():
        write_domains(
            generated / f"{name}.domains",
            domains,
            header=(
                "Generated by rule_tools/build_rules.py; do not edit.",
                f"profile={name}",
                f"rule_version={version}",
                f"rule_count={len(domains)}",
            ),
        )
        write_hosts(generated / f"{name}.hosts", domains, name, version)

    stale_reward_hosts = generated / "reward.hosts"
    if stale_reward_hosts.exists():
        stale_reward_hosts.unlink()

    write_domains(
        generated / "reward.domains",
        reward,
        header=(
            "Optional reward-ad blocking domains; excluded from every base profile.",
            f"rule_version={version}",
            f"rule_count={len(reward)}",
        ),
    )

    reward_pack_sets: dict[str, set[str]] = {pack["id"]: set() for pack in REWARD_PACKS}
    for domain in reward:
        reward_pack_sets[reward_pack_id(domain)].add(domain)
    for pack in REWARD_PACKS:
        domains = reward_pack_sets[pack["id"]]
        write_domains(
            generated / pack["file"],
            domains,
            header=(
                "Optional reward-ad blocking pack; enabled by default.",
                f"pack_id={pack['id']}",
                f"rule_version={version}",
                f"rule_count={len(domains)}",
            ),
        )

    all_domains = sorted(strict | reward)
    index_lines = ["domain\tstrict\tbalanced\treward_block\tsource"]
    for domain in all_domains:
        index_lines.append(
            "\t".join(
                (
                    domain,
                    "1" if domain in strict else "0",
                    "1" if domain in balanced else "0",
                    "1" if domain in reward else "0",
                    "Wei.G" if domain in vendor or domain in reward else "local",
                )
            )
        )
    (generated / "index.tsv").write_text(
        "\n".join(index_lines) + "\n", encoding="utf-8", newline="\n"
    )

    manifest = {
        "schema": 2,
        "version": version,
        "profiles": {
            name: {
                "rules": len(domains),
                "domains_file": f"{name}.domains",
                "hosts_file": f"{name}.hosts",
            }
            for name, domains in profiles.items()
        },
        "reward": {
            "rules": len(reward),
            "domains_file": "reward.domains",
        },
        "packs": [
            {
                "id": pack["id"],
                "type": "reward_block",
                "file": pack["file"],
                "title_en": pack["title_en"],
                "title_zh": pack["title_zh"],
                "rules": len(reward_pack_sets[pack["id"]]),
                "default_enabled": True,
            }
            for pack in REWARD_PACKS
        ],
        "build": {
            "vendor_rules": len(vendor),
            "reward_removed_from_strict": len((vendor | local_block) & reward),
            "strict_reward_overlap": len(strict & reward),
            "balanced_reward_overlap": len(balanced & reward),
        },
        "manual_rule_control": True,
        "reward_default_enabled": True,
        "status_fields": [
            "pending_reboot",
            "core_version",
            "core_version_code",
            "rules_downloaded",
            "compiled_rules",
            "running_rules",
            "disabled_rules",
            "user_block_rules",
            "user_allow_rules",
            "reward_block_rules",
            "reward_temporarily_allowed",
            "reward_expires_at",
            "enabled_packs",
        ],
    }
    manifest_path = generated / "manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )

    for name in profiles:
        manifest["profiles"][name]["domains_sha256"] = sha256_file(
            generated / f"{name}.domains"
        )
        manifest["profiles"][name]["hosts_sha256"] = sha256_file(
            generated / f"{name}.hosts"
        )
    manifest["reward"]["domains_sha256"] = sha256_file(generated / "reward.domains")
    for pack in manifest["packs"]:
        pack["domains_sha256"] = sha256_file(generated / pack["file"])

    (generated / "packs.json").write_text(
        json.dumps({"schema": 1, "version": version, "packs": manifest["packs"]},
                   ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    return {**profiles, "reward": reward}
