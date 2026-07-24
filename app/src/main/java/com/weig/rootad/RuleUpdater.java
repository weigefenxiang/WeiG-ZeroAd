package com.weig.rootad;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class RuleUpdater {
    record Result(long version, String statusJson) {}
    record Available(long version, String page, ReleaseClient.Asset asset) {}

    private static final Pattern RULE_TAG = Pattern.compile("rules-([0-9]{10})");

    private static final Set<String> PROFILE_FILES = Set.of(
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
        ReleaseClient.Release release = ReleaseClient.latestWithAsset(
                BuildConfig.RULES_REPOSITORY, "zeroad-rules", ".zip");
        Matcher matcher = RULE_TAG.matcher(release.tag());
        if (!matcher.matches()) throw new SecurityException("Invalid rules release tag");
        long version = Long.parseLong(matcher.group(1));
        if (version < 1) throw new SecurityException("Invalid rule version");
        ReleaseClient.Asset asset = release.matching("zeroad-rules", ".zip");
        if (asset == null) throw new IllegalStateException("Rules release has no ZIP asset");
        return new Available(version, release.page(), asset);
    }

    static Result install(Context context, Available available) throws Exception {
        File archive = ReleaseClient.download(context, available.asset(), 96L * 1024 * 1024);
        File extracted = new File(context.getCacheDir(), "zeroad-rules-staging");
        deleteTree(extracted);
        if (!extracted.mkdir()) throw new IllegalStateException("Cannot create rule staging directory");
        extractDataOnly(archive, extracted);

        JSONObject manifest = new JSONObject(new String(
                Files.readAllBytes(new File(extracted, "manifest.json").toPath()),
                StandardCharsets.UTF_8));
        if (manifest.getInt("schema") != 3) throw new SecurityException("Unsupported rule schema");
        long version = manifest.getLong("version");
        if (version != available.version())
            throw new SecurityException("Release tag and rule manifest version differ");

        JSONObject profiles = manifest.getJSONObject("profiles");
        Validated cnLean = validateProfile(extracted, profiles, "cn", "lean");
        Validated cnBalanced = validateProfile(extracted, profiles, "cn", "balanced");
        Validated cnStrict = validateProfile(extracted, profiles, "cn", "strict");
        Validated globalLean = validateProfile(extracted, profiles, "global", "lean");
        Validated globalBalanced = validateProfile(extracted, profiles, "global", "balanced");
        Validated globalStrict = validateProfile(extracted, profiles, "global", "strict");
        if (!cnBalanced.domains().containsAll(cnLean.domains()) ||
                !cnStrict.domains().containsAll(cnBalanced.domains()))
            throw new SecurityException("Domestic profiles are not monotonic");
        if (!globalBalanced.domains().containsAll(globalLean.domains()) ||
                !globalStrict.domains().containsAll(globalBalanced.domains()))
            throw new SecurityException("Global profiles are not monotonic");
        if (!disjoint(cnStrict.domains(), globalStrict.domains()))
            throw new SecurityException("Domestic and global profiles overlap");

        Validated reward = validateFile(extracted, "reward-ads.domains",
                manifest.getJSONObject("reward"));
        for (String name : PROFILE_FILES) {
            JSONObject region = profiles.getJSONObject(name.startsWith("cn-") ? "cn" : "global");
            String level = name.substring(name.indexOf('-') + 1, name.length() - ".domains".length());
            Validated profile = validateFile(extracted, name, region.getJSONObject(level));
            if (!disjoint(profile.domains(), reward.domains()))
                throw new SecurityException("Reward rules overlap " + name);
        }

        Set<String> packUnion = new HashSet<>();
        JSONArray packs = manifest.getJSONArray("packs");
        for (int index = 0; index < packs.length(); index++) {
            JSONObject pack = packs.getJSONObject(index);
            String name = pack.getString("file");
            if (!REWARD_FILES.contains(name) || name.equals("reward-ads.domains"))
                throw new SecurityException("Unknown reward pack file");
            Validated validated = validateFile(extracted, name, pack);
            for (String domain : validated.domains()) {
                if (!packUnion.add(domain)) throw new SecurityException("Reward packs overlap");
            }
        }
        if (!packUnion.equals(reward.domains()))
            throw new SecurityException("Reward pack union mismatch");

        String remote = "/data/local/tmp/weig_zeroad-rules-" + version;
        RootShell.Result stage = RootShell.run("rm -rf " + RootShell.quote(remote) +
                " && mkdir -p " + RootShell.quote(remote) + " && chmod 0700 " + RootShell.quote(remote));
        if (!stage.ok()) throw new IllegalStateException(stage.output());
        for (String name : RUNTIME_FILES) {
            File local = new File(extracted, name);
            String command = "cp " + RootShell.quote(local.getAbsolutePath()) + " " +
                    RootShell.quote(remote + "/" + name) + " && chmod 0600 " +
                    RootShell.quote(remote + "/" + name);
            RootShell.Result copied = RootShell.run(command);
            if (!copied.ok()) throw new IllegalStateException("Cannot stage " + name + ": " + copied.output());
        }
        RootShell.Result activated = RootShell.runControl("rules-install-dir " + RootShell.quote(remote));
        if (!activated.ok()) throw new IllegalStateException(activated.output());
        deleteTree(extracted);
        archive.delete();
        return new Result(version, activated.output());
    }

    private static Validated validateProfile(
            File directory, JSONObject profiles, String region, String level) throws Exception {
        return validateFile(directory, region + "-" + level + ".domains",
                profiles.getJSONObject(region).getJSONObject(level));
    }

    private record Validated(int count, Set<String> domains) {}

    private static Validated validateFile(File directory, String name, JSONObject metadata) throws Exception {
        File file = new File(directory, name);
        String expected = metadata.getString("domains_sha256").toLowerCase(Locale.ROOT);
        String actual = hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));
        if (!expected.equals(actual)) throw new SecurityException(name + " checksum mismatch");
        Set<String> domains = new HashSet<>();
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            String value = line.trim();
            if (value.isEmpty() || value.startsWith("#")) continue;
            if (value.length() > 253 || !value.matches(
                    "[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+"))
                throw new SecurityException("Invalid exact domain in " + name);
            if (!domains.add(value)) throw new SecurityException("Duplicate domain in " + name);
            if (domains.size() > 500_000) throw new SecurityException("Too many rules");
        }
        if (domains.size() != metadata.getInt("rules"))
            throw new SecurityException(name + " count mismatch");
        return new Validated(domains.size(), domains);
    }

    private static boolean disjoint(Set<String> first, Set<String> second) {
        for (String domain : second) if (first.contains(domain)) return false;
        return true;
    }

    private static void extractDataOnly(File archive, File directory) throws Exception {
        Set<String> seen = new HashSet<>();
        long total = 0;
        try (ZipInputStream input = new ZipInputStream(
                new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !ARCHIVE_FILES.contains(name) || !seen.add(name))
                    throw new SecurityException("Unsafe rules archive entry: " + name);
                File target = new File(directory, name);
                try (FileOutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[16 * 1024];
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

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item));
        return value.toString();
    }

    private static void deleteTree(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        file.delete();
    }
}
