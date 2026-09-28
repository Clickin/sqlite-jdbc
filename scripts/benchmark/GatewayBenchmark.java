package io.gateway;

import io.gateway.oauth.SecurityFixture;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.sqlite.Function;
import org.sqlite.SQLiteConnection;

/** Runs the unchanged gateway HTTP stack with its existing synthetic authority/source fixtures. */
public final class GatewayBenchmark {
    static final Map<String, Object> ARGUMENTS = Map.of("patientId", "A", "documentId", "DA");
    static final class Samples {
        final List<Long> values = Collections.synchronizedList(new ArrayList<>());
        void add(long nanos) { values.add(nanos); }
        Map<String, Object> summary() {
            long[] a;
            synchronized (values) { a = values.stream().mapToLong(Long::longValue).sorted().toArray(); }
            var result = new LinkedHashMap<String, Object>();
            result.put("count", a.length);
            for (int p : new int[]{50, 95, 99, 100})
                result.put(p == 100 ? "max_ms" : "p" + p + "_ms", a.length == 0 ? 0 : a[(int)Math.ceil(a.length * p / 100.0) - 1] / 1e6);
            for (int ms : new int[]{20, 100, 500}) result.put("over_" + ms + "ms", Arrays.stream(a).filter(n -> n > ms * 1_000_000L).count());
            return result;
        }
        void save(Path path) throws Exception {
            StringBuilder out = new StringBuilder("nanoseconds\n");
            synchronized (values) { for (long n : values) out.append(n).append('\n'); }
            Files.writeString(path, out);
        }
    }

    static Map<String, Object> recordingSummary(Path path) throws Exception {
        long pins = 0, pinNanos = 0, nativeSamples = 0, allNativeSamples = 0;
        Map<String, Long> pinStacks = new TreeMap<>(), nativeStacks = new TreeMap<>();
        try (var events = new RecordingFile(path)) {
            while (events.hasMoreEvents()) {
                var e = events.readEvent();
                String type = e.getEventType().getName();
                if (type.equals("jdk.VirtualThreadPinned")) {
                    pins++; pinNanos += e.getDuration().toNanos();
                    String frame = e.getStackTrace() == null ? "unknown" : e.getStackTrace().getFrames().stream()
                        .map(f -> f.getMethod().getType().getName() + "." + f.getMethod().getName())
                        .filter(s -> s.startsWith("org.sqlite.")).findFirst().orElse("not-sqlite");
                    pinStacks.merge(frame, 1L, Long::sum);
                }
                if (type.equals("jdk.NativeMethodSample")) {
                    allNativeSamples++;
                    if (e.getStackTrace() != null) {
                        var frame = e.getStackTrace().getFrames().stream().map(f -> f.getMethod().getType().getName() + "." + f.getMethod().getName())
                            .filter(s -> s.startsWith("org.sqlite.core.NativeDB.")).findFirst();
                        if (frame.isPresent()) { nativeSamples++; nativeStacks.merge(frame.get(), 1L, Long::sum); }
                    }
                }
            }
        }
        return Map.of("pinned_events", pins, "pinned_total_ms", pinNanos / 1e6, "pinned_sqlite_frames", pinStacks,
            "native_samples", allNativeSamples, "sqlite_native_samples", nativeSamples, "sqlite_native_frames", nativeStacks);
    }

    static Recording recording() {
        var r = new Recording();
        r.enable("jdk.VirtualThreadPinned").withThreshold(Duration.ZERO).withStackTrace();
        r.enable("jdk.NativeMethodSample").withPeriod(Duration.ofMillis(10)).withStackTrace();
        r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10)).withStackTrace();
        r.enable("jdk.GarbageCollection");
        return r;
    }

    static Recording profileRecording() {
        var r = recording();
        r.enable("jdk.GCPhasePause").withThreshold(Duration.ZERO);
        r.enable("jdk.SafepointBegin").withThreshold(Duration.ZERO);
        r.enable("jdk.SafepointStateSynchronization").withThreshold(Duration.ZERO);
        r.enable("jdk.SafepointEnd").withThreshold(Duration.ZERO);
        return r;
    }

    /** Opt-in collectors only; resource order drains request executors before error-path stops. */
    static final class MeasurementProfile implements AutoCloseable {
        final Map<String, Object> result;
        final Path output;
        final boolean enabled;
        final Integer mode;
        final Class<?> probe;
        final Object profiler;
        final java.lang.reflect.Method execute;
        boolean probeStarted, profilerStarted;

        MeasurementProfile(Path output, Map<String, Object> result) throws Exception {
            this.output = output;
            this.result = result;
            String configuredMode = System.getProperty("benchmark.cost.mode");
            mode = configuredMode == null ? null : Integer.valueOf(configuredMode);
            probe = mode == null ? null : Class.forName("org.sqlite.core.CostProbe");
            String library = System.getProperty("benchmark.async.library");
            enabled = library != null || Boolean.getBoolean("benchmark.cost.profile");
            if (library == null) {
                profiler = null; execute = null;
            } else {
                Class<?> api = Class.forName("one.profiler.AsyncProfiler");
                profiler = invoke(api.getMethod("getInstance", String.class), null, library);
                execute = api.getMethod("execute", String.class);
            }
        }

        static Object invoke(java.lang.reflect.Method method, Object receiver, Object... args) throws Exception {
            try { return method.invoke(receiver, args); }
            catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof Exception cause) throw cause;
                if (e.getCause() instanceof Error cause) throw cause;
                throw e;
            }
        }

        void start() throws Exception {
            if (profiler != null) {
                // record-cpu forces Linux perf_events: event=cpu alone can silently select wall.
                String command = "start,jfr,event=cpu,record-cpu,interval=1ms,alloc=512k,lock=10ms,cstack=dwarf,file="
                    + output.resolve("async-profile.jfr");
                result.put("async_profile", Map.of("file", "async-profile.jfr", "command", command,
                    "version", invoke(profiler.getClass().getMethod("getVersion"), profiler)));
                invoke(execute, profiler, command);
                profilerStarted = true;
            }
            if (probe != null) {
                invoke(probe.getMethod("start", int.class), null, mode);
                probeStarted = true;
            }
        }

        public void close() throws Exception {
            Exception failure = null;
            try {
                if (probeStarted) {
                    probeStarted = false;
                    result.put("native_cost", invoke(probe.getMethod("stop"), null));
                }
            } catch (Exception e) { failure = e; }
            finally {
                if (profilerStarted) {
                    profilerStarted = false;
                    try { invoke(execute, profiler, "stop"); }
                    catch (Exception e) {
                        if (failure == null) failure = e; else failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) throw failure;
        }
    }

    static void positiveControl(Path out) throws Exception {
        try (var c = DriverManager.getConnection("jdbc:sqlite::memory:"); var r = recording()) {
            Function.create(c, "pin_probe", new Function() {
                protected void xFunc() throws SQLException {
                    try { Thread.sleep(30); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new SQLException(e); }
                    result(1);
                }
            });
            r.start();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                executor.submit(() -> { try (var s = c.createStatement(); var rows = s.executeQuery("select pin_probe()")) {
                    if (!rows.next() || rows.getInt(1) != 1) throw new AssertionError("positive control result");
                } catch (SQLException e) { throw new RuntimeException(e); } }).get();
            }
            r.stop(); r.dump(out);
        }
        if (((Number)recordingSummary(out).get("pinned_events")).longValue() == 0) throw new AssertionError("JFR positive control recorded no pin");
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]).toAbsolutePath();
        int seconds = Integer.parseInt(args[1]), clients = Integer.parseInt(args[2]), copies = Integer.parseInt(args[3]);
        int backupPages = Integer.parseInt(args[4]);
        int warmupRequests = args.length > 5 ? Integer.parseInt(args[5]) : 120;
        if (clients < 1 || warmupRequests < clients) throw new IllegalArgumentException("warmup requests must be >= clients");
        Files.createDirectories(out);
        positiveControl(out.resolve("positive-control.jfr"));
        var result = new LinkedHashMap<String, Object>();
        result.put("java", System.getProperty("java.runtime.version"));
        result.put("driver_location", org.sqlite.JDBC.class.getProtectionDomain().getCodeSource().getLocation().toString());
        result.put("scheduler_parallelism", System.getProperty("jdk.virtualThreadScheduler.parallelism"));
        result.put("backup_pages_per_step", backupPages);
        result.put("clients", clients); result.put("backup_concurrency", copies); result.put("requested_seconds", seconds);
        result.put("positive_control", recordingSummary(out.resolve("positive-control.jfr")));
        Path data = out.resolve("data");
        try (var fixture = new GatewayFixture(data, -1); var client = new GatewayHttpClient(fixture.server.getURI())) {
            var thread = client.send("/test/runtime", "GET", null, Map.of());
            if (!Boolean.TRUE.equals(client.object(thread).get("virtual"))) throw new AssertionError("HTTP is not virtual-thread based");
            String token = client.authorize(SecurityFixture.RESOURCE, "A", List.of("clinical.lab.read"));
            try (var c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("platform.db")); var s = c.createStatement()) {
                try (var rows = s.executeQuery("select sqlite_version(), sqlite_source_id()")) {
                    rows.next(); result.put("sqlite_version", rows.getString(1)); result.put("sqlite_source_id", rows.getString(2));
                }
                var options = new ArrayList<String>();
                try (var rows = s.executeQuery("pragma compile_options")) { while (rows.next()) options.add(rows.getString(1)); }
                result.put("compile_options", options);
            }
            // Warm both HTTP and database paths; startup/authentication are not timed.
            long warmupStart = System.nanoTime();
            int warmupSourceBefore = fixture.sourceCalls.get();
            try (var warm = Executors.newFixedThreadPool(clients)) {
                List<Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < clients; i++) {
                    int count = warmupRequests / clients + (i < warmupRequests % clients ? 1 : 0);
                    futures.add(warm.submit(() -> {
                        for (int n = 0; n < count; n++) {
                            try { var response = client.call(token, ARGUMENTS);
                                if (response.statusCode() != 200 || !response.body().contains("SUCCEEDED")) throw new AssertionError(response.body());
                            } catch (Exception e) { throw new RuntimeException(e); }
                        }
                    }));
                }
                for (var f : futures) f.get();
            }
            result.put("warmup_requests", warmupRequests);
            result.put("warmup_elapsed_seconds", (System.nanoTime() - warmupStart) / 1e9);
            result.put("warmup_source_calls", fixture.sourceCalls.get() - warmupSourceBefore);
            if (fixture.sourceCalls.get() - warmupSourceBefore != warmupRequests) throw new AssertionError("Warmup source call mismatch");
            Samples latency = new Samples(), heartbeat = new Samples(), timerJitter = new Samples(), backupLatency = new Samples();
            AtomicLong succeeded = new AtomicLong(), failures = new AtomicLong(), backupSucceeded = new AtomicLong();
            var errors = new ConcurrentHashMap<String, AtomicInteger>();
            int sourceBefore = fixture.sourceCalls.get();
            List<Connection> backupSources = new ArrayList<>(), holders = new ArrayList<>();
            List<Path> destinations = new ArrayList<>();
            for (int i = 0; i < copies; i++) {
                backupSources.add(DriverManager.getConnection("jdbc:sqlite:" + data.resolve("platform.db")));
                Path destination = data.resolve("snapshot-" + i + ".db"); destinations.add(destination);
                var holder = DriverManager.getConnection("jdbc:sqlite:" + destination);
                try (var s = holder.createStatement()) { s.execute("pragma journal_mode=DELETE"); s.execute("create table seed(n)"); }
                holders.add(holder);
            }
            try (var profile = new MeasurementProfile(out, result);
                 var rec = profile.enabled ? profileRecording() : recording();
                 var vt = Executors.newVirtualThreadPerTaskExecutor();
                 var workers = Executors.newFixedThreadPool(clients); var timer = Executors.newSingleThreadScheduledExecutor()) {
                rec.start();
                profile.start();
                long start = System.nanoTime(), deadline = start + TimeUnit.SECONDS.toNanos(seconds);
                long cpuStart = ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos();
                AtomicLong tick = new AtomicLong(start);
                var heartbeatTask = timer.scheduleAtFixedRate(() -> {
                    long expected = tick.getAndAdd(10_000_000), submitted = System.nanoTime();
                    timerJitter.add(Math.max(0, submitted - expected));
                    vt.submit(() -> heartbeat.add(System.nanoTime() - submitted));
                }, 0, 10, TimeUnit.MILLISECONDS);
                // Controlled online-backup interference: destination readers/writers hold a lock for
                // 750ms every 2s. Lock holders are platform threads, independent of VT starvation.
                Thread backups = Thread.ofPlatform().start(() -> {
                    try {
                        for (long due = start + 1_000_000_000L; copies > 0 && due + 1_000_000_000L < deadline; due += 2_000_000_000L) {
                            TimeUnit.NANOSECONDS.sleep(Math.max(0, due - System.nanoTime()));
                            for (var h : holders) try (var s = h.createStatement()) { s.execute("BEGIN EXCLUSIVE"); }
                            List<Future<?>> tasks = new ArrayList<>();
                            for (int i = 0; i < copies; i++) {
                                int index = i;
                                tasks.add(vt.submit(() -> {
                                    long t = System.nanoTime();
                                    try {
                                        int rc = ((SQLiteConnection)backupSources.get(index)).getDatabase().backup("main", destinations.get(index).toString(), null, 10, 300, backupPages);
                                        if (rc != 0) throw new AssertionError("backup rc=" + rc);
                                        backupSucceeded.incrementAndGet();
                                    } catch (Exception e) { throw new RuntimeException(e); }
                                    finally { backupLatency.add(System.nanoTime() - t); }
                                }));
                            }
                            Thread.sleep(750);
                            for (var h : holders) try (var s = h.createStatement()) { s.execute("ROLLBACK"); }
                            for (var f : tasks) f.get(10, TimeUnit.SECONDS);
                        }
                    } catch (Throwable e) { errors.computeIfAbsent("backup:" + e, k -> new AtomicInteger()).incrementAndGet(); }
                });
                List<Future<?>> requests = new ArrayList<>();
                for (int i = 0; i < clients; i++) requests.add(workers.submit(() -> {
                    while (System.nanoTime() < deadline) {
                        long t = System.nanoTime();
                        try {
                            var response = client.call(token, ARGUMENTS);
                            if (response.statusCode() != 200 || !response.body().contains("SUCCEEDED") || !response.body().contains("합성 정상 결과"))
                                throw new IllegalStateException("HTTP " + response.statusCode() + ":" + response.body());
                            succeeded.incrementAndGet();
                        } catch (Throwable e) { failures.incrementAndGet(); errors.computeIfAbsent(e.toString(), k -> new AtomicInteger()).incrementAndGet(); }
                        latency.add(System.nanoTime() - t);
                    }
                }));
                for (var f : requests) f.get(seconds + 30, TimeUnit.SECONDS);
                backups.join(15_000);
                if (backups.isAlive()) throw new AssertionError("backup controller did not finish");
                heartbeatTask.cancel(false);
                timer.shutdown(); timer.awaitTermination(5, TimeUnit.SECONDS);
                vt.shutdown(); if (!vt.awaitTermination(15, TimeUnit.SECONDS)) throw new AssertionError("VTs did not finish");
                long end = System.nanoTime();
                long cpuEnd = ProcessHandle.current().info().totalCpuDuration().orElseThrow().toNanos();
                profile.close();
                if (profile.enabled) { rec.stop(); rec.dump(out.resolve("application.jfr")); }
                result.put("elapsed_seconds", (end - start) / 1e9);
                result.put("cpu_seconds", (cpuEnd - cpuStart) / 1e9);
                result.put("successes", succeeded.get()); result.put("failures", failures.get());
                result.put("requests_per_second", succeeded.get() / ((end - start) / 1e9));
                result.put("source_calls", fixture.sourceCalls.get() - sourceBefore);
                result.put("backup_successes", backupSucceeded.get());
                result.put("http_latency", latency.summary()); result.put("heartbeat_delay", heartbeat.summary());
                result.put("timer_jitter", timerJitter.summary()); result.put("backup_latency", backupLatency.summary());
                result.put("errors", errors);
                if (!profile.enabled) { rec.stop(); rec.dump(out.resolve("application.jfr")); }
            } finally {
                for (var c : holders) c.close(); for (var c : backupSources) c.close();
            }
            result.put("jfr", recordingSummary(out.resolve("application.jfr")));
            latency.save(out.resolve("http-latency.csv")); heartbeat.save(out.resolve("heartbeat-delay.csv"));
            timerJitter.save(out.resolve("timer-jitter.csv")); backupLatency.save(out.resolve("backup-latency.csv"));
            for (Path file : java.util.stream.Stream.concat(java.util.stream.Stream.of(data.resolve("platform.db")), destinations.stream()).toList()) {
                try (var c = DriverManager.getConnection("jdbc:sqlite:" + file); var s = c.createStatement(); var rows = s.executeQuery("pragma integrity_check")) {
                    if (!rows.next() || !"ok".equals(rows.getString(1))) throw new AssertionError("integrity: " + file);
                }
            }
            result.put("integrity", "ok");
            Files.writeString(out.resolve("result.json"), client.json.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            System.out.println("BENCHMARK_RESULT " + client.json.writeValueAsString(result));
            if (failures.get() != 0 || !errors.isEmpty() || fixture.sourceCalls.get() - sourceBefore != succeeded.get())
                throw new AssertionError("Application outcome mismatch");
        }
    }
}
