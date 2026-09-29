package io.gateway;

import static io.gateway.LockCorrectness.await;
import static io.gateway.LockCorrectness.check;
import static io.gateway.LockCorrectness.execute;
import static io.gateway.LockCorrectness.expectCode;
import static io.gateway.LockCorrectness.finished;
import static io.gateway.LockCorrectness.scalar;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.sqlite.BusyHandler;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;

/** Observable transaction and policy boundaries of public prepared-update calls. */
public final class ScopeTransactionCases {
    public static List<String> names() {
        return List.of("transaction_rollback", "savepoint_rollback_release", "constraint_reuse",
                "readonly_upgrade", "upgrade_writer_busy", "commit_restart_pending",
                "rollback_restart_pending", "query_timeout_success", "query_timeout_failure",
                "busy_timeout_setter", "cancel_busy_callback", "interrupt_busy_callback", "query_timeout_setter_race");
    }

    public static Map<String, Object> run(String scenario, Path data) throws Exception {
        return switch (scenario) {
            case "transaction_rollback" -> transaction(data);
            case "savepoint_rollback_release" -> savepoint(data);
            case "constraint_reuse" -> constraint(data);
            case "readonly_upgrade" -> readOnly(data);
            case "upgrade_writer_busy" -> upgradeBusy(data);
            case "commit_restart_pending" -> restart(data, true);
            case "rollback_restart_pending" -> restart(data, false);
            case "query_timeout_success" -> timeoutSuccess(data);
            case "query_timeout_failure" -> timeoutFailure(data);
            case "busy_timeout_setter" -> timeoutSetter(data);
            case "cancel_busy_callback" -> cancelOrInterrupt(data, false);
            case "interrupt_busy_callback" -> cancelOrInterrupt(data, true);
            case "query_timeout_setter_race" -> queryTimeoutSetterRace(data);
            default -> throw new IllegalArgumentException("unknown transaction scenario: " + scenario);
        };
    }

    private static SQLiteConnection open(Path file, int timeout, boolean explicitReadOnly)
            throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(timeout);
        config.setTransactionMode(SQLiteConfig.TransactionMode.DEFERRED);
        config.setExplicitReadOnly(explicitReadOnly);
        return (SQLiteConnection) DriverManager.getConnection(
                "jdbc:sqlite:" + file, config.toProperties());
    }

    private static Map<String, Object> failure(SQLException failure) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class", failure == null ? null : failure.getClass().getName());
        result.put("sqlState", failure == null ? null : failure.getSQLState());
        result.put("errorCode", failure == null ? null : failure.getErrorCode());
        return result;
    }

    private static SQLException attempt(LockCorrectness.SqlAction action) throws Exception {
        try {
            action.run();
            return null;
        } catch (SQLException failure) {
            return failure;
        }
    }

    private static Map<String, Object> policy(SQLiteConnection connection) throws SQLException {
        return Map.of("pragma", scalar(connection, "pragma busy_timeout"),
                "jdbc", connection.getBusyTimeout(),
                "config", connection.getDatabase().getConfig().getBusyTimeout());
    }

    private static void policyIs(SQLiteConnection connection, int expected) throws SQLException {
        check(scalar(connection, "pragma busy_timeout") == expected, "native busy policy changed");
        check(connection.getBusyTimeout() == expected, "JDBC busy policy changed");
        check(connection.getDatabase().getConfig().getBusyTimeout() == expected,
                "configuration busy policy changed");
    }

    private static Map<String, Object> transaction(Path data) throws Exception {
        Path file = data.resolve("transaction.db");
        try (SQLiteConnection writer = open(file, 0, false);
                SQLiteConnection observer = open(file, 0, false)) {
            execute(writer, "create table t(id integer primary key, v integer)");
            execute(writer, "insert into t values(1,10)");
            writer.setAutoCommit(false);
            try (PreparedStatement update = writer.prepareStatement("update t set v=? where id=1")) {
                update.setInt(1, 20);
                int changed = update.executeUpdate();
                long own = scalar(writer, "select v from t");
                long outside = scalar(observer, "select v from t");
                check(changed == 1 && own == 20 && outside == 10, "explicit update escaped its transaction");
                writer.rollback();
                long rolledBack = scalar(writer, "select v from t");
                check(rolledBack == 10 && !writer.getAutoCommit(), "rollback lost transaction ownership");
                // Reuse the unchanged parameter after rollback; exercise both public update widths.
                long reused = update.executeLargeUpdate();
                writer.commit();
                long committed = scalar(observer, "select v from t");
                check(reused == 1 && committed == 20 && !writer.getAutoCommit(), "commit/reuse lost data");
                return Map.of("changed", changed, "ownBeforeRollback", own,
                        "outsideBeforeRollback", outside, "rolledBack", rolledBack,
                        "reused", reused, "committed", committed, "autoCommit", writer.getAutoCommit());
            }
        }
    }

    private static Map<String, Object> savepoint(Path data) throws Exception {
        Path file = data.resolve("savepoint.db");
        try (SQLiteConnection writer = open(file, 0, false);
                SQLiteConnection observer = open(file, 0, false)) {
            execute(writer, "create table t(id integer primary key, v integer)");
            execute(writer, "insert into t values(1,10)");
            writer.setAutoCommit(false);
            try (PreparedStatement update = writer.prepareStatement("update t set v=? where id=1")) {
                update.setInt(1, 20);
                check(update.executeUpdate() == 1, "pre-savepoint update missing");
                Savepoint point = writer.setSavepoint("prepared_boundary");
                update.setInt(1, 30);
                check(update.executeLargeUpdate() == 1, "savepoint update missing");
                writer.rollback(point);
                long afterRollback = scalar(writer, "select v from t");
                check(afterRollback == 20, "savepoint rollback lost earlier transaction work");
                writer.releaseSavepoint(point);
                check(update.executeUpdate() == 1, "released savepoint prevented parameter reuse");
                long outside = scalar(observer, "select v from t");
                check(outside == 10 && !writer.getAutoCommit(), "savepoint release committed outer transaction");
                writer.commit();
                long committed = scalar(observer, "select v from t");
                check(committed == 30, "savepoint transaction committed wrong value");
                return Map.of("afterSavepointRollback", afterRollback,
                        "outsideAfterRelease", outside, "committed", committed,
                        "autoCommit", writer.getAutoCommit());
            }
        }
    }

    private static Map<String, Object> constraint(Path data) throws Exception {
        Path file = data.resolve("constraint.db");
        try (SQLiteConnection writer = open(file, 0, false);
                SQLiteConnection observer = open(file, 0, false)) {
            execute(writer, "create table t(id integer primary key, v integer unique)");
            execute(writer, "insert into t values(1,10),(2,20)");
            writer.setAutoCommit(false);
            execute(writer, "insert into t values(3,30)");
            try (PreparedStatement update = writer.prepareStatement("update t set v=? where id=2")) {
                update.setInt(1, 10);
                SQLException rejected = expectCode(19, update::executeUpdate);
                long unchanged = scalar(writer, "select v from t where id=2");
                long earlierWork = scalar(writer, "select v from t where id=3");
                check(unchanged == 20 && earlierWork == 30 && !writer.getAutoCommit(),
                        "constraint failure damaged transaction data");
                check(scalar(observer, "select count(*) from t") == 2, "constraint failure committed earlier work");
                execute(writer, "delete from t where id=1");
                long reused = update.executeLargeUpdate();
                check(reused == 1 && scalar(writer, "select v from t where id=2") == 10,
                        "failed update did not retain its parameter");
                writer.rollback();
                long restored = scalar(observer, "select sum(v) from t");
                check(restored == 30 && scalar(observer, "select count(*) from t") == 2,
                        "constraint/reuse rollback did not restore original rows");
                return Map.of("failure", failure(rejected), "unchangedValue", unchanged,
                        "earlierWork", earlierWork, "reused", reused, "restoredSum", restored,
                        "autoCommit", writer.getAutoCommit());
            }
        }
    }

    private static Map<String, Object> readOnly(Path data) throws Exception {
        Path file = data.resolve("readonly.db");
        try (SQLiteConnection observer = open(file, 0, false)) {
            execute(observer, "create table t(v integer)");
            try (SQLiteConnection writer = open(file, 0, true);
                    PreparedStatement insert = writer.prepareStatement("insert into t values(?)")) {
                insert.setInt(1, 7);
                writer.setReadOnly(true);
                writer.setAutoCommit(false);
                SQLException rejected = expectCode(8, insert::executeUpdate);
                long readOnlyPolicy = scalar(writer, "pragma query_only");
                check(writer.isReadOnly() && readOnlyPolicy == 1 && !writer.getAutoCommit(),
                        "read-only rejection lost connection policy");
                check(scalar(observer, "select count(*) from t") == 0, "read-only write changed data");
                writer.rollback();
                writer.setReadOnly(false);
                Map<String, Object> reuse = new LinkedHashMap<>();
                try { reuse.put("changed", insert.executeLargeUpdate()); }
                catch (SQLException invalidated) { reuse.put("failure", failure(invalidated)); }
                writer.rollback();
                long changed;
                try (PreparedStatement fresh = writer.prepareStatement("insert into t values(?)")) {
                    fresh.setInt(1, 7);
                    changed = fresh.executeLargeUpdate();
                }
                long writePolicy = scalar(writer, "pragma query_only");
                check(changed == 1 && writePolicy == 0 && !writer.isReadOnly(), "read-only transaction failed to upgrade");
                check(scalar(observer, "select count(*) from t") == 0, "upgraded transaction auto-committed");
                writer.commit();
                long committed = scalar(observer, "select v from t");
                check(committed == 7, "upgraded transaction lost its write");
                return Map.of("readOnlyFailure", failure(rejected), "readOnlyQueryPolicy", readOnlyPolicy,
                        "writeQueryPolicy", writePolicy, "changed", changed, "committed", committed,
                        "autoCommit", writer.getAutoCommit(), "sameStatementReuse", reuse);
            }
        }
    }

    private static Map<String, Object> upgradeBusy(Path data) throws Exception {
        Path file = data.resolve("upgrade-busy.db");
        try (SQLiteConnection blocker = open(file, 0, false)) {
            execute(blocker, "create table t(v integer)");
            try (SQLiteConnection writer = open(file, 0, true);
                    PreparedStatement insert = writer.prepareStatement("insert into t values(?)")) {
                insert.setInt(1, 7);
                writer.setAutoCommit(false);
                blocker.setAutoCommit(false);
                execute(blocker, "insert into t values(99)");
                SQLException rejected = expectCode(5, insert::executeLargeUpdate);
                check(!writer.getAutoCommit(), "failed BEGIN IMMEDIATE changed JDBC auto-commit");
                check(scalar(blocker, "select sum(v) from t") == 99, "failed upgrade ran user DML");
                blocker.rollback();
                long reused = insert.executeLargeUpdate();
                check(reused == 1 && scalar(writer, "select v from t") == 7, "pending upgrade did not recover");
                long outside = scalar(blocker, "select count(*) from t");
                check(outside == 0, "pending upgrade ran user DML in native auto-commit");
                writer.rollback();
                long remaining = scalar(blocker, "select count(*) from t");
                check(remaining == 0 && !writer.getAutoCommit(), "upgraded write escaped rollback");
                return Map.of("failure", failure(rejected), "reused", reused,
                        "outsideBeforeRollback", outside, "remaining", remaining,
                        "autoCommit", writer.getAutoCommit(), "policy", policy(writer));
            }
        }
    }

    private static Map<String, Object> restart(Path data, boolean commit) throws Exception {
        Path file = data.resolve(commit ? "commit-restart.db" : "rollback-restart.db");
        try (SQLiteConnection writer = open(file, 0, false);
                SQLiteConnection reader = open(file, 0, false)) {
            execute(writer, "create table t(v integer)");
            execute(writer, "insert into t values(1)");
            execute(writer, "create temp table temp_t(v integer)");
            execute(writer, "insert into temp_t values(0)");
            try (PreparedStatement update = writer.prepareStatement("update temp_t set v=?");
                    PreparedStatement insert = writer.prepareStatement("insert into temp_t values(?)")) {
                update.setInt(1, 1);
                insert.setInt(1, 2);
                writer.setAutoCommit(false);
                reader.setAutoCommit(false);
                check(scalar(reader, "select v from t") == 1, "main reader lock missing");
                writer.getConnectionConfig().setTransactionMode(SQLiteConfig.TransactionMode.EXCLUSIVE);
                check(update.executeUpdate() == 1, "TEMP update failed");
                SQLException control = expectCode(5, () -> {
                    if (commit) writer.commit(); else writer.rollback();
                });
                check(!writer.getAutoCommit(), "failed restart changed JDBC transaction ownership");
                SQLException pending = expectCode(5, insert::executeLargeUpdate);
                check(!writer.getAutoCommit(), "blocked pending SQL changed auto-commit");
                reader.setAutoCommit(true);
                // Recover through the next prepared update, not by explicitly retrying COMMIT/ROLLBACK.
                long recovered = insert.executeLargeUpdate();
                long beforeRollback = scalar(writer, "select sum(v) from temp_t");
                check(recovered == 1 && beforeRollback == (commit ? 3 : 2),
                        "completed control operation was replayed or pending SQL ran early");
                writer.rollback();
                long count = scalar(writer, "select count(*) from temp_t");
                long retained = scalar(writer, "select v from temp_t");
                check(count == 1 && retained == (commit ? 1 : 0),
                        "prepared recovery silently auto-committed or repeated completed control work");
                boolean autoCommit = writer.getAutoCommit();
                writer.setAutoCommit(true);
                check(scalar(reader, "select v from t") == 1, "TEMP recovery damaged main database");
                return Map.of("controlFailure", failure(control), "pendingFailure", failure(pending),
                        "recovered", recovered, "sumBeforeRollback", beforeRollback,
                        "retainedRows", count, "retainedValue", retained, "autoCommit", autoCommit);
            } finally {
                if (!reader.getAutoCommit()) reader.setAutoCommit(true);
            }
        }
    }

    private static Map<String, Object> timeoutSuccess(Path data) throws Exception {
        Path file = data.resolve("timeout-success.db");
        try (SQLiteConnection writer = open(file, 37, false);
                SQLiteConnection observer = open(file, 0, false)) {
            execute(writer, "create table t(v integer)");
            writer.setAutoCommit(false);
            try (PreparedStatement insert = writer.prepareStatement("insert into t values(?)")) {
                insert.setInt(1, 7);
                insert.setQueryTimeout(1);
                long changed = insert.executeLargeUpdate();
                policyIs(writer, 37);
                Map<String, Object> restored = policy(writer);
                check(changed == 1 && scalar(observer, "select count(*) from t") == 0,
                        "successful timed update escaped transaction");
                writer.commit();
                long committed = scalar(observer, "select v from t");
                check(committed == 7, "successful timed update lost data");
                return Map.of("changed", changed, "queryTimeout", insert.getQueryTimeout(),
                        "restoredPolicy", restored, "committed", committed,
                        "autoCommit", writer.getAutoCommit());
            }
        }
    }

    private static Map<String, Object> timeoutFailure(Path data) throws Exception {
        Path file = data.resolve("timeout-failure.db");
        try (SQLiteConnection blocker = open(file, 0, false);
                SQLiteConnection writer = open(file, 37, false)) {
            execute(blocker, "create table t(v integer)");
            try (PreparedStatement insert = writer.prepareStatement("insert into t values(?)")) {
                insert.setInt(1, 7);
                insert.setQueryTimeout(1);
                writer.setAutoCommit(false);
                blocker.setAutoCommit(false);
                execute(blocker, "insert into t values(99)");
                SQLException rejected = expectCode(5, insert::executeUpdate);
                policyIs(writer, 37);
                Map<String, Object> restored = policy(writer);
                check(!writer.getAutoCommit() && scalar(blocker, "select sum(v) from t") == 99,
                        "timed failure damaged transaction ownership or data");
                blocker.rollback();
                check(insert.executeLargeUpdate() == 1, "timed failure lost reusable parameters");
                policyIs(writer, 37);
                check(scalar(blocker, "select count(*) from t") == 0, "timed retry auto-committed");
                writer.rollback();
                long remaining = scalar(blocker, "select count(*) from t");
                check(remaining == 0, "timed retry escaped rollback");
                return Map.of("failure", failure(rejected), "queryTimeout", insert.getQueryTimeout(),
                        "restoredPolicy", restored, "remaining", remaining,
                        "autoCommit", writer.getAutoCommit());
            }
        }
    }

    private static final class BusyGate extends BusyHandler {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Thread> caller = new AtomicReference<>();
        final AtomicBoolean timedOut = new AtomicBoolean();
        final boolean retry;

        BusyGate(boolean retry) { this.retry = retry; }

        @Override
        protected int callback(int previousCalls) {
            int call = calls.incrementAndGet();
            if (call != 1) return 0; // An unexpected second lock conflict fails rather than loops.
            caller.set(Thread.currentThread());
            entered.countDown();
            boolean restoreInterrupt = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try {
                while (release.getCount() != 0) {
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        timedOut.set(true);
                        return 0;
                    }
                    try {
                        if (!release.await(left, TimeUnit.NANOSECONDS)) {
                            timedOut.set(true);
                            return 0;
                        }
                    } catch (InterruptedException expected) {
                        restoreInterrupt = true;
                        interrupted.countDown();
                    }
                }
                return retry ? 1 : 0;
            } finally {
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
        }
    }

    private static Map<String, Object> timeoutSetter(Path data) throws Exception {
        Path file = data.resolve("timeout-setter.db");
        try (SQLiteConnection blocker = open(file, 0, false);
                SQLiteConnection writer = open(file, 200, false);
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            execute(blocker, "create table t(v integer)");
            try (PreparedStatement insert = writer.prepareStatement("insert into t values(?)")) {
                insert.setInt(1, 7);
                writer.setAutoCommit(false);
                blocker.setAutoCommit(false);
                execute(blocker, "insert into t values(99)");
                BusyGate gate = new BusyGate(false);
                // Positive queryTimeout would replace this custom handler; keep this legacy-path case separate.
                BusyHandler.setHandler(writer, gate);
                Properties properties = writer.getDatabase().getConfig().toProperties();
                AtomicBoolean gateOpened = new AtomicBoolean();
                AtomicBoolean returnedBeforeRelease = new AtomicBoolean();
                AtomicReference<Thread> executing = new AtomicReference<>();
                Future<SQLException> update = workers.submit(() -> {
                    executing.set(Thread.currentThread());
                    return expectCode(5, insert::executeUpdate);
                });
                Future<?> setter = null;
                try {
                    await(gate.entered, "prepared update did not reach custom busy handler");
                    setter = workers.submit(() -> {
                        writer.setBusyTimeout(777);
                        returnedBeforeRelease.set(!gateOpened.get());
                        return null;
                    });
                    // Public Properties publication proves the setter entered its method, not merely its task.
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!"777".equals(properties.getProperty("busy_timeout"))
                            && System.nanoTime() < deadline) Thread.onSpinWait();
                    check("777".equals(properties.getProperty("busy_timeout")), "setter did not publish policy intent");
                    try {
                        setter.get(1, TimeUnit.SECONDS);
                        throw new AssertionError("busy timeout setter completed inside the busy callback");
                    } catch (TimeoutException expected) {
                        // Readiness is established above; this is not the sole serialization observation.
                    }
                } finally {
                    gateOpened.set(true);
                    gate.release.countDown();
                }
                SQLException rejected = finished(update);
                finished(setter);
                check(!gate.timedOut.get() && gate.calls.get() == 1, "busy callback gate did not complete normally");
                check(gate.caller.get() == executing.get(), "custom handler left its JDBC caller thread");
                check(!returnedBeforeRelease.get(), "setter bypassed active native operation");
                policyIs(writer, 777);
                Map<String, Object> finalPolicy = policy(writer);
                blocker.rollback();
                check(insert.executeLargeUpdate() == 1, "setter left prepared update unusable");
                writer.rollback();
                long remaining = scalar(blocker, "select count(*) from t");
                check(remaining == 0, "busy setter scenario committed failed/retried work");
                return Map.of("failure", failure(rejected), "callbackOnCaller", true,
                        "setterCompletedBeforeRelease", returnedBeforeRelease.get(),
                        "finalPolicy", finalPolicy, "remaining", remaining,
                        "autoCommit", writer.getAutoCommit());
            }
        }
    }

    private static Map<String, Object> cancelOrInterrupt(Path data, boolean interrupt) throws Exception {
        Path file = data.resolve(interrupt ? "interrupt-busy.db" : "cancel-busy.db");
        Map<String, Object> result = new LinkedHashMap<>();
        try (SQLiteConnection blocker = open(file, 0, false)) {
            execute(blocker, "create table t(v integer)");
            try (SQLiteConnection writer = open(file, 200, false);
                    PreparedStatement insert = writer.prepareStatement("insert into t values(?)");
                    var workers = Executors.newVirtualThreadPerTaskExecutor()) {
                execute(writer, "create temp table temp_t(v integer)");
                writer.setAutoCommit(false);
                execute(writer, "insert into temp_t values(10)");
                insert.setInt(1, 7);
                blocker.setAutoCommit(false);
                execute(blocker, "insert into t values(99)");
                BusyGate gate = new BusyGate(true);
                BusyHandler.setHandler(writer, gate);
                // A custom policy reports zero through PRAGMA; its JDBC configured timeout remains 200.
                Map<String, Object> beforePolicy = policy(writer);
                AtomicReference<Thread> executing = new AtomicReference<>();
                AtomicBoolean interruptAtReturn = new AtomicBoolean();
                AtomicInteger changed = new AtomicInteger(-1);
                Future<SQLException> update = workers.submit(() -> {
                    executing.set(Thread.currentThread());
                    SQLException failure = attempt(() -> changed.set(insert.executeUpdate()));
                    interruptAtReturn.set(Thread.currentThread().isInterrupted());
                    return failure;
                });
                try {
                    await(gate.entered, "prepared update did not reach cancellable busy callback");
                    if (interrupt) {
                        executing.get().interrupt();
                        await(gate.interrupted, "busy callback did not observe Java interruption");
                    } else {
                        Future<?> cancellation = workers.submit(() -> { insert.cancel(); return null; });
                        finished(cancellation);
                    }
                    check(!update.isDone(), "update escaped its closed busy callback");
                    // Remove the real file lock before allowing a retry: no timeout masquerades as cancellation.
                    blocker.rollback();
                } finally {
                    gate.release.countDown();
                }
                SQLException rejected = finished(update);
                check(!gate.timedOut.get() && gate.calls.get() == 1, "busy callback hit a deadline or second conflict");
                check(gate.caller.get() == executing.get(), "busy callback did not run on its JDBC caller");
                if (rejected != null) {
                    int code = rejected.getErrorCode() & 0xff;
                    check(code == 9 || code == 5, "unexpected cancellation/interrupt failure code: " + code);
                } else {
                    check(changed.get() == 1, "successful retry affected wrong number of rows");
                }
                long ownRows = scalar(writer, "select count(*) from t");
                long outsideRows = scalar(blocker, "select count(*) from t");
                long tempRows = scalar(writer, "select count(*) from temp_t");
                check(ownRows == (rejected == null ? 1 : 0) && outsideRows == 0,
                        "canceled/interrupted update lost atomicity or committed transaction work");
                Map<String, Object> afterPolicy = policy(writer);
                check(beforePolicy.equals(afterPolicy), "cancellation/interruption changed custom busy policy");
                check(!writer.getAutoCommit(), "cancellation/interruption changed JDBC transaction ownership");
                SQLException rollbackFailure = attempt(writer::rollback);
                if (rollbackFailure != null) {
                    // SQLite may auto-roll back an interrupted write transaction; report any JDBC/native mismatch.
                    check((rollbackFailure.getErrorCode() & 0xff) == 1 && tempRows == 0,
                            "rollback failed despite retained transaction data");
                }
                check(scalar(writer, "select count(*) from temp_t") == 0, "cleanup rollback retained TEMP work");
                result.put("failure", failure(rejected));
                result.put("changed", changed.get());
                result.put("callbackOnCaller", true);
                result.put("operationPendingAtCancellation", true);
                result.put("javaInterruptAtReturn", interruptAtReturn.get());
                result.put("ownRowsBeforeRollback", ownRows);
                result.put("outsideRowsBeforeRollback", outsideRows);
                result.put("tempRowsBeforeRollback", tempRows);
                result.put("rollbackFailure", failure(rollbackFailure));
                result.put("policy", afterPolicy);
                result.put("autoCommit", writer.getAutoCommit());
            }
            long committed = scalar(blocker, "select count(*) from t");
            check(committed == 0, "cancel/interrupt left committed work after connection close");
            result.put("committedRowsAfterClose", committed);
        }
        return result;
    }

    private static Map<String, Object> queryTimeoutSetterRace(Path data) throws Exception {
        try (SQLiteConnection connection = open(data.resolve("query-timeout-setter.db"), 200, false);
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            execute(connection, "create table t(v)");
            execute(connection, "insert into t values(0)");
            LockCorrectness.Gate gate = new LockCorrectness.Gate();
            org.sqlite.Function.create(connection, "scope_gate", gate);
            try (PreparedStatement update = connection.prepareStatement("update t set v=scope_gate()")) {
                update.setQueryTimeout(1);
                Future<Integer> execution = workers.submit(() -> { return update.executeUpdate(); });
                Future<?> setter = null;
                int visibleBeforeRelease;
                try {
                    await(gate.entered, "query timeout update did not enter callback");
                    CountDownLatch attempted = new CountDownLatch(1);
                    setter = workers.submit(() -> {
                        attempted.countDown();
                        connection.setBusyTimeout(777);
                        return null;
                    });
                    await(attempted, "timeout setter did not start");
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    while (connection.getBusyTimeout() != 777 && System.nanoTime() < deadline)
                        java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                    visibleBeforeRelease = connection.getBusyTimeout();
                } finally {
                    gate.release.countDown();
                }
                check(finished(execution) == 1, "query timeout update failed");
                finished(setter);
                int configured = connection.getBusyTimeout();
                long nativeTimeout = scalar(connection, "pragma busy_timeout");
                check(scalar(connection, "select v from t") == 1, "timeout setter changed the SQL outcome");
                return Map.of("configurationBeforeGateRelease", visibleBeforeRelease,
                        "requestedTimeout", 777, "finalJdbcTimeout", configured,
                        "finalNativeTimeout", nativeTimeout, "configurationMatchesNative", configured == nativeTimeout,
                        "finalValue", 1);
            }
        }
    }
}
