// --------------------------------------
// sqlite-jdbc Project
//
// BackupFaultInjectionTest.java
// --------------------------------------
package org.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.core.DB;
import org.sqlite.core.NativeDBHelper;

/**
 * B04/B08: backupInit resource handling under injected failures. Requires the fault-injection
 * native (build and run via `make test-faults`); against the shipped library every test is skipped
 * by assumption.
 */
public class BackupFaultInjectionTest {

    @TempDir File tempDir;

    /** Returns the outstanding session count, or null without the fault-injection library. */
    private static Long outstanding(DB db) {
        try {
            return NativeDBHelper.backupTestOutstanding(db)[0];
        } catch (UnsatisfiedLinkError noFaultLibrary) {
            return null;
        }
    }

    private static void createSample(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate("create table sample(id integer primary key, name)");
            stmt.executeUpdate("insert into sample values(1, 'leo')");
        }
    }

    /** B04a: a failed session allocation closes the temporary connection, no session escapes. */
    @Test
    void failedSessionAllocationClosesTemporaryConnection() throws Exception {
        File source = new File(tempDir, "source.sqlite");
        try (SQLiteConnection conn =
                (SQLiteConnection)
                        DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath())) {
            createSample(conn);
            DB db = conn.getDatabase();
            Long baseline = outstanding(db);
            assumeTrue(
                    baseline != null, "fault-injection native library required (make test-faults)");

            File destination = new File(tempDir, "dest1.sqlite");
            NativeDBHelper.backupTestSetFaultMode(db, 1);
            try {
                assertThatThrownBy(
                                () -> {
                                    try (Statement stmt = conn.createStatement()) {
                                        stmt.executeUpdate(
                                                "backup to " + destination.getAbsolutePath());
                                    }
                                })
                        .isInstanceOf(SQLException.class);
            } finally {
                NativeDBHelper.backupTestSetFaultMode(db, 0);
            }

            assertThat(outstanding(db)).isEqualTo(baseline);
            // The connection keeps working after the injected failure.
            assertThat(db.backup("main", destination.getAbsolutePath(), null, 100, 3, 1))
                    .isEqualTo(SQLiteErrorCode.SQLITE_OK.code);
        }
    }

    /**
     * B04b: a failure after the session was fully acquired (JNI result array) must release the
     * session natively; no session or temporary connection leaks.
     */
    @Test
    void failedResultArrayCreationReleasesSessionNatively() throws Exception {
        File source = new File(tempDir, "source.sqlite");
        try (SQLiteConnection conn =
                (SQLiteConnection)
                        DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath())) {
            createSample(conn);
            DB db = conn.getDatabase();
            Long baseline = outstanding(db);
            assumeTrue(
                    baseline != null, "fault-injection native library required (make test-faults)");

            File destination = new File(tempDir, "dest2.sqlite");
            NativeDBHelper.backupTestSetFaultMode(db, 2);
            try {
                assertThatThrownBy(
                                () -> {
                                    try (Statement stmt = conn.createStatement()) {
                                        stmt.executeUpdate(
                                                "backup to " + destination.getAbsolutePath());
                                    }
                                })
                        .isInstanceOf(SQLException.class);
            } finally {
                NativeDBHelper.backupTestSetFaultMode(db, 0);
            }

            assertThat(outstanding(db)).isEqualTo(baseline);
            assertThat(db.backup("main", destination.getAbsolutePath(), null, 100, 3, 1))
                    .isEqualTo(SQLiteErrorCode.SQLITE_OK.code);
        }
    }

    /** B08: repeated failures and successes return the outstanding session count to baseline. */
    @Test
    void repeatedSessionsReturnToBaseline() throws Exception {
        File source = new File(tempDir, "source.sqlite");
        try (SQLiteConnection conn =
                (SQLiteConnection)
                        DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath())) {
            createSample(conn);
            DB db = conn.getDatabase();
            Long baseline = outstanding(db);
            assumeTrue(
                    baseline != null, "fault-injection native library required (make test-faults)");

            File destination = new File(tempDir, "dest3.sqlite");
            for (int i = 0; i < 5; i++) {
                NativeDBHelper.backupTestSetFaultMode(db, 1);
                try (Statement stmt = conn.createStatement()) {
                    try {
                        stmt.executeUpdate("backup to " + destination.getAbsolutePath());
                    } catch (SQLException expected) {
                        // injected failure
                    } finally {
                        NativeDBHelper.backupTestSetFaultMode(db, 0);
                    }
                }
                assertThat(db.backup("main", destination.getAbsolutePath(), null, 100, 3, 1))
                        .isEqualTo(SQLiteErrorCode.SQLITE_OK.code);
            }

            assertThat(outstanding(db)).isEqualTo(baseline);
        }
    }
}
