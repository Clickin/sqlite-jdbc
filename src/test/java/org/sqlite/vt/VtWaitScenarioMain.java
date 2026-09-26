// --------------------------------------
// sqlite-jdbc Project
//
// VtWaitScenarioMain.java
// --------------------------------------
package org.sqlite.vt;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.sqlite.SQLiteConnection;
import org.sqlite.core.VtWaitProbe;

/**
 * Child-JVM scenario for single-carrier virtual-thread progress proofs. Launched by
 * VtCarrierProgressTest with -Djdk.virtualThreadScheduler.parallelism=1 and
 * -Djdk.virtualThreadScheduler.maxPoolSize=1 so exactly one carrier exists. Uses reflection for
 * virtual-thread APIs so this class compiles under Java 8.
 *
 * <p>stdout protocol: WAIT-ENTERED, B-START, B-DONE targetStillWaiting=..., EXCLUSION-BLOCKED=...,
 * PROBER-FINISHED=..., TARGET-RESULT=...; exit code 0 success, 1 failure, 2 no virtual threads.
 */
public class VtWaitScenarioMain {

    public static void main(String[] args) throws Exception {
        String scenario = args.length > 0 ? args[0] : "backup-progress";
        File workDir = new File(args.length > 1 ? args[1] : ".");
        // marker=call-start: for the parent-commit control group, where the whole backup is one
        // blocking native call, the marker is the statement right before that call.
        boolean callStartMarker =
                args.length > 2 && "--marker=call-start".equals(args[2]);

        Object builder;
        try {
            builder = Class.forName("java.lang.Thread").getMethod("ofVirtual").invoke(null);
        } catch (Throwable unsupported) {
            System.out.println("VT-UNSUPPORTED");
            System.exit(2);
            return;
        }

        String sourcePath = new File(workDir, "source.sqlite").getAbsolutePath();
        String destinationPath = new File(workDir, "destination.sqlite").getAbsolutePath();

        // The platform main thread holds the destination write lock for the whole scenario; it
        // never depends on the virtual threads, so releasing it cannot deadlock on a stolen
        // carrier. The connection must stay open: closing it would release the lock.
        Connection lock = DriverManager.getConnection("jdbc:sqlite:" + destinationPath);
        try (Statement lockStmt = lock.createStatement()) {
            lockStmt.execute("BEGIN EXCLUSIVE");
        }

        try (Connection source = DriverManager.getConnection("jdbc:sqlite:" + sourcePath)) {
            try (Statement setup = source.createStatement()) {
                setup.executeUpdate("create table sample(id integer primary key, name)");
                setup.executeUpdate("insert into sample values(1, 'leo')");
                setup.executeUpdate("insert into sample values(2, 'yui')");
            }

            final java.util.concurrent.atomic.AtomicBoolean waitEntered =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            AtomicBoolean targetDone = new AtomicBoolean(false);
            AtomicReference<String> targetResult = new AtomicReference<>("PENDING");
            Runnable targetTask = () -> {
                try {
                    if (callStartMarker) {
                        waitEntered.set(true);
                    }
                    // Generous Java-wait budget: 5 busy retries x 150ms so the scenario has room.
                    int rc = ((SQLiteConnection) source).getDatabase()
                            .backup("main", destinationPath, null, 150, 5, 1);
                    targetResult.set("RC=" + rc);
                } catch (SQLException error) {
                    targetResult.set("SQLERROR");
                } finally {
                    targetDone.set(true);
                }
            };
            Object targetThread = unstarted(builder, targetTask, "vt-target-backup");
            start(targetThread);

            long deadline = System.nanoTime() + 20_000_000_000L;
            while (!waitEntered.get()) {
                if (!callStartMarker) {
                    waitEntered.set(VtWaitProbe.javaWaitObservations() > 0);
                }
                if (System.nanoTime() > deadline || targetDone.get()) {
                    lock.close();
                    fail("target never reached the Java wait");
                    return;
                }
                Thread.sleep(5);
            }
            System.out.println("WAIT-ENTERED");
            if (targetDone.get()) {
                fail("target finished before the wait was observed");
                return;
            }

            if ("backup-progress".equals(scenario)) {
                final java.util.concurrent.CountDownLatch independentDone =
                        new java.util.concurrent.CountDownLatch(1);
                Runnable independentTask = () -> {
                    try {
                        System.out.println("B-START");
                        for (int i = 0; i < 5; i++) {
                            Thread.sleep(20);
                        }
                        // Non-blocking read only: the marker must not yield the carrier itself.
                        System.out.println("B-DONE targetStillWaiting=" + !targetDone.get());
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    } finally {
                        independentDone.countDown();
                    }
                };
                Object independentThread = unstarted(builder, independentTask, "vt-independent");
                start(independentThread);
                join(independentThread, 10_000L);
                if (!independentDone.await(0, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    lock.close();
                    fail("independent virtual thread made no progress while target waited");
                    return;
                }
            } else if ("backup-exclusion".equals(scenario)) {
                AtomicBoolean proberFinished = new AtomicBoolean(false);
                Thread prober = new Thread(() -> {
                    try (Statement stmt = source.createStatement()) {
                        stmt.executeQuery("select 1").close();
                        proberFinished.set(true);
                    } catch (SQLException ignored) {
                        // expected only if the wait aborted with the monitor released
                    }
                }, "same-connection-prober");
                prober.start();
                Thread.sleep(400);
                System.out.println("EXCLUSION-BLOCKED=" + !proberFinished.get());
                System.out.println("PROBER-ALIVE-AFTER-400MS=" + prober.isAlive());
            } else {
                fail("unknown scenario " + scenario);
                return;
            }

            // Always terminate the target deterministically by letting its budget exhaust; the
            // destination stays locked so a restore/backup cannot silently complete.
            join(targetThread, 30_000L);
            System.out.println("TARGET-RESULT=" + targetResult.get());
            if (!targetDone.get()) {
                lock.close();
                fail("target still running after 30s");
                return;
            }
            lock.close();
            System.out.println("RESULT=PASS");
            System.exit(0);
        }
    }

    private static void fail(String message) {
        System.out.println("RESULT=FAIL " + message);
        System.exit(1);
    }

    // --- reflection helpers (Java 8 compatible) ---

    private static Object unstarted(Object builder, Runnable task, String name) throws Exception {
        Class<?> builderClass = Class.forName("java.lang.Thread$Builder");
        builderClass.getMethod("name", String.class).invoke(builder, name);
        return builderClass.getMethod("unstarted", Runnable.class).invoke(builder, task);
    }

    private static void start(Object thread) throws Exception {
        Class.forName("java.lang.Thread").getMethod("start").invoke(thread);
    }

    private static void join(Object thread, long millis) throws Exception {
        Class.forName("java.lang.Thread").getMethod("join", long.class).invoke(thread, millis);
    }
}
