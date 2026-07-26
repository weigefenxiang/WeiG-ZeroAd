package com.weig.rootad;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class CrashLog {
    private static final String FILE_NAME = "manager-crashes.log";
    private static final String MARKER = "=== manager crash ";
    private static final int MAX_RECORDS = 10;
    private static final int MAX_STACK_FRAMES = 18;
    private static final int MAX_RECORD_CHARS = 6000;
    private static final int MAX_ISSUE_CHARS = 3500;
    // Compiled once: sanitize() runs several times per crash inside the
    // uncaught-exception handler, where the process is already dying.
    private static final Pattern URL_RE = Pattern.compile("(?i)https?://\\S+");
    private static final Pattern DOMAIN_RE =
            Pattern.compile("(?i)\\b(?:[a-z0-9-]+\\.)+[a-z]{2,63}\\b");
    private static final Pattern PATH_RE = Pattern.compile("(?i)/(?:data|storage)/\\S+");
    private static final Pattern SECRET_RE =
            Pattern.compile("(?i)(authorization|cookie|token)(\\s*[:=]\\s*)\\S+");
    private static final Pattern WHITESPACE_RE = Pattern.compile("[\\r\\n\\t]+");
    private static final Pattern RECORD_SPLIT_RE = Pattern.compile("(?m)(?=^=== manager crash )");
    private static boolean installed;

    private CrashLog() {}

    static synchronized void install(Context context) {
        if (installed) return;
        Context application = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try { record(application, thread, error); }
            catch (Throwable ignored) {}
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                android.os.Process.killProcess(android.os.Process.myPid());
                System.exit(10);
            }
        });
        installed = true;
    }

    static synchronized String read(Context context) {
        try { return readFile(file(context)); }
        catch (Exception ignored) { return ""; }
    }

    static String latest(Context context) {
        String all = read(context);
        int start = all.lastIndexOf(MARKER);
        if (start < 0) return "";
        String value = all.substring(start).trim();
        return value.length() <= MAX_ISSUE_CHARS
                ? value : value.substring(0, MAX_ISSUE_CHARS) + "\n…";
    }

    private static synchronized void record(
            Context context, Thread thread, Throwable error) throws Exception {
        String existing = read(context);
        List<String> records = new ArrayList<>();
        for (String part : RECORD_SPLIT_RE.split(existing)) {
            if (!part.isBlank()) records.add(part.trim());
        }
        while (records.size() >= MAX_RECORDS) records.remove(0);
        records.add(format(thread, error));

        StringBuilder output = new StringBuilder();
        for (String record : records) {
            if (!output.isEmpty()) output.append("\n\n");
            output.append(record);
        }
        output.append('\n');
        writeFile(file(context), output.toString());
    }

    private static String format(Thread thread, Throwable error) {
        String timestamp = new SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date());
        StringBuilder output = new StringBuilder(MARKER)
                .append(timestamp).append(" ===\n")
                .append("app=").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                .append("android=").append(Build.VERSION.RELEASE)
                .append(" sdk=").append(Build.VERSION.SDK_INT).append('\n')
                .append("device=").append(sanitize(Build.MANUFACTURER))
                .append(' ').append(sanitize(Build.MODEL)).append('\n')
                .append("thread=").append(sanitize(thread.getName())).append('\n');

        Throwable current = error;
        int cause = 0;
        while (current != null && cause < 3) {
            output.append(cause == 0 ? "exception=" : "caused-by=")
                    .append(current.getClass().getName()).append('\n');
            String message = sanitize(current.getMessage());
            if (!message.isBlank()) output.append("message=").append(message).append('\n');
            StackTraceElement[] stack = current.getStackTrace();
            for (int index = 0; index < stack.length && index < MAX_STACK_FRAMES; index++) {
                output.append("at ").append(stack[index]).append('\n');
            }
            current = current.getCause();
            cause++;
        }
        String record = output.toString().trim();
        return record.length() <= MAX_RECORD_CHARS
                ? record : record.substring(0, MAX_RECORD_CHARS) + "\n…";
    }

    private static String sanitize(String value) {
        if (value == null) return "";
        String safe = value;
        safe = URL_RE.matcher(safe).replaceAll("<url>");
        safe = DOMAIN_RE.matcher(safe).replaceAll("<domain>");
        safe = PATH_RE.matcher(safe).replaceAll("<path>");
        safe = SECRET_RE.matcher(safe).replaceAll("$1$2<redacted>");
        safe = WHITESPACE_RE.matcher(safe).replaceAll(" ").trim();
        return safe.length() <= 240 ? safe : safe.substring(0, 240) + "…";
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    private static String readFile(File file) throws Exception {
        if (!file.isFile()) return "";
        try (FileInputStream input = new FileInputStream(file)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void writeFile(File target, String value) throws Exception {
        File temporary = new File(target.getParentFile(), FILE_NAME + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        try {
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
