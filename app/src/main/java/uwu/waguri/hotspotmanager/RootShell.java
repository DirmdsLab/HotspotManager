package uwu.waguri.hotspotmanager;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Executes a bounded root command while draining merged output to avoid pipe deadlocks. */
final class RootShell {
    static final class Result {
        final int exitCode;
        final String output;

        Result(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output;
        }

        String legacyOutput() {
            return exitCode == 0 ? output : output + "\nROOT_EXIT=" + exitCode;
        }
    }

    static Result run(String command) {
        Process process = null;
        StringBuffer output = new StringBuffer();
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
            final Process child = process;
            Thread reader = new Thread(() -> {
                try (InputStreamReader input = new InputStreamReader(
                        child.getInputStream(), StandardCharsets.UTF_8)) {
                    char[] buffer = new char[2048];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        synchronized (output) {
                            int remaining = 65536 - output.length();
                            if (remaining > 0) output.append(buffer, 0, Math.min(count, remaining));
                        }
                    }
                } catch (Exception ignored) {
                    // Closing a timed-out process also closes its output stream.
                }
            }, "HotspotRootOutput");
            reader.setDaemon(true);
            reader.start();
            if (!process.waitFor(35, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                reader.join(500L);
                return new Result(124, output + "\nROOT_ERROR: Root command timed out.");
            }
            reader.join(1000L);
            return new Result(process.exitValue(), output.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(130, "ROOT_ERROR: Operation interrupted.");
        } catch (Exception e) {
            return new Result(1, "ROOT_ERROR: " + e.getClass().getSimpleName()
                    + ": " + String.valueOf(e.getMessage()));
        } finally {
            if (process != null) process.destroy();
        }
    }

    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private RootShell() {}
}
