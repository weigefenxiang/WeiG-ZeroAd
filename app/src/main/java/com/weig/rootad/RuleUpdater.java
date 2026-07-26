package com.weig.rootad;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class RuleUpdater {
    record Result(long version, String statusJson) {}
    record Available(long version, ReleaseClient.Asset asset) {}

    private static final Pattern RULE_TAG = Pattern.compile("rules-([0-9]{10})");
    // Hoisted out of the per-line loop: String.matches recompiles the pattern on
    // every call, which cost one compilation per domain across all eleven files.
    private static final Pattern EXACT_DOMAIN = Pattern.compile(
            "[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");

    private static final List<String> PROFILE_FILES = List.of(
            "cn-lean.domains", "cn-balanced.domains", "cn-strict.domains",
            "global-lean.domains", "global-balanced.domains", "global-strict.domains");
    private static final Set<String> REWARD_FILES = Set.of(
            "reward-ads.domains", "reward-tencent.domains", "reward-wechat.domains",
            "reward-short-video.domains", "reward-other.domains");
    private static final Set<String> RUNTIME_FILES;
    private static final Set<String> ARCHIVE_FILES;

    static {
        Set<String> runtime = new HashSet<>();
        runtime.addAll(PROFILE_FILES);
        runtime.addAll(REWARD_FILES);
        runtime.add("manifest.json");
        runtime.add("packs.json");
        RUNTIME_FILES = Set.copyOf(runtime);

        Set<String> archive = new HashSet<>(runtime);
        archive.add("health-summary.json");
        for (String profile : PROFILE_FILES) {
            archive.add(profile.replace(".domains", ".hosts"));
        }
        ARCHIVE_FILES = Set.copyOf(archive);
    }

    private RuleUpdater() {}

    static Available checkLatest() throws Exception {
        ReleaseClient.Match match = ReleaseClient.latestWithAsset(
                BuildConfig.RULES_REPOSITORY, "zeroad-rules", ".zip");
        Matcher matcher = RULE_TAG.matcher(match.release().tag());
        if (!matcher.matches()) throw new SecurityException("Invalid rules release tag");
        long version = Long.parseLong(matcher.group(1));
        if (version < 1) throw new SecurityException("Invalid rule version");
        return new Available(version, match.asset());
    }

    static Result install(Context context, Available available) throws Exception {
        File archive = null;
        File extracted = new File(context.getCacheDir(), "zeroad-rules-staging");
        String remote = null;
        try {
            archive = ReleaseClient.download(
                    context, available.asset(), 96L * 1024 * 1024);
            deleteTree(extracted);
            if (!extracted.mkdir())
                throw new IllegalStateException("Cannot create rule staging directory");
            extractDataOnly(archive, extracted);

            File manifestFile = new File(extracted, "manifest.json");
            // The aggregate archive cap alone would still let a hostile manifest
            // reach ~160 MB; parse only a plausibly sized one.
            if (manifestFile.length() > 1024 * 1024)
                throw new SecurityException("Rule manifest is too large");
            JSONObject manifest = new JSONObject(new String(
                    Files.readAllBytes(manifestFile.toPath()),
                    StandardCharsets.UTF_8));
            if (manifest.getInt("schema") != 3)
                throw new SecurityException("Unsupported rule schema");
            long version = manifest.getLong("version");
            if (version != available.version())
                throw new SecurityException("Release tag and rule manifest version differ");

            JSONObject profiles = manifest.getJSONObject("profiles");
            Set<String> cnStrict = validateRegion(extracted, profiles, "cn", "Domestic");
            Set<String> globalStrict = validateRegion(extracted, profiles, "global", "Global");
            if (!Collections.disjoint(cnStrict, globalStrict))
                throw new SecurityException("Domestic and global profiles overlap");

            Set<String> reward = validateFile(extracted, "reward-ads.domains",
                    manifest.getJSONObject("reward"));
            // The profiles are monotonic, so a reward domain absent from both
            // strict sets cannot appear in lean or balanced either. Two checks
            // therefore cover all six profiles.
            if (!Collections.disjoint(cnStrict, reward))
                throw new SecurityException("Reward rules overlap the domestic profiles");
            if (!Collections.disjoint(globalStrict, reward))
                throw new SecurityException("Reward rules overlap the global profiles");

            Set<String> packUnion = new HashSet<>();
            JSONArray packs = manifest.getJSONArray("packs");
            for (int index = 0; index < packs.length(); index++) {
                JSONObject pack = packs.getJSONObject(index);
                String name = pack.getString("file");
                if (!REWARD_FILES.contains(name) || name.equals("reward-ads.domains"))
                    throw new SecurityException("Unknown reward pack file");
                for (String domain : validateFile(extracted, name, pack)) {
                    if (!packUnion.add(domain))
                        throw new SecurityException("Reward packs overlap");
                }
            }
            if (!packUnion.equals(reward))
                throw new SecurityException("Reward pack union mismatch");

            remote = "/data/local/tmp/weig_zeroad-rules-" + version;
            StringBuilder command = new StringBuilder("rm -rf ")
                    .append(RootShell.quote(remote))
                    .append(" && mkdir -p ").append(RootShell.quote(remote))
                    .append(" && chmod 0700 ").append(RootShell.quote(remote));
            for (String name : RUNTIME_FILES) {
                File local = new File(extracted, name);
                command.append(" && cp ")
                        .append(RootShell.quote(local.getAbsolutePath())).append(' ')
                        .append(RootShell.quote(remote + "/" + name));
            }
            command.append(" && chmod 0600 ").append(RootShell.quote(remote)).append("/*");
            RootShell.Result stage = RootShell.run(command.toString());
            if (!stage.ok())
                throw new IllegalStateException("Cannot stage rules: " + stage.output());
            RootShell.Result activated = RootShell.runControl(
                    "rules-install-dir " + RootShell.quote(remote));
            if (!activated.ok()) throw new IllegalStateException(activated.output());
            remote = null;
            return new Result(version, activated.output());
        } finally {
            if (remote != null) {
                RootShell.run("rm -rf " + RootShell.quote(remote));
            }
            deleteTree(extracted);
            if (archive != null) archive.delete();
        }
    }

    /**
     * Validates one region's three profiles and returns only its strict set.
     *
     * Monotonicity makes strict a superset of lean and balanced, so no later
     * check needs the two smaller sets. Returning just the strict one lets them
     * be collected before the next region is read, which keeps peak memory at
     * two large sets rather than six.
     */
    private static Set<String> validateRegion(
            File directory, JSONObject profiles, String region, String label) throws Exception {
        Set<String> balanced;
        {
            Set<String> lean = validateProfile(directory, profiles, region, "lean");
            balanced = validateProfile(directory, profiles, region, "balanced");
            if (!balanced.containsAll(lean))
                throw new SecurityException(label + " profiles are not monotonic");
        }
        Set<String> strict = validateProfile(directory, profiles, region, "strict");
        if (!strict.containsAll(balanced))
            throw new SecurityException(label + " profiles are not monotonic");
        return strict;
    }

    private static Set<String> validateProfile(
            File directory, JSONObject profiles, String region, String level) throws Exception {
        return validateFile(directory, region + "-" + level + ".domains",
                profiles.getJSONObject(region).getJSONObject(level));
    }

    private static Set<String> validateFile(File directory, String name, JSONObject metadata) throws Exception {
        File file = new File(directory, name);
        String expected = metadata.getString("domains_sha256").toLowerCase(Locale.ROOT);
        int expectedRules = metadata.getInt("rules");
        if (expectedRules < 0 || expectedRules > 500_000)
            throw new SecurityException("Too many rules");
        // One pass hashes and parses together; the digest sees the raw bytes
        // before the reader decodes them, and nothing is returned until the
        // checksum has been verified below.
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Set<String> domains = new HashSet<>(expectedRules * 4 / 3 + 16);
        Matcher domainMatcher = EXACT_DOMAIN.matcher("");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new DigestInputStream(new FileInputStream(file), digest),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String value = line.trim();
                if (value.isEmpty() || value.startsWith("#")) continue;
                if (value.length() > 253 || !domainMatcher.reset(value).matches())
                    throw new SecurityException("Invalid exact domain in " + name);
                if (!domains.add(value))
                    throw new SecurityException("Duplicate domain in " + name);
                if (domains.size() > expectedRules)
                    throw new SecurityException(name + " count mismatch");
            }
        }
        String actual = Hex.encode(digest.digest());
        if (!expected.equals(actual)) throw new SecurityException(name + " checksum mismatch");
        if (domains.size() != expectedRules)
            throw new SecurityException(name + " count mismatch");
        return domains;
    }

    private static void extractDataOnly(File archive, File directory) throws Exception {
        Set<String> seen = new HashSet<>();
        long total = 0;
        try (ZipInputStream input = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive)))) {
            byte[] buffer = new byte[16 * 1024];
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !ARCHIVE_FILES.contains(name) || !seen.add(name))
                    throw new SecurityException("Unsafe rules archive entry: " + name);
                File target = new File(directory, name);
                try (FileOutputStream output = new FileOutputStream(target)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > 160L * 1024 * 1024)
                            throw new SecurityException("Expanded rules are too large");
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
        if (!seen.equals(ARCHIVE_FILES)) throw new SecurityException("Rules archive is incomplete");
    }

    private static void deleteTree(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
