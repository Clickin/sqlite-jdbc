// --------------------------------------
// sqlite-jdbc Project
//
// BackupSessionLifecycleTest.java
// --------------------------------------
package org.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.core.DB;

/**
 * Phase 1 accuracy tests for the Java-driven backup/restore session lifecycle: initialization
 * failures must report the right connection's error, must not leak the temporary connection, and
 * dangerous re-entry must be rejected.
 */
public class BackupSessionLifecycleTest {

    @TempDir File tempDir;

    private static void createSample(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("create table sample(id integer primary key, name)");
            stmt.executeUpdate("insert into sample values(1, 'leo')");
            stmt.executeUpdate("insert into sample values(2, 'yui')");
        }
    }

    /**
     * B01: normal completion reports progress on the caller thread; the connection stays usable.
     */
    @Test
    void backupReportsProgressOnCallerThreadAndConnectionIsReusable() throws Exception {
        File source = new File(tempDir, "source.sqlite");
        File destination = new File(tempDir, "destination.sqlite");

        try (SQLiteConnection conn =
                (SQLiteConnection)
                        DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath())) {
            createSample(conn);

            AtomicInteger progressCalls = new AtomicInteger();
            AtomicReference<Thread> progressThread = new AtomicReference<>();
            DB.ProgressObserver observer =
                    (remaining, pageCount) -> {
                        progressCalls.incrementAndGet();
                        progressThread.compareAndSet(null, Thread.currentThread());
                    };

            int rc =
                    conn.getDatabase()
                            .backup("main", destination.getAbsolutePath(), observer, 100, 3, 1);
            assertThat(rc).isEqualTo(SQLiteErrorCode.SQLITE_OK.code);
            assertThat(progressCalls.get()).isGreaterThan(0);
            assertThat(progressThread.get()).isEqualTo(Thread.currentThread());

            // The connection accepts more work after the session finished.
            assertThat(
                            conn.getDatabase()
                                    .backup("main", destination.getAbsolutePath(), null, 100, 3, 1))
                    .isEqualTo(SQLiteErrorCode.SQLITE_OK.code);
        }
    }

    /**
     * B02: an unopenable destination fails with the real error and leaves the connection usable.
     */
    @Test
    void backupToUnopenableDestinationFailsAndKeepsSourceUsable() throws Exception {
        File source = new File(tempDir, "source.sqlite");
        File badDestination =
                new File(tempDir, "missing-directory" + File.separator + "dest.sqlite");

        try (Connection conn =
                        DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath());
                Statement stmt = conn.createStatement()) {
            createSample(conn);

            assertThatThrownBy(
                            () ->
                                    stmt.executeUpdate(
                                            "backup to " + badDestination.getAbsolutePath()))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Backup failed");

            // The source connection survived the failed session.
            try (java.sql.ResultSet rs = stmt.executeQuery("select count(*) from sample")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }
    }

    /**
     * B03: a restore into a destination with an active transaction fails at initialization and
     * reports the destination connection's error, not a spurious step-time MISUSE.
     */
    @Test
    void restoreIntoDestinationWithActiveTransactionReportsDestinationError() throws Exception {
        File destination = new File(tempDir, "destination.sqlite");
        File source = new File(tempDir, "source.sqlite");

        try (Connection destConn =
                        DriverManager.getConnection(
                                "jdbc:sqlite:" + destination.getAbsolutePath());
                Connection sourceConn =
                        DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath());
                Statement destStmt = destConn.createStatement()) {
            createSample(sourceConn);

            // A write transaction on the destination makes sqlite3_backup_init fail
            // and record the error on the destination connection (pDb).
            destStmt.execute("BEGIN IMMEDIATE");

            // Pre-fix behavior reported a step-time MISUSE; the fix surfaces the
            // destination connection's SQLITE_ERROR from initialization.
            assertThatThrownBy(
                            () ->
                                    destStmt.executeUpdate(
                                            "restore from " + source.getAbsolutePath()))
                    .isInstanceOfSatisfying(
                            SQLiteException.class,
                            error ->
                                    assertThat(error.getResultCode())
                                            .isEqualTo(SQLiteErrorCode.SQLITE_ERROR))
                    .hasMessageContaining("Restore failed");

            destStmt.execute("ROLLBACK");
        }
    }

    /**
     * B07a: an observer that throws mid-copy propagates the original exception, releases the
     * session exactly once, and leaves the connection usable.
     */
    @Test
    void observerExceptionPropagatesAndSessionIsReleased() throws Exception {
        File source = new File(tempDir, "source.sqlite");
        File destination = new File(tempDir, "destination.sqlite");

        try (SQLiteConnection conn =
                (SQLiteConnection)
                        DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath())) {
            createSample(conn);

            RuntimeException failure = new RuntimeException("observer boom");
            DB.ProgressObserver throwingObserver =
                    (remaining, pageCount) -> {
                        throw failure;
                    };

            assertThatThrownBy(
                            () ->
                                    conn.getDatabase()
                                            .backup(
                                                    "main",
                                                    destination.getAbsolutePath(),
                                                    throwingObserver,
                                                    100,
                                                    3,
                                                    1))
                    .isSameAs(failure);

            // Session released: a fresh backup on the same connection completes.
            assertThat(
                            conn.getDatabase()
                                    .backup("main", destination.getAbsolutePath(), null, 100, 3, 1))
                    .isEqualTo(SQLiteErrorCode.SQLITE_OK.code);
        }
    }

    /**
     * B07b: starting a second backup/restore on a connection that already drives one is rejected
     * before entering JNI. The restore destination must not be handed to another session.
     */
    @Test
    void nestedSessionOnSameConnectionIsRejected() throws Exception {
        File source = new File(tempDir, "source.sqlite");
        File destination = new File(tempDir, "destination.sqlite");
        File other = new File(tempDir, "other.sqlite");

        try (SQLiteConnection conn =
                (SQLiteConnection)
                        DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath())) {
            createSample(conn);

            DB.ProgressObserver reenteringObserver =
                    (remaining, pageCount) -> {
                        assertThatThrownBy(
                                        () ->
                                                conn.getDatabase()
                                                        .backup(
                                                                "main",
                                                                other.getAbsolutePath(),
                                                                null,
                                                                100,
                                                                3,
                                                                1))
                                .isInstanceOf(SQLException.class)
                                .hasMessageContaining("already active");
                        assertThatThrownBy(
                                        () ->
                                                conn.getDatabase()
                                                        .restore(
                                                                "main",
                                                                source.getAbsolutePath(),
                                                                null,
                                                                100,
                                                                3,
                                                                1))
                                .isInstanceOf(SQLException.class)
                                .hasMessageContaining("already active");
                    };

            int rc =
                    conn.getDatabase()
                            .backup(
                                    "main",
                                    destination.getAbsolutePath(),
                                    reenteringObserver,
                                    100,
                                    3,
                                    1);
            assertThat(rc).isEqualTo(SQLiteErrorCode.SQLITE_OK.code);
        }
    }

    /** B08: repeated successes and failures leave the connection fully reusable every time. */
    @Test
    void repeatedBackupsAndFailuresStayBounded() throws Exception {
        File source = new File(tempDir, "source.sqlite");
        File destination = new File(tempDir, "destination.sqlite");

        try (Connection conn =
                        DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath());
                Statement stmt = conn.createStatement()) {
            createSample(conn);

            for (int i = 0; i < 10; i++) {
                stmt.executeUpdate("backup to " + destination.getAbsolutePath());
                assertThatThrownBy(
                                () ->
                                        stmt.executeUpdate(
                                                "backup to "
                                                        + new File(
                                                                tempDir,
                                                                "no-such-dir"
                                                                        + File.separator
                                                                        + "x.db")))
                        .isInstanceOf(SQLException.class);
            }

            try (Connection verify =
                            DriverManager.getConnection(
                                    "jdbc:sqlite:" + destination.getAbsolutePath());
                    Statement verifyStmt = verify.createStatement();
                    java.sql.ResultSet rs =
                            verifyStmt.executeQuery("select count(*) from sample")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isEqualTo(2);
            }
        }
    }
}
