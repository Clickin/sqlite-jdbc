// --------------------------------------
// sqlite-jdbc Project
//
// VtCarrierProgressTest.java
// --------------------------------------
package org.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves Java-side busy waits release the virtual-thread carrier, inside an isolated child JVM
 * whose scheduler allows exactly one carrier. The child starts before any virtual thread exists,
 * so the scheduler configuration is process-global and never shared with other tests in this
 * suite. See {@link org.sqlite.vt.VtWaitScenarioMain} for the stdout protocol.
 *
 * <p>Runs only on JDKs with virtual threads (19+); JUnit assumptions keep the skips honest on
 * Java 8. Evidence (child command, output, exit codes, JFR recordings) is written under
 * target/vt-wait-evidence/.
 */
public class VtCarrierProgressTest {

    @TempDir
    File tempDir;

    private static final String EVIDENCE_DIR = "target/vt-wait-evidence";

    @Test
    void backupJavaWaitLetsIndependentVirtualThreadProgress() throws Exception {
        runScenarioAndAssert("backup-progress", true);
    }

    @Test
    void backupJavaWaitKeepsSameConnectionExclusive() throws Exception {
        runScenarioAndAssert("backup-exclusion", false);
    }

    private void runScenarioAndAssert(String scenario, boolean assertIndependentProgress)
            throws Exception {
        assumeTrue(jdkFeatureVersion() >= 19, "virtual threads require JDK 19+");

        Path evidenceDir = Paths.get(EVIDENCE_DIR, scenario + "-" + System.currentTimeMillis());
        Files.createDirectories(evidenceDir);

        String javaBin = new File(new File(System.getProperty("java.home"), "bin"),
                OperatingSystemDetector.isWindows() ? "java.exe" : "java").getAbsolutePath();

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-Djdk.virtualThreadScheduler.parallelism=1");
        command.add("-Djdk.virtualThreadScheduler.maxPoolSize=1");
        if (jdkFeatureVersion() >= 21) {
            File recording = new File(evidenceDir.toFile(), scenario + ".jfr");
            command.add("-XX:StartFlightRecording=filename=" + recording.getAbsolutePath()
                    + ",dumponexit=true,maxsize=20m");
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add("org.sqlite.vt.VtWaitScenarioMain");
        command.add(scenario);
        command.add(tempDir.getAbsolutePath());

        Path stdoutLog = evidenceDir.resolve(scenario + "-stdout.txt");
        Path stderrLog = evidenceDir.resolve(scenario + "-stderr.txt");
        Files.write(evidenceDir.resolve(scenario + "-command.txt"),
                (String.join("\n", command) + "\njava.version="
                        + System.getProperty("java.version") + "\n").getBytes(StandardCharsets.UTF_8));

        Process child = new ProcessBuilder(command).redirectErrorStream(false).start();
        List<String> stdout = new ArrayList<>();
        List<String> stderr = new ArrayList<>();
        Thread stdoutReader = pipe(child.getInputStream(), stdout);
        Thread stderrReader = pipe(child.getErrorStream(), stderr);
        boolean finished = child.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            child.destroyForcibly();
            child.waitFor(10, TimeUnit.SECONDS);
        }
        stdoutReader.join(5_000L);
        stderrReader.join(5_000L);
        Files.write(stdoutLog, String.join("\n", stdout).getBytes(StandardCharsets.UTF_8));
        Files.write(stderrLog, String.join("\n", stderr).getBytes(StandardCharsets.UTF_8));
        Files.write(evidenceDir.resolve(scenario + "-exit.txt"),
                String.valueOf(child.exitValue()).getBytes(StandardCharsets.UTF_8));

        assertThat(finished).as("child JVM must finish").isTrue();
        assertThat(child.exitValue())
                .as("child stdout:\n" + String.join("\n", stdout))
                .isEqualTo(0);
        assertThat(stdout).anyMatch(line -> line.startsWith("WAIT-ENTERED"));

        if (assertIndependentProgress) {
            // The independent virtual thread must have finished while the target was still
            // waiting for the still-held lock.
            assertThat(stdout)
                    .anyMatch(line -> line.equals("B-DONE targetStillWaiting=true"));
        } else {
            assertThat(stdout).anyMatch(line -> line.equals("EXCLUSION-BLOCKED=true"));
            assertThat(stdout).anyMatch(line -> line.equals("PROBER-ALIVE-AFTER-400MS=true"));
        }

        int pinned = readPinnedEventCount(evidenceDir.resolve(scenario + ".jfr"));
        if (jdkFeatureVersion() >= 24) {
            // JEP 491: a waiting virtual thread must release its carrier; sleeping inside the
            // native busy loop would keep it. JFR is auxiliary evidence, the child progress
            // markers above are the primary proof.
            assertThat(pinned)
                    .as("VirtualThreadPinned events in %s", scenario + ".jfr")
                    .isZero();
        } else if (pinned >= 0) {
            System.out.println(scenario + ": pinned events: " + pinned
                    + " (pre-JEP-491 JDK, informational)");
        }
    }

    /** Reads jdk.VirtualThreadPinned events with a dedicated reader JVM; -1 when unavailable. */
    private int readPinnedEventCount(Path recording) throws Exception {
        if (!Files.exists(recording)) {
            return -1;
        }
        String javaBin = new File(new File(System.getProperty("java.home"), "bin"),
                OperatingSystemDetector.isWindows() ? "java.exe" : "java").getAbsolutePath();
        Process reader = new ProcessBuilder(
                javaBin, "-cp", System.getProperty("java.class.path"),
                "org.sqlite.vt.JfrPinnedReader", recording.toString())
                .redirectErrorStream(true).start();
        List<String> output = new ArrayList<>();
        pipe(reader.getInputStream(), output).join(30_000L);
        reader.waitFor(30, TimeUnit.SECONDS);
        for (String line : output) {
            if (line.startsWith("PINNED=")) {
                return Integer.parseInt(line.substring("PINNED=".length()).trim());
            }
        }
        // The reader JVM must be able to read recordings on verification JDKs; do not swallow.
        if (jdkFeatureVersion() >= 21) {
            throw new AssertionError("JFR reader failed: " + String.join("\n", output));
        }
        return -1;
    }

    private static Thread pipe(java.io.InputStream stream, List<String> sink) {
        Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (sink != null) {
                        synchronized (sink) {
                            sink.add(line);
                        }
                    }
                }
            } catch (Exception ignored) {
                // process ended
            }
        });
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    static int jdkFeatureVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            return 8;
        }
        return Integer.parseInt(version.split("\\.")[0]);
    }

    /** Nested to keep this class independent of OSInfo in production code. */
    static final class OperatingSystemDetector {
        static boolean isWindows() {
            return System.getProperty("os.name", "").toLowerCase().contains("win");
        }
    }
}
