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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that backup/restore busy waits happen on the Java side, outside any SQLite native
 * frame, while preserving the locking and result-code contract of the former native loop.
 */
public class BackupBusyWaitTest {
    static {
        // Must run before any virtual thread starts in this JVM so the scheduler allows exactly
        // one carrier beyond the callers; with JEP 491 the waiting backup VT still yields it.
        System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", "1");
    }

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

    /**
     * On JDK 24+ (JEP 491) a virtual thread sleeping in the Java-side busy wait must release its
     * carrier: no jdk.VirtualThreadPinned events may be recorded. Uses reflection so this test
     * class still compiles and is skipped cleanly on Java 8.
     */
    @Test
    @SuppressWarnings("unchecked")
    void virtualThreadBackupWaitDoesNotPinCarrierOnModernJdks() throws Exception {
        if (compareVersion(System.getProperty("java.version")) < 24) {
            return; // JEP 491 landed in JDK 24; older runtimes pin by design.
        }
        Class<?> recordingClass = Class.forName("jdk.jfr.Recording");
        Object recording = recordingClass.getDeclaredConstructor().newInstance();
        boolean jfrAvailable;
        try {
            Object flightRecorder = Class.forName("jdk.jfr.FlightRecorder")
                    .getMethod("getFlightRecorder")
                    .invoke(null);
            Object pinnedEvent = null;
            for (Object type : (Iterable<Object>) Class.forName("jdk.jfr.FlightRecorder")
                    .getMethod("getEventTypes")
                    .invoke(flightRecorder)) {
                if ("jdk.VirtualThreadPinned".equals(
                        Class.forName("jdk.jfr.EventType").getMethod("getName").invoke(type))) {
                    pinnedEvent = type;
                }
            }
            assertThat(pinnedEvent).as("VirtualThreadPinned event must exist").isNotNull();
            Object settings = recordingClass
                    .getMethod("enable", String.class)
                    .invoke(recording, "jdk.VirtualThreadPinned");
            Class.forName("jdk.jfr.EventSettings")
                    .getMethod("withThreshold", java.time.Duration.class)
                    .invoke(settings, java.time.Duration.ZERO);
            recordingClass.getMethod("start").invoke(recording);
            jfrAvailable = true;
        } catch (Exception unavailable) {
            // JFR unavailable in this JVM: keep the progress assertions, skip the pinned count.
            jfrAvailable = false;
        }

        File source = new File(tempDir, "vt-source.sqlite");
        File destination = new File(tempDir, "vt-destination.sqlite");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + source.getAbsolutePath());
             Connection destConn =
                     DriverManager.getConnection("jdbc:sqlite:" + destination.getAbsolutePath())) {
            createSample(conn);
            try (Statement destStmt = destConn.createStatement()) {
                destStmt.execute("BEGIN EXCLUSIVE");
            }

            AtomicBoolean independentProgress = new AtomicBoolean(false);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Class<?> threadClass = Class.forName("java.lang.Thread");
            Class<?> builderClass = Class.forName("java.lang.Thread$Builder");
            Object builder =
                    threadClass.getMethod("ofVirtual").invoke(null);
            builderClass.getMethod("name", String.class).invoke(builder, "backup-vt");

            Runnable backupTask = () -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.executeUpdate("backup to " + destination.getAbsolutePath());
                } catch (SQLException error) {
                    failure.set(error);
                }
            };
            Object backupThread =
                    builderClass.getMethod("unstarted", Runnable.class).invoke(builder, backupTask);
            Runnable independentTask = () -> independentProgress.set(true);
            Object independentThread =
                    builderClass.getMethod("unstarted", Runnable.class)
                            .invoke(builder, independentTask);

            threadClass.getMethod("start").invoke(backupThread);
            // While the backup VT sleeps in its Java-side busy wait, a second virtual thread must
            // still run even though the sleeping thread keeps the DB monitor held.
            threadClass.getMethod("start").invoke(independentThread);
            threadClass.getMethod("join", long.class).invoke(backupThread, 20_000L);
            threadClass.getMethod("join", long.class).invoke(independentThread, 20_000L);

            // The destination stays locked for the whole scenario, so the backup must exhaust its
            // budget and report SQLITE_BUSY; the wait itself is what must not pin.
            assertThat(failure.get())
                    .isInstanceOfSatisfying(
                            SQLiteException.class,
                            error -> assertThat(error.getResultCode())
                                    .isEqualTo(SQLiteErrorCode.SQLITE_BUSY));
            assertThat(independentProgress.get()).isTrue();

            if (jfrAvailable) {
                recordingClass.getMethod("stop").invoke(recording);
                File dumpFile = new File(tempDir, "backup-vt-pinned.jfr");
                recordingClass
                        .getMethod("dump", java.nio.file.Path.class)
                        .invoke(recording, dumpFile.toPath());
                Class<?> recordingFileClass = Class.forName("jdk.jfr.consumer.RecordingFile");
                Object recordingFile = recordingFileClass
                        .getConstructor(java.nio.file.Path.class)
                        .newInstance(dumpFile.toPath());
                int count = 0;
                try {
                    while ((Boolean) recordingFileClass
                            .getMethod("hasMoreEvents")
                            .invoke(recordingFile)) {
                        Object event = recordingFileClass
                                .getMethod("readEvent")
                                .invoke(recordingFile);
                        Object eventType = Class.forName("jdk.jfr.RecordedEvent")
                                .getMethod("getEventType")
                                .invoke(event);
                        if ("jdk.VirtualThreadPinned".equals(
                                Class.forName("jdk.jfr.EventType")
                                        .getMethod("getName")
                                        .invoke(eventType))) {
                            count++;
                        }
                    }
                } finally {
                    recordingFileClass.getMethod("close").invoke(recordingFile);
                }
                assertThat(count)
                        .as("virtual threads must not pin carriers while waiting in backup")
                        .isZero();
            }
        } finally {
            try {
                recordingClass.getMethod("close").invoke(recording);
            } catch (Exception ignored) {
            }
        }
    }

    private static int compareVersion(String version) {
        // "25", "1.8.0_472", "17.0.14": extract the feature version.
        String[] parts = version.replace("\"", "").split("\\.");
        if ("1".equals(parts[0])) {
            return 8; // legacy 1.x scheme ends at Java 8 here
        }
        return Integer.parseInt(parts[0]);
    }
}
