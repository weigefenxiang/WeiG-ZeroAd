package com.weig.rootad;

import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

final class CodeUpdater {
    record Component(
            String versionName,
            int versionCode,
            ReleaseClient.Asset asset,
            String sha256
    ) {}
    record Available(Component manager, Component core) {}

    private CodeUpdater() {}

    static Available check(Context context) throws Exception {
        ReleaseClient.Release release = ReleaseClient.latestChannelWithAsset(
                BuildConfig.CODE_REPOSITORY, "update-manifest", ".json",
                BuildConfig.UPDATE_CHANNEL);
        ReleaseClient.Asset manifestAsset = release.matching("update-manifest", ".json");
        if (manifestAsset == null)
            throw new IllegalStateException("Release has no update manifest");
        File file = ReleaseClient.download(context, manifestAsset, 1024L * 1024L);
        try {
            JSONObject manifest = new JSONObject(new String(
                    Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            if (manifest.getInt("schema") != 1)
                throw new SecurityException("Unsupported update manifest");
            if (!manifest.getString("channel").equals(BuildConfig.UPDATE_CHANNEL))
                throw new SecurityException("Update channel mismatch");
            if (!manifest.getString("tag").equals(release.tag()))
                throw new SecurityException("Update release tag mismatch");
            Component manager = component(
                    release, manifest.getJSONObject("manager"), "zeroad-manager", ".apk");
            Component core = component(
                    release, manifest.getJSONObject("core"), "core-only", ".zip");
            return new Available(manager, core);
        } finally {
            file.delete();
        }
    }

    private static Component component(
            ReleaseClient.Release release, JSONObject json,
            String nameFragment, String suffix) throws Exception {
        int versionCode = json.getInt("version_code");
        if (versionCode < 1) throw new SecurityException("Invalid update version code");
        String sha256 = json.getString("sha256").toLowerCase();
        if (!sha256.matches("[0-9a-f]{64}"))
            throw new SecurityException("Invalid update checksum");
        String assetName = json.getString("asset");
        String lowerName = assetName.toLowerCase(Locale.ROOT);
        if (!lowerName.contains(nameFragment) || !lowerName.endsWith(suffix))
            throw new SecurityException("Unexpected update asset name");
        ReleaseClient.Asset asset = release.named(assetName);
        if (asset == null) throw new SecurityException("Update asset is missing");
        return new Component(json.getString("version_name"), versionCode, asset, sha256);
    }

    static File download(
            Context context, Component component, long maximumBytes) throws Exception {
        return ReleaseClient.download(
                context, component.asset(), maximumBytes, component.sha256());
    }
}
