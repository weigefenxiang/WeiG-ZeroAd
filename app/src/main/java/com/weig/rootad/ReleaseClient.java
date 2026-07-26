package com.weig.rootad;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class ReleaseClient {
    record Asset(String name, String url, long size, String digest) {}
    record Release(String tag, Asset[] assets) {
        Asset matching(String fragment, String suffix) {
            String part = fragment.toLowerCase(Locale.ROOT);
            String ending = suffix.toLowerCase(Locale.ROOT);
            for (Asset asset : assets) {
                String candidate = asset.name.toLowerCase(Locale.ROOT);
                if (candidate.contains(part) && candidate.endsWith(ending)) return asset;
            }
            return null;
        }
        Asset named(String name) {
            for (Asset asset : assets) if (asset.name.equals(name)) return asset;
            return null;
        }
    }

    private ReleaseClient() {}

    static Release latestWithAsset(String repository, String nameFragment, String suffix) throws Exception {
        String endpoint = "https://api.github.com/repos/" + BuildConfig.GITHUB_OWNER + "/" + repository +
                "/releases?per_page=30";
        JSONArray releases = new JSONArray(new String(get(new URL(endpoint), 5_000_000), StandardCharsets.UTF_8));
        String fragment = nameFragment.toLowerCase(Locale.ROOT);
        String ending = suffix.toLowerCase(Locale.ROOT);
        for (int releaseIndex = 0; releaseIndex < releases.length(); releaseIndex++) {
            JSONObject json = releases.getJSONObject(releaseIndex);
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) continue;
            Release release = parseRelease(json);
            for (Asset asset : release.assets) {
                String name = asset.name.toLowerCase(Locale.ROOT);
                if (name.contains(fragment) && name.endsWith(ending)) return release;
            }
        }
        throw new IllegalStateException("No matching GitHub release asset found");
    }

    static Release latestChannelWithAsset(
            String repository, String nameFragment, String suffix, String channel) throws Exception {
        String endpoint = "https://api.github.com/repos/" + BuildConfig.GITHUB_OWNER + "/" + repository +
                "/releases?per_page=30";
        JSONArray releases = new JSONArray(new String(get(new URL(endpoint), 5_000_000), StandardCharsets.UTF_8));
        boolean test = channel.equals("test");
        for (int releaseIndex = 0; releaseIndex < releases.length(); releaseIndex++) {
            JSONObject json = releases.getJSONObject(releaseIndex);
            if (json.optBoolean("draft")) continue;
            boolean prerelease = json.optBoolean("prerelease");
            if (test ? (!prerelease || !json.optString("tag_name").equals("test-latest")) : prerelease)
                continue;
            Release release = parseRelease(json);
            if (release.matching(nameFragment, suffix) != null) return release;
        }
        throw new IllegalStateException("No " + channel + " update release found");
    }

    private static Release parseRelease(JSONObject json) throws Exception {
        JSONArray source = json.getJSONArray("assets");
        Asset[] assets = new Asset[source.length()];
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.getJSONObject(index);
            assets[index] = new Asset(item.getString("name"), item.getString("browser_download_url"),
                    item.optLong("size"), item.optString("digest", ""));
        }
        return new Release(json.getString("tag_name"), assets);
    }

    static File download(Context context, Asset asset, long maximumBytes) throws Exception {
        return download(context, asset, maximumBytes, "");
    }

    static File download(
            Context context, Asset asset, long maximumBytes, String expectedSha256) throws Exception {
        if (asset.size > maximumBytes) throw new IllegalArgumentException("Release asset is too large");
        File target = new File(context.getCacheDir(), safeName(asset.name));
        File temporary = new File(context.getCacheDir(), safeName(asset.name) + ".part");
        temporary.delete();
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        try {
            downloadToFile(new URL(asset.url), temporary, maximumBytes, asset.size, sha256);
            String actual = hex(sha256.digest());
            if (asset.digest.startsWith("sha256:")) {
                String expected = asset.digest.substring(7).toLowerCase(Locale.ROOT);
                if (!expected.equals(actual))
                    throw new SecurityException("GitHub asset digest mismatch");
            }
            if (!expectedSha256.isEmpty() &&
                    !expectedSha256.toLowerCase(Locale.ROOT).equals(actual))
                throw new SecurityException("Update manifest checksum mismatch");
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            return target;
        } finally {
            temporary.delete();
        }
    }

    private static void downloadToFile(
            URL url, File target, long maximumBytes, long expectedBytes,
            MessageDigest sha256) throws Exception {
        HttpURLConnection connection = open(url);
        long declared = connection.getContentLengthLong();
        if (declared > maximumBytes) {
            connection.disconnect();
            throw new IllegalArgumentException("Download exceeds size limit");
        }
        long total = 0;
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes)
                    throw new IllegalArgumentException("Download exceeds size limit");
                sha256.update(buffer, 0, read);
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }
        if (expectedBytes > 0 && total != expectedBytes)
            throw new SecurityException("Downloaded asset size mismatch");
    }

    private static byte[] get(URL url, long maximumBytes) throws Exception {
        HttpURLConnection connection = open(url);
        try (BufferedInputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            long total = 0;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maximumBytes) throw new IllegalArgumentException("Download exceeds size limit");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection open(URL url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "WeiG-ZeroAd/" + BuildConfig.VERSION_NAME);
        connection.setInstanceFollowRedirects(true);
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            connection.disconnect();
            throw new IllegalStateException("GitHub returned HTTP " + code);
        }
        return connection;
    }

    private static String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item));
        return value.toString();
    }
}
