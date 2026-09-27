# Virtual-thread wait behavior

The driver moves backup/restore step waits and a narrow set of driver-generated transaction-control waits from native sleep to `Thread.sleep`. SQL remains on the calling thread; the driver adds no executor and does not retry user SQL.

## Wait paths

### Backup and restore

`NativeDB` exposes backup session init/step/progress/finish JNI calls. Java drives the existing retry loop. The DB monitor stays held through every step, observer callback, wait, and finish, preserving same-connection exclusion. An incomplete restore continues to report its last BUSY/LOCKED/INTERRUPT result rather than a temporary connection's error code.

The monitor is reentrant, so callbacks are also guarded explicitly: closing the connection that owns an active backup/restore session or starting a nested copy on it throws `SQLException`. A restore destination rejects SQL, existing prepared-statement access, and native connection operations until the session is finished; a backup observer may still query its source. Rejected access does not finalize the prepared statement or close the connection. JNI initialization failures retain their original Java exception and release resources before returning. The public String overloads retain native argument behavior, including the `SQLITE_NOMEM` result for a null filename.

### Generated transaction controls

The closed internal allowlist is BEGIN DEFERRED/IMMEDIATE/EXCLUSIVE, COMMIT, and the compatibility BEGIN probe in `DB.ensureAutoCommit`. The same boundary covers `SQLiteConnection.setAutoCommit`, `commit`, `rollback`'s following BEGIN, and `JDBC3Connection.tryEnforceTransactionMode`'s generated COMMIT/BEGIN IMMEDIATE. COMMIT and its following BEGIN are separate operations with independent timeout budgets.

Java waits are enabled only when the live native `PRAGMA busy_timeout` is positive, no driver BusyHandler is registered, the linked engine does not enable `SQLITE_ENABLE_SETLK_TIMEOUT`, and the current thread is not inside a driver-managed SQLite-to-Java callback. Each no-wait native attempt installs a stack-local marker for one `sqlite3_step`, restores the native default timeout handler, and returns before Java sleeps. Only exact `SQLITE_BUSY` results that invoked the marker are retried. Zero/unknown policy, custom handlers, setlk timeout builds, and detected callback reentry keep the legacy native path without replacing policy.

The live timeout is read from SQLite, not inferred from `SQLiteConfig`; SQL `PRAGMA busy_timeout` can take effect during prepare. Callback reentry through driver-managed UDF, aggregate/window, collation, busy/progress handler, update listener, or commit listener is tracked per thread. Native extensions that install handlers or call Java outside those wrappers remain unverified.

The first native attempt reuses the live timeout snapshot just read for that control operation while the same DB monitor remains held. It does not prepare a second identical PRAGMA. Retries still read the native policy again. This is not a connection-level timeout cache and does not change custom-handler, callback, cancellation, or zero-timeout fallbacks.

The DB monitor remains held during Java sleep. Other connections can progress; another thread using or closing the same connection waits. On a BEGIN failure, the JDBC auto-commit setting remains unchanged. If COMMIT/ROLLBACK succeeded but its following BEGIN failed, the driver records the exact pending BEGIN and blocks user SQL until only that BEGIN is recovered. It never replays the completed COMMIT, ROLLBACK, DML, or batch entry.

## Runtime behavior

| Runtime | Java `Thread.sleep` while holding the DB monitor |
|---|---|
| JDK 8–20 | No stable virtual threads; the calling platform thread blocks. JDK 19–20 preview virtual threads remain monitor-pinned. |
| JDK 21–23 | A virtual thread can still pin its carrier on the monitor. |
| JDK 24+ | JEP 491 allows a waiting virtual thread to unmount from the synchronized DB method. |

The carrier claim applies only to the eligible generated-control waits and backup/restore waits. Legacy fallbacks and unrelated native I/O/callbacks can still block a carrier.

## Verification

- `mvn -q test -Dtest=BusyPolicyTest,ControlTransactionTest` exercises real lock contention, all P01–P12 policy transitions, transaction restart failures, cancellation, attached databases, extended BUSY_SNAPSHOT, and callback reentry.
- `mvn -q test -Dtest=VtCarrierProgressTest` launches independent child JVMs with one scheduler carrier. BEGIN IMMEDIATE and reader-blocked COMMIT both let an independent virtual thread finish while the target is still waiting; the same-connection exclusion scenario stays blocked.
- `bash scripts/vt-wait-control-group.sh` runs the same backup, BEGIN, and COMMIT scenarios against the parent native-wait implementation and current Java-wait implementation. In the current JDK 25 run, the independent virtual thread completed during each current wait; parent runs completed it only after native timeout. JFR is auxiliary; child progress markers are the primary evidence.
- Final JDK 25 full suite: 446 tests, 0 failures, 0 errors, 12 skipped (3 backup fault-injection tests and the T12 probe-fault test require `make test-faults`).
- `make test-faults`: 3 backup ownership-fault tests and T12 autocommit-probe failure recovery passed.
- `JAVA_HOME=<JDK 8> bash scripts/vt-java8-smoke.sh`: production compile plus backup, generated BEGIN wait, and interrupt restoration passed. The full Maven test build is blocked on JDK 8 by the Java 11 Enforcer rule and test dependencies compiled for class-file version 55; no full Java 8 suite result is claimed.

## Application benchmark

[Gateway HTTP/MCP benchmark](docs/vt-java-wait/APPLICATION-BENCHMARK.md) compares released xerial 3.53.4.0 with the reviewed working tree on JDK 25. With four carriers and four contended online backups, independent-VT p99 start delay fell from 815ms to 96ms; normal HTTP throughput was 6–11% lower. With ten carriers, the backup workload showed no throughput advantage. SQLite-attributed JFR pin events were zero for both drivers: carrier starvation, not event-count reduction, is the demonstrated improvement. The report preserves the failed incremental-backup pilot, raw evidence locations, reproduction commands, and deferred performance work.

[Profile-driven optimization](docs/vt-java-wait/PROFILE-OPTIMIZATION.md) records the subsequent Luna-max analysis and direct native timing. Reusing the first live timeout snapshot reduced readbacks from about 168 to 84 per Gateway request; instrumented readback elapsed fell from 48.4µs to 28.5µs per request. Full-suite/fault/Java-8 checks passed and carrier progress remained intact. High HTTP run variance prevents claiming an end-to-end speedup or upstream throughput parity.
