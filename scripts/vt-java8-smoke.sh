#!/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:?set JAVA_HOME to a Java 8 installation}"
JAVA_BIN="${JAVA_HOME}/bin/java"
JAVAC_BIN="${JAVA_HOME}/bin/javac"
TMP_DIR="$(mktemp -d)"
EVIDENCE="${ROOT}/target/vt-wait-evidence/java8-smoke"
mkdir -p "${EVIDENCE}"
trap 'rm -rf "${TMP_DIR}"' EXIT

cd "${ROOT}"
mvn -Denforcer.skip=true -q compiler:compile

cat > "${TMP_DIR}/Java8Smoke.java" <<'EOF'
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.core.VtWaitProbe;

public final class Java8Smoke {
    private static Connection open(Path path, int timeout, boolean immediate) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(timeout);
        if (immediate) config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        return DriverManager.getConnection("jdbc:sqlite:" + path, config.toProperties());
    }

    private static void insert(Connection connection, int value) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("insert into t values (" + value + ")");
        }
    }

    private static int count(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("select count(*) from t")) {
            result.next();
            return result.getInt(1);
        }
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("sqlite-jdbc-java8");
        Path sourcePath = dir.resolve("source.db");
        Path backupPath = dir.resolve("backup.db");
        try (Connection source = open(sourcePath, 1000, false)) {
            try (Statement statement = source.createStatement()) {
                statement.executeUpdate("create table t(v)");
            }
            insert(source, 1);
            insert(source, 2);
            int rc = ((SQLiteConnection) source).getDatabase()
                    .backup("main", backupPath.toString(), null, 10, 2, 1);
            if (rc != 0) throw new AssertionError("backup rc=" + rc);
        }
        try (Connection backup = open(backupPath, 1000, false)) {
            if (count(backup) != 2) throw new AssertionError("backup rows");
        }

        Path lockedPath = dir.resolve("locked.db");
        try (Connection holder = open(lockedPath, 1000, false);
                Connection waiter = open(lockedPath, 5000, true)) {
            try (Statement statement = holder.createStatement()) {
                statement.executeUpdate("create table t(v)");
            }
            holder.setAutoCommit(false);
            insert(holder, 1);
            long before = VtWaitProbe.javaWaitObservations();
            AtomicReference<Thread> worker = new AtomicReference<>();
            AtomicReference<SQLException> failure = new AtomicReference<>();
            AtomicBoolean interruptRestored = new AtomicBoolean();
            Thread thread = new Thread(() -> {
                worker.set(Thread.currentThread());
                try {
                    waiter.setAutoCommit(false);
                } catch (SQLException exception) {
                    failure.set(exception);
                } finally {
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                }
            });
            thread.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (VtWaitProbe.javaWaitObservations() == before && System.nanoTime() < deadline) {
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            if (VtWaitProbe.javaWaitObservations() == before) throw new AssertionError("no Java wait");
            worker.get().interrupt();
            thread.join(3000);
            if (thread.isAlive() || failure.get() == null
                    || (failure.get().getErrorCode() & 0xff) != 9 || !interruptRestored.get()) {
                throw new AssertionError("interrupt state");
            }
            holder.setAutoCommit(true);
            waiter.setAutoCommit(false);
            waiter.rollback();
        }
        System.out.println("JAVA8-SMOKE-PASS");
    }
}
EOF

CLASSPATH="${ROOT}/target/classes:${ROOT}/target/classpath/slf4j-api.jar"
"${JAVAC_BIN}" -source 8 -target 8 -cp "${CLASSPATH}" -d "${TMP_DIR}" \
  "${ROOT}/src/test/java/org/sqlite/core/VtWaitProbe.java" "${TMP_DIR}/Java8Smoke.java"
"${JAVA_BIN}" -ea -cp "${TMP_DIR}:${CLASSPATH}" Java8Smoke | tee "${EVIDENCE}/stdout.txt"
