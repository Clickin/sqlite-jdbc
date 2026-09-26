package org.sqlite;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.BatchUpdateException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.core.NativeDBHelper;
import org.sqlite.core.VtWaitProbe;

/** P01–P12: characterize the effective busy policy through real lock contention. */
public class BusyPolicyTest {
    @TempDir Path tempDir;

    private Connection open(String file, int timeout, boolean immediate) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(timeout);
        if (immediate) config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        return DriverManager.getConnection(
                "jdbc:sqlite:" + tempDir.resolve(file), config.toProperties());
    }

    private void holdWriteLock(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("create table if not exists t(v)");
        }
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into t values (1)");
        }
    }

    private int busyTimeout(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("pragma busy_timeout")) {
            result.next();
            return result.getInt(1);
        }
    }

    private boolean setlkTimeoutEnabled(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("pragma compile_options")) {
            while (result.next()) {
                if (result.getString(1).contains("ENABLE_SETLK_TIMEOUT")) return true;
            }
            return false;
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

    private SQLException expectBusy(SqlAction action) throws Exception {
        try {
            action.run();
        } catch (SQLException exception) {
            assertThat(exception.getErrorCode() & 0xff).isEqualTo(5);
            return exception;
        }
        throw new AssertionError("expected SQLITE_BUSY");
    }

    @Test
    void p01_defaultTimeoutSurvivesSuccessfulAndFailedControlStatements() throws Exception {
        try (Connection blocker = open("p01.db", 100, false);
                Connection waiter = open("p01.db", 100, true)) {
            assertThat(setlkTimeoutEnabled(waiter)).isFalse();
            holdWriteLock(blocker);
            long waits = waitCount();
            expectBusy(() -> waiter.setAutoCommit(false));
            assertThat(waitCount()).isGreaterThan(waits);
            assertThat(waiter.getAutoCommit()).isTrue();
            assertThat(busyTimeout(waiter)).isEqualTo(100);

            blocker.setAutoCommit(true);
            waiter.setAutoCommit(false);
            assertThat(waiter.getAutoCommit()).isFalse();
            waiter.rollback();
        }
    }

    @Test
    void p02_latestDriverTimeoutIsUsedAndRestored() throws Exception {
        try (Connection blocker = open("p02.db", 100, false);
                Connection waiter = open("p02.db", 3000, true)) {
            ((SQLiteConnection) waiter).setBusyTimeout(10);
            ((SQLiteConnection) waiter).setBusyTimeout(45);
            assertThat(busyTimeout(waiter)).isEqualTo(45);
            holdWriteLock(blocker);

            long waits = waitCount();
            expectBusy(() -> waiter.setAutoCommit(false));
            assertThat(waitCount()).isGreaterThan(waits);
            assertThat(busyTimeout(waiter)).isEqualTo(45);
            assertThat(waiter.getAutoCommit()).isTrue();
        }
    }

    @Test
    void p03SqlPragmaTimeoutIsTheEffectivePolicy() throws Exception {
        try (Connection blocker = open("p03.db", 100, false);
                Connection waiter = open("p03.db", 3000, true);
                Statement statement = waiter.createStatement()) {
            statement.execute("pragma busy_timeout=35");
            assertThat(((SQLiteConnection) waiter).getBusyTimeout()).isEqualTo(3000);
            assertThat(busyTimeout(waiter)).isEqualTo(35);
            holdWriteLock(blocker);

            long waits = waitCount();
            expectBusy(() -> waiter.setAutoCommit(false));
            assertThat(waitCount()).isGreaterThan(waits);
            assertThat(busyTimeout(waiter)).isEqualTo(35);
            expectBusy(() -> statement.executeUpdate("insert into t values (2)"));
            assertThat(busyTimeout(waiter)).isEqualTo(35);
        }
    }

    @Test
    void p04CustomHandlerRunsOnCallerAndUsesLegacyPath() throws Exception {
        try (Connection blocker = open("p04.db", 100, false);
                Connection waiter = open("p04.db", 1000, true)) {
            holdWriteLock(blocker);
            AtomicInteger calls = new AtomicInteger();
            Thread caller = Thread.currentThread();
            Thread[] callbackThread = new Thread[1];
            BusyHandler handler =
                    new BusyHandler() {
                        @Override
                        protected int callback(int previousCalls) {
                            assertThat(previousCalls).isEqualTo(calls.getAndIncrement());
                            callbackThread[0] = Thread.currentThread();
                            return 0;
                        }
                    };
            BusyHandler.setHandler(waiter, handler);
            long waits = waitCount();

            expectBusy(() -> waiter.setAutoCommit(false));
            assertThat(calls.get()).isEqualTo(1);
            assertThat(callbackThread[0]).isSameAs(caller);
            assertThat(waitCount()).isEqualTo(waits);
            assertThat(NativeDBHelper.getBusyHandler(((SQLiteConnection) waiter).getDatabase()))
                    .isNotZero();
        }
    }

    @Test
    void p05PragmaTimeoutDoesNotResurrectReplacedCustomHandler() throws Exception {
        try (Connection blocker = open("p05.db", 100, false);
                Connection waiter = open("p05.db", 1000, true);
                Statement statement = waiter.createStatement()) {
            AtomicInteger calls = new AtomicInteger();
            BusyHandler.setHandler(
                    waiter,
                    new BusyHandler() {
                        @Override
                        protected int callback(int previousCalls) {
                            calls.incrementAndGet();
                            return 0;
                        }
                    });
            statement.execute("pragma busy_timeout=35");
            holdWriteLock(blocker);
            long waits = waitCount();

            expectBusy(() -> waiter.setAutoCommit(false));
            assertThat(calls.get()).isZero();
            assertThat(waitCount()).isEqualTo(waits);
            assertThat(busyTimeout(waiter)).isEqualTo(35);
        }
    }

    @Test
    void p06ClearingCustomHandlerLeavesCurrentDisabledPolicy() throws Exception {
        try (Connection blocker = open("p06.db", 100, false);
                Connection waiter = open("p06.db", 1000, true)) {
            ((SQLiteConnection) waiter).setBusyTimeout(80);
            AtomicInteger calls = new AtomicInteger();
            BusyHandler.setHandler(
                    waiter,
                    new BusyHandler() {
                        @Override
                        protected int callback(int previousCalls) {
                            calls.incrementAndGet();
                            return 0;
                        }
                    });
            BusyHandler.clearHandler(waiter);
            holdWriteLock(blocker);
            long waits = waitCount();

            expectBusy(() -> waiter.setAutoCommit(false));
            assertThat(calls.get()).isZero();
            assertThat(waitCount()).isEqualTo(waits);
            assertThat(busyTimeout(waiter)).isZero();
        }
    }

    @Test
    void p07ZeroAndNegativeTimeoutDoNotSleepInJava() throws Exception {
        for (int timeout : new int[] {0, -1}) {
            try (Connection blocker = open("p07-" + timeout + ".db", 100, false);
                    Connection waiter = open("p07-" + timeout + ".db", timeout, true)) {
                holdWriteLock(blocker);
                long waits = waitCount();
                expectBusy(() -> waiter.setAutoCommit(false));
                assertThat(waitCount()).isEqualTo(waits);
                assertThat(busyTimeout(waiter)).isZero();
                assertThat(waiter.getAutoCommit()).isTrue();
            }
        }
    }

    @Test
    void p08QueryTimeoutIsTemporaryAndRestoredAfterBusySql() throws Exception {
        try (Connection blocker = open("p08.db", 100, false);
                Connection waiter = open("p08.db", 35, false);
                Statement statement = waiter.createStatement()) {
            holdWriteLock(blocker);
            statement.setQueryTimeout(1);

            expectBusy(() -> statement.executeUpdate("insert into t values (2)"));
            assertThat(busyTimeout(waiter)).isEqualTo(35);
            assertThat(((SQLiteConnection) waiter).getBusyTimeout()).isEqualTo(35);
        }
    }

    @Test
    void p09PragmaChangesAreVisibleAcrossPrepareExecuteExecAndBatch() throws Exception {
        try (Connection connection = open("p09.db", 3000, false)) {
            try (PreparedStatement prepared =
                    connection.prepareStatement("pragma busy_timeout=41")) {
                assertThat(busyTimeout(connection)).isEqualTo(41);
                prepared.execute();
            }
            assertThat(busyTimeout(connection)).isEqualTo(41);

            try (Statement statement = connection.createStatement()) {
                statement.execute("pragma busy_timeout=52");
            }
            assertThat(busyTimeout(connection)).isEqualTo(52);

            ((SQLiteConnection) connection).getDatabase()._exec("pragma busy_timeout=63");
            assertThat(busyTimeout(connection)).isEqualTo(63);

            try (Statement statement = connection.createStatement()) {
                statement.addBatch("pragma busy_timeout=74");
                try {
                    statement.executeBatch();
                    throw new AssertionError("batch PRAGMA unexpectedly succeeded");
                } catch (BatchUpdateException expected) {
                    assertThat(expected.getMessage()).contains("query returns results");
                }
            }
            assertThat(busyTimeout(connection)).isEqualTo(74);
        }
    }

    @Test
    void p10ReadbackUsesCurrentPragmaValueNotAnEarlierSnapshot() throws Exception {
        try (Connection blocker = open("p10.db", 100, false);
                Connection waiter = open("p10.db", 0, true);
                Statement statement = waiter.createStatement()) {
            statement.execute("pragma busy_timeout=55");
            holdWriteLock(blocker);
            long waits = waitCount();

            expectBusy(() -> waiter.setAutoCommit(false));
            assertThat(waitCount()).isGreaterThan(waits);
            assertThat(busyTimeout(waiter)).isEqualTo(55);
        }
    }

    @Test
    void p11ConcurrentTimeoutChangeTakesEffectAfterCurrentWait() throws Exception {
        try (Connection blocker = open("p11.db", 100, false);
                Connection waiter = open("p11.db", 80, true)) {
            holdWriteLock(blocker);
            long waits = waitCount();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<SQLException> pending =
                        executor.submit(
                                () -> {
                                    try {
                                        waiter.setAutoCommit(false);
                                        throw new AssertionError("expected SQLITE_BUSY");
                                    } catch (SQLException exception) {
                                        return exception;
                                    }
                                });
                awaitWait(waits);
                ((SQLiteConnection) waiter).setBusyTimeout(300);
                assertThat(pending.get(5, TimeUnit.SECONDS).getErrorCode() & 0xff).isEqualTo(5);
                assertThat(waiter.getAutoCommit()).isTrue();
                assertThat(busyTimeout(waiter)).isEqualTo(300);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void p12FallbackLeavesCustomHandlerAvailableToLaterSql() throws Exception {
        try (Connection blocker = open("p12.db", 100, false);
                Connection waiter = open("p12.db", 1000, true);
                Statement statement = waiter.createStatement()) {
            AtomicInteger calls = new AtomicInteger();
            BusyHandler.setHandler(
                    waiter,
                    new BusyHandler() {
                        @Override
                        protected int callback(int previousCalls) {
                            calls.incrementAndGet();
                            return 0;
                        }
                    });
            holdWriteLock(blocker);
            long waits = waitCount();

            expectBusy(() -> waiter.setAutoCommit(false));
            assertThat(calls.get()).isEqualTo(1);
            assertThat(waitCount()).isEqualTo(waits);
            expectBusy(() -> statement.executeUpdate("insert into t values (2)"));
            assertThat(calls.get()).isEqualTo(2);
            assertThat(waitCount()).isEqualTo(waits);
        }
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws Exception;
    }
}
