package io.gateway;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.sqlite.Function;
import org.sqlite.SQLiteCommitListener;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.core.NativeDB;

/** Public-interface checks for the fork/flat/reentrant experiment; no test JNI or reflection. */
public final class LockCorrectness {
    private static final int SECONDS = 10;

    @FunctionalInterface
    interface SqlAction {
        void run() throws Exception;
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static SQLiteConnection open(Path path) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(0);
        config.setTransactionMode(SQLiteConfig.TransactionMode.DEFERRED);
        return (SQLiteConnection) DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
    }

    static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            check(result.next(), "missing scalar result: " + sql);
            long value = result.getLong(1);
            check(!result.next(), "multiple scalar rows: " + sql);
            return value;
        }
    }

    static SQLException expectCode(int code, SqlAction action) throws Exception {
        try {
            action.run();
        } catch (SQLException failure) {
            check((failure.getErrorCode() & 0xff) == code, "expected SQLite " + code + ", got " + failure);
            return failure;
        }
        throw new AssertionError("expected SQLite error " + code);
    }

    static void await(CountDownLatch latch, String message) throws Exception {
        check(latch.await(SECONDS, TimeUnit.SECONDS), message);
    }

    static <T> T finished(Future<T> future) throws Exception {
        return future.get(SECONDS, TimeUnit.SECONDS);
    }

    static void mustWait(Future<?> future, String message) throws Exception {
        try {
            future.get(150, TimeUnit.MILLISECONDS);
            throw new AssertionError(message);
        } catch (TimeoutException expected) {
            // A started operation must not complete while another thread owns the DB guard.
        }
    }

    static final class Gate extends Function {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        protected void xFunc() throws SQLException {
            entered.countDown();
            try {
                if (!release.await(SECONDS, TimeUnit.SECONDS)) throw new SQLException("gate timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new SQLException("gate interrupted", interrupted);
            }
            result(1); // Re-enters the same DB guard from a JNI Function callback.
        }
    }

    static void cancellation(Path data) throws Exception {
        try (SQLiteConnection connection = open(data.resolve("cancel.db"));
                Statement statement = connection.createStatement();
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Gate gate = new Gate();
            Function.create(connection, "lock_gate", gate);
            Future<SQLException> execution = workers.submit(() -> expectCode(9, () -> {
                try (ResultSet ignored = statement.executeQuery(
                        "with recursive n(x) as (values(lock_gate()) union all "
                                + "select x+1 from n where x<10000000) select sum(x) from n")) {
                    while (ignored.next()) { ignored.getLong(1); }
                }
            }));
            try {
                await(gate.entered, "execution did not enter callback");
                Future<?> cancel = workers.submit(() -> { statement.cancel(); return null; });
                // Completing before the gate opens proves cancel does not acquire the DB guard.
                finished(cancel);
                check(!execution.isDone(), "execution escaped its closed callback gate");
            } finally {
                gate.release.countDown();
            }
            finished(execution);
            check(scalar(connection, "select 42") == 42, "cancel poisoned the next operation");
        }
    }

    static void concurrentClose(Path data) throws Exception {
        try (SQLiteConnection connection = open(data.resolve("close.db"));
                Statement statement = connection.createStatement();
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Gate gate = new Gate();
            Function.create(connection, "lock_gate", gate);
            Future<Boolean> execution = workers.submit(() -> statement.execute("select lock_gate()"));
            Future<?> close = null;
            try {
                await(gate.entered, "close test execution did not enter callback");
                CountDownLatch started = new CountDownLatch(1);
                close = workers.submit(() -> { started.countDown(); connection.close(); return null; });
                await(started, "close worker did not start");
                mustWait(close, "close raced the executing native statement");
                check(!connection.isClosed(), "DB closed while an operation owns its guard");
            } finally {
                gate.release.countDown();
            }
            check(finished(execution), "gate SELECT did not produce a row");
            finished(close);
            check(connection.isClosed(), "concurrent close did not close connection");
            try {
                connection.createStatement();
                throw new AssertionError("closed connection accepted a new statement");
            } catch (SQLException expected) {
                check(connection.isClosed(), "closed state lost after statement rejection");
            }
        }
    }

    static void publicNativeSerialization(Path data) throws Exception {
        try (SQLiteConnection connection = open(data.resolve("native.db"));
                Statement statement = connection.createStatement();
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            Gate gate = new Gate();
            Function.create(connection, "lock_gate", gate);
            // Exercises the public NativeDB type, not just methods called through JDBC wrappers.
            NativeDB db = (NativeDB) connection.getDatabase();
            Future<Boolean> execution = workers.submit(() -> statement.execute("select lock_gate()"));
            Future<?> timeout = null;
            try {
                await(gate.entered, "native test execution did not enter callback");
                CountDownLatch started = new CountDownLatch(1);
                timeout = workers.submit(() -> { started.countDown(); db.busy_timeout(777); return null; });
                await(started, "public native worker did not start");
                mustWait(timeout, "public NativeDB operation bypassed the execution guard");
            } finally {
                gate.release.countDown();
            }
            check(finished(execution), "native guard SELECT did not produce a row");
            finished(timeout);
            check(db.busyTimeoutReadback() == 777, "public native timeout change was lost");
            check(scalar(connection, "pragma busy_timeout") == 777, "native timeout disagrees with SQLite");
        }
    }

    static void callbacks(Path data) throws Exception {
        try (SQLiteConnection connection = open(data.resolve("callbacks.db"))) {
            execute(connection, "create table t(id integer primary key, v)");
            Function.create(connection, "plus_one", new Function() {
                @Override
                protected void xFunc() throws SQLException { result(value_int(0) + 1); }
            });
            AtomicInteger updates = new AtomicInteger();
            AtomicInteger commits = new AtomicInteger();
            connection.addUpdateListener((type, database, table, row) -> {
                check(table.equals("t"), "wrong update callback table");
                updates.incrementAndGet();
            });
            SQLiteCommitListener listener = new SQLiteCommitListener() {
                @Override
                public void onCommit() { commits.incrementAndGet(); }
                @Override
                public void onRollback() {}
            };
            connection.addCommitListener(listener);
            check(scalar(connection, "select plus_one(41)") == 42, "JNI Function value/result reentry failed");
            execute(connection, "insert into t values(1, plus_one(41))");
            check(updates.get() == 1, "update callback missing or duplicated");
            check(commits.get() >= 1, "commit callback not delivered");
            connection.removeCommitListener(listener);
            int priorCommits = commits.get();
            execute(connection, "insert into t values(2, 43)");
            check(commits.get() == priorCommits, "removed commit listener was invoked");
            byte[] serialized = connection.serialize("main");
            try (SQLiteConnection copy = open(data.resolve("serialized.db"))) {
                copy.deserialize("main", serialized);
                check(scalar(copy, "select sum(v) from t") == 85, "serialize/deserialize data mismatch");
            }
        }
    }

    static void contention(Path data) throws Exception {
        try (SQLiteConnection connection = open(data.resolve("contention.db"));
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            execute(connection, "create table t(id integer primary key, v integer unique)");
            int threads = 8, perThread = 60;
            Map<Long, Integer> expected = new ConcurrentHashMap<>();
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            for (int index = 0; index < threads; index++) {
                final int worker = index;
                tasks.add(workers.submit(() -> {
                    await(start, "contention start gate timed out");
                    try (PreparedStatement insert = connection.prepareStatement(
                            "insert into t(v) values (?)", Statement.RETURN_GENERATED_KEYS)) {
                        for (int i = 0; i < perThread; i++) {
                            int value = worker * perThread + i;
                            insert.setInt(1, value);
                            check(insert.executeUpdate() == 1, "insert affected wrong number of rows");
                            try (ResultSet keys = insert.getGeneratedKeys()) {
                                check(keys.next(), "missing generated key");
                                long id = keys.getLong(1);
                                check(expected.putIfAbsent(id, value) == null, "generated key was stolen by another statement");
                                check(!keys.next(), "unexpected extra generated key");
                            }
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) finished(task);
            check(expected.size() == threads * perThread, "concurrent inserts lost keys");
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("select id,v from t")) {
                int found = 0;
                while (rows.next()) {
                    check(Integer.valueOf(rows.getInt(2)).equals(expected.get(rows.getLong(1))),
                            "generated key associated with another writer's row");
                    found++;
                }
                check(found == threads * perThread, "concurrent inserts lost data");
            }
        }
    }

    // Public-interface versions of ControlTransactionTest t06/t07/t08: the control statement
    // succeeds on TEMP, then its automatic BEGIN EXCLUSIVE fails against a main-database reader.
    static void restartRecovery(Path data, boolean commit) throws Exception {
        Path file = data.resolve(commit ? "commit-restart.db" : "rollback-restart.db");
        try (SQLiteConnection waiter = open(file); SQLiteConnection reader = open(file)) {
            execute(waiter, "create table t(v)");
            execute(waiter, "insert into t values(1)");
            execute(waiter, "create temp table temp_t(v)");
            execute(waiter, "insert into temp_t values(0)");
            waiter.setAutoCommit(false);
            reader.setAutoCommit(false);
            check(scalar(reader, "select count(*) from t") == 1, "reader did not acquire its transaction");
            waiter.getConnectionConfig().setTransactionMode(SQLiteConfig.TransactionMode.EXCLUSIVE);
            execute(waiter, "update temp_t set v=1");
            expectCode(5, () -> { if (commit) waiter.commit(); else waiter.rollback(); });
            check(!waiter.getAutoCommit(), "failed follow-up BEGIN changed JDBC auto-commit");
            expectCode(5, () -> execute(waiter, "insert into temp_t values(2)"));
            reader.setAutoCommit(true);
            if (commit) waiter.commit(); else waiter.rollback();
            check(scalar(waiter, "select count(*) from temp_t") == 1, "pending user SQL silently committed");
            check(scalar(waiter, "select v from temp_t") == (commit ? 1 : 0),
                    "restart replayed or lost the completed transaction");
            execute(waiter, "insert into temp_t values(3)");
            waiter.rollback();
            check(scalar(waiter, "select count(*) from temp_t") == 1, "recovered transaction did not roll back user SQL");
            waiter.setAutoCommit(true);
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: LockCorrectness <output-dir>");
        Path output = Path.of(args[0]);
        Path data = output.resolve("data");
        Files.createDirectories(output);
        Files.createDirectory(data); // Do not accidentally reuse a previous run's databases.
        Class.forName("org.sqlite.JDBC");
        List<String> passed = new ArrayList<>();
        String[] names = {"cancellation_while_execution_locked", "concurrent_close", "public_native_serialization",
                "jni_callbacks_and_serialization", "shared_connection_generated_key_contention",
                "commit_restart_recovery", "rollback_restart_recovery"};
        SqlAction[] cases = {() -> cancellation(data), () -> concurrentClose(data),
                () -> publicNativeSerialization(data), () -> callbacks(data), () -> contention(data),
                () -> restartRecovery(data, true), () -> restartRecovery(data, false)};
        for (int i = 0; i < cases.length; i++) {
            cases[i].run();
            passed.add(names[i]);
            System.out.println("PASS " + names[i]);
        }
        try (var files = Files.list(data)) {
            for (Path file : files.toList()) Files.delete(file);
        }
        Files.delete(data);
        Files.writeString(output.resolve("correctness.json"),
                "{\n  \"passed\": [\"" + String.join("\", \"", passed) + "\"]\n}\n");
    }
}
