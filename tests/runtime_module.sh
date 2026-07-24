#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
TEMP_DIR=$(mktemp -d)
trap 'rm -rf "$TEMP_DIR"' EXIT

MODULE_DIR="$TEMP_DIR/module"
mkdir -p "$MODULE_DIR/bin" "$MODULE_DIR/rules" "$TEMP_DIR/data" "$TEMP_DIR/system"
cp "$ROOT/module/bin/rulectl" "$MODULE_DIR/bin/rulectl"
cp "$ROOT/rules/generated/balanced.domains" "$MODULE_DIR/rules/cn-lean.domains"
cp "$ROOT/rules/generated/balanced.domains" "$MODULE_DIR/rules/cn-balanced.domains"
cp "$ROOT/rules/generated/strict.domains" "$MODULE_DIR/rules/cn-strict.domains"
printf '# test global lean\nglobal-lean.example\n' > "$MODULE_DIR/rules/global-lean.domains"
printf '# test global balanced\nglobal-lean.example\nglobal-balanced.example\n' > "$MODULE_DIR/rules/global-balanced.domains"
printf '# test global strict\nglobal-lean.example\nglobal-balanced.example\nglobal-strict.example\n' > "$MODULE_DIR/rules/global-strict.domains"
cp "$ROOT/rules/generated/reward.domains" "$MODULE_DIR/rules/reward-ads.domains"
cp "$ROOT/rules/generated/reward-tencent.domains" "$MODULE_DIR/rules/reward-tencent.domains"
cp "$ROOT/rules/generated/reward-wechat.domains" "$MODULE_DIR/rules/reward-wechat.domains"
cp "$ROOT/rules/generated/reward-short-video.domains" "$MODULE_DIR/rules/reward-short-video.domains"
cp "$ROOT/rules/generated/reward-other.domains" "$MODULE_DIR/rules/reward-other.domains"
cp "$ROOT/rules/generated/manifest.json" "$MODULE_DIR/rules/manifest.json"
cp "$ROOT/rules/generated/packs.json" "$MODULE_DIR/rules/packs.json"
touch "$TEMP_DIR/system/hosts"

export ADSHIELD_TEST_MODE=1
export ADSHIELD_TEST_DATA_DIR="$TEMP_DIR/data"
export ADSHIELD_TEST_HOSTS_TARGET="$TEMP_DIR/system/hosts"

RULECTL="$MODULE_DIR/bin/rulectl"

# Runtime reward exclusion remains a second line of defense.
echo "wxsnsad.tc.qq.com" >> "$MODULE_DIR/rules/cn-lean.domains"

bash "$RULECTL" boot
status=$(bash "$RULECTL" status)
echo "$status" | grep -q '"protection_enabled":true'
echo "$status" | grep -q '"pending_reboot":false'
echo "$status" | grep -q '"healthy":true'
echo "$status" | grep -q '"cn_profile":"lean"'
echo "$status" | grep -q '"global_profile":"off"'
echo "$status" | grep -q '"rules_downloaded":false'
echo "$status" | grep -q '"compiled_rules":17041'
echo "$status" | grep -q '"running_rules":17115'
echo "$status" | grep -q '"reward_block_rules":74'

bash "$RULECTL" cn-profile off >/dev/null
status=$(bash "$RULECTL" status)
echo "$status" | grep -q '"cn_profile":"off"'
echo "$status" | grep -q '"compiled_rules":0'
echo "$status" | grep -q '"running_rules":74'
bash "$RULECTL" cn-profile on >/dev/null
status=$(bash "$RULECTL" status)
echo "$status" | grep -q '"cn_profile":"lean"'
echo "$status" | grep -q '"running_rules":17115'

bash "$RULECTL" domain-disable 0.r.msn.com >/dev/null
status=$(bash "$RULECTL" status)
echo "$status" | grep -q '"running_rules":17114'
echo "$status" | grep -q '"disabled_rules":1'

bash "$RULECTL" domain-enable 0.r.msn.com >/dev/null
bash "$RULECTL" protection-off >/dev/null
status=$(bash "$RULECTL" status)
echo "$status" | grep -q '"protection_enabled":false'
echo "$status" | grep -q '"running_rules":0'

bash "$RULECTL" protection-on >/dev/null
bash "$RULECTL" cn-profile balanced >/dev/null
bash "$RULECTL" global-profile lean >/dev/null
status=$(bash "$RULECTL" status)
echo "$status" | grep -q '"cn_profile":"balanced"'
echo "$status" | grep -q '"global_profile":"lean"'
echo "$status" | grep -q '"running_rules":17116'

bash "$RULECTL" reward 1 >/dev/null
status=$(bash "$RULECTL" status)
echo "$status" | grep -q '"reward_temporarily_allowed":true'
echo "$status" | grep -q '"running_rules":17042'
bash "$RULECTL" reward-stop >/dev/null
status=$(bash "$RULECTL" status)
echo "$status" | grep -q '"reward_temporarily_allowed":false'
echo "$status" | grep -q '"running_rules":17116'

bash "$RULECTL" pack-disable reward.wechat >/dev/null
status=$(bash "$RULECTL" status)
echo "$status" | grep -q '"reward_block_rules":66'
echo "$status" | grep -q '"running_rules":17108'

bash "$RULECTL" block-add custom-ad.example >/dev/null
bash "$RULECTL" list-block | grep -qx 'custom-ad.example'
status=$(bash "$RULECTL" status)
echo "$status" | grep -q '"running_rules":17109'

bash "$RULECTL" allow-add custom-ad.example >/dev/null
bash "$RULECTL" list-allow | grep -qx 'custom-ad.example'
status=$(bash "$RULECTL" status)
echo "$status" | grep -q '"running_rules":17108'

echo "module runtime simulation passed"
