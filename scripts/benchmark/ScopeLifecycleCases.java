package io.gateway;

import static io.gateway.LockCorrectness.await;
import static io.gateway.LockCorrectness.check;
import static io.gateway.LockCorrectness.execute;
import static io.gateway.LockCorrectness.expectCode;
import static io.gateway.LockCorrectness.finished;
import static io.gateway.LockCorrectness.open;
import static io.gateway.LockCorrectness.scalar;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.sqlite.Function;
import org.sqlite.SQLiteCommitListener;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteUpdateListener;

/** Public JDBC observations for the prepared-update outer-lock experiment. */
public final class ScopeLifecycleCases {
    private ScopeLifecycleCases() {}

    public static List<String> names() {
        return List.of(
                "closed-update-preflight", "select-update-preflight", "cancel-active-update",
                "close-active-connection", "close-active-statement", "udf-read-reentry",
                "listener-state-reentry", "udf-error-recovery", "listener-update-failure",
                "listener-commit-failure", "generated-keys-lifecycle", "keys-close-on-completion");
    }

    public static Map<String, Object> run(String scenario, Path data) throws Exception {
        return switch (scenario) {
            case "closed-update-preflight" -> preflight(data, true);
            case "select-update-preflight" -> preflight(data, false);
            case "cancel-active-update" -> cancellation(data);
            case "close-active-connection" -> concurrentClose(data, true);
            case "close-active-statement" -> concurrentClose(data, false);
            case "udf-read-reentry" -> udfReentry(data);
            case "listener-state-reentry" -> listenerReentry(data);
            case "udf-error-recovery" -> udfFailure(data);
            case "listener-update-failure" -> updateListenerFailure(data);
            case "listener-commit-failure" -> commitListenerFailure(data);
            case "generated-keys-lifecycle" -> generatedKeys(data);
            case "keys-close-on-completion" -> closeOnCompletion(data);
            default -> throw new IllegalArgumentException("Unknown lifecycle scenario: " + scenario);
        };
    }

    private static Map<String, Object> failure(Throwable error) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("class", error.getClass().getName());
        if (error instanceof SQLException sql) {
            result.put("sql_state", sql.getSQLState());
            result.put("code", sql.getErrorCode());
        }
        return result;
    }

    private static Map<String, Object> observe(LockCorrectness.SqlAction action) throws Exception {
        try {
            action.run();
            return Map.of("completed", true);
        } catch (SQLException | RuntimeException | AssertionError error) {
            return failure(error);
        }
    }

    private static SQLException sqlFailure(LockCorrectness.SqlAction action) throws Exception {
        try {
            action.run();
        } catch (SQLException error) {
            return error;
        }
        throw new AssertionError("expected SQLException");
    }

    private static boolean completedBeforeRelease(Future<?> operation) throws Exception {
        try {
            // A start latch alone does not prove method entry. Give the runnable a generous
            // scheduling window; this is an observation, not proof of a JVM blocked state.
            operation.get(2, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException waiting) {
            return false;
        }
    }

    private static void seed(Connection connection) throws SQLException {
        execute(connection, "create table t(id integer primary key, v integer not null unique)");
        execute(connection, "insert into t values(1,10),(2,20),(3,30)");
    }

    private static List<Long> rows(Connection connection) throws SQLException {
        List<Long> values = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("select v from t order by id")) {
            while (result.next()) values.add(result.getLong(1));
        }
        return values;
    }

    private static List<Long> otherThreadRows(Connection connection) throws Exception {
        var worker = Executors.newVirtualThreadPerTaskExecutor();
        try {
            return finished(worker.submit(() -> rows(connection)));
        } finally {
            worker.shutdownNow();
        }
    }

    private static Map<String, Object> preflight(Path data, boolean closed) throws Exception {
        try (SQLiteConnection connection = open(data.resolve("preflight.db"))) {
            seed(connection);
            LockCorrectness.Gate gate = new LockCorrectness.Gate();
            Function.create(connection, "lifecycle_gate", gate);
            try (PreparedStatement candidate = connection.prepareStatement(
                            closed ? "update t set v=v+1" : "select v from t order by id");
                    PreparedStatement holder = connection.prepareStatement("select lifecycle_gate()")) {
                if (closed) candidate.close();
                var workers = Executors.newVirtualThreadPerTaskExecutor();
                try {
                    Future<Long> held = workers.submit(() -> {
                        try (ResultSet result = holder.executeQuery()) {
                            check(result.next(), "gate query lost its row");
                            long value = result.getLong(1);
                            check(!result.next(), "gate query returned extra rows");
                            return value;
                        }
                    });
                    Future<SQLException> rejected;
                    boolean before;
                    try {
                        await(gate.entered, "holder did not enter native callback");
                        CountDownLatch started = new CountDownLatch(1);
                        rejected = workers.submit(() -> {
                            started.countDown();
                            return sqlFailure(candidate::executeUpdate);
                        });
                        await(started, "preflight worker did not start");
                        before = completedBeforeRelease(rejected);
                        check(!held.isDone(), "holder escaped the unreleased gate");
                    } finally {
                        gate.release.countDown();
                    }
                    SQLException error = finished(rejected);
                    check(finished(held) == 1, "gate SELECT became invalid");
                    List<Long> values = rows(connection);
                    check(values.equals(List.of(10L, 20L, 30L)), "rejected update wrote rows");
                    if (!closed) {
                        try (ResultSet result = candidate.executeQuery()) {
                            check(result.next() && result.getLong(1) == 10,
                                    "invalid executeUpdate poisoned the valid SELECT");
                        }
                    }
                    return Map.of("before_release", before, "failure", failure(error),
                            "rows", values, "gate_value", 1, "statement_closed", candidate.isClosed());
                } finally {
                    gate.release.countDown();
                    workers.shutdownNow();
                }
            }
        }
    }

    private static Map<String, Object> cancellation(Path data) throws Exception {
        Path file = data.resolve("cancel.db");
        try (SQLiteConnection connection = open(file)) {
            seed(connection);
            connection.setAutoCommit(false);
            LockCorrectness.Gate gate = new LockCorrectness.Gate();
            Function.create(connection, "lifecycle_gate", gate);
            try (PreparedStatement update = connection.prepareStatement(
                    "update t set v=v+case when id=2 then lifecycle_gate() else 1 end")) {
                var workers = Executors.newVirtualThreadPerTaskExecutor();
                try {
                    Future<SQLException> execution = workers.submit(
                            () -> expectCode(9, update::executeUpdate));
                    try {
                        await(gate.entered, "UPDATE did not enter native callback");
                        Future<?> cancel = workers.submit(() -> { update.cancel(); return null; });
                        finished(cancel);
                        check(!execution.isDone(), "UPDATE returned before callback release");
                    } finally {
                        gate.release.countDown();
                    }
                    SQLException interrupted = finished(execution);
                    // SQLite may already have rolled the explicit transaction back on INTERRUPT.
                    // Preserve that existing JDBC rollback outcome rather than requiring success.
                    Map<String, Object> rollback = observe(connection::rollback);
                    List<Long> values = otherThreadRows(connection);
                    check(values.equals(List.of(10L, 20L, 30L)), "interrupted UPDATE kept partial rows");
                    try (SQLiteConnection observer = open(file)) {
                        check(rows(observer).equals(values), "interrupted UPDATE committed partial rows");
                    }
                    check(scalar(connection, "select 42") == 42, "cancel poisoned connection");
                    return Map.of("cancel_before_release", true, "failure", failure(interrupted),
                            "rollback", rollback, "rows", values, "statement_closed", update.isClosed());
                } finally {
                    gate.release.countDown();
                    workers.shutdownNow();
                }
            }
        }
    }

    private static Map<String, Object> concurrentClose(Path data, boolean closeConnection)
            throws Exception {
        Path file = data.resolve("close.db");
        try (SQLiteConnection connection = open(file)) {
            seed(connection);
            LockCorrectness.Gate gate = new LockCorrectness.Gate();
            Function.create(connection, "lifecycle_gate", gate);
            try (PreparedStatement update = connection.prepareStatement(
                    "update t set v=v+lifecycle_gate() where id=1")) {
                var workers = Executors.newVirtualThreadPerTaskExecutor();
                try {
                    AtomicInteger count = new AtomicInteger(-1);
                    Future<Map<String, Object>> execution = workers.submit(
                            () -> observe(() -> count.set(update.executeUpdate())));
                    Future<Map<String, Object>> closing;
                    boolean closeBefore;
                    boolean executeBefore;
                    try {
                        await(gate.entered, "close scenario did not enter callback");
                        CountDownLatch started = new CountDownLatch(1);
                        closing = workers.submit(() -> {
                            started.countDown();
                            return observe(() -> {
                                if (closeConnection) connection.close();
                                else update.close();
                            });
                        });
                        await(started, "close worker did not start");
                        closeBefore = completedBeforeRelease(closing);
                        executeBefore = execution.isDone();
                        check(!closeBefore, "close completed while native callback still owns DB");
                        check(!executeBefore, "SQL method completed inside closed callback gate");
                    } finally {
                        gate.release.countDown();
                    }
                    Map<String, Object> sqlOutcome = finished(execution);
                    Map<String, Object> closeOutcome = finished(closing);
                    check(closeOutcome.equals(Map.of("completed", true)), "close failed: " + closeOutcome);
                    check(connection.isClosed() == closeConnection, "wrong connection terminal state");
                    if (!closeConnection) check(update.isClosed(), "statement close lost closed state");
                    SQLException rejected = sqlFailure(update::executeUpdate);
                    List<Long> durable;
                    try (SQLiteConnection observer = open(file)) {
                        durable = rows(observer);
                    }
                    check(durable.equals(List.of(11L, 20L, 30L)), "close changed UPDATE durability");
                    if (!closeConnection) check(rows(connection).equals(durable), "remaining connection unusable");
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("close_before_release", closeBefore);
                    result.put("execute_before_release", executeBefore);
                    result.put("execute", sqlOutcome);
                    result.put("update_count", count.get());
                    result.put("close", closeOutcome);
                    result.put("connection_closed", connection.isClosed());
                    result.put("statement_closed", update.isClosed());
                    result.put("later_execute", failure(rejected));
                    result.put("durable_rows", durable);
                    return result;
                } finally {
                    gate.release.countDown();
                    workers.shutdownNow();
                }
            }
        }
    }

    private static Map<String, Object> udfReentry(Path data) throws Exception {
        try (SQLiteConnection connection = open(data.resolve("udf-reentry.db"))) {
            seed(connection);
            connection.setAutoCommit(false);
            Thread caller = Thread.currentThread();
            List<Long> reads = new ArrayList<>();
            AtomicBoolean sameThread = new AtomicBoolean(true);
            Function.create(connection, "read_other_row", new Function() {
                @Override
                protected void xFunc() throws SQLException {
                    sameThread.compareAndSet(true, Thread.currentThread() == caller);
                    long value = scalar(connection, "select v from t where id=2");
                    reads.add(value);
                    result(value);
                }
            });
            try (PreparedStatement update = connection.prepareStatement(
                    "update t set v=read_other_row()+1 where id=1")) {
                int count = update.executeUpdate();
                check(count == 1 && rows(connection).equals(List.of(21L, 20L, 30L)),
                        "reentrant read changed UPDATE values");
                check(reads.equals(List.of(20L)) && sameThread.get(), "Function callback thread/value mismatch");
                connection.rollback();
                List<Long> recovered = otherThreadRows(connection);
                check(recovered.equals(List.of(10L, 20L, 30L)), "reentry rollback changed seed rows");
                return Map.of("count", count, "callback_reads", reads, "same_thread", sameThread.get(),
                        "after_rollback", recovered);
            }
        }
    }

    private static Map<String, Object> listenerReentry(Path data) throws Exception {
        try (SQLiteConnection connection = open(data.resolve("listener-reentry.db"))) {
            seed(connection);
            Thread caller = Thread.currentThread();
            int isolation = connection.getTransactionIsolation();
            List<String> events = new ArrayList<>();
            AtomicBoolean sameThread = new AtomicBoolean(true);
            AtomicBoolean getterValues = new AtomicBoolean(true);
            Runnable inspect = () -> {
                sameThread.compareAndSet(true, Thread.currentThread() == caller);
                try {
                    getterValues.compareAndSet(true, !connection.isClosed() && connection.getAutoCommit()
                            && connection.getTransactionIsolation() == isolation);
                } catch (SQLException error) {
                    throw new AssertionError(error);
                }
            };
            // SQLite forbids prepare/step (even SELECT) in update/commit hooks. Reenter only
            // non-mutating JDBC state getters: sqlite.org/c3ref/{update_hook,commit_hook}.html.
            SQLiteUpdateListener updateListener = (type, database, table, row) -> {
                inspect.run();
                events.add(type.name() + ":" + database + ":" + table + ":" + row);
            };
            SQLiteCommitListener commitListener = new SQLiteCommitListener() {
                public void onCommit() { inspect.run(); events.add("commit"); }
                public void onRollback() { inspect.run(); events.add("rollback"); }
            };
            connection.addUpdateListener(updateListener);
            connection.addCommitListener(commitListener);
            try (PreparedStatement update = connection.prepareStatement("update t set v=11 where id=1")) {
                check(update.executeUpdate() == 1, "listener UPDATE count mismatch");
            } finally {
                connection.removeUpdateListener(updateListener);
                connection.removeCommitListener(commitListener);
            }
            check(sameThread.get() && getterValues.get(), "listener state getter reentry failed");
            check(events.contains("UPDATE:main:t:1") && events.contains("commit")
                    && !events.contains("rollback"), "listener events lost UPDATE/commit");
            List<Long> values = otherThreadRows(connection);
            check(values.equals(List.of(11L, 20L, 30L)), "listener changed rows");
            return Map.of("events", events, "same_thread", sameThread.get(),
                    "state_getters_valid", getterValues.get(), "rows", values);
        }
    }

    private static Map<String, Object> udfFailure(Path data) throws Exception {
        try (SQLiteConnection connection = open(data.resolve("udf-error.db"))) {
            seed(connection);
            connection.setAutoCommit(false);
            AtomicBoolean fail = new AtomicBoolean(true);
            AtomicInteger calls = new AtomicInteger();
            Function.create(connection, "maybe_fail", new Function() {
                @Override
                protected void xFunc() throws SQLException {
                    calls.incrementAndGet();
                    if (fail.get()) throw new SQLException("intentional callback failure", "ZZ999", 765);
                    result(1);
                }
            });
            String sql = "update t set v=v+case when id=2 then maybe_fail() else 1 end";
            try (PreparedStatement update = connection.prepareStatement(sql)) {
                SQLException error = expectCode(1, update::executeUpdate);
                connection.rollback();
                List<Long> rolledBack = otherThreadRows(connection);
                check(rolledBack.equals(List.of(10L, 20L, 30L)), "failed UDF kept partial rows");
                fail.set(false);
                Map<String, Object> reuse = new LinkedHashMap<>();
                try { reuse.put("changed", update.executeUpdate()); }
                catch (SQLException invalidated) { reuse.put("failure", failure(invalidated)); }
                connection.rollback();
                check(rows(connection).equals(rolledBack), "post-error reuse escaped rollback");
                try (PreparedStatement fresh = connection.prepareStatement(sql)) {
                    check(fresh.executeUpdate() == 3, "connection did not recover after UDF error");
                }
                check(rows(connection).equals(List.of(11L, 21L, 31L)), "recovered UDF wrote wrong rows");
                connection.rollback();
                check(rows(connection).equals(rolledBack), "recovered UDF rollback failed");
                return Map.of("failure", failure(error), "callback_calls", calls.get(),
                        "after_rollback", rolledBack, "statement_closed", update.isClosed(), "same_statement_reuse", reuse);
            }
        }
    }

    private static Map<String, Object> updateListenerFailure(Path data) throws Exception {
        Map<String, Object> outcomes = new LinkedHashMap<>();
        for (boolean useError : new boolean[] {false, true}) {
            try (SQLiteConnection connection = open(data.resolve("listener-" + useError + ".db"))) {
                seed(connection);
                connection.setAutoCommit(false);
                AtomicInteger calls = new AtomicInteger();
                SQLiteUpdateListener listener = (type, database, table, row) -> {
                    calls.incrementAndGet();
                    if (useError) throw new AssertionError("intentional listener error");
                    throw new IllegalStateException("intentional listener exception");
                };
                connection.addUpdateListener(listener);
                try (PreparedStatement update = connection.prepareStatement("update t set v=11 where id=1")) {
                    Map<String, Object> error;
                    try {
                        error = observe(update::executeUpdate);
                    } finally {
                        connection.removeUpdateListener(listener);
                    }
                    check(calls.get() == 1, "listener did not run exactly once");
                    List<Long> beforeRollback = rows(connection);
                    connection.rollback();
                    List<Long> rolledBack = otherThreadRows(connection);
                    check(rolledBack.equals(List.of(10L, 20L, 30L)), "listener failure escaped rollback");
                    check(update.executeUpdate() == 1, "statement did not recover after listener failure");
                    check(rows(connection).equals(List.of(11L, 20L, 30L)), "listener recovery wrote wrong rows");
                    connection.rollback();
                    check(rows(connection).equals(rolledBack), "listener recovery rollback failed");
                    outcomes.put(useError ? "error" : "exception", Map.of("execute", error,
                            "before_rollback", beforeRollback, "after_rollback", rolledBack));
                }
            }
        }
        return outcomes;
    }

    private static Map<String, Object> commitListenerFailure(Path data) throws Exception {
        Path file = data.resolve("commit-listener.db");
        try (SQLiteConnection connection = open(file)) {
            seed(connection);
            AtomicInteger calls = new AtomicInteger();
            SQLiteCommitListener listener = new SQLiteCommitListener() {
                public void onCommit() {
                    calls.incrementAndGet();
                    throw new IllegalStateException("intentional commit listener exception");
                }
                public void onRollback() {}
            };
            connection.addCommitListener(listener);
            try (PreparedStatement update = connection.prepareStatement("update t set v=11 where id=1")) {
                Map<String, Object> error;
                try {
                    error = observe(update::executeUpdate);
                } finally {
                    connection.removeCommitListener(listener);
                }
                check(calls.get() > 0, "commit listener never ran");
                List<Long> durable;
                try (SQLiteConnection observer = open(file)) {
                    durable = rows(observer);
                }
                // A void Java commit listener is not SQLite's nonzero-return commit veto.
                check(durable.equals(List.of(10L, 20L, 30L)) || durable.equals(List.of(11L, 20L, 30L)),
                        "listener exception produced non-atomic durable data");
                check(otherThreadRows(connection).equals(durable), "listener leaked lock or visibility");
                connection.setAutoCommit(false);
                check(update.executeUpdate() == 1, "statement did not recover after commit callback failure");
                check(rows(connection).equals(List.of(11L, 20L, 30L)), "commit listener recovery changed rows");
                connection.rollback();
                List<Long> rolledBack = otherThreadRows(connection);
                check(rolledBack.equals(durable), "recovery rollback changed previously committed data");
                return Map.of("execute", error, "callback_calls", calls.get(),
                        "durable_after_failure", durable, "after_recovery_rollback", rolledBack);
            }
        }
    }

    private static long key(ResultSet keys) throws SQLException {
        check(keys.next(), "generated key missing");
        return keys.getLong(1); // Deliberately leave the one-row cursor open for the next operation.
    }

    private static Map<String, Object> generatedKeys(Path data) throws Exception {
        try (SQLiteConnection connection = open(data.resolve("keys.db"))) {
            execute(connection, "create table t(id integer primary key, v integer unique)");
            try (PreparedStatement first = connection.prepareStatement(
                            "insert into t values(?,?)", Statement.RETURN_GENERATED_KEYS);
                    PreparedStatement second = connection.prepareStatement(
                            "insert into t values(?,?)", Statement.RETURN_GENERATED_KEYS)) {
                first.setInt(1, 101); first.setInt(2, 1);
                check(first.executeUpdate() == 1, "first insert failed");
                ResultSet prior = first.getGeneratedKeys();
                check(key(prior) == 101, "first key belongs to wrong insert");
                Statement priorOwner = prior.getStatement();
                boolean priorOwnedByCaller = priorOwner == first;
                first.setInt(1, 102); first.setInt(2, 2);
                check(first.executeUpdate() == 1, "second insert failed");
                check(prior.isClosed(), "successful reexecution retained old key cursor");
                boolean priorOwnerClosed = priorOwner.isClosed();
                ResultSet current = first.getGeneratedKeys();
                check(key(current) == 102, "second key belongs to wrong insert");
                second.setInt(1, 201); second.setInt(2, 3);
                check(second.executeUpdate() == 1, "other statement insert failed");
                ResultSet other = second.getGeneratedKeys();
                check(key(other) == 201 && current.getLong(1) == 102,
                        "generated keys were shared across statements");
                SQLException constraint = expectCode(19, first::executeUpdate);
                boolean oldClosedAfterFailure = current.isClosed();
                ResultSet afterFailure = first.getGeneratedKeys();
                boolean sameAfterFailure = afterFailure == current;
                Long failureKey = null;
                if (!afterFailure.isClosed()) {
                    if (sameAfterFailure) failureKey = afterFailure.getLong(1);
                    else if (afterFailure.next()) failureKey = afterFailure.getLong(1);
                }
                first.setInt(1, 103); first.setInt(2, 4);
                check(first.executeLargeUpdate() == 1, "large update did not recover after constraint");
                ResultSet recovered = first.getGeneratedKeys();
                check(key(recovered) == 103, "recovered key belongs to failed insert");
                check(!other.isClosed() && other.getLong(1) == 201, "other statement lost key ownership");
                first.close();
                check(recovered.isClosed(), "statement close leaked generated key cursor");
                check(!other.isClosed(), "closing first statement closed second statement's keys");
                second.close();
                check(other.isClosed(), "second statement close leaked key cursor");
                check(rows(connection).equals(List.of(1L, 2L, 4L, 3L)), "key operations wrote wrong rows");
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("keys", List.of(101, 102, 201, 103));
                result.put("key_owner_is_prepared_statement", priorOwnedByCaller);
                result.put("prior_owner_closed_on_success", priorOwnerClosed);
                result.put("prior_cursor_closed_on_failure", oldClosedAfterFailure);
                result.put("same_cursor_after_failure", sameAfterFailure);
                result.put("key_after_failure", failureKey);
                result.put("constraint", failure(constraint));
                result.put("all_keys_closed_after_owner_close", recovered.isClosed() && other.isClosed());
                result.put("rows", rows(connection));
                return result;
            }
        }
    }

    private static Map<String, Object> closeOnCompletion(Path data) throws Exception {
        try (SQLiteConnection connection = open(data.resolve("completion.db"))) {
            execute(connection, "create table t(id integer primary key, v integer)");
            try (PreparedStatement update = connection.prepareStatement(
                    "insert into t values(?,?)", Statement.RETURN_GENERATED_KEYS)) {
                update.closeOnCompletion();
                check(update.isCloseOnCompletion(), "close-on-completion flag not set");
                update.setInt(1, 301); update.setInt(2, 10);
                check(update.executeUpdate() == 1, "completion insert failed");
                ResultSet first = update.getGeneratedKeys();
                check(key(first) == 301, "completion key mismatch");
                Statement keyOwner = first.getStatement();
                first.close();
                boolean statementClosedWithKeys = update.isClosed();
                boolean keyOwnerClosedWithKeys = keyOwner.isClosed();
                Map<String, Object> next = observe(() -> {
                    update.setInt(1, 302); update.setInt(2, 20);
                    check(update.executeUpdate() == 1, "next completion insert failed");
                });
                ResultSet second = null;
                if (next.equals(Map.of("completed", true))) {
                    second = update.getGeneratedKeys();
                    check(key(second) == 302, "next completion key mismatch");
                }
                update.close();
                check(first.isClosed() && (second == null || second.isClosed()), "explicit close leaked key cursor");
                List<Long> values = rows(connection);
                check(values.equals(statementClosedWithKeys ? List.of(10L) : List.of(10L, 20L)),
                        "close-on-completion changed insert data");
                return Map.of("statement_closed_with_keys", statementClosedWithKeys,
                        "key_owner_is_prepared_statement", keyOwner == update,
                        "key_owner_closed_with_keys", keyOwnerClosedWithKeys,
                        "next_update", next, "all_keys_closed_after_explicit_close", true, "rows", values);
            }
        }
    }
}
