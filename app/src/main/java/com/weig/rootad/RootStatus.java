package com.weig.rootad;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

record RootStatus(
        boolean installed,
        boolean pendingReboot,
        boolean protection,
        boolean healthy,
        String root,
        String coreVersion,
        int coreVersionCode,
        String cnProfile,
        String globalProfile,
        long ruleVersion,
        boolean rulesDownloaded,
        int running,
        int disabled,
        int customBlock,
        int customAllow,
        int rewardBlock,
        boolean rewardTemporarilyAllowed,
        long rewardExpiresAt,
        Set<String> enabledPacks,
        String error
) {
    static RootStatus read() {
        RootShell.Result result = RootShell.runControl("status");
        if (!result.ok()) {
            RootShell.Result probe = RootShell.run(
                    "if [ -d /data/adb/modules_update/weig_rootad ] || " +
                    "[ -f /data/adb/modules/weig_rootad/update ]; then echo pending; " +
                    "elif [ -d /data/adb/modules/weig_rootad ]; then echo broken; else echo missing; fi");
            boolean pending = probe.ok() && probe.output().contains("pending");
            return empty(pending, result.output());
        }
        return fromResult(result);
    }

    static RootStatus fromResult(RootShell.Result result) {
        if (!result.ok()) return empty(false, result.output());
        try {
            String output = result.output();
            int start = output.lastIndexOf('{');
            if (start < 0) throw new IllegalStateException("Missing JSON status");
            JSONObject json = new JSONObject(output.substring(start));
            Set<String> packs = new HashSet<>();
            String packList = json.optString("enabled_packs", "");
            if (!packList.isBlank()) packs.addAll(Arrays.asList(packList.split(",")));
            String legacy = json.optString("profile", "lean");
            return new RootStatus(true, json.optBoolean("pending_reboot"),
                    json.optBoolean("protection_enabled"), json.optBoolean("healthy"),
                    json.optString("root_impl", "root"),
                    json.optString("core_version", "unknown"),
                    json.optInt("core_version_code"),
                    json.optString("cn_profile", legacy),
                    json.optString("global_profile", "off"),
                    json.optLong("rule_version"), json.optBoolean("rules_downloaded"),
                    json.optInt("running_rules"), json.optInt("disabled_rules"),
                    json.optInt("user_block_rules"), json.optInt("user_allow_rules"),
                    json.optInt("reward_block_rules"),
                    json.optBoolean("reward_temporarily_allowed"),
                    json.optLong("reward_expires_at"), Set.copyOf(packs), "");
        } catch (Exception error) {
            return empty(false, "Invalid core response: " + error.getMessage());
        }
    }

    boolean packEnabled(String id) { return enabledPacks.contains(id); }
    boolean requiresReboot() { return pendingReboot || (installed && !healthy); }

    private static RootStatus empty(boolean pending, String error) {
        return new RootStatus(false, pending, false, false, "unknown", "unknown", 0,
                "lean", "off", 0, false,
                0, 0, 0, 0, 0, false, 0, Set.of(), error);
    }
}
