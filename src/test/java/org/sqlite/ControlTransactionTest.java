package org.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.core.NativeDBHelper;
import org.sqlite.core.VtWaitProbe;

/** Public JDBC behavior tests for driver-generated transaction control waits. */
public class ControlTransactionTest {
    @TempDir Path tempDir;

    private Connection open(String file, int timeout, SQLiteConfig.TransactionMode mode)
            throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(timeout);
        config.setTransactionMode(mode);
        return DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve(file), config.toProperties());
    }

    private void createTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table t(v)");
        }
    }

    private void insert(Connection connection, int value) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into t values (" + value + ")");
        }
    }

    private int count(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("select count(*) from t")) {
            result.next();
            return result.getInt(1);
        }
    }

    private long waitCount() {
        return VtWaitProbe.javaWaitObservations();
    }

    private void awaitWait(long previous) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (waitCount() == previous && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertThat(waitCount()).isGreaterThan(previous);
    }

    @Test
    void t01_beginImmediateRetriesOnlyUntilWriterReleases() throws Exception {
        try (Connection blocker = open("begin.db", 500, SQLiteConfig.TransactionMode.DEFERRED);
                Connection waiter =
                        open("begin.db", 1000, SQLiteConfig.TransactionMode.IMMEDIATE)) {
            createTable(blocker);
            blocker.setAutoCommit(false);
            insert(blocker, 1);

            long waits = waitCount();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> begin =
                        executor.submit(
                                () -> {
                                    waiter.setAutoCommit(false);
                                    return !waiter.getAutoCommit();
                                });
                awaitWait(waits);
                blocker.setAutoCommit(true);
                assertThat(begin.get(5, TimeUnit.SECONDS)).isTrue();
                waiter.rollback();
                assertThat(count(blocker)).isEqualTo(1);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void t02_exclusiveBeginWaitsForDeleteJournalReader() throws Exception {
        try (Connection waiter = open("exclusive.db", 60, SQLiteConfig.TransactionMode.EXCLUSIVE);
                Connection reader =
                        open("exclusive.db", 100, SQLiteConfig.TransactionMode.DEFERRED)) {
            createTable(waiter);
            insert(waiter, 1);
            reader.setAutoCommit(false);
            try (Statement statement = reader.createStatement();
                    ResultSet result = statement.executeQuery("select * from t")) {
                assertThat(result.next()).isTrue();
            }
            try (Statement statement = reader.createStatement();
                    ResultSet result = statement.executeQuery("pragma journal_mode")) {
                result.next();
                assertThat(result.getString(1)).isEqualTo("delete");
            }

            long waits = waitCount();
            expectBusy(() -> waiter.setAutoCommit(false));
            assertThat(waitCount()).isGreaterThan(waits);
            assertThat(waiter.getAutoCommit()).isTrue();
            reader.setAutoCommit(true);
            waiter.setAutoCommit(false);
            waiter.rollback();
        }
    }

    @Test
    void t04_commitRetriesWithoutRepeatingDml() throws Exception {
        try (Connection writer =
                        open("commit-retry.db", 1000, SQLiteConfig.TransactionMode.DEFERRED);
                Connection reader =
                        open("commit-retry.db", 100, SQLiteConfig.TransactionMode.DEFERRED)) {
            createTable(writer);
            AtomicInteger updates = new AtomicInteger();
            ((SQLiteConnection) writer)
                    .addUpdateListener(
                            (type, database, table, rowId) -> {
                                assertThat(table).isEqualTo("t");
                                updates.incrementAndGet();
                            });
            AtomicInteger commits = new AtomicInteger();
            ((SQLiteConnection) writer)
                    .addCommitListener(
                            new SQLiteCommitListener() {
                                @Override
                                public void onCommit() {
                                    commits.incrementAndGet();
                                }

                                @Override
                                public void onRollback() {}
                            });
            reader.setAutoCommit(false);
            try (Statement statement = reader.createStatement();
                    ResultSet result = statement.executeQuery("select * from t")) {
                assertThat(result.next()).isFalse();
            }
            writer.setAutoCommit(false);
            insert(writer, 1);

            long waits = waitCount();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> commit =
                        executor.submit(
                                () -> {
                                    writer.commit();
                                    return null;
                                });
                awaitWait(waits);
                reader.setAutoCommit(true);
                commit.get(5, TimeUnit.SECONDS);
                assertThat(count(reader)).isEqualTo(1);
                assertThat(updates).hasValue(1);
                assertThat(count(writer)).isEqualTo(1);
                assertThat(commits).hasValue(1);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void t05_commitTimeoutLeavesTransactionAvailableForRetry() throws Exception {
        try (Connection writer =
                        open("commit-timeout.db", 45, SQLiteConfig.TransactionMode.DEFERRED);
                Connection reader =
                        open("commit-timeout.db", 100, SQLiteConfig.TransactionMode.DEFERRED)) {
            createTable(writer);
            reader.setAutoCommit(false);
            try (Statement statement = reader.createStatement();
                    ResultSet result = statement.executeQuery("select * from t")) {
                assertThat(result.next()).isFalse();
            }
            writer.setAutoCommit(false);
            insert(writer, 1);
            long waits = waitCount();

            expectBusy(writer::commit);
            assertThat(waitCount()).isGreaterThan(waits);
            assertThat(writer.getAutoCommit()).isFalse();
            assertThat(count(writer)).isEqualTo(1);

            reader.setAutoCommit(true);
            writer.commit();
            assertThat(count(reader)).isEqualTo(1);
        }
    }

    @Test
    void t09_setAutoCommitTrueOnlyFlipsAfterCommitSucceeds() throws Exception {
        try (Connection writer =
                        open("autocommit-commit.db", 45, SQLiteConfig.TransactionMode.DEFERRED);
                Connection reader =
                        open("autocommit-commit.db", 100, SQLiteConfig.TransactionMode.DEFERRED)) {
            createTable(writer);
            reader.setAutoCommit(false);
            try (Statement statement = reader.createStatement();
                    ResultSet result = statement.executeQuery("select * from t")) {
                assertThat(result.next()).isFalse();
            }
            writer.setAutoCommit(false);
            insert(writer, 1);

            expectBusy(() -> writer.setAutoCommit(true));
            assertThat(writer.getAutoCommit()).isFalse();
            assertThat(count(writer)).isEqualTo(1);

            reader.setAutoCommit(true);
            writer.setAutoCommit(true);
            assertThat(writer.getAutoCommit()).isTrue();
            assertThat(count(reader)).isEqualTo(1);
        }
    }

    @Test
    void t11_readOnlyUpgradeRetriesOnlyBeginImmediate() throws Exception {
        try (Connection blocker =
                open("readonly-upgrade.db", 100, SQLiteConfig.TransactionMode.DEFERRED)) {
            createTable(blocker);
            SQLiteConfig config = new SQLiteConfig();
            config.setBusyTimeout(80);
            config.setTransactionMode(SQLiteConfig.TransactionMode.DEFERRED);
            config.setExplicitReadOnly(true);
            try (Connection upgrader =
                            DriverManager.getConnection(
                                    "jdbc:sqlite:" + tempDir.resolve("readonly-upgrade.db"),
                                    config.toProperties());
                    Statement statement = upgrader.createStatement()) {
                upgrader.setAutoCommit(false);
                blocker.setAutoCommit(false);
                insert(blocker, 1);
                AtomicInteger updates = new AtomicInteger();
                ((SQLiteConnection) upgrader)
                        .addUpdateListener(
                                (type, database, table, rowId) -> updates.incrementAndGet());

                long waits = waitCount();
                expectBusy(() -> statement.executeUpdate("insert into t values (2)"));
                assertThat(waitCount()).isGreaterThan(waits);
                blocker.setAutoCommit(true);
                assertThat(statement.executeUpdate("insert into t values (2)")).isEqualTo(1);
                assertThat(updates).hasValue(1);
                upgrader.commit();
                assertThat(count(blocker)).isEqualTo(2);
            }
        }
    }

    @Test
    void t16_attachedDatabaseUsesTheSameGeneratedBeginBoundary() throws Exception {
        String auxiliary = tempDir.resolve("auxiliary.db").toString().replace("'", "''");
        try (Connection waiter =
                        open("attached-main.db", 1000, SQLiteConfig.TransactionMode.IMMEDIATE);
                Connection blocker =
                        open("attached-main.db", 100, SQLiteConfig.TransactionMode.DEFERRED)) {
            try (Statement statement = waiter.createStatement()) {
                statement.execute("attach database '" + auxiliary + "' as aux");
                statement.executeUpdate("create table aux.t(v)");
            }
            try (Statement statement = blocker.createStatement()) {
                statement.execute("attach database '" + auxiliary + "' as aux");
            }
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                statement.executeUpdate("insert into aux.t values (1)");
            }

            long waits = waitCount();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> begin =
                        executor.submit(
                                () -> {
                                    waiter.setAutoCommit(false);
                                    return null;
                                });
                awaitWait(waits);
                blocker.setAutoCommit(true);
                begin.get(5, TimeUnit.SECONDS);
                try (Statement statement = waiter.createStatement()) {
                    statement.executeUpdate("insert into aux.t values (2)");
                }
                waiter.commit();
                try (Statement statement = blocker.createStatement();
                        ResultSet result = statement.executeQuery("select count(*) from aux.t")) {
                    result.next();
                    assertThat(result.getInt(1)).isEqualTo(2);
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void t10_inFlightReturningBusyDoesNotEnterJavaRetry() throws Exception {
        try (Connection connection =
                        open("returning.db", 1000, SQLiteConfig.TransactionMode.DEFERRED);
                Statement statement = connection.createStatement()) {
            createTable(connection);
            connection.setAutoCommit(false);
            try (ResultSet result =
                    statement.executeQuery("insert into t values (1) returning v")) {
                assertThat(result.next()).isTrue();
                long waits = waitCount();
                expectBusy(connection::commit);
                assertThat(waitCount()).isEqualTo(waits);
            }
            connection.commit();
            assertThat(count(connection)).isEqualTo(1);
        }
    }

    @Test
    void t12_compatibilityProbeFailureDoesNotRepeatUserDml() throws Exception {
        try (Connection connection =
                        open("autocommit-probe.db", 1000, SQLiteConfig.TransactionMode.DEFERRED);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table t(v)");
            statement.executeUpdate("create table audit(n)");
            statement.executeUpdate("insert into audit values (0)");
            statement.executeUpdate(
                    "create trigger audit_insert after insert on t "
                            + "begin update audit set n=n+1; end");

            boolean faultLibrary = true;
            try {
                NativeDBHelper.backupTestOutstanding(((SQLiteConnection) connection).getDatabase());
            } catch (UnsatisfiedLinkError unavailable) {
                faultLibrary = false;
            }
            assumeTrue(faultLibrary, "run with make test-faults");
            NativeDBHelper.failNextAutocommitProbeCommit(
                    ((SQLiteConnection) connection).getDatabase());

            SQLException failure = null;
            try {
                statement.execute("insert into t values (1)");
            } catch (SQLException exception) {
                failure = exception;
            }
            assertThat(failure.getErrorCode() & 0xff).isEqualTo(5);
            assertThat(connection.getAutoCommit()).isTrue();
            try (Connection observer =
                            open(
                                    "autocommit-probe.db",
                                    1000,
                                    SQLiteConfig.TransactionMode.DEFERRED);
                    Statement observerStatement = observer.createStatement();
                    ResultSet result = observerStatement.executeQuery("select n from audit")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }

            assertThat(statement.execute("insert into t values (2)")).isFalse();
            try (Connection observer =
                            open(
                                    "autocommit-probe.db",
                                    1000,
                                    SQLiteConfig.TransactionMode.DEFERRED);
                    Statement observerStatement = observer.createStatement();
                    ResultSet result = observerStatement.executeQuery("select n from audit")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(2);
            }

            connection.setAutoCommit(false);
            assertThat(statement.executeUpdate("insert into t values (3)")).isEqualTo(2);
            NativeDBHelper.failNextAutocommitProbeCommit(
                    ((SQLiteConnection) connection).getDatabase());
            SQLException transitionFailure = null;
            try {
                connection.setAutoCommit(true);
            } catch (SQLException exception) {
                transitionFailure = exception;
            }
            assertThat(transitionFailure.getErrorCode() & 0xff).isEqualTo(5);
            assertThat(connection.getAutoCommit()).isTrue();
            try (Connection observer =
                            open(
                                    "autocommit-probe.db",
                                    1000,
                                    SQLiteConfig.TransactionMode.DEFERRED);
                    Statement observerStatement = observer.createStatement();
                    ResultSet result = observerStatement.executeQuery("select n from audit")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(3);
            }
            assertThat(statement.execute("insert into t values (4)")).isFalse();
        }
    }

    @Test
    void t12_interruptedCompatibilityCommitDoesNotLeakAnOpenTransaction() throws Exception {
        try (Connection connection =
                        open("interrupted-probe.db", 1000, SQLiteConfig.TransactionMode.DEFERRED);
                Statement statement = connection.createStatement();
                Connection observer =
                        open("interrupted-probe.db", 1000, SQLiteConfig.TransactionMode.DEFERRED)) {
            createTable(connection);
            boolean faultLibrary = true;
            try {
                NativeDBHelper.backupTestOutstanding(((SQLiteConnection) connection).getDatabase());
            } catch (UnsatisfiedLinkError unavailable) {
                faultLibrary = false;
            }
            assumeTrue(faultLibrary, "run with make test-faults");
            NativeDBHelper.interruptNextAutocommitProbeCommit(
                    ((SQLiteConnection) connection).getDatabase());
            assertThatExceptionOfType(SQLException.class)
                    .isThrownBy(() -> statement.execute("insert into t values (1)"))
                    .satisfies(failure -> assertThat(failure.getErrorCode() & 0xff).isEqualTo(9));
            assertThat(connection.getAutoCommit()).isTrue();
            assertThat(count(observer)).isEqualTo(1);
            statement.execute("insert into t values (2)");
            assertThat(count(observer)).isEqualTo(2);
        }
    }

    @Test
    void t13_constraintErrorsAreNotRetried() throws Exception {
        try (Connection connection =
                        open("constraint.db", 1000, SQLiteConfig.TransactionMode.DEFERRED);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table t(v unique)");
            statement.executeUpdate("insert into t values (1)");
            long waits = waitCount();
            SQLException failure = null;
            try {
                statement.executeUpdate("insert into t values (1)");
            } catch (SQLException exception) {
                failure = exception;
            }
            assertThat(failure.getErrorCode() & 0xff).isEqualTo(19);
            assertThat(waitCount()).isEqualTo(waits);
            assertThat(count(connection)).isEqualTo(1);
        }
    }

    @Test
    void t14_busySnapshotKeepsExtendedCodeAndDoesNotRetryUserDml() throws Exception {
        try (Connection setup =
                open("busy-snapshot.db", 1000, SQLiteConfig.TransactionMode.DEFERRED)) {
            try (Statement statement = setup.createStatement();
                    ResultSet result = statement.executeQuery("pragma journal_mode=wal")) {
                result.next();
                assertThat(result.getString(1)).isEqualTo("wal");
            }
            try (Statement statement = setup.createStatement()) {
                statement.executeUpdate("create table t(v)");
            }
            insert(setup, 0);
        }
        try (Connection stale =
                        open("busy-snapshot.db", 1000, SQLiteConfig.TransactionMode.DEFERRED);
                Connection writer =
                        open("busy-snapshot.db", 1000, SQLiteConfig.TransactionMode.DEFERRED)) {
            stale.setAutoCommit(false);
            writer.setAutoCommit(false);
            try (Statement statement = stale.createStatement();
                    ResultSet result = statement.executeQuery("select * from t")) {
                assertThat(result.next()).isTrue();
            }
            try (Statement statement = writer.createStatement();
                    ResultSet result = statement.executeQuery("select * from t")) {
                assertThat(result.next()).isTrue();
            }
            insert(writer, 1);
            writer.commit();
            long waits = waitCount();

            SQLException failure = null;
            try (PreparedStatement statement = stale.prepareStatement("insert into t values (?)")) {
                statement.setInt(1, 2);
                statement.executeUpdate();
            } catch (SQLException exception) {
                failure = exception;
            }
            assertThat(((SQLiteException) failure).getResultCode())
                    .isEqualTo(SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT);
            assertThat(waitCount()).isEqualTo(waits);
            stale.rollback();
        }
    }

    @Test
    void t15_ftsWritesCommitAndRemainSearchable() throws Exception {
        try (Connection connection =
                        open("fts-commit.db", 1000, SQLiteConfig.TransactionMode.DEFERRED);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("create virtual table docs using fts5(content)");
            connection.setAutoCommit(false);
            statement.executeUpdate("insert into docs values ('virtual transaction content')");
            connection.commit();
            try (ResultSet result =
                    statement.executeQuery(
                            "select count(*) from docs where docs match 'transaction'")) {
                result.next();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }

    @Test
    void v06_nativeCallbackReentryDoesNotCountNestedJavaWaitAsUnpinned() throws Exception {
        try (Connection outer =
                        open("callback-outer.db", 1000, SQLiteConfig.TransactionMode.DEFERRED);
                Connection blocker =
                        open("callback-inner.db", 100, SQLiteConfig.TransactionMode.DEFERRED);
                Connection nested =
                        open("callback-inner.db", 60, SQLiteConfig.TransactionMode.IMMEDIATE)) {
            createTable(blocker);
            blocker.setAutoCommit(false);
            insert(blocker, 1);
            AtomicReference<SQLException> nestedFailure = new AtomicReference<>();
            Function.create(
                    outer,
                    "nested_begin",
                    new Function() {
                        @Override
                        protected void xFunc() throws SQLException {
                            try {
                                nested.setAutoCommit(false);
                                result(1);
                            } catch (SQLException exception) {
                                nestedFailure.set(exception);
                                result(0);
                            }
                        }
                    });
            long waits = waitCount();

            try (Statement statement = outer.createStatement();
                    ResultSet result = statement.executeQuery("select nested_begin()")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
            assertThat(nestedFailure.get().getErrorCode() & 0xff).isEqualTo(5);
            assertThat(waitCount()).isEqualTo(waits);
            assertThat(nested.getAutoCommit()).isTrue();

            blocker.setAutoCommit(true);
            nested.setAutoCommit(false);
            nested.rollback();
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws Exception;
    }

    @Test
    void t03ThreadInterruptAbortsWaitAndPreservesFlag() throws Exception {
        try (Connection blocker =
                        open("thread-interrupt.db", 500, SQLiteConfig.TransactionMode.DEFERRED);
                Connection waiter =
                        open("thread-interrupt.db", 5000, SQLiteConfig.TransactionMode.IMMEDIATE)) {
            createTable(blocker);
            blocker.setAutoCommit(false);
            insert(blocker, 1);
            long waits = waitCount();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            AtomicReference<Thread> worker = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            try {
                Future<SQLException> begin =
                        executor.submit(
                                () -> {
                                    worker.set(Thread.currentThread());
                                    try {
                                        waiter.setAutoCommit(false);
                                        return null;
                                    } catch (SQLException exception) {
                                        return exception;
                                    } finally {
                                        interrupted.set(Thread.currentThread().isInterrupted());
                                    }
                                });
                awaitWait(waits);
                worker.get().interrupt();
                SQLException failure = begin.get(2, TimeUnit.SECONDS);
                assertThat(failure.getErrorCode() & 0xff).isEqualTo(9);
                assertThat(interrupted).isTrue();
                assertThat(waiter.getAutoCommit()).isTrue();

                blocker.setAutoCommit(true);
                waiter.setAutoCommit(false);
                waiter.rollback();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void t03PreInterruptedBeginDoesNotStartATransaction() throws Exception {
        try (Connection connection =
                open("pre-interrupted-begin.db", 1000, SQLiteConfig.TransactionMode.IMMEDIATE)) {
            try {
                Thread.currentThread().interrupt();
                assertThatExceptionOfType(SQLException.class)
                        .isThrownBy(() -> connection.setAutoCommit(false))
                        .satisfies(
                                failure -> assertThat(failure.getErrorCode() & 0xff).isEqualTo(9));
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(connection.getAutoCommit()).isTrue();
            } finally {
                Thread.interrupted();
            }
            connection.setAutoCommit(false);
            connection.rollback();
        }
    }

    @Test
    void t03StatementCancelDoesNotWaitForDatabaseMonitorOrPoisonNextOperation() throws Exception {
        try (Connection blocker =
                        open("statement-cancel.db", 500, SQLiteConfig.TransactionMode.DEFERRED);
                Connection waiter =
                        open("statement-cancel.db", 5000, SQLiteConfig.TransactionMode.IMMEDIATE);
                Statement statement = waiter.createStatement()) {
            createTable(blocker);
            blocker.setAutoCommit(false);
            insert(blocker, 1);
            long waits = waitCount();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<SQLException> begin =
                        executor.submit(
                                () -> {
                                    try {
                                        waiter.setAutoCommit(false);
                                        return null;
                                    } catch (SQLException exception) {
                                        return exception;
                                    }
                                });
                awaitWait(waits);
                Future<?> cancel =
                        executor.submit(
                                () -> {
                                    statement.cancel();
                                    return null;
                                });
                cancel.get(2, TimeUnit.SECONDS);
                SQLException failure = begin.get(2, TimeUnit.SECONDS);
                assertThat(failure.getErrorCode() & 0xff).isEqualTo(9);
                assertThat(waiter.getAutoCommit()).isTrue();

                blocker.setAutoCommit(true);
                waiter.setAutoCommit(false);
                waiter.rollback();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void t06t07CommittedWorkIsNotReplayedAndPendingSqlCannotAutoCommit() throws Exception {
        try (Connection waiter =
                        open("pending-restart.db", 0, SQLiteConfig.TransactionMode.IMMEDIATE);
                Connection reader =
                        open("pending-restart.db", 0, SQLiteConfig.TransactionMode.DEFERRED)) {
            ((SQLiteConnection) waiter)
                    .getConnectionConfig()
                    .setTransactionMode(SQLiteConfig.TransactionMode.DEFERRED);
            createTable(waiter);
            insert(waiter, 1);
            try (Statement statement = waiter.createStatement()) {
                statement.executeUpdate("create temp table temp_t(v)");
                statement.executeUpdate("insert into temp_t values (0)");
            }

            waiter.setAutoCommit(false);
            reader.setAutoCommit(false);
            try (Statement statement = reader.createStatement();
                    ResultSet result = statement.executeQuery("select * from t")) {
                assertThat(result.next()).isTrue();
            }
            ((SQLiteConnection) waiter)
                    .getConnectionConfig()
                    .setTransactionMode(SQLiteConfig.TransactionMode.EXCLUSIVE);

            try (Statement statement = waiter.createStatement()) {
                statement.executeUpdate("update temp_t set v=1");
            }

            expectBusy(waiter::commit);
            assertThat(waiter.getAutoCommit()).isFalse();
            try (Statement statement = waiter.createStatement()) {
                expectBusy(() -> statement.executeUpdate("insert into temp_t values (2)"));
            }

            reader.setAutoCommit(true);
            waiter.commit();
            try (Statement statement = waiter.createStatement();
                    ResultSet result = statement.executeQuery("select v from temp_t")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
                assertThat(result.next()).isFalse();
            }
            waiter.setAutoCommit(true);
            ((SQLiteConnection) waiter)
                    .getConnectionConfig()
                    .setTransactionMode(SQLiteConfig.TransactionMode.DEFERRED);
            waiter.setAutoCommit(false);
            reader.setAutoCommit(false);
            try (Statement statement = reader.createStatement();
                    ResultSet result = statement.executeQuery("select * from t")) {
                assertThat(result.next()).isTrue();
            }
            ((SQLiteConnection) waiter)
                    .getConnectionConfig()
                    .setTransactionMode(SQLiteConfig.TransactionMode.EXCLUSIVE);
            try (Statement statement = waiter.createStatement()) {
                statement.executeUpdate("update temp_t set v=2");
            }
            expectBusy(waiter::commit);
            waiter.setAutoCommit(true);
            assertThat(waiter.getAutoCommit()).isTrue();
            reader.setAutoCommit(true);
            try (Statement statement = waiter.createStatement();
                    ResultSet result = statement.executeQuery("select v from temp_t")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(2);
                assertThat(result.next()).isFalse();
            }
        }
    }

    @Test
    void t07ConcurrentStatementCannotBypassAFailedTransactionRestart() throws Exception {
        try (Connection waiter =
                        open("concurrent-restart.db", 5000, SQLiteConfig.TransactionMode.DEFERRED);
                Connection reader =
                        open("concurrent-restart.db", 1000, SQLiteConfig.TransactionMode.DEFERRED);
                Statement statement = waiter.createStatement()) {
            createTable(waiter);
            insert(waiter, 1);
            statement.executeUpdate("create temp table temp_t(v)");
            waiter.setAutoCommit(false);
            statement.executeUpdate("insert into temp_t values (1)");
            reader.setAutoCommit(false);
            try (Statement readStatement = reader.createStatement();
                    ResultSet result = readStatement.executeQuery("select * from t")) {
                assertThat(result.next()).isTrue();
            }
            ((SQLiteConnection) waiter)
                    .getConnectionConfig()
                    .setTransactionMode(SQLiteConfig.TransactionMode.EXCLUSIVE);
            long waits = waitCount();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            AtomicReference<Thread> statementThread = new AtomicReference<>();
            try {
                Future<SQLException> commit =
                        executor.submit(
                                () -> {
                                    try {
                                        waiter.commit();
                                        return null;
                                    } catch (SQLException exception) {
                                        return exception;
                                    }
                                });
                awaitWait(waits);
                Future<Integer> insert =
                        executor.submit(
                                () -> {
                                    statementThread.set(Thread.currentThread());
                                    return statement.executeUpdate("insert into temp_t values (2)");
                                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while ((statementThread.get() == null
                                || statementThread.get().getState() != Thread.State.BLOCKED)
                        && System.nanoTime() < deadline) {
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
                }
                assertThat(statementThread.get()).isNotNull();
                assertThat(statementThread.get().getState()).isEqualTo(Thread.State.BLOCKED);
                assertThat(commit.get(7, TimeUnit.SECONDS).getErrorCode() & 0xff).isEqualTo(5);
                reader.setAutoCommit(true);
                assertThat(insert.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                waiter.rollback();
                try (ResultSet result = statement.executeQuery("select count(*) from temp_t")) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt(1)).isEqualTo(1);
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void t08RollbackIsNotRepeatedWhenItsFollowupBeginFails() throws Exception {
        try (Connection waiter =
                        open("rollback-restart.db", 0, SQLiteConfig.TransactionMode.DEFERRED);
                Connection reader =
                        open("rollback-restart.db", 0, SQLiteConfig.TransactionMode.DEFERRED)) {
            createTable(waiter);
            insert(waiter, 1);
            try (Statement statement = waiter.createStatement()) {
                statement.executeUpdate("create temp table temp_t(v)");
                statement.executeUpdate("insert into temp_t values (0)");
            }

            waiter.setAutoCommit(false);
            reader.setAutoCommit(false);
            try (Statement statement = reader.createStatement();
                    ResultSet result = statement.executeQuery("select * from t")) {
                assertThat(result.next()).isTrue();
            }
            ((SQLiteConnection) waiter)
                    .getConnectionConfig()
                    .setTransactionMode(SQLiteConfig.TransactionMode.EXCLUSIVE);
            try (Statement statement = waiter.createStatement()) {
                statement.executeUpdate("update temp_t set v=1");
            }

            expectBusy(waiter::rollback);
            assertThat(waiter.getAutoCommit()).isFalse();
            reader.setAutoCommit(true);
            waiter.rollback();
            try (Statement statement = waiter.createStatement();
                    ResultSet result = statement.executeQuery("select v from temp_t")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isZero();
            }
            waiter.setAutoCommit(true);
        }
    }

    private SQLException expectBusy(SqlAction action) throws Exception {
        try {
            action.run();
        } catch (SQLException exception) {
            assertThat(exception.getErrorCode() & 0xff).as(exception.getMessage()).isEqualTo(5);
            return exception;
        }
        throw new AssertionError("expected SQLITE_BUSY");
    }
}
