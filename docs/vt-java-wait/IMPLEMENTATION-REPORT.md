# Implementation report

## Result

Implemented the missing backup ownership fixes, policy-preserving Java waits for driver-generated transaction controls, transaction restart protection, callback-reentry fallback, and single-carrier verification. No generic user-SQL retry, public JDBC retry API, executor, or SQLite VFS change was added.

## Runtime and native identity

- Host: macOS 25.5, arm64; Apple clang 16.0.0.
- JDK 25: OpenJDK 25.0.2; Maven 3.9.16.
- JDK 8: Amazon Corretto 8.0.472.
- SQLite: 3.53.4; `sqlite_source_id()` = `2026-07-24 19:02:57 bf7c7f30031888f4e796e429ab3978879485813aaca6f641c7b33e4e09459bcc`.
- Runtime `PRAGMA compile_options`: `ENABLE_FTS5`; no `ENABLE_SETLK_TIMEOUT`.
- Loaded resource: `target/classes/org/sqlite/native/Mac/aarch64/libsqlitejdbc.dylib`; one observed extraction path was `/var/folders/gz/2s4x2dyx1czdvm3gv6g4nty00000gn/T/sqlite-3.53.4-f0b7f5da-498a-4f6a-a7e6-0c3d80e6fc1f-libsqlitejdbc.dylib` (deleted on JVM exit). SHA-256 for resource and extracted copy: `c217842d2a434b16b8a70b60f1b0741b560d84971b5b7bd2daf36ceea6edf171`.

## Policy and wait boundary

The native attempt changes the busy handler only for a positive, readable default timeout and restores it before returning to Java. It returns a packed `long` (`rc` in low 32 bits; busy-marker bit 32), avoiding a per-attempt JNI array allocation. The Java loop retries only exact `SQLITE_BUSY` plus a marker invocation. `SQLITE_BUSY_SNAPSHOT`, LOCKED, interrupt, and all unmarked BUSY paths return without Java retry.

The retry schedule comes from the built SQLite 3.53.4 `sqliteDefaultBusyCallback` at `target/sqlite-amalgamation-3530400/sqlite3.c:189120-189149`. The Java loop preserves its cumulative requested-sleep schedule and also applies a monotonic wall-time ceiling for attempt/JNI overhead; scheduler oversleep can shorten retries relative to SQLite's cumulative-sleep-only behavior. `BUSY-POLICY.md` records the difference.

Eligibility uses the live native `PRAGMA busy_timeout`, driver BusyHandler registration, `PRAGMA compile_options`, and a per-thread driver-callback depth. Known callback reentry falls back before changing native policy. Unknown native/VFS configurations remain legacy fallback or unverified as detailed in `BUSY-POLICY.md`.

## Call-site coverage

| Path | Default positive timeout | Custom/unsupported policy | Verification |
|---|---|---|---|
| Backup/restore between `sqlite3_backup_step` calls | Java `Thread.sleep`; DB monitor retained | Existing policy/native waits remain inside an individual native step | B01–B08, V01/V02, `BackupBusyWaitTest` |
| `setAutoCommit(false)` BEGIN | Java wait | Legacy step, unchanged policy | P01–P02, T01–T03, V03 |
| `setAutoCommit(true)` COMMIT and compatibility probe | Independent boundary; JDBC state changes only after COMMIT, before probe | Legacy step | T09 and test-fault T12 |
| `commit()` COMMIT then next BEGIN | Separate budgets; completed COMMIT is never replayed | Legacy step | T04–T07, V04 |
| `rollback()` body then next BEGIN | ROLLBACK stays on the old path; only BEGIN uses boundary | Legacy step | T08 |
| `DB.ensureAutoCommit` probe BEGIN/COMMIT | Each cached statement step uses the boundary | Legacy step | T12 fault injection, exactly-once side-effect test |
| Read-only transaction upgrade | Only generated COMMIT and BEGIN IMMEDIATE use the boundary; surrounding PRAGMA is not replayed | Legacy step | T11 |
| Plain/prepared SQL, DML, batch, multi-statement `_exec` | No retry added | Existing execution path | T12–T14 and full suite |

If COMMIT/ROLLBACK succeeds but the following BEGIN fails, the exact failed BEGIN is retained as pending. User SQL is blocked before prepare/execute can cause side effects; `commit`, `rollback`, `setAutoCommit(true)`, or a later statement recover only that BEGIN. Recovery preserves the primary SQLite error code and does not imply that the preceding operation failed.

Transaction transitions now hold the DB monitor until the JDBC restart state is updated. Statement execution, batch execution, savepoints, and prepared-statement creation check recovery while holding that same monitor, so a statement queued behind a failed BEGIN cannot run in implicit auto-commit mode. An already-interrupted thread is rejected before its first eligible control attempt. If the compatibility COMMIT throws during cancellation, its BEGIN is rolled back just as it is for a returned error code.

Review regressions were reproduced before the fixes: a pre-interrupted BEGIN succeeded, a compatibility-COMMIT exception left later inserts invisible to another connection, and an insert queued behind a failed restart survived `rollback()`. All three passed after the fixes with the fault-enabled native library:

```shell
mvn -q test '-Dtest=ControlTransactionTest#t03PreInterruptedBeginDoesNotStartATransaction+t12_interruptedCompatibilityCommitDoesNotLeakAnOpenTransaction+t07ConcurrentStatementCannotBypassAFailedTransactionRestart'
```

The review run exercised 3 tests with no failures, errors, or skips. A standalone JDBC smoke also observed `SQLITE_INTERRUPT` with auto-commit and the interrupt flag preserved, then successfully inserted and rolled back on the recovered connection. The full-suite numbers below describe the original implementation run, not a new post-review full-suite run.

## Verification

- JDK 25 full suite: `mvn -q test` — 446 tests, 0 failures, 0 errors, 12 skipped.
  - Three `BackupFaultInjectionTest` cases and the T12 injection case require the test-only native library; the remaining skips are from other suite assumptions.
- Busy policy P01–P12: `mvn -q test -Dtest=BusyPolicyTest` — 12 passed.
- Transaction, cancellation, error-code, attached-schema, read-only, listener, FTS, and restart regressions are in `ControlTransactionTest`; included in the full-suite result.
- Native fault build: `make test-faults` — 3 backup ownership/fault tests and T12 passed. T12 forces the cached compatibility COMMIT probe to return BUSY after DML; the test verifies the probe is rolled back, the DML/trigger ran once, `setAutoCommit(true)` remains true after a subsequent probe failure, and the connection handles later SQL. The target restored the normal native library.
- JDK 25 single-carrier/JFR: `mvn -q test -Dtest=VtCarrierProgressTest` — backup, BEGIN, and COMMIT progress plus same-connection exclusion passed; JDK 25 asserts zero `jdk.VirtualThreadPinned` events.
- Parent/current comparison: `bash scripts/vt-wait-control-group.sh` — current backup/BEGIN/COMMIT each reported `B-DONE targetStillWaiting=true`; the parent native-wait controls reported `false` for all three.
- JDK 8: `JAVA_HOME=<JDK 8> bash scripts/vt-java8-smoke.sh` — production compile and backup/BEGIN-wait/interrupt-restoration smoke passed (`JAVA8-SMOKE-PASS`). Full Maven test execution is blocked by the POM's Java 11 minimum; bypassing Enforcer exposes test dependencies compiled for class-file version 55 (`mockito-core` and JUnit Pioneer). No full Java 8 suite result is claimed.
- Formatting: `mvn spotless:check` passed on JDK 17. The configured Google Java Format 1.11 step fails internally on JDK 25 (`DeferredDiagnosticHandler.getDiagnostics`), so the formatting check was run on JDK 17 instead.

Child stdout/stderr/exit codes, JFR recordings, control-group results, Java 8 smoke output, and native identity probe are under ignored `target/vt-wait-evidence/`.

## Remaining boundaries

- `sqlite3_backup_step` and `COMMIT`/BEGIN on unsupported policies may still block natively; the change is not a blanket nonblocking SQLite guarantee.
- Native I/O, fsync, UDF code itself, user BusyHandler code, and external native callbacks are not moved to Java.
- The callback-depth guard covers callbacks routed through the driver's Java wrappers; third-party native code that calls Java without those wrappers remains unverified.
- Java 21–23 virtual-thread monitor pinning and non-macOS native builds were not tested in this environment.
