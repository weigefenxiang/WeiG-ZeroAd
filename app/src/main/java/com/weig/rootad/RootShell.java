package com.weig.rootad;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class RootShell {
    static final String CONTROL = "/data/adb/modules/weig_rootad/bin/rulectl";
    private static Boolean masterMountSupported;

    record Result(int code, String output) {
        boolean ok() { return code == 0; }
    }

    private RootShell() {}

    static Result runControl(String arguments) {
        return run(quote(CONTROL) + " " + arguments);
    }

    static Result run(String command) {
        if (supportsMasterMount()) return execute(new String[]{"su", "-mm", "-c", command});
        return execute(new String[]{"su", "-c", command});
    }

    private static synchronized boolean supportsMasterMount() {
        if (masterMountSupported == null) {
            Result probe = execute(new String[]{"su", "-mm", "-c", "exit 0"});
            if (probe.ok()) {
                masterMountSupported = true;
            } else {
                // Only a working plain su proves -mm itself is unsupported. A
                // failed probe while root has not been granted yet must not
                // stick for the rest of the process lifetime.
                Result plain = execute(new String[]{"su", "-c", "exit 0"});
                if (plain.ok()) masterMountSupported = false;
                else return false;
            }
        }
        return masterMountSupported;
    }

    private static Result execute(String[] command) {
        // StringBuffer, not StringBuilder: when join() below times out, the
        // reader thread may still append while this thread calls toString().
        StringBuffer output = new StringBuffer();
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            Process running = process;
            Thread readerThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        running.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) output.append(line).append('\n');
                } catch (Exception ignored) {
                    // A forced timeout closes the stream; the timeout result below is authoritative.
                }
            }, "zeroad-root-output");
            readerThread.setDaemon(true);
            readerThread.start();
            if (!process.waitFor(25, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                readerThread.join(1_000);
                return new Result(124, "Root command timed out");
            }
            readerThread.join(1_000);
            return new Result(process.exitValue(), output.toString().trim());
        } catch (Exception error) {
            // Covers the interrupted path too: shutting the worker down while a
            // command is in flight must not leave an orphaned su process behind.
            if (process != null) process.destroyForcibly();
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Result(127, error.getMessage() == null ? error.toString() : error.getMessage());
        }
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
