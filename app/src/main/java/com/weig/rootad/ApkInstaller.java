package com.weig.rootad;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;

final class ApkInstaller {
    interface Callback { void completed(String message, boolean success); }
    private static final String ACTION_PREFIX = "com.weig.rootad.INSTALL_RESULT.";

    private ApkInstaller() {}

    static void install(Activity activity, File apk, Callback callback) throws Exception {
        verifySigner(activity, apk);
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(settings);
            apk.delete();
            callback.completed(message(
                    "请允许 WeiG ZeroAd 安装应用，然后再次点击更新。",
                    "Allow WeiG ZeroAd to install apps, then tap update again."), false);
            return;
        }
        String resultAction = ACTION_PREFIX + UUID.randomUUID();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    Intent confirmation;
                    if (Build.VERSION.SDK_INT >= 33) {
                        confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                    } else {
                        //noinspection deprecation
                        confirmation = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    }
                    if (confirmation != null) {
                        try {
                            activity.startActivity(confirmation);
                            callback.completed(message(
                                    "请在系统安装界面确认更新。",
                                    "Confirm the update in the system installer."), false);
                        } catch (Exception error) {
                            unregister(activity, this);
                            callback.completed(message(
                                    "无法打开系统安装界面：",
                                    "Cannot open the system installer: ") + error.getMessage(), false);
                        }
                    } else {
                        unregister(activity, this);
                        callback.completed(message(
                                "系统没有返回安装确认界面。",
                                "The system did not return an installation confirmation."), false);
                    }
                    return;
                }
                unregister(activity, this);
                if (status == PackageInstaller.STATUS_SUCCESS) {
                    callback.completed(message("APK 更新成功。", "APK update installed."), true);
                    return;
                }
                String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                if (detail == null || detail.isBlank()) detail = "status=" + status;
                callback.completed(message("APK 安装失败：", "APK installation failed: ") + detail, false);
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(
                    receiver, new IntentFilter(resultAction), Context.RECEIVER_NOT_EXPORTED);
        } else {
            // Android 12 has no receiver export flag overload. The per-install random
            // action and package-scoped PendingIntent prevent a predictable spoof target.
            activity.registerReceiver(receiver, new IntentFilter(resultAction));
        }

        PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(activity.getPackageName());
        params.setSize(apk.length());
        int sessionId = -1;
        try {
            sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId)) {
                try (FileInputStream input = new FileInputStream(apk);
                     OutputStream output = session.openWrite(
                             "WeiGZeroAd.apk", 0, apk.length())) {
                    byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                    session.fsync(output);
                }

                // PackageInstaller rejects commit() while an APK stream is still open.
                Intent result = new Intent(resultAction).setPackage(activity.getPackageName());
                PendingIntent pending = PendingIntent.getBroadcast(activity, sessionId, result,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(pending.getIntentSender());
                apk.delete();
            }
        } catch (Exception error) {
            if (sessionId >= 0) {
                try { installer.abandonSession(sessionId); } catch (Exception ignored) {}
            }
            unregister(activity, receiver);
            apk.delete();
            throw error;
        }
    }

    private static void unregister(Activity activity, BroadcastReceiver receiver) {
        try { activity.unregisterReceiver(receiver); } catch (Exception ignored) {}
    }

    private static String message(String chinese, String english) {
        return Locale.getDefault().getLanguage().equals("zh") ? chinese : english;
    }

    private static void verifySigner(Activity activity, File apk) throws Exception {
        PackageManager manager = activity.getPackageManager();
        PackageInfo candidate;
        PackageInfo installed;
        if (Build.VERSION.SDK_INT >= 33) {
            candidate = manager.getPackageArchiveInfo(apk.getAbsolutePath(),
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES));
            installed = manager.getPackageInfo(activity.getPackageName(),
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES));
        } else {
            //noinspection deprecation
            candidate = manager.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
            //noinspection deprecation
            installed = manager.getPackageInfo(activity.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        }
        if (candidate == null || candidate.signingInfo == null || installed.signingInfo == null)
            throw new SecurityException("Cannot verify APK signing certificate");
        Signature[] candidateSigners = candidate.signingInfo.getApkContentsSigners();
        Signature[] installedSigners = installed.signingInfo.getApkContentsSigners();
        if (candidateSigners.length != 1 || installedSigners.length != 1 ||
                !candidateSigners[0].equals(installedSigners[0]))
            throw new SecurityException("Update APK is signed by a different key");
    }
}
