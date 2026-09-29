package io.gateway;

import static io.gateway.LockCorrectness.*;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.sqlite.jdbc4.JDBC4Connection;

/** Public extension boundaries and existing metadata monitor order, not new driver test hooks. */
public final class ScopeExtensionCases {
    public static List<String> names() {
        return List.of("extension_peer_query", "metadata_lock_cycle", "external_db_monitor", "external_connection_monitor");
    }

    static final class HookConnection extends JDBC4Connection {
        volatile Thread actor;
        volatile SqlAction afterEnforce;

        HookConnection(Path path) throws SQLException {
            super("jdbc:sqlite:" + path, path.toString(), new Properties());
        }

        @Override
        public void tryEnforceTransactionMode() throws SQLException {
            super.tryEnforceTransactionMode();
            if (Thread.currentThread() == actor) {
                SqlAction action = afterEnforce;
                afterEnforce = null; // Do not recurse when the hook itself uses JDBC.
                if (action != null) {
                    try { action.run(); }
                    catch (SQLException failure) { throw failure; }
                    catch (Exception failure) { throw new SQLException("extension hook failed", failure); }
                }
            }
        }
    }

    static Map<String, Object> peerQuery(Path data) throws Exception {
        try (HookConnection connection = new HookConnection(data.resolve("peer.db"));
             var peers = Executors.newSingleThreadExecutor()) {
            execute(connection, "create table t(n)");
            execute(connection, "insert into t values(0)");
            try (PreparedStatement update = connection.prepareStatement("update t set n=n+1")) {
                AtomicReference<Future<Long>> peer = new AtomicReference<>();
                AtomicBoolean completedInHook = new AtomicBoolean();
                connection.actor = Thread.currentThread();
                connection.afterEnforce = () -> {
                    CountDownLatch entered = new CountDownLatch(1);
                    peer.set(peers.submit(() -> { entered.countDown(); return scalar(connection, "select n from t"); }));
                    await(entered, "peer query did not start");
                    try { peer.get().get(2, TimeUnit.SECONDS); completedInHook.set(true); }
                    catch (TimeoutException scopeBlocksPeer) { /* Observation, not a swallowed SQL failure. */ }
                };
                check(update.executeUpdate() == 1, "update row count");
                long seen = finished(peer.get());
                check(seen == (completedInHook.get() ? 0 : 1), "unexpected peer snapshot");
                check(scalar(connection, "select n from t") == 1, "update data lost");
                return Map.of("peer_completed_inside_hook", completedInHook.get(), "peer_snapshot", seen, "final_value", 1);
            }
        }
    }

    static Map<String, Object> metadataCycle(Path data) throws Exception {
        HookConnection connection = new HookConnection(data.resolve("metadata.db"));
        execute(connection, "create table t(n)");
        execute(connection, "insert into t values(0)");
        var metadata = connection.getMetaData();
        PreparedStatement update = connection.prepareStatement("update t set n=n+1");
        CountDownLatch hookEntered = new CountDownLatch(1), metadataHeld = new CountDownLatch(1);
        AtomicReference<Throwable> updateFailure = new AtomicReference<>(), metadataFailure = new AtomicReference<>();
        AtomicBoolean updated = new AtomicBoolean(), queried = new AtomicBoolean();
        Thread writer = Thread.ofPlatform().daemon(true).name("scope-update").unstarted(() -> {
            try { check(update.executeUpdate() == 1, "update row count"); updated.set(true); }
            catch (Throwable failure) { updateFailure.set(failure); }
        });
        connection.actor = writer;
        connection.afterEnforce = () -> {
            hookEntered.countDown();
            await(metadataHeld, "metadata holder did not start");
            try (var tables = metadata.getTables(null, null, "t", null)) {
                check(tables.next(), "hook metadata query missing table");
            }
        };
        Thread reader = Thread.ofPlatform().daemon(true).name("scope-metadata").unstarted(() -> {
            try {
                // getTables itself is synchronized on this monitor; the outer scope only fixes rendezvous order.
                synchronized (metadata) {
                    metadataHeld.countDown();
                    try (var tables = metadata.getTables(null, null, "t", null)) {
                        check(tables.next(), "metadata query missing table");
                    }
                }
                queried.set(true);
            } catch (Throwable failure) { metadataFailure.set(failure); }
        });
        writer.start();
        await(hookEntered, "execution did not enter extension hook");
        reader.start();
        var threads = ManagementFactory.getThreadMXBean();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean cycle = false;
        while (!(updated.get() && queried.get()) && System.nanoTime() < deadline) {
            if (updateFailure.get() != null || metadataFailure.get() != null) break;
            long[] ids = threads.findDeadlockedThreads();
            if (ids != null && Arrays.stream(ids).anyMatch(id -> id == writer.threadId())
                    && Arrays.stream(ids).anyMatch(id -> id == reader.threadId())) {
                cycle = true;
                StringBuilder dump = new StringBuilder();
                for (var info : threads.getThreadInfo(new long[]{writer.threadId(), reader.threadId()}, true, true))
                    dump.append(info).append('\n');
                Files.writeString(data.getParent().resolve("thread-dump.txt"), dump);
                break;
            }
            Thread.sleep(10);
        }
        if (cycle) {
            // Fresh JVM per case: daemon actors cannot be unwound safely while they own the detected cycle.
            return Map.of("deadlock_detected", true, "update_completed", updated.get(), "metadata_completed", queried.get());
        }
        check(updated.get() && queried.get(), "actors did not complete: " + updateFailure.get() + ", " + metadataFailure.get());
        writer.join(1000); reader.join(1000);
        check(scalar(connection, "select n from t") == 1, "metadata interleaving changed data");
        update.close(); connection.close();
        return Map.of("deadlock_detected", false, "update_completed", true, "metadata_completed", true);
    }

    static Map<String, Object> externalMonitor(Path data, boolean dbMonitor) throws Exception {
        try (var connection = open(data.resolve("external.db")); var workers = Executors.newFixedThreadPool(2)) {
            execute(connection, "create table t(n)");
            execute(connection, "insert into t values(0)");
            try (var update = connection.prepareStatement("update t set n=n+1")) {
                Object monitor = dbMonitor ? connection.getDatabase() : connection;
                CountDownLatch held = new CountDownLatch(1), release = new CountDownLatch(1), attempted = new CountDownLatch(1);
                Future<?> holder = workers.submit(() -> { synchronized (monitor) { held.countDown(); await(release, "external holder release"); } return null; });
                Future<Integer> sql = null;
                boolean beforeRelease = false;
                try {
                    await(held, "external monitor not held");
                    sql = workers.submit(() -> { attempted.countDown(); return update.executeUpdate(); });
                    await(attempted, "SQL actor not started");
                    try { check(sql.get(300, TimeUnit.MILLISECONDS) == 1, "row count"); beforeRelease = true; }
                    catch (TimeoutException excludedByMonitor) { /* Relative completion observation. */ }
                } finally { release.countDown(); }
                finished(holder);
                check(finished(sql) == 1 && scalar(connection, "select n from t") == 1, "external monitor data mismatch");
                return Map.of("sql_completed_before_external_monitor_release", beforeRelease, "final_value", 1);
            }
        }
    }

    public static Map<String, Object> run(String scenario, Path data) throws Exception {
        return switch (scenario) {
            case "extension_peer_query" -> peerQuery(data);
            case "metadata_lock_cycle" -> metadataCycle(data);
            case "external_db_monitor" -> externalMonitor(data, true);
            case "external_connection_monitor" -> externalMonitor(data, false);
            default -> throw new IllegalArgumentException("unknown extension case " + scenario);
        };
    }
}
