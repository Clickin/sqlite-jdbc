// --------------------------------------
// sqlite-jdbc Project
//
// BackupBusyWaitTest.java
// --------------------------------------
package org.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that backup/restore busy waits happen on the Java side, outside any SQLite native
 * frame, while preserving the locking and result-code contract of the former native loop.
 */
public class BackupBusyWaitTest {

    @TempDir File tempDir;

    private static void createSample(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("create table sample(id integer primary key, name)");
            stmt.executeUpdate("insert into sample values(1, 'leo')");
            stmt.executeUpdate("insert into sample values(2, 'yui')");
        }
    }

    @Test
    void backupReportsBusyWhenDestinationIsLockedAndSucceedsAfterRelease() throws Exception {
        File source = new File(tempDir, "source.sqlite");
        File destination = new File(tempDir, "destination.sqlite");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath());
             Connection destConn =
                     DriverManager.getConnection("jdbc:sqlite:" + destination.getAbsolutePath())) {
            createSample(conn);
            try (Statement destStmt = destConn.createStatement()) {
                destStmt.execute("BEGIN EXCLUSIVE");
            }

            // Default budget: 3 busy retries x 100ms. The wait runs on the Java side.
            try (Statement stmt = conn.createStatement()) {
                long started = System.nanoTime();
                assertThatThrownBy(() -> stmt.executeUpdate("backup to " + destination.getAbsolutePath()))
                        .isInstanceOfSatisfying(
                                SQLiteException.class,
                                error -> assertThat(error.getResultCode())
                                        .isEqualTo(SQLiteErrorCode.SQLITE_BUSY))
                        .hasMessageContaining("Backup failed");
                long waitedMillis = (System.nanoTime() - started) / 1_000_000L;
                // The three Java-side sleeps must actually elapse.
                assertThat(waitedMillis).isGreaterThanOrEqualTo(200L);
            }

            try (Statement destStmt = destConn.createStatement()) {
                destStmt.execute("ROLLBACK");
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("backup to " + destination.getAbsolutePath());
            }
        }

        try (Connection verify = DriverManager.getConnection("jdbc:sqlite:" + destination.getAbsolutePath());
             Statement stmt = verify.createStatement();
             java.sql.ResultSet rs = stmt.executeQuery("select count(*) from sample")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getInt(1)).isEqualTo(2);
        }
    }

    @Test
    void restoreReportsBusyWhenDestinationIsLocked() throws Exception {
        File source = new File(tempDir, "source.sqlite");
        File destination = new File(tempDir, "destination.sqlite");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + destination.getAbsolutePath());
             Connection sourceConn =
                     DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath());
             Connection blockerConn =
                     DriverManager.getConnection("jdbc:sqlite:" + destination.getAbsolutePath())) {
            createSample(sourceConn);

            // A second connection holds the destination file; the restoring connection must see BUSY.
            try (Statement blockerStmt = blockerConn.createStatement()) {
                blockerStmt.execute("BEGIN EXCLUSIVE");
            }

            // A busy destination must surface as an exception, never as a silent partial restore.
            try (Statement stmt = conn.createStatement()) {
                assertThatThrownBy(() -> stmt.executeUpdate("restore from " + source.getAbsolutePath()))
                        .isInstanceOfSatisfying(
                                SQLiteException.class,
                                error -> assertThat(error.getResultCode())
                                        .isEqualTo(SQLiteErrorCode.SQLITE_BUSY))
                        .hasMessageContaining("Restore failed");
            }

            try (Statement blockerStmt = blockerConn.createStatement()) {
                blockerStmt.execute("ROLLBACK");
            }

            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("restore from " + source.getAbsolutePath());
                try (java.sql.ResultSet rs = stmt.executeQuery("select count(*) from sample")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(2);
                }
            }
        }
    }

    @Test
    void interruptedBackupAbortsAndKeepsConnectionUsable() throws Exception {
        File source = new File(tempDir, "source.sqlite");
        File destination = new File(tempDir, "destination.sqlite");

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath());
             Connection destConn =
                     DriverManager.getConnection("jdbc:sqlite:" + destination.getAbsolutePath())) {
            createSample(conn);
            try (Statement destStmt = destConn.createStatement()) {
                destStmt.execute("BEGIN EXCLUSIVE");
            }

            Thread worker = new Thread(() -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("backup to " + destination.getAbsolutePath());
                } catch (SQLException ignored) {
                    // Expected: the interrupted wait aborts the backup.
                }
            });
            worker.start();
            Thread.sleep(50);
            worker.interrupt();
            worker.join(10_000);
            assertThat(worker.isAlive()).isFalse();

            try (Statement destStmt = destConn.createStatement()) {
                destStmt.execute("ROLLBACK");
            }

            // The connection and a fresh backup must still work.
            try (Statement stmt = conn.createStatement()) {
                assertThatCode(() -> stmt.executeUpdate("backup to " + destination.getAbsolutePath()))
                        .doesNotThrowAnyException();
            }
        }
    }

}
