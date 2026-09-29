package io.gateway;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import jdk.jfr.Recording;

/** Fixed total work, real JDBC, no application lock or admission layer. */
public final class JdbcLockBenchmark {
    static final OperatingSystemMXBean OS =
            (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static long share(long total, int workers, int worker) {
        return total / workers + (worker < total % workers ? 1 : 0);
    }

    static Map<String, Object> phase(PreparedStatement[] statements, long total, int timeoutSeconds)
            throws Exception {
        int workers = statements.length;
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(workers);
        long[] completed = new long[workers], starts = new long[workers], ends = new long[workers];
        Throwable[] errors = new Throwable[workers];
        Thread[] threads = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            final int worker = i;
            final long planned = share(total, workers, worker);
            threads[i] = Thread.ofVirtual().name("jdbc-lock-" + i).start(() -> {
                long count = 0;
                try {
                    ready.countDown();
                    start.await();
                    starts[worker] = System.nanoTime();
                    for (; count < planned; count++) {
                        if (statements[worker].executeUpdate() != 1)
                            throw new AssertionError("UPDATE did not affect exactly one row: worker " + worker);
                    }
                } catch (Throwable failure) {
                    errors[worker] = failure;
                } finally {
                    ends[worker] = System.nanoTime();
                    completed[worker] = count;
                    done.countDown();
                }
            });
        }
        check(ready.await(timeoutSeconds, TimeUnit.SECONDS), "worker readiness timeout");
        long cpuStart = OS.getProcessCpuTime();
        long wallStart = System.nanoTime();
        long deadline = wallStart + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        start.countDown();
        check(done.await(timeoutSeconds, TimeUnit.SECONDS), "operation timeout; cell invalid");
        for (Thread thread : threads) {
            long remaining = deadline - System.nanoTime();
            check(remaining > 0 && thread.join(Duration.ofNanos(remaining)), "worker join timeout");
        }
        long wallNs = System.nanoTime() - wallStart;
        long cpuNs = OS.getProcessCpuTime() - cpuStart;
        check(cpuStart >= 0 && cpuNs >= 0 && wallNs > 0, "process CPU/wall clock unavailable");
        var details = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < workers; i++) {
            if (errors[i] != null) throw new IllegalStateException("worker " + i + " failed", errors[i]);
            check(completed[i] == share(total, workers, i), "worker completed/planned mismatch");
            details.add(Map.of("worker", i, "planned", share(total, workers, i), "completed", completed[i],
                    "start_offset_ns", starts[i] - wallStart, "duration_ns", ends[i] - starts[i]));
        }
        check(Arrays.stream(completed).sum() == total, "total completed/planned mismatch");
        return Map.of("planned_operations", total, "completed_operations", total,
                "wall_ns", wallNs, "process_cpu_ns", cpuNs, "workers", details,
                "startup_spread_ns", Arrays.stream(starts).max().orElseThrow()
                        - Arrays.stream(starts).min().orElseThrow());
    }

    static Recording monitors() {
        var recording = new Recording();
        for (String event : List.of("jdk.JavaMonitorInflate", "jdk.JavaMonitorEnter", "jdk.ThreadPark"))
            recording.enable(event).withThreshold(Duration.ZERO).withStackTrace();
        recording.start();
        return recording;
    }

    static Map<String, Object> recordedPhase(PreparedStatement[] statements, long total, int timeout,
            Path recordingPath) throws Exception {
        if (recordingPath == null) return phase(statements, total, timeout);
        try (var recording = monitors()) {
            var result = phase(statements, total, timeout);
            recording.stop();
            recording.dump(recordingPath);
            return result;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException(
                "Usage: JdbcLockBenchmark output-dir single|private16|shared4|shared16 operations warmup-operations timeout-seconds performance|diagnostic");
        Path output = Path.of(args[0]);
        Files.createDirectories(output);
        var result = new LinkedHashMap<String, Object>();
        result.put("valid", false);
        try {
            String scenario = args[1];
            int workers = switch (scenario) {
                case "single" -> 1;
                case "shared4" -> 4;
                case "private16", "shared16" -> 16;
                default -> throw new IllegalArgumentException("Unknown scenario: " + scenario);
            };
            boolean shared = scenario.startsWith("shared");
            long operations = Long.parseLong(args[2]), warmup = Long.parseLong(args[3]);
            int timeout = Integer.parseInt(args[4]);
            check(operations > 0 && warmup >= 0 && operations <= Long.MAX_VALUE - warmup
                    && timeout > 0, "invalid operation counts or timeout");
            check(args[5].equals("performance") || args[5].equals("diagnostic"), "invalid run kind");
            boolean diagnostic = args[5].equals("diagnostic");
            result.put("scenario", scenario);
            result.put("kind", args[5]);
            result.put("operations", operations);
            result.put("warmup_operations", warmup);
            result.put("worker_count", workers);
            result.put("connection_count", shared ? 1 : workers);
            result.put("jdk", Map.of("runtime_version", System.getProperty("java.runtime.version"),
                    "vm_name", System.getProperty("java.vm.name"), "vendor", System.getProperty("java.vendor"),
                    "java_home", System.getProperty("java.home"),
                    "vm_arguments", ManagementFactory.getRuntimeMXBean().getInputArguments()));
            Class.forName("org.sqlite.JDBC");
            // Include connection initialization: it can inflate a monitor before the first UPDATE.
            var warmupRecording = diagnostic ? monitors() : null;
            Connection[] connections = new Connection[shared ? 1 : workers];
            for (int i = 0; i < connections.length; i++) {
                connections[i] = DriverManager.getConnection("jdbc:sqlite::memory:");
                try (var statement = connections[i].createStatement()) {
                    statement.executeUpdate("CREATE TABLE counters (id INTEGER PRIMARY KEY, n INTEGER NOT NULL)");
                }
            }
            try (var statement = connections[0].createStatement()) {
                try (var rows = statement.executeQuery("SELECT sqlite_source_id()")) {
                    check(rows.next(), "missing SQLite source id");
                    result.put("sqlite_source_id", rows.getString(1));
                }
                var options = new ArrayList<String>();
                try (var rows = statement.executeQuery("PRAGMA compile_options")) {
                    while (rows.next()) options.add(rows.getString(1));
                }
                options.sort(String::compareTo);
                result.put("compile_options", options);
            }
            PreparedStatement[] statements = new PreparedStatement[workers];
            for (int i = 0; i < workers; i++) {
                Connection connection = connections[shared ? 0 : i];
                try (var insert = connection.prepareStatement("INSERT INTO counters VALUES (?,0)")) {
                    insert.setInt(1, i);
                    check(insert.executeUpdate() == 1, "counter setup failed");
                }
                statements[i] = connection.prepareStatement("UPDATE counters SET n=n+1 WHERE id=?");
                statements[i].setInt(1, i);
            }
            result.put("warmup", phase(statements, warmup, timeout));
            if (warmupRecording != null) {
                warmupRecording.stop();
                warmupRecording.dump(output.resolve("warmup-monitors.jfr"));
                warmupRecording.close();
            }
            Map<String, Object> measured = recordedPhase(statements, operations, timeout,
                    diagnostic ? output.resolve("measurement-monitors.jfr") : null);
            result.put("measurement", measured);
            var counters = new ArrayList<Map<String, Object>>();
            for (int i = 0; i < workers; i++) {
                try (var query = connections[shared ? 0 : i].prepareStatement("SELECT n FROM counters WHERE id=?")) {
                    query.setInt(1, i);
                    try (var rows = query.executeQuery()) {
                        check(rows.next(), "missing counter " + i);
                        long expected = share(warmup, workers, i) + share(operations, workers, i);
                        long actual = rows.getLong(1);
                        check(actual == expected && !rows.next(), "incorrect final counter " + i);
                        counters.add(Map.of("worker", i, "expected", expected, "actual", actual));
                    }
                }
            }
            for (Connection connection : connections) {
                try (var statement = connection.createStatement();
                        var rows = statement.executeQuery("PRAGMA integrity_check")) {
                    check(rows.next() && rows.getString(1).equals("ok") && !rows.next(), "integrity_check failed");
                }
            }
            for (var statement : statements) statement.close();
            for (var connection : connections) connection.close();
            result.put("counters", counters);
            result.put("integrity", "ok");
            // Diagnostic CPU/wall stays in its phase data, never a headline rate.
            if (!diagnostic) {
                result.put("cpu_ns_per_operation", ((Number) measured.get("process_cpu_ns")).doubleValue() / operations);
                result.put("operations_per_second", operations * 1e9 / ((Number) measured.get("wall_ns")).doubleValue());
            }
            result.put("valid", true);
            Files.writeString(output.resolve("result.json"), ProfileSummary.json(result) + "\n");
            System.out.println("PASS " + scenario + " " + operations + " measured operations");
        } catch (Throwable failure) {
            result.put("error", failure.toString());
            Files.writeString(output.resolve("result.json"), ProfileSummary.json(result) + "\n");
            failure.printStackTrace();
            // A failed native call can strand workers holding JDBC locks: do not close/join on failure.
            System.exit(1);
        }
    }
}
